package com.modemdoctor.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.modemdoctor.MainActivity
import com.modemdoctor.R
import com.modemdoctor.network.GitHubUploader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground Service для мониторинга состояния модема в реальном времени
 * Отслеживает изменения состояния сети, сигнала и регистрирует проблемы
 */
class ModemMonitorService : Service() {
    
    companion object {
        const val TAG = "ModemMonitorService"
        const val CHANNEL_ID = "modem_monitor_channel"
        const val NOTIFICATION_ID = 1001
        
        const val ACTION_START_MONITORING = "com.modemdoctor.START_MONITORING"
        const val ACTION_STOP_MONITORING = "com.modemdoctor.STOP_MONITORING"
        const val ACTION_COLLECT_LOGS = "com.modemdoctor.COLLECT_LOGS"
        
        // Состояние мониторинга
        val isMonitoring = MutableStateFlow(false)
        val lastEvent = MutableStateFlow<MonitorEvent?>(null)
        val events = MutableStateFlow<List<MonitorEvent>>(emptyList())
        val totalEvents = MutableStateFlow(0)
        val networkLossCount = MutableStateFlow(0)
        val lastNetworkLoss = MutableStateFlow<String?>(null)
        val autoUploadStatus = MutableStateFlow<String?>(null)
    }
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private var lastSignalStrength = -1
    private var lastServiceState = -1
    private var lastDataState = TelephonyManager.DATA_DISCONNECTED
    
    // Время последней проверки
    private val lastCheckTime = AtomicLong(0)
    
    // Для защиты от повторных авто-загрузок
    private var lastAutoUploadTime = 0L
    private val autoUploadCooldown = 60_000L // 1 минута между авто-загрузками
    
    data class MonitorEvent(
        val timestamp: String,
        val type: EventType,
        val description: String,
        val details: Map<String, Any> = emptyMap()
    )
    
    enum class EventType {
        SIGNAL_LOSS,
        NETWORK_LOSS,
        NETWORK_RESTORE,
        SERVICE_STATE_CHANGE,
        DATA_STATE_CHANGE,
        HANDOVER,
        REGISTRATION_FAIL,
        MODEM_ERROR,
        CUSTOM_LOG
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        Log.d(TAG, "Service created")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_START_MONITORING -> startMonitoring()
            ACTION_STOP_MONITORING -> stopMonitoring()
            ACTION_COLLECT_LOGS -> collectAndUploadLogs()
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        stopMonitoring()
        scope.cancel()
        Log.d(TAG, "Service destroyed")
    }
    
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Modem Monitor",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Monitoring modem state"
        }
        
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
    
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Modem Doctor")
            .setContentText("Monitoring network state...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    private fun startMonitoring() {
        if (isMonitoring.value) return
        
        startForeground(NOTIFICATION_ID, createNotification())
        isMonitoring.value = true
        
        // Запускаем слушатель состояния телефона
        setupPhoneStateListener()
        
        // Периодическая проверка состояния модема
        scope.launch {
            while (isMonitoring.value) {
                checkModemState()
                delay(5000) // каждые 5 секунд
            }
        }
        
        Log.d(TAG, "Monitoring started")
        addEvent(EventType.CUSTOM_LOG, "Monitoring started", mapOf("root" to RootShell.checkRoot()))
    }
    
    private fun stopMonitoring() {
        isMonitoring.value = false
        removePhoneStateListener()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "Monitoring stopped")
    }
    
    private fun setupPhoneStateListener() {
        phoneStateListener = object : PhoneStateListener() {
            override fun onServiceStateChanged(serviceState: ServiceState?) {
                serviceState?.let { state ->
                    val newState = state.state
                    if (lastServiceState != -1 && lastServiceState != newState) {
                        val description = when (newState) {
                            ServiceState.STATE_IN_SERVICE -> "In Service"
                            ServiceState.STATE_OUT_OF_SERVICE -> "Out of Service"
                            ServiceState.STATE_EMERGENCY_ONLY -> "Emergency Only"
                            ServiceState.STATE_POWER_OFF -> "Power Off"
                            else -> "Unknown"
                        }
                        
                        if (newState == ServiceState.STATE_OUT_OF_SERVICE || 
                            newState == ServiceState.STATE_EMERGENCY_ONLY) {
                            networkLossCount.value++
                            lastNetworkLoss.value = dateFormat.format(Date())
                            addEvent(EventType.NETWORK_LOSS, description, mapOf(
                                "previousState" to lastServiceState,
                                "newState" to newState
                            ))
                            // АВТО-ЗАГРУЗКА ЛОГОВ при потере сети!
                            triggerAutoUpload("Network lost: $description")
                        } else if (lastServiceState == ServiceState.STATE_OUT_OF_SERVICE && 
                            newState == ServiceState.STATE_IN_SERVICE) {
                            addEvent(EventType.NETWORK_RESTORE, "Network restored", mapOf(
                                "duration" to "unknown"
                            ))
                        } else {
                            addEvent(EventType.SERVICE_STATE_CHANGE, description, mapOf(
                                "previousState" to lastServiceState,
                                "newState" to newState
                            ))
                        }
                    }
                    lastServiceState = newState
                }
            }
            
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                signalStrength?.let { strength ->
                    val newLevel = strength.level
                    if (lastSignalStrength != -1 && lastSignalStrength != newLevel) {
                        if (newLevel == 0 && lastSignalStrength > 0) {
                            addEvent(EventType.SIGNAL_LOSS, "Signal lost", mapOf(
                                "previousLevel" to lastSignalStrength,
                                "newLevel" to newLevel
                            ))
                        }
                    }
                    lastSignalStrength = newLevel
                }
            }
        }
        
        try {
            telephonyManager?.listen(phoneStateListener, 
                PhoneStateListener.LISTEN_SERVICE_STATE or 
                PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup phone state listener", e)
        }
    }
    
    private fun removePhoneStateListener() {
        phoneStateListener?.let {
            telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE)
        }
    }
    
    private suspend fun checkModemState() {
        if (!RootShell.checkRoot()) return
        
        // Проверяем состояние радиоинтерфейса через dumpsys
        val (_, output) = RootShell.execute("dumpsys telephony.registry | head -50")
        
        // Анализируем на наличие проблем
        if (output.contains("mServiceState=0") && 
            output.contains("mSignalStrength=0")) {
            // Возможно зависание модема
            if (System.currentTimeMillis() - lastCheckTime.get() > 30000) {
                addEvent(EventType.MODEM_ERROR, "Possible modem hang detected", mapOf(
                    "output" to output.take(200)
                ))
            }
        }
        
        lastCheckTime.set(System.currentTimeMillis())
        
        // Проверяем наличие процессов rild
        val (_, rildOutput) = RootShell.execute("ps -A | grep rild")
        if (rildOutput.isEmpty()) {
            addEvent(EventType.MODEM_ERROR, "rild process not found!", emptyMap())
        }
    }
    
    private fun addEvent(type: EventType, description: String, details: Map<String, Any>) {
        val event = MonitorEvent(
            timestamp = dateFormat.format(Date()),
            type = type,
            description = description,
            details = details
        )
        
        lastEvent.value = event
        totalEvents.value++
        
        val currentEvents = events.value.toMutableList()
        currentEvents.add(0, event)
        // Храним последние 100 событий
        if (currentEvents.size > 100) {
            events.value = currentEvents.take(100)
        } else {
            events.value = currentEvents
        }
        
        Log.d(TAG, "Event: $type - $description")
        
        // Обновляем нотификацию
        updateNotification(event)
    }
    
    private fun updateNotification(event: MonitorEvent) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Modem Doctor: ${event.type}")
            .setContentText(event.description)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
        
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun collectAndUploadLogs() {
        scope.launch {
            addEvent(EventType.CUSTOM_LOG, "Collecting logs...", emptyMap())
            
            val collector = LogCollector(this@ModemMonitorService)
            val result = collector.collectAll()
            
            // Добавляем события мониторинга в лог
            val eventsLog = events.value.joinToString("\n") { event ->
                "[${event.timestamp}] ${event.type}: ${event.description}"
            }
            
            // Формируем полный лог
            val fullLog = buildString {
                appendLine("=== MONITORING EVENTS ===")
                appendLine("Total events: ${totalEvents.value}")
                appendLine("Network loss count: ${networkLossCount.value}")
                appendLine("Last network loss: ${lastNetworkLoss.value}")
                appendLine()
                appendLine(eventsLog)
                appendLine()
                appendLine("=== DEVICE INFO ===")
                appendLine("Device: ${result.deviceInfo.manufacturer} ${result.deviceInfo.model}")
                appendLine("Android: ${result.deviceInfo.androidVersion}")
                appendLine("Radio: ${result.deviceInfo.radioVersion}")
                appendLine()
                appendLine("=== NETWORK STATE ===")
                appendLine("Type: ${result.networkState.networkType}")
                appendLine("Operator: ${result.networkState.networkOperatorName}")
                appendLine("Signal: ${result.networkState.signalStrength}/4")
                appendLine()
                result.logs.forEach { (name, content) ->
                    appendLine("=== $name ===")
                    appendLine(content.take(5000)) // Ограничиваем размер
                    appendLine()
                }
            }
            
            // Загружаем на GitHub
            val uploader = GitHubUploader(this@ModemMonitorService)
            val gistUrl = uploader.uploadToGist(
                filename = "modem_log_${result.timestamp}.txt",
                content = fullLog,
                description = "Modem Doctor Log - ${result.deviceInfo.model} - ${result.networkState.networkOperatorName}"
            )
            
            if (gistUrl != null) {
                addEvent(EventType.CUSTOM_LOG, "Logs uploaded: $gistUrl", mapOf("url" to gistUrl))
            } else {
                addEvent(EventType.CUSTOM_LOG, "Failed to upload logs", emptyMap())
            }
        }
    }
    
    /**
     * Принудительный сбор логов при обнаружении проблемы
     */
    fun triggerLogCollection(reason: String) {
        addEvent(EventType.CUSTOM_LOG, "Manual log collection: $reason", emptyMap())
        collectAndUploadLogs()
    }
    
    /**
     * АВТОМАТИЧЕСКАЯ загрузка логов при потере сети
     * Использует компактный режим (~30KB)
     */
    private fun triggerAutoUpload(reason: String) {
        val now = System.currentTimeMillis()
        
        // Защита от повторных загрузок (cooldown 1 минута)
        if (now - lastAutoUploadTime < autoUploadCooldown) {
            Log.d(TAG, "Auto-upload skipped (cooldown)")
            return
        }
        lastAutoUploadTime = now
        
        scope.launch {
            try {
                autoUploadStatus.value = "Auto-uploading: $reason"
                addEvent(EventType.CUSTOM_LOG, "AUTO-UPLOAD: $reason", emptyMap())
                
                // Ждём 3 секунды чтобы накопить больше событий
                delay(3000)
                
                val collector = LogCollector(this@ModemMonitorService)
                // Компактный режим - минимум данных
                val result = collector.collectAll(compact = true)
                
                // Формируем лог
                val fullLog = buildString {
                    appendLine("╔══════════════════════════════════════════════════════════════╗")
                    appendLine("║  MODEM DOCTOR - AUTO-UPLOADED LOG (NETWORK LOSS DETECTED)   ║")
                    appendLine("╚══════════════════════════════════════════════════════════════╝")
                    appendLine()
                    appendLine("=== TRIGGER REASON ===")
                    appendLine(reason)
                    appendLine()
                    appendLine("=== MONITORING SUMMARY ===")
                    appendLine("Total events: ${totalEvents.value}")
                    appendLine("Network loss count: ${networkLossCount.value}")
                    appendLine("Last network loss: ${lastNetworkLoss.value}")
                    appendLine()
                    appendLine("=== RECENT EVENTS (last 20) ===")
                    events.value.take(20).forEach { event ->
                        appendLine("[${event.timestamp}] ${event.type}: ${event.description}")
                    }
                    appendLine()
                    appendLine("=== DEVICE INFO ===")
                    appendLine("Device: ${result.deviceInfo.manufacturer} ${result.deviceInfo.model}")
                    appendLine("Android: ${result.deviceInfo.androidVersion}")
                    appendLine("Radio: ${result.deviceInfo.radioVersion}")
                    appendLine()
                    appendLine("=== NETWORK STATE ===")
                    appendLine("Type: ${result.networkState.networkType}")
                    appendLine("Operator: ${result.networkState.networkOperatorName}")
                    appendLine("Signal: ${result.networkState.signalStrength}/4")
                    appendLine("SIM: ${result.networkState.simState}")
                    appendLine()
                    result.logs.forEach { (name, content) ->
                        appendLine("=== $name ===")
                        appendLine(content.take(3000)) // Ограничение 3KB на секцию
                        appendLine()
                    }
                }
                
                // Загружаем на GitHub
                val uploader = GitHubUploader(this@ModemMonitorService)
                val gistUrl = uploader.uploadToGist(
                    filename = "auto_log_${result.timestamp}.txt",
                    content = fullLog,
                    description = "AUTO: ${result.deviceInfo.model} - $reason"
                )
                
                if (gistUrl != null) {
                    autoUploadStatus.value = "Uploaded: $gistUrl"
                    addEvent(EventType.CUSTOM_LOG, "Auto-upload SUCCESS: $gistUrl", mapOf("url" to gistUrl))
                    Log.i(TAG, "Auto-uploaded to: $gistUrl")
                } else {
                    autoUploadStatus.value = "Upload failed"
                    addEvent(EventType.MODEM_ERROR, "Auto-upload FAILED", emptyMap())
                }
            } catch (e: Exception) {
                autoUploadStatus.value = "Error: ${e.message}"
                Log.e(TAG, "Auto-upload error", e)
                addEvent(EventType.MODEM_ERROR, "Auto-upload error: ${e.message}", emptyMap())
            }
        }
    }
}

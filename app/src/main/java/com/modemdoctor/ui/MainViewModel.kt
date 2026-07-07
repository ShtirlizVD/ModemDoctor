package com.modemdoctor.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.modemdoctor.core.LogCollector
import com.modemdoctor.core.ModemMonitorService
import com.modemdoctor.core.RootShell
import com.modemdoctor.network.GitHubUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel для главного экрана приложения
 */
class MainViewModel : ViewModel() {
    
    // Состояния UI
    private val _uiState = MutableStateFlow<UiState>(UiState.Initial)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    
    private val _hasRoot = MutableStateFlow<Boolean?>(null)
    val hasRoot: StateFlow<Boolean?> = _hasRoot.asStateFlow()
    
    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()
    
    private val _githubToken = MutableStateFlow("")
    val githubToken: StateFlow<String> = _githubToken.asStateFlow()
    
    private val _githubTokenValid = MutableStateFlow<Boolean?>(null)
    val githubTokenValid: StateFlow<Boolean?> = _githubTokenValid.asStateFlow()
    
    private val _logCollectionProgress = MutableStateFlow(0f)
    val logCollectionProgress: StateFlow<Float> = _logCollectionProgress.asStateFlow()
    
    private val _lastGistUrl = MutableStateFlow<String?>(null)
    val lastGistUrl: StateFlow<String?> = _lastGistUrl.asStateFlow()
    
    private val _events = MutableStateFlow<List<ModemMonitorService.MonitorEvent>>(emptyList())
    val events: StateFlow<List<ModemMonitorService.MonitorEvent>> = _events.asStateFlow()
    
    private val _networkLossCount = MutableStateFlow(0)
    val networkLossCount: StateFlow<Int> = _networkLossCount
    
    private val _vowifiStatus = MutableStateFlow("")
    val vowifiStatus: StateFlow<String> = _vowifiStatus
    
    private val _vowifiEnabled = MutableStateFlow<Boolean?>(null)
    val vowifiEnabled: StateFlow<Boolean?> = _vowifiEnabled.asStateFlow()
    
    sealed class UiState {
        object Initial : UiState()
        object CheckingRoot : UiState()
        object Ready : UiState()
        object CollectingLogs : UiState()
        data class LogsCollected(val filesCount: Int) : UiState()
        data class Error(val message: String) : UiState()
    }
    
    /**
     * Инициализация - проверка root
     */
    fun checkRootAccess() {
        viewModelScope.launch {
            _uiState.value = UiState.CheckingRoot
            _hasRoot.value = withContext(Dispatchers.IO) {
                RootShell.checkRoot()
            }
            _uiState.value = UiState.Ready
        }
    }
    
    /**
     * Установка GitHub токена
     */
    fun setGithubToken(token: String) {
        _githubToken.value = token
        GitHubUploader.setToken(token)
        
        // Валидируем токен
        viewModelScope.launch {
            _githubTokenValid.value = null
            val isValid = withContext(Dispatchers.IO) {
                // Простая проверка формата
                token.startsWith("ghp_") || token.startsWith("github_pat_")
            }
            _githubTokenValid.value = isValid
        }
    }
    
    /**
     * Запуск мониторинга
     */
    fun startMonitoring(context: Context) {
        if (!_isMonitoring.value) {
            val intent = Intent(context, ModemMonitorService::class.java).apply {
                action = ModemMonitorService.ACTION_START_MONITORING
            }
            context.startForegroundService(intent)
            _isMonitoring.value = true
            
            // Подписываемся на события
            viewModelScope.launch {
                ModemMonitorService.events.collect { events ->
                    _events.value = events
                }
            }
            viewModelScope.launch {
                ModemMonitorService.networkLossCount.collect { count ->
                    _networkLossCount.value = count
                }
            }
        }
    }
    
    /**
     * Остановка мониторинга
     */
    fun stopMonitoring(context: Context) {
        val intent = Intent(context, ModemMonitorService::class.java).apply {
            action = ModemMonitorService.ACTION_STOP_MONITORING
        }
        context.startService(intent)
        _isMonitoring.value = false
    }
    
    /**
     * Сбор логов один раз
     */
    fun collectLogs(context: Context) {
        viewModelScope.launch {
            _uiState.value = UiState.CollectingLogs
            _logCollectionProgress.value = 0f
            
            try {
                val collector = LogCollector(context)
                
                _logCollectionProgress.value = 0.3f
                val result = withContext(Dispatchers.IO) {
                    collector.collectAll()
                }
                
                _logCollectionProgress.value = 0.6f
                
                // Формируем лог для отправки
                val logBuilder = StringBuilder()
                logBuilder.appendLine("=== MODEM DOCTOR DIAGNOSTIC LOG ===")
                logBuilder.appendLine("Timestamp: ${result.timestamp}")
                logBuilder.appendLine()
                logBuilder.appendLine("=== DEVICE INFO ===")
                logBuilder.appendLine("Manufacturer: ${result.deviceInfo.manufacturer}")
                logBuilder.appendLine("Model: ${result.deviceInfo.model}")
                logBuilder.appendLine("Device: ${result.deviceInfo.device}")
                logBuilder.appendLine("Board: ${result.deviceInfo.board}")
                logBuilder.appendLine("Android: ${result.deviceInfo.androidVersion} (SDK ${result.deviceInfo.sdkVersion})")
                logBuilder.appendLine("Build ID: ${result.deviceInfo.buildId}")
                logBuilder.appendLine("Fingerprint: ${result.deviceInfo.buildFingerprint}")
                logBuilder.appendLine("Radio Version: ${result.deviceInfo.radioVersion}")
                logBuilder.appendLine("Kernel: ${result.deviceInfo.kernelVersion}")
                logBuilder.appendLine()
                logBuilder.appendLine("=== NETWORK STATE ===")
                logBuilder.appendLine("Network Type: ${result.networkState.networkType}")
                logBuilder.appendLine("Operator: ${result.networkState.networkOperatorName} (${result.networkState.networkOperator})")
                logBuilder.appendLine("SIM State: ${result.networkState.simState}")
                logBuilder.appendLine("SIM Operator: ${result.networkState.simOperatorName}")
                logBuilder.appendLine("Signal Level: ${result.networkState.signalStrength}/4")
                logBuilder.appendLine("Roaming: ${result.networkState.isRoaming}")
                logBuilder.appendLine()
                
                result.logs.forEach { (name, content) ->
                    logBuilder.appendLine("=== $name ===")
                    logBuilder.appendLine(content)
                    logBuilder.appendLine()
                }
                
                if (result.errors.isNotEmpty()) {
                    logBuilder.appendLine("=== ERRORS ===")
                    result.errors.forEach { logBuilder.appendLine("- $it") }
                }
                
                _logCollectionProgress.value = 0.8f
                
                // Загружаем на GitHub если есть токен
                if (_githubToken.value.isNotBlank()) {
                    val uploader = GitHubUploader(context)
                    val url = withContext(Dispatchers.IO) {
                        uploader.uploadToGist(
                            filename = "modem_log_${result.timestamp}.txt",
                            content = logBuilder.toString(),
                            description = "Modem Doctor - ${result.deviceInfo.model} - ${result.timestamp}"
                        )
                    }
                    _lastGistUrl.value = url
                }
                
                _logCollectionProgress.value = 1f
                _uiState.value = UiState.LogsCollected(result.logs.size)
                
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Unknown error")
            }
        }
    }
    
    /**
     * Принудительный сбор логов при проблеме
     */
    fun triggerProblemLog(context: Context, description: String) {
        viewModelScope.launch {
            val intent = Intent(context, ModemMonitorService::class.java).apply {
                action = ModemMonitorService.ACTION_COLLECT_LOGS
            }
            context.startService(intent)
        }
    }
    
    /**
     * Проверка разрешений
     */
    fun checkPermissions(context: Context): List<String> {
        val requiredPermissions = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        return requiredPermissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Проверка текущего статуса VoWiFi
     */
    fun checkVoWiFiStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            _vowifiStatus.value = "Проверяем статус VoWiFi..."
            
            val commands = listOf(
                "getprop persist.dbg.wfc_avail_ovr",
                "getprop persist.vendor.radio.wfc_enabled",
                "getprop persist.vendor.ims.wfc_enabled",
                "getprop persist.vendor.ims.volte_enabled",
                "settings get global wfc_ims_enabled",
                "settings get global wfc_ims_mode",
                "settings get global wfc_ims_roaming_enabled",
                "dumpsys carrier_config | grep -iE 'wfc|wifi_calling|carrier_wfc_ims_available'",
                "dumpsys imsphone | head -30",
                "getprop gsm.sim.operator.numeric"
            )
            
            val (_, output) = RootShell.execute(commands)
            _vowifiStatus.value = output
            
            // Проверяем включено ли
            val wfcAvail = output.contains("1")
            _vowifiEnabled.value = wfcAvail
        }
    }
    
    /**
     * Принудительное включение VoWiFi
     */
    fun enableVoWiFi() {
        viewModelScope.launch(Dispatchers.IO) {
            _vowifiStatus.value = "Включаем VoWiFi принудительно...\nСобираем информацию..."
            
            // Получаем MCC+MNC SIM карты
            val (_, simInfo) = RootShell.execute("getprop gsm.sim.operator.numeric")
            val simOperator = simInfo.trim()
            
            // Получаем subscription id
            val (_, subIdOut) = RootShell.execute("dumpsys isub | grep 'id=' | head -1")
            val subIdMatch = Regex("id=(\\d+)").find(subIdOut)
            val subId = subIdMatch?.groupValues?.get(1) ?: "1"
            
            // Получаем carrier_id
            val (_, carrierIdOut) = RootShell.execute("cmd phone get-carrier-id 2>/dev/null || echo 'unknown'")
            val carrierId = carrierIdOut.trim()
            
            _vowifiStatus.value = "Включаем VoWiFi...\nSIM: $simOperator\nSubId: $subId\nCarrier: $carrierId\n"
            
            val commands = listOf(
                // === 1. WFC availability override - самое важное для Pixel ===
                "resetprop -p persist.dbg.wfc_avail_ovr 1",
                "resetprop -p persist.dbg.wfc_allow_over_cellular 1",
                "resetprop -p persist.dbg.volte_avail_ovr 1",
                "resetprop -p persist.dbg.vt_avail_ovr 1",
                
                // === 2. Vendor radio свойства ===
                "resetprop -p persist.vendor.radio.wfc_enabled 1",
                "resetprop -p persist.vendor.radio.wfc_support 1",
                "resetprop -p persist.vendor.radio.wfc_mode 1",
                "resetprop -p persist.vendor.radio.sib16_support 1",
                
                // === 3. IMS свойства ===
                "resetprop -p persist.vendor.ims.wfc_enabled 1",
                "resetprop -p persist.vendor.ims.volte_enabled 1",
                "resetprop -p persist.vendor.ims.vt_enabled 1",
                "resetprop -p persist.vendor.ims.rcs_enabled 1",
                "resetprop -p persist.vendor.ims.disable_user_config 0",
                
                // === 4. Settings global ===
                "settings put global wfc_ims_enabled 1",
                "settings put global wfc_ims_mode 1",  // 1 = Wi-Fi preferred
                "settings put global wfc_ims_roaming_enabled 1",
                "settings put global volte_ims_enabled 1",
                "settings put global vt_ims_enabled 1",
                
                // === 5. Carrier config override через cmd phone (Pixel-specific) ===
                // Этот метод реально работает на Pixel, в отличие от settings put
                "cmd phone set-carrier-config $subId carrier_wfc_ims_available_bool true",
                "cmd phone set-carrier-config $subId carrier_volte_available_bool true",
                "cmd phone set-carrier-config $subId carrier_vt_available_bool true",
                "cmd phone set-carrier-config $subId wfc_mode_support_v2 3",
                "cmd phone set-carrier-config $subId carrier_wfc_spn_format_rule_int 0",
                "cmd phone set-carrier-config $subId wfc_data_migration_enabled_bool true",
                "cmd phone set-carrier-config $subId wfc_emergency_address_override_enabled_bool true",
                "cmd phone set-carrier-config $subId carrier_name_wfc_pref_string wifi",
                
                // === 6. Прямое редактирование carrier_config XML ===
                // Backup оригинальных файлов
                "mkdir -p /data/local/tmp/wfc_backup 2>/dev/null",
                "cp /data/user_de/0/com.android.phone/files/carrier_config_*.xml /data/local/tmp/wfc_backup/ 2>/dev/null",
                
                // === 7. Форсирование обновления carrier_config ===
                "am broadcast -a com.android.internal.telephony.CARRIER_SIGNAL_RESET",
                "cmd phone reload-carrier-config $subId 2>/dev/null || true",
                
                // === 8. Перезапуск IMS сервиса ===
                "am force-stop com.android.ims.rcsservice 2>/dev/null || true",
                "am force-stop org.codeaurora.ims 2>/dev/null || true",
                
                // === 9. Дополнительно для Samsung Exynos модема ===
                "resetprop -p persist.vendor.radio.custom_ecc 1",
                "resetprop -p persist.vendor.radio.add_power_save 0",
                "resetprop -p persist.vendor.radio.data_con_rdx 1"
            )
            
            val (exitCode, output) = RootShell.execute(commands)
            
            // Теперь airplane mode toggle для перерегистрации сети
            _vowifiStatus.value = (_vowifiStatus.value + "\nПерезапуск радиомодуля...")
            
            Thread.sleep(2000)
            
            // Мягкий restart telephony через airplane mode
            val restartCommands = listOf(
                "cmd connectivity airplane-mode enable",
                "sleep 3",
                "cmd connectivity airplane-mode disable"
            )
            RootShell.execute(restartCommands)
            
            Thread.sleep(3000)
            
            // Проверяем результат
            val verifyCommands = listOf(
                "echo '=== WFC PROPERTIES ==='",
                "getprop persist.dbg.wfc_avail_ovr",
                "getprop persist.vendor.radio.wfc_enabled",
                "getprop persist.vendor.ims.wfc_enabled",
                "getprop persist.dbg.volte_avail_ovr",
                "echo '=== SETTINGS ==='",
                "settings get global wfc_ims_enabled",
                "settings get global wfc_ims_mode",
                "settings get global volte_ims_enabled",
                "echo '=== CARRIER CONFIG ==='",
                "cmd phone get-carrier-config $subId 2>/dev/null | grep -iE 'wfc|wifi_calling|volte' | head -20",
                "echo '=== IMS STATUS ==='",
                "dumpsys imsphone | grep -iE 'wfc|volte|ims|registered' | head -15",
                "echo '=== SIM INFO ==='",
                "getprop gsm.sim.operator.numeric",
                "getprop gsm.sim.operator.alpha",
                "echo '=== SERVICE STATE ==='",
                "dumpsys telephony.registry | grep -E 'mServiceState|isImsRegistered' | head -10"
            )
            val (_, verify) = RootShell.execute(verifyCommands)
            
            _vowifiStatus.value = buildString {
                appendLine("=== ПРИМЕНЕНО ===")
                appendLine("SIM оператор: $simOperator")
                appendLine("SubId: $subId")
                appendLine("Carrier: $carrierId")
                appendLine()
                appendLine("=== ТЕКУЩИЕ ЗНАЧЕНИЯ ===")
                appendLine(verify)
                appendLine()
                appendLine("=== РЕКОМЕНДАЦИЯ ===")
                if (simOperator.isNotEmpty()) {
                    appendLine("SIM: $simOperator (${simOperator.take(3)})")
                    when (simOperator.take(3)) {
                        "250" -> {
                            appendLine("Россия:")
                            when (simOperator.substring(3)) {
                                "01" -> appendLine("  МТС - VoWiFi поддерживается")
                                "02" -> appendLine("  Мегафон - VoWiFi поддерживается")
                                "99" -> appendLine("  Билайн - VoWiFi поддерживается")
                                "20" -> appendLine("  Tele2 - VoWiFi не везде")
                                else -> appendLine("  Оператор: ${simOperator.substring(3)}")
                            }
                        }
                        "255" -> appendLine("Украина")
                        "257" -> appendLine("Беларусь")
                        else -> appendLine("MCC: ${simOperator.take(3)}")
                    }
                }
                appendLine()
                appendLine("⚠️ Если НЕ работает:")
                appendLine("1. Перезагрузите телефон полностью")
                appendLine("2. Проверьте: *#*#4636#*#* → Phone info")
                appendLine("   IMS Registered: true")
                appendLine("3. Settings → Network → Wi-Fi Calling")
                appendLine("4. Если IMS=false - проблема в модеме")
                appendLine("   (возможно не лечится на Pixel 6A)")
            }
            
            _vowifiEnabled.value = true
        }
    }
    
    /**
     * Отключение VoWiFi
     */
    fun disableVoWiFi() {
        viewModelScope.launch(Dispatchers.IO) {
            _vowifiStatus.value = "Отключаем VoWiFi..."
            
            val commands = listOf(
                "resetprop -p persist.dbg.wfc_avail_ovr 0",
                "resetprop -p persist.vendor.radio.wfc_enabled 0",
                "resetprop -p persist.vendor.ims.wfc_enabled 0",
                "settings put global wfc_ims_enabled 0",
                "settings put global wfc_ims_mode 0"
            )
            
            RootShell.execute(commands)
            
            _vowifiStatus.value = "VoWiFi отключен. Требуется перезагрузка."
            _vowifiEnabled.value = false
        }
    }
    
    /**
     * Переключатель VoWiFi
     */
    fun toggleVoWiFi() {
        if (_vowifiEnabled.value == true) {
            disableVoWiFi()
        } else {
            enableVoWiFi()
        }
    }
}

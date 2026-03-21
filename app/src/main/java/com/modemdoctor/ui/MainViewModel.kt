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
    val networkLossCount: StateFlow<Int> = _networkLossCount.asStateFlow()
    
    private val _autoUploadStatus = MutableStateFlow<String?>(null)
    val autoUploadStatus: StateFlow<String?> = _autoUploadStatus.asStateFlow()
    
    private val _disable5GStatus = MutableStateFlow<String?>(null)
    val disable5GStatus: StateFlow<String?> = _disable5GStatus.asStateFlow()
    
    private val _volteStatus = MutableStateFlow<String?>(null)
    val volteStatus: StateFlow<String?> = _volteStatus.asStateFlow()
    
    private val _ultra3GStatus = MutableStateFlow<String?>(null)
    val ultra3GStatus: StateFlow<String?> = _ultra3GStatus.asStateFlow()
    
    // Battery charge limit
    private val _chargeLimitEnabled = MutableStateFlow(false)
    val chargeLimitEnabled: StateFlow<Boolean> = _chargeLimitEnabled.asStateFlow()
    
    private val _batteryMessage = MutableStateFlow<String?>(null)
    val batteryMessage: StateFlow<String?> = _batteryMessage.asStateFlow()
    
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
            
            // Проверяем статус ограничения зарядки
            checkChargeLimitStatus()
        }
    }
    
    /**
     * Проверка статуса ограничения зарядки
     */
    fun checkChargeLimitStatus() {
        viewModelScope.launch {
            val (enabled, _) = withContext(Dispatchers.IO) {
                RootShell.getChargeLimitStatus()
            }
            _chargeLimitEnabled.value = enabled
        }
    }
    
    /**
     * Включение/выключение ограничения зарядки до 80%
     */
    fun toggleChargeLimit(enabled: Boolean) {
        viewModelScope.launch {
            _batteryMessage.value = if (enabled) "Включаем ограничение..." else "Отключаем ограничение..."
            
            val (success, message) = withContext(Dispatchers.IO) {
                if (enabled) {
                    RootShell.setChargeLimit80()
                } else {
                    RootShell.disableChargeLimit()
                }
            }
            
            if (success) {
                _chargeLimitEnabled.value = enabled
            }
            _batteryMessage.value = message
            
            // Очищаем сообщение через 5 секунд
            kotlinx.coroutines.delay(5000)
            _batteryMessage.value = null
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
            viewModelScope.launch {
                ModemMonitorService.autoUploadStatus.collect { status ->
                    _autoUploadStatus.value = status
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
            Manifest.permission.READ_PHONE_STATE
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        return requiredPermissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Отключение 5G функций
     */
    fun disable5G(context: Context) {
        viewModelScope.launch {
            _disable5GStatus.value = "Disabling 5G..."
            
            val (success, message) = withContext(Dispatchers.IO) {
                RootShell.disable5G()
            }
            
            _disable5GStatus.value = if (success) {
                "✓ 5G disabled successfully!\nModem should be more stable now."
            } else {
                "✗ Failed: ${message.take(100)}"
            }
        }
    }
    
    /**
     * Включение VoLTE навсегда
     */
    fun enableVoLTE(context: Context) {
        viewModelScope.launch {
            _volteStatus.value = "Enabling VoLTE..."
            
            val (success, message) = withContext(Dispatchers.IO) {
                RootShell.enableVoLTE()
            }
            
            _volteStatus.value = if (success) {
                "✓ VoLTE enabled!\nRestart phone to apply fully."
            } else {
                "✗ Failed: ${message.take(100)}"
            }
        }
    }
    
    /**
     * Проверка статуса VoLTE
     */
    fun checkVoLTEStatus(context: Context) {
        viewModelScope.launch {
            _volteStatus.value = "Checking VoLTE status..."
            
            val status = withContext(Dispatchers.IO) {
                RootShell.checkVoLTEStatus()
            }
            
            val raw = status["raw"] ?: "No data"
            _volteStatus.value = "VoLTE Status:\n${raw.take(200)}"
        }
    }
    
    /**
     * АГРЕССИВНОЕ отключение 3G/WCDMA для Pixel 6 с Exynos модемом
     */
    fun ultraDisable3G(context: Context) {
        viewModelScope.launch {
            _ultra3GStatus.value = "Executing ULTRA disable 3G..."
            
            val (success, message) = withContext(Dispatchers.IO) {
                RootShell.ultraDisable3G()
            }
            
            _ultra3GStatus.value = if (success) {
                "✓ 3G/WCDMA DISABLED!\n${message.take(300)}"
            } else {
                "✗ Failed: ${message.take(200)}"
            }
        }
    }
    
    /**
     * Проверка статуса отключения 3G
     */
    fun check3GDisableStatus(context: Context) {
        viewModelScope.launch {
            _ultra3GStatus.value = "Checking 3G disable status..."
            
            val status = withContext(Dispatchers.IO) {
                RootShell.check3GDisableStatus()
            }
            
            val raw = status["raw"] ?: "No data"
            _ultra3GStatus.value = "3G Status:\n${raw.take(300)}"
        }
    }
}

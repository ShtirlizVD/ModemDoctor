package com.modemdoctor.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Сборщик логов модема и радио-интерфейса
 * Собирает все доступные логи для диагностики проблем с модемом
 */
class LogCollector(private val context: Context) {
    
    private val tag = "LogCollector"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
    
    /**
     * Результат сбора логов
     */
    data class LogCollectionResult(
        val timestamp: String,
        val logs: Map<String, String>,
        val deviceInfo: DeviceInfo,
        val networkState: NetworkState,
        val errors: List<String> = emptyList()
    )
    
    /**
     * Информация об устройстве
     */
    data class DeviceInfo(
        val manufacturer: String = Build.MANUFACTURER,
        val model: String = Build.MODEL,
        val device: String = Build.DEVICE,
        val board: String = Build.BOARD,
        val brand: String = Build.BRAND,
        val product: String = Build.PRODUCT,
        val androidVersion: String = Build.VERSION.RELEASE,
        val sdkVersion: Int = Build.VERSION.SDK_INT,
        val buildId: String = Build.ID,
        val buildFingerprint: String = Build.FINGERPRINT,
        val bootloader: String = Build.BOOTLOADER,
        val radioVersion: String = Build.getRadioVersion() ?: "unknown",
        val kernelVersion: String = System.getProperty("os.version") ?: "unknown"
    )
    
    /**
     * Состояние сети
     */
    data class NetworkState(
        val networkType: String = "unknown",
        val networkOperator: String = "unknown",
        val networkOperatorName: String = "unknown",
        val simState: String = "unknown",
        val simOperator: String = "unknown",
        val simOperatorName: String = "unknown",
        val signalStrength: Int = -1,
        val isRoaming: Boolean = false,
        val imei: String = "unknown",
        val imsi: String = "unknown",
        val phoneNumber: String = "unknown"
    )

/**
     * Собирает все логи и информацию о состоянии
     * @param compact Минимальный набор для авто-загрузки (~30KB)
     */
    suspend fun collectAll(compact: Boolean = false): LogCollectionResult {
        val timestamp = dateFormat.format(Date())
        val errors = mutableListOf<String>()
        val logs = mutableMapOf<String, String>()
        
        // Проверяем root
        if (!RootShell.checkRoot()) {
            errors.add("Root access not available")
        }
        
        if (compact) {
            // Компактный режим для авто-загрузки (~30KB)
            logs["radio_logcat"] = collectRadioLogcatCompact()
            logs["telephony_registry"] = collectTelephonyRegistry()
            logs["modem_properties"] = collectModemProperties()
            logs["modem_dmesg"] = collectModemDmesgCompact()
        } else {
            // Полный режим для ручного сбора (~100KB)
            logs["radio_logcat"] = collectRadioLogcat()
            logs["modem_dmesg"] = collectModemDmesg()
            logs["telephony_registry"] = collectTelephonyRegistry()
            logs["modem_properties"] = collectModemProperties()
            logs["ril_logs"] = collectRilLogs()
            logs["modem_stats"] = collectModemStats()
            logs["network_interfaces"] = collectNetworkInterfaces()
            logs["last_kmsg"] = collectLastKmsg()
            logs["modem_crash_logs"] = collectModemCrashLogsCompact()
        }
        
        // Информация об устройстве
        val deviceInfo = collectDeviceInfo()
        
        // Состояние сети
        val networkState = collectNetworkState()
        
        return LogCollectionResult(
            timestamp = timestamp,
            logs = logs.filter { it.value.isNotEmpty() },
            deviceInfo = deviceInfo,
            networkState = networkState,
            errors = errors
        )
    }
    
    /**
     * Логи radio buffer из logcat (полные)
     */
    private fun collectRadioLogcat(): String {
        val (_, output) = RootShell.execute("logcat -b radio -d -v time | tail -500")
        return output
    }
    
    /**
     * Логи radio buffer из logcat (компактные - последние 200 строк)
     */
    private fun collectRadioLogcatCompact(): String {
        val (_, output) = RootShell.execute("logcat -b radio -d -v time | tail -200")
        return output
    }
    
    /**
     * Сообщения ядра связанные с модемом (полные)
     */
    private fun collectModemDmesg(): String {
        val (_, output) = RootShell.execute("dmesg | grep -iE 'modem|radio|rild|qmi|imei|lte|5g|4g|3g|gsm|wcdma|nr|nsa|sa|exynos|s5100|s5300' | tail -100")
        return output
    }
    
    /**
     * Сообщения ядра связанные с модемом (компактные)
     */
    private fun collectModemDmesgCompact(): String {
        val (_, output) = RootShell.execute("dmesg | grep -iE 'modem|radio|rild|qmi|imei|lte|5g|4g|3g|gsm|wcdma|nr|nsa|sa|exynos|s5100|s5300' | tail -50")
        return output
    }
    
    /**
     * Состояние telephony registry
     */
    private fun collectTelephonyRegistry(): String {
        val (_, output) = RootShell.execute("dumpsys telephony.registry")
        return output
    }
    
    /**
     * Свойства модема из system properties
     */
    private fun collectModemProperties(): String {
        val commands = listOf(
            "getprop | grep -iE 'gsm|ril|radio|modem|telephony|qcom|exynos|vendor.ril'",
            "getprop ro.hardware",
            "getprop ro.board.platform",
            "getprop ro.baseband",
            "getprop gsm.version.ril-impl",
            "getprop gsm.nitz.time",
            "getprop gsm.operator.numeric",
            "getprop gsm.sim.operator.numeric",
            "getprop gsm.network.type"
        )
        val (_, output) = RootShell.execute(commands)
        return output
    }
    
    /**
     * RIL логи
     */
    private fun collectRilLogs(): String {
        val commands = listOf(
            "ls -la /data/vendor/radio/",
            "ls -la /data/misc/rild/",
            "cat /data/vendor/radio/rild.log 2>/dev/null || echo 'No rild.log'",
            "cat /data/misc/rild/rild.log 2>/dev/null || echo 'No misc rild.log'"
        )
        val (_, output) = RootShell.execute(commands)
        return output
    }
    

    
    /**
     * Статистика модема
     */
    private fun collectModemStats(): String {
        val commands = listOf(
            "cat /proc/net/arp",
            "cat /sys/class/net/rmnet0/statistics/rx_bytes 2>/dev/null || echo 'No rmnet0 rx'",
            "cat /sys/class/net/rmnet0/statistics/tx_bytes 2>/dev/null || echo 'No rmnet0 tx'",
            "cat /sys/class/net/rmnet_data0/statistics/rx_bytes 2>/dev/null || echo 'No rmnet_data0'",
            "ls -la /dev/cdc-wdm* 2>/dev/null || echo 'No cdc-wdm'",
            "ls -la /dev/qcqmi* 2>/dev/null || echo 'No qcqmi'",
            "cat /sys/kernel/debug/msm_ipc/router/xprt 2>/dev/null || echo 'No msm_ipc'"
        )
        val (_, output) = RootShell.execute(commands)
        return output
    }
    
    /**
     * Сетевые интерфейсы
     */
    private fun collectNetworkInterfaces(): String {
        val commands = listOf(
            "ip link show",
            "ip addr show",
            "ip route show",
            "ifconfig"
        )
        val (_, output) = RootShell.execute(commands)
        return output
    }
    

    

    

    
    /**
     * Last kernel message (последние сообщения ядра перед падением)
     */
    private fun collectLastKmsg(): String {
        val commands = listOf(
            "cat /proc/last_kmsg 2>/dev/null || echo 'No last_kmsg'",
            "cat /sys/fs/pstore/console-ramoops 2>/dev/null || echo 'No pstore console'",
            "ls -la /sys/fs/pstore/ 2>/dev/null || echo 'No pstore'"
        )
        val (_, output) = RootShell.execute(commands)
        return output
    }
    
    /**
     * Логи крашей модема (компактные)
     */
    private fun collectModemCrashLogsCompact(): String {
        val commands = listOf(
            "ls -la /data/tombstones/ 2>/dev/null || echo 'No tombstones'",
            "ls -la /data/vendor/radio/logs/ 2>/dev/null || echo 'No radio logs'"
        )
        val (_, output) = RootShell.execute(commands)
        return output
    }
    

    
    /**
     * Информация об устройстве
     */
    private fun collectDeviceInfo(): DeviceInfo {
        return DeviceInfo()
    }
    
    /**
     * Состояние сети через TelephonyManager
     */
    private fun collectNetworkState(): NetworkState {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        
        val networkType = when (telephonyManager.dataNetworkType) {
            TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE, 
            TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN -> "2G"
            TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_EVDO_B, TelephonyManager.NETWORK_TYPE_EHRPD,
            TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"
            TelephonyManager.NETWORK_TYPE_LTE -> "4G/LTE"
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            else -> "Unknown (${telephonyManager.dataNetworkType})"
        }
        
        val simStateStr = when (telephonyManager.simState) {
            TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
            TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN_REQUIRED"
            TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK_REQUIRED"
            TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "NETWORK_LOCKED"
            TelephonyManager.SIM_STATE_READY -> "READY"
            TelephonyManager.SIM_STATE_NOT_READY -> "NOT_READY"
            TelephonyManager.SIM_STATE_PERM_DISABLED -> "PERM_DISABLED"
            TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "CARD_IO_ERROR"
            else -> "UNKNOWN"
        }
        
        var imei = "unknown"
        var imsi = "unknown"
        var phoneNumber = "unknown"
        
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) 
                == PackageManager.PERMISSION_GRANTED) {
                imei = telephonyManager.imei ?: "unknown"
                imsi = telephonyManager.subscriberId ?: "unknown"
                phoneNumber = telephonyManager.line1Number ?: "unknown"
            }
        } catch (e: Exception) {
            Log.e(tag, "Error getting phone info", e)
        }
        
        return NetworkState(
            networkType = networkType,
            networkOperator = telephonyManager.networkOperator ?: "unknown",
            networkOperatorName = telephonyManager.networkOperatorName ?: "unknown",
            simState = simStateStr,
            simOperator = telephonyManager.simOperator ?: "unknown",
            simOperatorName = telephonyManager.simOperatorName ?: "unknown",
            signalStrength = telephonyManager.signalStrength?.level ?: -1,
            isRoaming = telephonyManager.isNetworkRoaming,
            imei = imei,
            imsi = imsi,
            phoneNumber = phoneNumber
        )
    }
    
    /**
     * Сохраняет логи в файл
     */
    fun saveToFile(result: LogCollectionResult, directory: File): File {
        val filename = "modem_log_${result.timestamp}.txt"
        val file = File(directory, filename)
        
        val sb = StringBuilder()
        sb.appendLine("=".repeat(80))
        sb.appendLine("MODEM DOCTOR - DIAGNOSTIC LOG")
        sb.appendLine("Timestamp: ${result.timestamp}")
        sb.appendLine("=".repeat(80))
        sb.appendLine()
        
        sb.appendLine("=== DEVICE INFO ===")
        result.deviceInfo.let { info ->
            sb.appendLine("Manufacturer: ${info.manufacturer}")
            sb.appendLine("Model: ${info.model}")
            sb.appendLine("Device: ${info.device}")
            sb.appendLine("Board: ${info.board}")
            sb.appendLine("Android: ${info.androidVersion} (SDK ${info.sdkVersion})")
            sb.appendLine("Build ID: ${info.buildId}")
            sb.appendLine("Fingerprint: ${info.buildFingerprint}")
            sb.appendLine("Radio Version: ${info.radioVersion}")
            sb.appendLine("Kernel: ${info.kernelVersion}")
        }
        sb.appendLine()
        
        sb.appendLine("=== NETWORK STATE ===")
        result.networkState.let { state ->
            sb.appendLine("Network Type: ${state.networkType}")
            sb.appendLine("Operator: ${state.networkOperatorName} (${state.networkOperator})")
            sb.appendLine("SIM State: ${state.simState}")
            sb.appendLine("SIM Operator: ${state.simOperatorName} (${state.simOperator})")
            sb.appendLine("Signal Level: ${state.signalStrength}/4")
            sb.appendLine("Roaming: ${state.isRoaming}")
            sb.appendLine("IMEI: ${state.imei}")
        }
        sb.appendLine()
        
        result.logs.forEach { (name, content) ->
            sb.appendLine("=== $name ===")
            sb.appendLine(content)
            sb.appendLine()
        }
        
        if (result.errors.isNotEmpty()) {
            sb.appendLine("=== ERRORS ===")
            result.errors.forEach { sb.appendLine("- $it") }
        }
        
        file.writeText(sb.toString())
        return file
    }
}

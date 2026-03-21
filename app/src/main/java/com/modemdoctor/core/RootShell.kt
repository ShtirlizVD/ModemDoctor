package com.modemdoctor.core

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Класс для выполнения команд с root-правами
 * Использует su binary для получения привилегий
 */
object RootShell {
    private const val TAG = "RootShell"
    private var hasRoot: Boolean? = null
    
    // Флаг для отключения 5G
    private val is5GDisabled = AtomicBoolean(false)
    
    /**
     * Проверяет наличие root-доступа (только один раз)
     */
    fun checkRoot(): Boolean {
        if (hasRoot != null) return hasRoot!!
        
        try {
            val process = Runtime.getRuntime().exec("su")
            val outputStream = process.outputStream
            outputStream.write("id\n".toByteArray())
            outputStream.flush()
            outputStream.write("exit\n".toByteArray())
            outputStream.flush()
            outputStream.close()
            
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readLine() ?: ""
            reader.close()
            
            process.waitFor()
            
            hasRoot = output.contains("uid=0") || process.exitValue() == 0
            Log.d(TAG, "Root check result: $hasRoot, output: $output")
            return hasRoot!!
        } catch (e: Exception) {
            Log.e(TAG, "Root check failed", e)
            hasRoot = false
            return false
        }
    }
    
    /**
     * Отключает все 5G функции на уровне системы
     * Это помогает предотвратить краши модема на Pixel 6 серии
     */
    fun disable5G(): Pair<Boolean, String> {
        if (!checkRoot()) return Pair(false, "No root access")
        
        val commands = listOf(
            // Отключаем NR Dual Connectivity
            "settings put global nr_dual_connectivity_enabled 0",
            "settings put global nr_state_tracking_enabled 0",
            
            // Отключаем VoNR (Voice over New Radio)
            "settings put global vo5g_enabled 0",
            "settings put global vonr_enabled 0",
            
            // Отключаем 5G в preferred network type
            "settings put global preferred_network_mode 11", // LTE only
            "settings put global preferred_network_mode0 11",
            
            // Properties для отключения 5G
            "setprop persist.radio.is_vonr_enabled_0 false",
            "setprop persist.vendor.radio.nsac.mode 0",
            "setprop persist.vendor.radio.support_nr_ds 0",
            
            // Принудительно LTE
            "setprop gsm.network.lte.access.allowed 1",
            
            // Перезапуск telephony service (опционально)
            // "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true",
            // "sleep 2",
            // "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false"
        )
        
        val (exitCode, output) = execute(commands)
        
        if (exitCode == 0) {
            is5GDisabled.set(true)
            Log.i(TAG, "5G disabled successfully")
            return Pair(true, "5G functions disabled successfully\n$output")
        } else {
            Log.e(TAG, "Failed to disable 5G: $output")
            return Pair(false, "Failed: $output")
        }
    }
    
    /**
     * Проверяет статус 5G
     */
    fun check5GStatus(): Map<String, String> {
        val status = mutableMapOf<String, String>()
        
        val commands = listOf(
            "settings get global nr_dual_connectivity_enabled",
            "settings get global vo5g_enabled",
            "settings get global preferred_network_mode",
            "getprop persist.radio.is_vonr_enabled_0",
            "getprop persist.vendor.radio.support_nr_ds"
        )
        
        val (_, output) = execute(commands)
        status["raw"] = output
        
        // Парсим вывод
        output.lines().forEach { line ->
            when {
                line.contains("nr_dual_connectivity") -> status["nr_dual"] = line.substringAfter("=").trim()
                line.contains("vo5g") -> status["vo5g"] = line.substringAfter("=").trim()
                line.contains("preferred_network") -> status["network_mode"] = line.substringAfter("=").trim()
            }
        }
        
        return status
    }
    
    fun is5GDisabled(): Boolean = is5GDisabled.get()
    
    /**
     * Включает VoLTE (Voice over LTE) навсегда
     * Работает на Samsung Exynos модемах (Pixel 6 series)
     */
    fun enableVoLTE(): Pair<Boolean, String> {
        if (!checkRoot()) return Pair(false, "No root access")
        
        val commands = listOf(
            // === ГЛОБАЛЬНЫЕ НАСТРОЙКИ ANDROID ===
            "settings put global volte_vt_enabled 1",
            "settings put global volte_enabled 1",
            "settings put global vt_ims_enabled 1",
            "settings put global wfc_ims_enabled 1",
            "settings put global volte_vt_available 1",
            "settings put global volte_available 1",
            
            // === НАСТРОЙКИ ДЛЯ СИМ-КАРТ (slot 0) ===
            "settings put global volte_vt_enabled0 1",
            "settings put global volte_enabled0 1",
            "settings put global vt_ims_enabled0 1",
            "settings put global wfc_ims_enabled0 1",
            
            // === Samsung/Exynos RIL properties ===
            "setprop persist.radio.vt.enable 1",
            "setprop persist.radio.volte.enable 1",
            "setprop persist.vendor.radio.volte.enable 1",
            "setprop persist.vendor.radio.vt.enable 1",
            "setprop persist.radio.calls.vt.enable 1",
            "setprop persist.radio.ims.vt.enable 1",
            
            // === Дополнительные IMS настройки ===
            "setprop persist.radio.ims.qmi.enable 1",
            "setprop persist.vendor.ims.disable 0",
            "setprop persist.radio.ims.disable 0",
            
            // === Включаем IMS сервисы ===
            "setprop vendor.ims.disabled 0",
            "setprop persist.vendor.ims.disabled 0",
            
            // === Для Google Pixel (Exynos variant) ===
            "setprop persist.radio.volte.donotinit 0"
        )
        
        val (exitCode, output) = execute(commands)
        
        if (exitCode == 0) {
            Log.i(TAG, "VoLTE enabled successfully")
            return Pair(true, "VoLTE enabled successfully!\n$output")
        } else {
            Log.e(TAG, "Failed to enable VoLTE: $output")
            return Pair(false, "Failed: $output")
        }
    }
    
    /**
     * АГРЕССИВНОЕ отключение 3G/WCDMA для Pixel 6 с проблемным Exynos модемом
     * Это НЕ просто LTE-only в настройках - это полный запрет WCDMA на уровне radio properties
     */
    fun ultraDisable3G(): Pair<Boolean, String> {
        if (!checkRoot()) return Pair(false, "No root access")
        
        val results = StringBuilder()
        results.appendLine("=== ULTRA DISABLE 3G/WCDMA ===")
        results.appendLine()
        
        val commands = mutableListOf<String>()
        
        // 1️⃣ Полностью отключить CS fallback (ГЛАВНОЕ!)
        // Это запрещает переход звонков в 3G
        commands.addAll(listOf(
            "resetprop -p persist.vendor.radio.disable_csfb 1",
            "setprop persist.vendor.radio.disable_csfb 1"
        ))
        
        // 2️⃣ Отключить WCDMA capability
        // Заставляет стек считать, что 3G нет (11 = LTE only)
        commands.addAll(listOf(
            "resetprop -p persist.radio.networkmode 11",
            "resetprop -p persist.vendor.radio.preferred_network_mode 11",
            "setprop persist.radio.networkmode 11",
            "setprop persist.vendor.radio.preferred_network_mode 11",
            "settings put global preferred_network_mode 11",
            "settings put global preferred_network_mode0 11"
        ))
        
        // 3️⃣ Отключить WCDMA scanning (ОЧЕНЬ ВАЖНО!)
        commands.addAll(listOf(
            "resetprop -p persist.vendor.radio.wcdma_disabled 1",
            "setprop persist.vendor.radio.wcdma_disabled 1"
        ))
        
        // 4️⃣ Принудительно LTE only через разные методы
        commands.addAll(listOf(
            "cmd phone set-preferred-network-type 0 LTE_ONLY",
            "service call phone 73 i32 0 i32 11"
        ))
        
        // 5️⃣ Зафиксировать IMS (чтобы не было fallback на 3G)
        commands.addAll(listOf(
            "resetprop -p persist.dbg.ims_volte_enable 1",
            "resetprop -p persist.dbg.volte_avail_ovr 1",
            "resetprop -p persist.dbg.ims_avail_ovr 1",
            "resetprop -p persist.dbg.wfc_avail_ovr 1",
            "setprop persist.dbg.ims_volte_enable 1",
            "setprop persist.dbg.volte_avail_ovr 1",
            "setprop persist.dbg.ims_avail_ovr 1",
            "setprop persist.dbg.wfc_avail_ovr 1"
        ))
        
        // 6️⃣ САМЫЙ ЖЁСТКИЙ - отключить WCDMA band scanning полностью
        commands.addAll(listOf(
            "resetprop -p persist.vendor.radio.wcdma_supported 0",
            "setprop persist.vendor.radio.wcdma_supported 0"
        ))
        
        // Дополнительные Samsung/Exynos специфичные настройки
        commands.addAll(listOf(
            // Блокируем все WCDMA частоты
            "resetprop -p persist.radio.wcdma.band 0",
            "setprop persist.radio.wcdma.band 0",
            
            // Отключаем TD-SCDMA (китайский 3G)
            "resetprop -p persist.vendor.radio.tdscdma_supported 0",
            "setprop persist.vendor.radio.tdscdma_supported 0",
            
            // Форсируем только LTE bands
            "resetprop -p persist.radio.lte.band 1:2:3:4:5:7:8:12:13:14:17:18:19:20:25:26:28:29:30:38:39:40:41:46:48:66:71",
            
            // Запрещаем любой fallback
            "resetprop -p persist.vendor.radio.no_ps_fb 1",
            "setprop persist.vendor.radio.no_ps_fb 1"
        ))
        
        // Записываем в telephony.db через content provider
        commands.addAll(listOf(
            "content insert --uri content://telephony/carriers/preferapn --bind preferred_network_type:i:11",
            "settings put secure preferred_network_mode 11"
        ))
        
        val (exitCode, output) = execute(commands)
        
        if (exitCode == 0) {
            results.appendLine("✓ Все команды выполнены успешно!")
            results.appendLine()
            results.appendLine("Выполнено:")
            results.appendLine("1. CS Fallback отключен (звонки не упадут в 3G)")
            results.appendLine("2. WCDMA capability отключена")
            results.appendLine("3. WCDMA scanning отключен")
            results.appendLine("4. LTE only форсирован")
            results.appendLine("5. IMS/VoLTE зафиксирован")
            results.appendLine("6. WCDMA supported = 0 (САМОЕ ЖЁСТКОЕ)")
            results.appendLine()
            results.appendLine("⚠️ ТРЕБУЕТСЯ ПЕРЕЗАГРУЗКА для полного применения!")
            results.appendLine()
            results.appendLine("Output:")
            results.append(output)
            
            Log.i(TAG, "Ultra 3G disable completed successfully")
            return Pair(true, results.toString())
        } else {
            results.appendLine("✗ Ошибка выполнения!")
            results.append(output)
            Log.e(TAG, "Ultra 3G disable failed: $output")
            return Pair(false, results.toString())
        }
    }
    
    /**
     * Проверяет статус отключения 3G
     */
    fun check3GDisableStatus(): Map<String, String> {
        val status = mutableMapOf<String, String>()
        
        val commands = listOf(
            "echo '=== CS FALLBACK ==='",
            "getprop persist.vendor.radio.disable_csfb",
            "echo '=== NETWORK MODE ==='",
            "getprop persist.radio.networkmode",
            "getprop persist.vendor.radio.preferred_network_mode",
            "settings get global preferred_network_mode",
            "echo '=== WCDMA STATUS ==='",
            "getprop persist.vendor.radio.wcdma_disabled",
            "getprop persist.vendor.radio.wcdma_supported",
            "echo '=== IMS STATUS ==='",
            "getprop persist.dbg.ims_volte_enable",
            "getprop persist.dbg.volte_avail_ovr",
            "echo '=== CURRENT NETWORK ==='",
            "dumpsys telephony.registry | grep -E 'mServiceState|mDataConnectionState' | head -10"
        )
        
        val (_, output) = execute(commands)
        status["raw"] = output
        
        // Парсим важные значения
        output.lines().forEach { line ->
            when {
                line.contains("disable_csfb") && !line.contains("echo") -> 
                    status["csfb_disabled"] = line.trim()
                line.contains("wcdma_disabled") && !line.contains("echo") -> 
                    status["wcdma_disabled"] = line.trim()
                line.contains("wcdma_supported") && !line.contains("echo") -> 
                    status["wcdma_supported"] = line.trim()
                line.contains("preferred_network_mode") && !line.contains("echo") && !line.contains("getprop") -> 
                    status["network_mode"] = line.trim()
            }
        }
        
        return status
    }
    
    /**
     * Проверяет статус VoLTE
     */
    fun checkVoLTEStatus(): Map<String, String> {
        val status = mutableMapOf<String, String>()
        
        val commands = listOf(
            "echo '=== VoLTE Settings ==='",
            "settings get global volte_vt_enabled",
            "settings get global volte_enabled",
            "settings get global vt_ims_enabled",
            "settings get global volte_vt_available",
            "echo '=== VoLTE Properties ==='",
            "getprop persist.radio.volte.enable",
            "getprop persist.vendor.radio.volte.enable",
            "getprop persist.radio.vt.enable",
            "getprop persist.vendor.ims.disabled",
            "echo '=== IMS Status ==='",
            "dumpsys isms | head -30",
            "dumpsys ims | head -30"
        )
        
        val (_, output) = execute(commands)
        status["raw"] = output
        
        return status
    }
    
    /**
     * Перезапускает IMS сервис (для применения настроек VoLTE)
     */
    fun restartImsService(): Pair<Boolean, String> {
        if (!checkRoot()) return Pair(false, "No root access")
        
        val commands = listOf(
            "am force-stop com.android.ims.rcsservice",
            "am force-stop com.android.ims.service",
            "sleep 1",
            "am start-service -n com.android.ims.rcsservice/.RcsService",
            "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true",
            "sleep 2",
            "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false"
        )
        
        val (exitCode, output) = execute(commands)
        
        return if (exitCode == 0) {
            Pair(true, "IMS service restarted\n$output")
        } else {
            Pair(false, "Failed: $output")
        }
    }

    /**
     * Выполняет команду с root-правами
     * @param command Команда для выполнения
     * @return Pair<exitCode, output>
     */
    fun execute(command: String): Pair<Int, String> {
        return execute(listOf(command))
    }

    /**
     * Выполняет несколько команд с root-правами
     * @param commands Список команд
     * @return Pair<exitCode, combinedOutput>
     */
    fun execute(commands: List<String>): Pair<Int, String> {
        try {
            val process = Runtime.getRuntime().exec("su")
            val outputStream = process.outputStream
            val writer = outputStream.writer()
            
            commands.forEach { cmd ->
                writer.write("$cmd\n")
            }
            writer.write("exit\n")
            writer.flush()
            writer.close()
            outputStream.close()
            
            val outputReader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            
            val output = StringBuilder()
            val errors = StringBuilder()
            
            var line: String? = outputReader.readLine()
            while (line != null) {
                output.append(line).append("\n")
                line = outputReader.readLine()
            }
            outputReader.close()
            
            line = errorReader.readLine()
            while (line != null) {
                errors.append(line).append("\n")
                line = errorReader.readLine()
            }
            errorReader.close()
            
            val exitCode = process.waitFor()
            
            val combinedOutput = if (errors.isNotEmpty()) {
                "${output}\n[STDERR]\n$errors"
            } else {
                output.toString()
            }
            
            return Pair(exitCode, combinedOutput)
        } catch (e: Exception) {
            Log.e(TAG, "Command execution failed", e)
            return Pair(-1, "Exception: ${e.message}")
        }
    }

    /**
     * Выполняет команду и возвращает результат асинхронно
     */
    fun executeAsync(command: String, callback: (Int, String) -> Unit) {
        Thread {
            val result = execute(command)
            callback(result.first, result.second)
        }.start()
    }

    // ============= BATTERY CHARGE LIMIT =============

    /**
     * Проверяет текущий статус ограничения зарядки
     * @return Pair<enabled, limitPercent>
     */
    fun getChargeLimitStatus(): Pair<Boolean, Int> {
        // Метод 1: Android 15+ Battery Charging State Controls
        val (_, output1) = execute("settings get global battery_charging_state_controls")
        if (output1.trim() == "1" || output1.trim() == "80") {
            return Pair(true, 80)
        }

        // Метод 2: Adaptive Charging
        val (_, output2) = execute("settings get global adaptive_charging_enabled")
        if (output2.trim() == "1") {
            return Pair(true, 80)
        }

        // Метод 3: sysfs (Samsung/Some devices)
        val (_, output3) = execute("cat /sys/class/power_supply/battery/charge_control_limit 2>/dev/null")
        val limit = output3.trim().toIntOrNull()
        if (limit != null && limit in 60..100) {
            return Pair(limit < 100, limit)
        }

        return Pair(false, 100)
    }

    /**
     * Включает ограничение зарядки до 80%
     */
    fun setChargeLimit80(): Pair<Boolean, String> {
        if (!checkRoot()) return Pair(false, "No root access")
        
        val results = mutableListOf<String>()
        var success = false

        // Метод 1: Android 15+ (Pixel)
        val (exit1, _) = execute("settings put global battery_charging_state_controls 1")
        if (exit1 == 0) {
            results.add("✓ battery_charging_state_controls=1")
            success = true
        }

        // Метод 2: Adaptive Charging (Android 12+)
        val (exit2, _) = execute("settings put global adaptive_charging_enabled 1")
        if (exit2 == 0) {
            results.add("✓ adaptive_charging_enabled=1")
            success = true
        }

        // Метод 3: Secure settings
        val (exit3, _) = execute("settings put secure adaptive_charging_enabled 1")
        if (exit3 == 0) {
            results.add("✓ secure adaptive_charging=1")
        }

        // Метод 4: sysfs для Samsung/других
        val sysfsPaths = listOf(
            "/sys/class/power_supply/battery/charge_control_limit",
            "/sys/class/power_supply/google,battery/charge_control_limit"
        )

        for (path in sysfsPaths) {
            val (exit, out) = execute("if [ -f $path ]; then echo 80 > $path && echo OK; fi")
            if (exit == 0 && out.contains("OK")) {
                results.add("✓ sysfs: $path = 80")
                success = true
            }
        }

        // Метод 5: Pixel vendor property
        val (exit5, _) = execute("setprop persist.vendor.charger.limit 80")
        if (exit5 == 0) {
            results.add("✓ vendor.charger.limit=80")
        }

        return if (success) {
            Pair(true, "Ограничение до 80% включено:\n${results.joinToString("\n")}")
        } else {
            Pair(false, "Не удалось включить:\n${results.joinToString("\n")}")
        }
    }

    /**
     * Отключает ограничение зарядки (100%)
     */
    fun disableChargeLimit(): Pair<Boolean, String> {
        if (!checkRoot()) return Pair(false, "No root access")
        
        val results = mutableListOf<String>()
        var success = false

        val (exit1, _) = execute("settings put global battery_charging_state_controls 0")
        if (exit1 == 0) {
            results.add("✓ battery_charging_state_controls=0")
            success = true
        }

        val (exit2, _) = execute("settings put global adaptive_charging_enabled 0")
        if (exit2 == 0) {
            results.add("✓ adaptive_charging_enabled=0")
            success = true
        }

        val (exit3, _) = execute("settings put secure adaptive_charging_enabled 0")
        if (exit3 == 0) {
            results.add("✓ secure adaptive_charging=0")
        }

        // sysfs - вернуть 100%
        val sysfsPaths = listOf(
            "/sys/class/power_supply/battery/charge_control_limit",
            "/sys/class/power_supply/google,battery/charge_control_limit"
        )

        for (path in sysfsPaths) {
            val (exit, out) = execute("if [ -f $path ]; then echo 100 > $path && echo OK; fi")
            if (exit == 0 && out.contains("OK")) {
                results.add("✓ sysfs: $path = 100")
                success = true
            }
        }

        val (exit5, _) = execute("setprop persist.vendor.charger.limit 100")
        if (exit5 == 0) {
            results.add("✓ vendor.charger.limit=100")
        }

        return if (success) {
            Pair(true, "Зарядка до 100%:\n${results.joinToString("\n")}")
        } else {
            Pair(false, "Не удалось отключить:\n${results.joinToString("\n")}")
        }
    }
}

package com.modemdoctor.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receiver для автозапуска мониторинга при загрузке устройства
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
        const val PREFS_NAME = "modem_doctor_prefs"
        const val KEY_AUTO_START = "auto_start_monitoring"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received boot intent: ${intent.action}")
        
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || 
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            
            // Проверяем, включён ли автозапуск
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean(KEY_AUTO_START, false)
            
            if (autoStart) {
                Log.d(TAG, "Auto-starting modem monitoring service")
                val serviceIntent = Intent(context, ModemMonitorService::class.java).apply {
                    action = ModemMonitorService.ACTION_START_MONITORING
                }
                context.startForegroundService(serviceIntent)
            }
        }
    }
}

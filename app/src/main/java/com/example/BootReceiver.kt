package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * BootReceiver detects when the Android system finishes booting (ACTION_BOOT_COMPLETED).
 * It automatically launches the persistent foreground services so that modular launcher overlay features
 * are immediately functional upon startup.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Boot COMPLETED, initiating LauncherService...")
            val serviceIntent = Intent(context, LauncherService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e("BootReceiver", "Failed to auto-start LauncherService on boot", e)
            }
        }
    }
}

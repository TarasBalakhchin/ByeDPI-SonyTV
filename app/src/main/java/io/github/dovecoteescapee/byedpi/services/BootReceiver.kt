package io.github.dovecoteescapee.byedpi.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.dovecoteescapee.byedpi.data.START_ACTION
import io.github.dovecoteescapee.byedpi.utility.getPreferences

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            val prefs = context.getPreferences()
            if (prefs.getBoolean("tv_autostart", false)) {
                // Запускаем только если разрешение VPN уже было выдано ранее
                if (VpnService.prepare(context) == null) {
                    Log.i("BootReceiver", "Autostarting ByeDPI VPN on TV boot")
                    val serviceIntent = Intent(context, ByeDpiVpnService::class.java).apply {
                        this.action = START_ACTION
                    }
                    ContextCompat.startForegroundService(context, serviceIntent)
                }
            }
        }
    }
}

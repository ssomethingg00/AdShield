package com.example.adshield.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restarts the VPN service after device reboot.
 * Disabled by default in AndroidManifest — enabled when user enables "Start on boot" setting.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("BootReceiver", "Boot completed — starting AdShield VPN")
            AdBlockVpnService.start(context)
        }
    }
}

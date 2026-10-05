package com.safeguard.tubeguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.safeguard.tubeguard.service.TubeGuardVpnService
import com.safeguard.tubeguard.util.PrefsManager

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TubeGuardBoot"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "Received boot/update broadcast: $action")
                handleBoot(context)
            }
        }
    }

    private fun handleBoot(context: Context) {
        val prefsManager = PrefsManager.getInstance(context)

        if (!prefsManager.isBlocking) {
            Log.i(TAG, "Blocking was disabled, skipping auto-start")
            return
        }

        // Check if VPN permission is still granted
        val vpnIntent = VpnService.prepare(context)
        if (vpnIntent != null) {
            // VPN permission needs user interaction; cannot auto-start without it
            Log.w(TAG, "VPN permission not granted, cannot auto-start VPN on boot")
            // Mark VPN as not enabled since we can't start it
            prefsManager.vpnEnabled = false
            return
        }

        // Start the VPN service
        try {
            val serviceIntent = Intent(context, TubeGuardVpnService::class.java).apply {
                action = TubeGuardVpnService.ACTION_START
            }
            context.startForegroundService(serviceIntent)
            Log.i(TAG, "TubeGuard VPN service started on boot")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN service on boot: ${e.message}")
        }
    }
}

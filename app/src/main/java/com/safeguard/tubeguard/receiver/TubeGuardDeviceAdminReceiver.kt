package com.safeguard.tubeguard.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.safeguard.tubeguard.util.PrefsManager

class TubeGuardDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "TubeGuardAdmin"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device admin enabled")
        PrefsManager.getInstance(context).deviceAdminEnabled = true
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(TAG, "Device admin disabled")
        PrefsManager.getInstance(context).deviceAdminEnabled = false
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "TubeGuard 기기 관리자를 해제하면 앱 삭제 방지가 비활성화됩니다. PIN이 필요합니다."
    }
}

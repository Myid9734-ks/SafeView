package com.safeguard.tubeguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.safeguard.tubeguard.service.TubeGuardAccessibilityService
import com.safeguard.tubeguard.service.TubeGuardVpnService
import com.safeguard.tubeguard.util.FileLogger
import com.safeguard.tubeguard.util.PrefsManager

class TimerExpiredReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TIMER_EXPIRED = "com.safeguard.tubeguard.TIMER_EXPIRED"
        const val ACTION_MIDNIGHT_RESET = "com.safeguard.tubeguard.MIDNIGHT_RESET"
        private const val TAG = "TimerExpiredReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val prefs = PrefsManager.getInstance(context)

        when (action) {
            ACTION_TIMER_EXPIRED -> {
                val remaining = prefs.getTimerRemainingSeconds()
                val isRunning = prefs.timerIsRunning
                val allowed = prefs.allowedSeconds
                val msg = "TIMER_EXPIRED 수신 | remaining=${remaining}s | isRunning=$isRunning | allowed=${allowed}s | isBlocking=${prefs.isBlocking}"
                Log.i(TAG, msg)
                FileLogger.log(context, TAG, msg)
                if (isRunning) {
                    prefs.pauseTimer()
                    Log.i(TAG, "Timer paused — enabling blocking")
                    FileLogger.log(context, TAG, "→ 조건 통과: 차단 활성화 시도")
                    enableBlocking(context, prefs)
                } else {
                    val skip = "→ 조건 실패로 차단 건너뜀: remaining=${remaining}s isRunning=$isRunning"
                    Log.w(TAG, skip)
                    FileLogger.log(context, TAG, skip)
                }
            }
            ACTION_MIDNIGHT_RESET -> {
                val wasRunning = prefs.timerIsRunning
                val allowed = prefs.allowedSeconds
                Log.i(TAG, "Midnight reset alarm received | wasRunning=$wasRunning | allowed=${allowed}s")
                prefs.checkMidnightReset()
                Log.i(TAG, "Midnight reset done | newRemaining=${prefs.getTimerRemainingSeconds()}s")
                if (wasRunning) {
                    Log.i(TAG, "Timer was running — enabling blocking after midnight reset")
                    enableBlocking(context, prefs)
                }
            }
        }
    }

    private fun enableBlocking(context: Context, prefs: PrefsManager) {
        prefs.isBlocking = true
        try {
            val serviceIntent = Intent(context, TubeGuardVpnService::class.java).apply {
                action = TubeGuardVpnService.ACTION_START
            }
            context.startForegroundService(serviceIntent)
            Log.i(TAG, "Blocking re-enabled after timer")
            FileLogger.log(context, TAG, "VPN startForegroundService 호출 성공")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to re-enable blocking: ${e.message}")
            FileLogger.log(context, TAG, "VPN 시작 실패: ${e.message}")
        }
        LocalBroadcastManager.getInstance(context)
            .sendBroadcast(Intent(TubeGuardAccessibilityService.ACTION_FORCE_BLOCK_NOW))
    }
}

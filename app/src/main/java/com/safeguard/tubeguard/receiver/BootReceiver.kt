package com.safeguard.tubeguard.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.safeguard.tubeguard.MainActivity
import com.safeguard.tubeguard.service.TubeGuardAccessibilityService
import com.safeguard.tubeguard.service.TubeGuardVpnService
import com.safeguard.tubeguard.util.FileLogger
import com.safeguard.tubeguard.util.PrefsManager
import java.util.Calendar

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

        // 자정 리셋 확인 (날짜가 바뀌었으면 타이머 초기화)
        prefsManager.checkMidnightReset()

        // 자정 리셋 알람 재등록 (재부팅 시 AlarmManager 알람이 모두 삭제되므로)
        scheduleMidnightReset(context)

        // 타이머가 실행 중이었는지 확인
        if (prefsManager.timerIsRunning) {
            val remaining = prefsManager.getTimerRemainingSeconds()
            FileLogger.log(context, TAG, "부팅 감지: 타이머 실행 중 | remaining=${remaining}s")

            if (remaining <= 0) {
                // 재부팅 중 타이머 만료 → 차단 활성화
                Log.i(TAG, "Timer expired during reboot — enabling blocking")
                FileLogger.log(context, TAG, "타이머 재부팅 중 만료 → 차단 활성화")
                prefsManager.pauseTimer()
                prefsManager.isBlocking = true
                startVpnIfPermitted(context, prefsManager)
                // 접근성 서비스에 즉시 차단 신호
                LocalBroadcastManager.getInstance(context)
                    .sendBroadcast(Intent(TubeGuardAccessibilityService.ACTION_FORCE_BLOCK_NOW))
            } else {
                // 타이머 아직 남음 → 만료 알람 재등록, 차단은 해제 상태 유지
                Log.i(TAG, "Timer still running after reboot — re-scheduling alarm (${remaining}s)")
                FileLogger.log(context, TAG, "타이머 잔여 ${remaining}s → 알람 재등록")
                scheduleTimerExpiryAlarm(context, remaining)
            }
            return
        }

        // 기존 로직: isBlocking이면 VPN 재시작
        if (!prefsManager.isBlocking) {
            Log.i(TAG, "Blocking was disabled, skipping auto-start")
            return
        }

        startVpnIfPermitted(context, prefsManager)
    }

    private fun startVpnIfPermitted(context: Context, prefsManager: PrefsManager) {
        val vpnIntent = VpnService.prepare(context)
        if (vpnIntent != null) {
            Log.w(TAG, "VPN permission not granted, cannot auto-start VPN on boot")
            prefsManager.vpnEnabled = false
            return
        }

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

    private fun scheduleTimerExpiryAlarm(context: Context, remainingSeconds: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, TimerExpiredReceiver::class.java).apply {
            action = TimerExpiredReceiver.ACTION_TIMER_EXPIRED
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, MainActivity.TIMER_EXPIRY_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = System.currentTimeMillis() + remainingSeconds * 1000L
        val canExact = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()
        if (canExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
        FileLogger.log(context, TAG, "타이머 알람 재등록: ${remainingSeconds}초 후 | canExact=$canExact")
    }

    private fun scheduleMidnightReset(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, TimerExpiredReceiver::class.java).apply {
            action = TimerExpiredReceiver.ACTION_MIDNIGHT_RESET
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, MainActivity.MIDNIGHT_RESET_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val midnight = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, midnight, pendingIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, midnight, pendingIntent)
        }
        Log.i(TAG, "Midnight reset alarm re-scheduled after boot")
    }
}

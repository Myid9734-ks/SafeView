package com.safeguard.tubeguard.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BlockLogEntry(val timestamp: String, val type: String, val detail: String)

class PrefsManager(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    companion object {
        private const val PREFS_NAME = "tubeguard_prefs"
        private const val KEY_IS_BLOCKING = "is_blocking"
        private const val KEY_HAS_PIN = "has_pin"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_VPN_ENABLED = "vpn_enabled"
        private const val KEY_ACCESSIBILITY_NOTICE_SHOWN = "accessibility_notice_shown"
        private const val KEY_BLOCK_COUNT = "block_count"
        private const val KEY_DEVICE_ADMIN_ENABLED = "device_admin_enabled"
        private const val KEY_BLOCK_LOG = "block_log"
        private const val MAX_LOG_ENTRIES = 100
        private const val LOG_SEPARATOR = "\n"
        private const val FIELD_SEPARATOR = "|"
        const val KEY_ALLOWED_SECONDS = "allowed_seconds"
        const val KEY_TIMER_SAVED_REMAINING = "timer_saved_remaining"
        const val KEY_TIMER_IS_RUNNING = "timer_is_running"
        const val KEY_TIMER_START_EPOCH = "timer_start_epoch"
        const val KEY_TIMER_RESET_DATE = "timer_reset_date"
        private const val KEY_INITIAL_SETUP_COMPLETE = "initial_setup_complete"
        const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_IS_PREMIUM = "is_premium"
        private const val KEY_PURCHASE_TOKEN = "purchase_token"
        private const val KEY_TRIAL_START_DATE = "trial_start_date"
        private const val KEY_KAKAO_HOURS_ENABLED = "kakao_hours_enabled"
        private const val KEY_KAKAO_START_MINUTES = "kakao_start_minutes"
        private const val KEY_KAKAO_END_MINUTES = "kakao_end_minutes"
        private const val KEY_UNAPPROVED_PACKAGES = "unapproved_packages"
        private const val KEY_STORE_UNLOCK_LAST_ACTIVE = "store_unlock_last_active"
        // 스토어 잠금 해제가 끝난 뒤에도 이 시간 안에 설치 완료된 앱은 보호자가 설치한 것으로 간주 (큰 앱 다운로드 대기)
        private const val STORE_UNLOCK_GRACE_MS = 30 * 60 * 1000L

        @Volatile
        private var instance: PrefsManager? = null

        fun getInstance(context: Context): PrefsManager {
            return instance ?: synchronized(this) {
                instance ?: PrefsManager(context.applicationContext).also { instance = it }
            }
        }
    }

    var isBlocking: Boolean
        get() = prefs.getBoolean(KEY_IS_BLOCKING, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_BLOCKING, value).apply()

    var hasPin: Boolean
        get() = prefs.getBoolean(KEY_HAS_PIN, false)
        set(value) = prefs.edit().putBoolean(KEY_HAS_PIN, value).apply()

    var pinHash: String
        get() = prefs.getString(KEY_PIN_HASH, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PIN_HASH, value).apply()

    var vpnEnabled: Boolean
        get() = prefs.getBoolean(KEY_VPN_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_VPN_ENABLED, value).apply()

    var accessibilityNoticeShown: Boolean
        get() = prefs.getBoolean(KEY_ACCESSIBILITY_NOTICE_SHOWN, false)
        set(value) = prefs.edit().putBoolean(KEY_ACCESSIBILITY_NOTICE_SHOWN, value).apply()

    var blockCount: Int
        get() = prefs.getInt(KEY_BLOCK_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_BLOCK_COUNT, value).apply()

    var deviceAdminEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEVICE_ADMIN_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_DEVICE_ADMIN_ENABLED, value).apply()

    var initialSetupComplete: Boolean
        get() = prefs.getBoolean(KEY_INITIAL_SETUP_COMPLETE, false)
        set(value) = prefs.edit().putBoolean(KEY_INITIAL_SETUP_COMPLETE, value).apply()

    var pinSalt: String
        get() = prefs.getString(KEY_PIN_SALT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PIN_SALT, value).apply()

    var isPremium: Boolean
        get() = prefs.getBoolean(KEY_IS_PREMIUM, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_PREMIUM, value).apply()

    var purchaseToken: String
        get() = prefs.getString(KEY_PURCHASE_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PURCHASE_TOKEN, value).apply()

    var trialStartDate: Long
        get() = prefs.getLong(KEY_TRIAL_START_DATE, 0L)
        set(value) = prefs.edit().putLong(KEY_TRIAL_START_DATE, value).apply()

    // 스토어 잠금 상태에서 설치된 앱 — PIN 승인 전까지 실행 차단
    fun getUnapprovedPackages(): Set<String> =
        prefs.getStringSet(KEY_UNAPPROVED_PACKAGES, emptySet())?.toSet() ?: emptySet()

    fun addUnapprovedPackage(packageName: String) {
        prefs.edit().putStringSet(KEY_UNAPPROVED_PACKAGES, getUnapprovedPackages() + packageName).apply()
    }

    fun removeUnapprovedPackage(packageName: String) {
        prefs.edit().putStringSet(KEY_UNAPPROVED_PACKAGES, getUnapprovedPackages() - packageName).apply()
    }

    /** 스토어가 PIN으로 잠금 해제된 상태였음을 기록 (해제 시점·재잠금 시점에 호출) */
    fun markStoreUnlockActive() {
        prefs.edit().putLong(KEY_STORE_UNLOCK_LAST_ACTIVE, System.currentTimeMillis()).apply()
    }

    fun isWithinStoreUnlockGrace(): Boolean {
        val last = prefs.getLong(KEY_STORE_UNLOCK_LAST_ACTIVE, 0L)
        return last > 0L && System.currentTimeMillis() - last < STORE_UNLOCK_GRACE_MS
    }

    fun incrementBlockCount() {
        blockCount = blockCount + 1
    }

    fun addBlockLog(type: String, detail: String) {
        val timestamp = SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault()).format(Date())
        val newEntry = "$timestamp$FIELD_SEPARATOR$type$FIELD_SEPARATOR$detail"
        val existing = prefs.getString(KEY_BLOCK_LOG, "") ?: ""
        val entries = if (existing.isBlank()) mutableListOf() else existing.split(LOG_SEPARATOR).toMutableList()
        entries.add(0, newEntry) // newest first
        if (entries.size > MAX_LOG_ENTRIES) entries.subList(MAX_LOG_ENTRIES, entries.size).clear()
        prefs.edit().putString(KEY_BLOCK_LOG, entries.joinToString(LOG_SEPARATOR)).apply()
    }

    fun getBlockLogs(): List<BlockLogEntry> {
        val raw = prefs.getString(KEY_BLOCK_LOG, "") ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split(LOG_SEPARATOR).mapNotNull { line ->
            val parts = line.split(FIELD_SEPARATOR)
            if (parts.size >= 3) BlockLogEntry(parts[0], parts[1], parts[2]) else null
        }
    }

    fun clearBlockLogs() {
        prefs.edit().remove(KEY_BLOCK_LOG).apply()
    }

    var allowedSeconds: Long
        get() = prefs.getLong(KEY_ALLOWED_SECONDS, 3600L)
        set(value) = prefs.edit().putLong(KEY_ALLOWED_SECONDS, value).apply()

    var timerSavedRemaining: Long
        get() = prefs.getLong(KEY_TIMER_SAVED_REMAINING, -1L).let {
            if (it == -1L) allowedSeconds else it
        }
        set(value) = prefs.edit().putLong(KEY_TIMER_SAVED_REMAINING, value).apply()

    var timerIsRunning: Boolean
        get() = prefs.getBoolean(KEY_TIMER_IS_RUNNING, false)
        set(value) = prefs.edit().putBoolean(KEY_TIMER_IS_RUNNING, value).apply()

    var timerStartEpoch: Long
        get() = prefs.getLong(KEY_TIMER_START_EPOCH, 0L)
        set(value) = prefs.edit().putLong(KEY_TIMER_START_EPOCH, value).apply()

    var timerResetDate: String
        get() = prefs.getString(KEY_TIMER_RESET_DATE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TIMER_RESET_DATE, value).apply()

    fun getTimerRemainingSeconds(): Long {
        checkMidnightReset()
        val saved = timerSavedRemaining
        if (!timerIsRunning) return saved
        val elapsed = (System.currentTimeMillis() - timerStartEpoch) / 1000L
        return maxOf(0L, saved - elapsed)
    }

    fun checkMidnightReset() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        if (timerResetDate != today) {
            timerResetDate = today
            timerSavedRemaining = allowedSeconds
            timerIsRunning = false
            timerStartEpoch = 0L
            blockCount = 0
        }
    }

    fun pauseTimer() {
        if (timerIsRunning) {
            timerSavedRemaining = getTimerRemainingSeconds()
            timerIsRunning = false
            timerStartEpoch = 0L
        }
    }

    fun startTimerNow() {
        checkMidnightReset()
        timerIsRunning = true
        timerStartEpoch = System.currentTimeMillis()
    }

    // 카카오톡 사용 가능 시간대 (자정 기준 분 단위, 예: 08:00 = 480)
    var kakaoHoursEnabled: Boolean
        get() = prefs.getBoolean(KEY_KAKAO_HOURS_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_KAKAO_HOURS_ENABLED, value).apply()

    var kakaoStartMinutes: Int
        get() = prefs.getInt(KEY_KAKAO_START_MINUTES, 8 * 60)
        set(value) = prefs.edit().putInt(KEY_KAKAO_START_MINUTES, value).apply()

    var kakaoEndMinutes: Int
        get() = prefs.getInt(KEY_KAKAO_END_MINUTES, 20 * 60)
        set(value) = prefs.edit().putInt(KEY_KAKAO_END_MINUTES, value).apply()

    /** 지금 카카오톡을 열 수 있는지. 시작 > 종료이면 자정을 넘기는 구간(예: 22:00~07:00)으로 본다. */
    fun isKakaoAllowedNow(): Boolean {
        if (!kakaoHoursEnabled) return true
        val cal = java.util.Calendar.getInstance()
        val now = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val start = kakaoStartMinutes
        val end = kakaoEndMinutes
        return if (start <= end) now in start until end else now >= start || now < end
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}

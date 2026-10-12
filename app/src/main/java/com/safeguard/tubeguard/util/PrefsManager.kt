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

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}

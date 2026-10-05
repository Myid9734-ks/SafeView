package com.safeguard.tubeguard.util

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BlockLogEntry(val timestamp: String, val type: String, val detail: String)

class PrefsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}

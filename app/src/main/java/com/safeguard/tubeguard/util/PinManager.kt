package com.safeguard.tubeguard.util

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64

class PinManager(private val context: Context) {

    private val prefsManager = PrefsManager.getInstance(context)

    companion object {
        private const val HASH_ALGORITHM = "SHA-256"
        private const val SALT_PREFIX = "TubeGuard_Salt_v1_"
        private const val MIN_PIN_LENGTH = 4
        private const val MAX_PIN_LENGTH = 8

        fun isValidPin(pin: String): Boolean {
            return pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all { it.isDigit() }
        }
    }

    /**
     * Hash a PIN using SHA-256 with a static salt derived from the package name.
     * This provides consistent hashing across app restarts without needing to store a salt.
     */
    private fun hashPin(pin: String): String {
        val saltedPin = "$SALT_PREFIX$pin"
        val digest = MessageDigest.getInstance(HASH_ALGORITHM)
        val hashBytes = digest.digest(saltedPin.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(hashBytes, Base64.NO_WRAP)
    }

    /**
     * Set a new PIN. Stores the hashed version in SharedPreferences.
     * @param pin The plain-text PIN to store (4-8 digits)
     * @return true if PIN was set successfully, false if PIN format is invalid
     */
    fun setPin(pin: String): Boolean {
        if (!isValidPin(pin)) return false
        val hashed = hashPin(pin)
        prefsManager.pinHash = hashed
        prefsManager.hasPin = true
        return true
    }

    /**
     * Verify a PIN against the stored hash.
     * @param pin The plain-text PIN to verify
     * @return true if PIN matches, false otherwise
     */
    fun verifyPin(pin: String): Boolean {
        if (!prefsManager.hasPin) return false
        val storedHash = prefsManager.pinHash
        if (storedHash.isEmpty()) return false
        val inputHash = hashPin(pin)
        return inputHash == storedHash
    }

    /**
     * Check if a PIN has been set.
     */
    fun hasPin(): Boolean = prefsManager.hasPin

    /**
     * Remove the stored PIN.
     */
    fun clearPin() {
        prefsManager.pinHash = ""
        prefsManager.hasPin = false
    }

    /**
     * Change the PIN after verifying the old one.
     * @param oldPin Current PIN for verification
     * @param newPin New PIN to set
     * @return true if changed successfully, false if old PIN is wrong or new PIN is invalid
     */
    fun changePin(oldPin: String, newPin: String): Boolean {
        if (!verifyPin(oldPin)) return false
        return setPin(newPin)
    }
}

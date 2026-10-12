package com.safeguard.tubeguard.util

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class PinManager(private val context: Context) {

    private val prefsManager = PrefsManager.getInstance(context)

    companion object {
        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val ITERATIONS = 10000
        private const val KEY_LENGTH = 256
        private const val SALT_BYTES = 16
        private const val MIN_PIN_LENGTH = 4
        private const val MAX_PIN_LENGTH = 8

        fun isValidPin(pin: String): Boolean {
            return pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all { it.isDigit() }
        }
    }

    private fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_BYTES)
        SecureRandom().nextBytes(salt)
        return salt
    }

    private fun hashPin(pin: String, salt: ByteArray): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        val hash = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    fun setPin(pin: String): Boolean {
        if (!isValidPin(pin)) return false
        val salt = generateSalt()
        val hashed = hashPin(pin, salt)
        prefsManager.pinSalt = Base64.encodeToString(salt, Base64.NO_WRAP)
        prefsManager.pinHash = hashed
        prefsManager.hasPin = true
        return true
    }

    fun verifyPin(pin: String): Boolean {
        if (!prefsManager.hasPin) return false
        val storedHash = prefsManager.pinHash
        val storedSalt = prefsManager.pinSalt
        if (storedHash.isEmpty() || storedSalt.isEmpty()) return false
        val salt = Base64.decode(storedSalt, Base64.NO_WRAP)
        val inputHash = hashPin(pin, salt)
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

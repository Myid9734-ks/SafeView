package com.safeguard.tubeguard.billing

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.safeguard.tubeguard.util.PrefsManager

class TrialManager(private val context: Context) {

    companion object {
        private const val TAG = "TrialManager"
        private const val COLLECTION_TRIALS = "trials"
        private const val FIELD_START_DATE = "trial_start"
        private const val FIELD_DEVICE_ID = "device_id"
        const val TRIAL_DAYS = 5
    }

    private val firestore = FirebaseFirestore.getInstance()
    private val prefs = PrefsManager.getInstance(context)

    fun getAndroidId(): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }

    /**
     * Check trial status. Calls back with (isTrialActive, remainingDays).
     * If trial expired and not purchased → (false, 0)
     * If trial active → (true, remainingDays)
     * If purchased → callback not needed (caller checks isPremium first)
     */
    fun checkTrialStatus(callback: (isTrialActive: Boolean, remainingDays: Int) -> Unit) {
        val deviceId = getAndroidId()

        // First check local cache
        val localStart = prefs.trialStartDate
        if (localStart > 0) {
            val result = calculateTrial(localStart)
            callback(result.first, result.second)
            // Also sync with Firestore in background
            syncWithFirestore(deviceId)
            return
        }

        // No local cache → check Firestore
        firestore.collection(COLLECTION_TRIALS)
            .document(deviceId)
            .get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    val startDate = doc.getLong(FIELD_START_DATE) ?: System.currentTimeMillis()
                    prefs.trialStartDate = startDate
                    val result = calculateTrial(startDate)
                    callback(result.first, result.second)
                } else {
                    // First time ever on this device → create trial
                    val now = System.currentTimeMillis()
                    val data = hashMapOf(
                        FIELD_DEVICE_ID to deviceId,
                        FIELD_START_DATE to now
                    )
                    firestore.collection(COLLECTION_TRIALS)
                        .document(deviceId)
                        .set(data)
                        .addOnSuccessListener {
                            Log.i(TAG, "Trial started for device: $deviceId")
                            prefs.trialStartDate = now
                            callback(true, TRIAL_DAYS)
                        }
                        .addOnFailureListener { e ->
                            Log.w(TAG, "Failed to create trial record", e)
                            // Fallback: start trial locally
                            prefs.trialStartDate = now
                            callback(true, TRIAL_DAYS)
                        }
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to check trial", e)
                // Offline fallback: use local data or start new trial
                if (localStart > 0) {
                    val result = calculateTrial(localStart)
                    callback(result.first, result.second)
                } else {
                    val now = System.currentTimeMillis()
                    prefs.trialStartDate = now
                    callback(true, TRIAL_DAYS)
                }
            }
    }

    private fun syncWithFirestore(deviceId: String) {
        firestore.collection(COLLECTION_TRIALS)
            .document(deviceId)
            .get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    val remoteStart = doc.getLong(FIELD_START_DATE) ?: return@addOnSuccessListener
                    val localStart = prefs.trialStartDate
                    // Use the earlier date (prevents trial reset by reinstall)
                    if (remoteStart < localStart) {
                        prefs.trialStartDate = remoteStart
                    }
                }
            }
    }

    private fun calculateTrial(startDate: Long): Pair<Boolean, Int> {
        val elapsed = System.currentTimeMillis() - startDate
        val daysElapsed = (elapsed / (1000L * 60 * 60 * 24)).toInt()
        val remaining = TRIAL_DAYS - daysElapsed
        return if (remaining > 0) {
            Pair(true, remaining)
        } else {
            Pair(false, 0)
        }
    }
}

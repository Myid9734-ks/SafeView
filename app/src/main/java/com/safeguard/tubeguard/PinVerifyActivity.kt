package com.safeguard.tubeguard

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.animation.AnimationUtils
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.safeguard.tubeguard.service.TubeGuardAccessibilityService
import com.safeguard.tubeguard.util.PinManager
import com.safeguard.tubeguard.util.PrefsManager

class PinVerifyActivity : AppCompatActivity() {

    companion object {
        const val RESULT_PIN_VERIFIED = 200
        const val EXTRA_PURPOSE = "extra_purpose"
        const val EXTRA_TARGET_PACKAGE = "extra_target_package"
        const val PURPOSE_STORE_UNLOCK = "store_unlock"
        const val PURPOSE_SETTINGS_UNLOCK = "settings_unlock"
        const val PURPOSE_APP_APPROVE = "app_approve"
        private const val MAX_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 30_000L // 30 seconds
    }

    private lateinit var pinManager: PinManager
    private lateinit var tilPin: TextInputLayout
    private lateinit var etPin: TextInputEditText
    private lateinit var btnVerify: MaterialButton
    private lateinit var tvAttemptsRemaining: TextView

    private var attemptCount = 0
    private var isLockedOut = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pin_verify)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.enter_pin_title)
        }

        pinManager = PinManager(this)

        initViews()
        setupListeners()
        updateAttemptsDisplay()
    }

    private fun initViews() {
        tilPin = findViewById(R.id.tilPin)
        etPin = findViewById(R.id.etPin)
        btnVerify = findViewById(R.id.btnVerify)
        tvAttemptsRemaining = findViewById(R.id.tvAttemptsRemaining)
    }

    private fun setupListeners() {
        etPin.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                tilPin.error = null
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnVerify.setOnClickListener {
            if (!isLockedOut) {
                attemptVerify()
            }
        }

        // Allow submitting with action button on keyboard
        etPin.setOnEditorActionListener { _, _, _ ->
            if (!isLockedOut) attemptVerify()
            true
        }
    }

    private fun attemptVerify() {
        val pin = etPin.text?.toString()?.trim() ?: ""

        if (pin.isEmpty()) {
            tilPin.error = getString(R.string.pin_empty_error)
            return
        }

        if (pinManager.verifyPin(pin)) {
            // Success
            when {
                isStoreUnlock() -> openUnlockedStore()
                isSettingsUnlock() -> openUnlockedSettings()
                isAppApprove() -> openApprovedApp()
            }
            setResult(RESULT_PIN_VERIFIED)
            finish()
        } else {
            attemptCount++
            etPin.setText("")

            if (attemptCount >= MAX_ATTEMPTS) {
                startLockout()
            } else {
                val remaining = MAX_ATTEMPTS - attemptCount
                tilPin.error = getString(R.string.pin_incorrect)
                updateAttemptsDisplay()

                // Shake animation on error
                val shake = AnimationUtils.loadAnimation(this, R.anim.shake)
                tilPin.startAnimation(shake)
            }
        }
    }

    private fun startLockout() {
        isLockedOut = true
        btnVerify.isEnabled = false
        etPin.isEnabled = false
        tilPin.error = null

        val lockoutEnd = System.currentTimeMillis() + LOCKOUT_DURATION_MS
        updateLockoutDisplay(lockoutEnd)
    }

    private fun updateLockoutDisplay(lockoutEnd: Long) {
        val remaining = lockoutEnd - System.currentTimeMillis()
        if (remaining <= 0) {
            // Lockout expired
            isLockedOut = false
            attemptCount = 0
            btnVerify.isEnabled = true
            etPin.isEnabled = true
            tvAttemptsRemaining.text = getString(R.string.lockout_ended)
            return
        }

        val seconds = (remaining / 1000) + 1
        tvAttemptsRemaining.text = getString(R.string.lockout_message, seconds)

        handler.postDelayed({
            updateLockoutDisplay(lockoutEnd)
        }, 1000)
    }

    private fun updateAttemptsDisplay() {
        if (attemptCount == 0) {
            tvAttemptsRemaining.text = ""
        } else {
            val remaining = MAX_ATTEMPTS - attemptCount
            tvAttemptsRemaining.text = getString(R.string.attempts_remaining, remaining)
        }
    }

    private fun isStoreUnlock(): Boolean =
        intent.getStringExtra(EXTRA_PURPOSE) == PURPOSE_STORE_UNLOCK

    private fun isSettingsUnlock(): Boolean =
        intent.getStringExtra(EXTRA_PURPOSE) == PURPOSE_SETTINGS_UNLOCK

    private fun isAppApprove(): Boolean =
        intent.getStringExtra(EXTRA_PURPOSE) == PURPOSE_APP_APPROVE

    /** 스토어 잠금 해제 후 스토어를 다시 앞으로 가져옴 */
    private fun openUnlockedStore() {
        val target = intent.getStringExtra(EXTRA_TARGET_PACKAGE) ?: return
        TubeGuardAccessibilityService.unlockedStorePackage = target
        PrefsManager.getInstance(this).markStoreUnlockActive()
        packageManager.getLaunchIntentForPackage(target)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(it)
        }
    }

    /** 설정 잠금 해제 후 설정을 다시 앞으로 가져옴 */
    private fun openUnlockedSettings() {
        val target = intent.getStringExtra(EXTRA_TARGET_PACKAGE) ?: return
        TubeGuardAccessibilityService.isSettingsUnlocked = true
        packageManager.getLaunchIntentForPackage(target)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(it)
        }
    }

    /** 승인되지 않은 새 앱을 PIN으로 승인 → 이후 자유롭게 실행 가능 */
    private fun openApprovedApp() {
        val target = intent.getStringExtra(EXTRA_TARGET_PACKAGE) ?: return
        PrefsManager.getInstance(this).removeUnapprovedPackage(target)
        packageManager.getLaunchIntentForPackage(target)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(it)
        }
    }

    /** 잠금 화면에서 취소 시 홈으로 이동 + 연타 방지 쿨다운 */
    private fun cancel() {
        setResult(RESULT_CANCELED)
        val now = System.currentTimeMillis()
        if (isStoreUnlock()) {
            TubeGuardAccessibilityService.storePinPromptedAt = now
        } else if (isSettingsUnlock()) {
            TubeGuardAccessibilityService.settingsPinPromptedAt = now
        } else if (isAppApprove()) {
            TubeGuardAccessibilityService.appApprovePromptedAt = now
        }
        startActivity(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
        finish()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            cancel()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        cancel()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

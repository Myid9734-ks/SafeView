package com.safeguard.tubeguard

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
import com.safeguard.tubeguard.util.PinManager

class PinVerifyActivity : AppCompatActivity() {

    companion object {
        const val RESULT_PIN_VERIFIED = 200
        const val EXTRA_PURPOSE = "extra_purpose"
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
            title = "Enter PIN"
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
            tilPin.error = "Please enter your PIN"
            return
        }

        if (pinManager.verifyPin(pin)) {
            // Success
            setResult(RESULT_PIN_VERIFIED)
            finish()
        } else {
            attemptCount++
            etPin.setText("")

            if (attemptCount >= MAX_ATTEMPTS) {
                startLockout()
            } else {
                val remaining = MAX_ATTEMPTS - attemptCount
                tilPin.error = "Incorrect PIN"
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
            tvAttemptsRemaining.text = "You may try again"
            return
        }

        val seconds = (remaining / 1000) + 1
        tvAttemptsRemaining.text = "Too many attempts. Try again in ${seconds}s"

        handler.postDelayed({
            updateLockoutDisplay(lockoutEnd)
        }, 1000)
    }

    private fun updateAttemptsDisplay() {
        if (attemptCount == 0) {
            tvAttemptsRemaining.text = ""
        } else {
            val remaining = MAX_ATTEMPTS - attemptCount
            tvAttemptsRemaining.text = "Attempts remaining: $remaining"
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            setResult(RESULT_CANCELED)
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

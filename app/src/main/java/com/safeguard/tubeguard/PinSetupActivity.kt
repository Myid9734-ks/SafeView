package com.safeguard.tubeguard

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.safeguard.tubeguard.util.PinManager

class PinSetupActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IS_CHANGE = "is_change"
        const val RESULT_PIN_SET = 100
    }

    private lateinit var pinManager: PinManager
    private lateinit var tilCurrentPin: TextInputLayout
    private lateinit var etCurrentPin: TextInputEditText
    private lateinit var tilNewPin: TextInputLayout
    private lateinit var etNewPin: TextInputEditText
    private lateinit var tilConfirmPin: TextInputLayout
    private lateinit var etConfirmPin: TextInputEditText
    private lateinit var btnSetPin: MaterialButton

    private var isChange = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pin_setup)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Set PIN"
        }

        pinManager = PinManager(this)
        isChange = intent.getBooleanExtra(EXTRA_IS_CHANGE, false)

        initViews()
        setupListeners()

        if (isChange) {
            supportActionBar?.title = "Change PIN"
            tilCurrentPin.visibility = android.view.View.VISIBLE
        } else {
            tilCurrentPin.visibility = android.view.View.GONE
        }
    }

    private fun initViews() {
        tilCurrentPin = findViewById(R.id.tilCurrentPin)
        etCurrentPin = findViewById(R.id.etCurrentPin)
        tilNewPin = findViewById(R.id.tilNewPin)
        etNewPin = findViewById(R.id.etNewPin)
        tilConfirmPin = findViewById(R.id.tilConfirmPin)
        etConfirmPin = findViewById(R.id.etConfirmPin)
        btnSetPin = findViewById(R.id.btnSetPin)
    }

    private fun setupListeners() {
        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                clearErrors()
            }
            override fun afterTextChanged(s: Editable?) {}
        }

        etCurrentPin.addTextChangedListener(textWatcher)
        etNewPin.addTextChangedListener(textWatcher)
        etConfirmPin.addTextChangedListener(textWatcher)

        btnSetPin.setOnClickListener {
            attemptSetPin()
        }
    }

    private fun clearErrors() {
        tilCurrentPin.error = null
        tilNewPin.error = null
        tilConfirmPin.error = null
    }

    private fun attemptSetPin() {
        val newPin = etNewPin.text?.toString()?.trim() ?: ""
        val confirmPin = etConfirmPin.text?.toString()?.trim() ?: ""

        // Validate new PIN
        if (newPin.isEmpty()) {
            tilNewPin.error = "PIN is required"
            return
        }

        if (!PinManager.isValidPin(newPin)) {
            tilNewPin.error = "PIN must be 4-8 digits"
            return
        }

        // Validate confirmation
        if (newPin != confirmPin) {
            tilConfirmPin.error = "PINs do not match"
            return
        }

        if (isChange) {
            // Verify current PIN first
            val currentPin = etCurrentPin.text?.toString()?.trim() ?: ""
            if (currentPin.isEmpty()) {
                tilCurrentPin.error = "Current PIN is required"
                return
            }
            if (!pinManager.verifyPin(currentPin)) {
                tilCurrentPin.error = "Incorrect PIN"
                return
            }
            pinManager.changePin(currentPin, newPin)
        } else {
            pinManager.setPin(newPin)
        }

        Toast.makeText(this, "PIN set successfully", Toast.LENGTH_SHORT).show()
        setResult(RESULT_PIN_SET)
        finish()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}

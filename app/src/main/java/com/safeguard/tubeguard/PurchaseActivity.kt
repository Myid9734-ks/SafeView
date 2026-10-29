package com.safeguard.tubeguard

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.safeguard.tubeguard.billing.BillingManager
import com.safeguard.tubeguard.billing.TrialManager
import com.safeguard.tubeguard.util.PrefsManager

class PurchaseActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_REMAINING_DAYS = "remaining_days"
        const val EXTRA_TRIAL_EXPIRED = "trial_expired"
    }

    private lateinit var billingManager: BillingManager
    private lateinit var prefsManager: PrefsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_purchase)

        prefsManager = PrefsManager.getInstance(this)

        val remainingDays = intent.getIntExtra(EXTRA_REMAINING_DAYS, 0)
        val trialExpired = intent.getBooleanExtra(EXTRA_TRIAL_EXPIRED, false)

        val tvTrialStatus = findViewById<TextView>(R.id.tvTrialStatus)
        val tvTrialDescription = findViewById<TextView>(R.id.tvTrialDescription)
        val btnPurchase = findViewById<MaterialButton>(R.id.btnPurchase)
        val btnContinueTrial = findViewById<MaterialButton>(R.id.btnContinueTrial)

        if (trialExpired) {
            tvTrialStatus.text = getString(R.string.trial_expired_title)
            tvTrialDescription.text = getString(R.string.trial_expired_description)
            btnContinueTrial.visibility = android.view.View.GONE
        } else {
            tvTrialStatus.text = getString(R.string.trial_remaining_format, remainingDays)
            tvTrialDescription.text = getString(R.string.trial_active_description, TrialManager.TRIAL_DAYS)
            btnContinueTrial.visibility = android.view.View.VISIBLE
        }

        billingManager = BillingManager(this,
            onPurchaseComplete = {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.purchase_success), Toast.LENGTH_LONG).show()
                    setResult(RESULT_OK)
                    finish()
                }
            },
            onProductReady = { price ->
                runOnUiThread {
                    if (price != null) {
                        btnPurchase.text = getString(R.string.purchase_button_format, price)
                    }
                }
            },
            onError = { message ->
                runOnUiThread {
                    Log.e("PurchaseActivity", "Billing error: $message")
                    Toast.makeText(this, "Billing error: $message", Toast.LENGTH_LONG).show()
                }
            }
        )
        billingManager.startConnection()

        btnPurchase.setOnClickListener {
            // DEBUG: simulate purchase without Play Console
            if (BuildConfig.DEBUG && !billingManager.isReady()) {
                Log.w("PurchaseActivity", "DEBUG: Simulating purchase (billing not ready)")
                prefsManager.isPremium = true
                Toast.makeText(this, getString(R.string.purchase_success), Toast.LENGTH_LONG).show()
                setResult(RESULT_OK)
                finish()
                return@setOnClickListener
            }
            if (!billingManager.isReady()) {
                Toast.makeText(this, getString(R.string.purchase_loading), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            billingManager.launchPurchaseFlow(this)
        }

        btnContinueTrial.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        // Handle back press
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (trialExpired) {
                    Toast.makeText(this@PurchaseActivity, getString(R.string.purchase_required), Toast.LENGTH_SHORT).show()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        billingManager.destroy()
    }
}

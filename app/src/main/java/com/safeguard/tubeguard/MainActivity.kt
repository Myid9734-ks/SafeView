package com.safeguard.tubeguard

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import androidx.appcompat.widget.SwitchCompat
import com.safeguard.tubeguard.billing.BillingManager
import com.safeguard.tubeguard.billing.TrialManager
import com.safeguard.tubeguard.receiver.TimerExpiredReceiver
import com.safeguard.tubeguard.receiver.TubeGuardDeviceAdminReceiver
import com.safeguard.tubeguard.service.TubeGuardVpnService
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.safeguard.tubeguard.util.FileLogger
import com.safeguard.tubeguard.util.PinManager
import com.safeguard.tubeguard.util.PrefsManager
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TubeGuardMain"
        private const val REQUEST_VPN_PERMISSION = 1001
        const val TIMER_EXPIRY_REQUEST_CODE = 2001
        const val MIDNIGHT_RESET_REQUEST_CODE = 2002
    }

    private lateinit var prefsManager: PrefsManager
    private lateinit var pinManager: PinManager
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponentName: ComponentName
    private lateinit var billingManager: BillingManager
    private lateinit var trialManager: TrialManager
    private var trialCheckDone = false

    // Views
    private lateinit var switchBlocking: SwitchCompat
    private lateinit var tvStatus: TextView
    private lateinit var tvBlockCount: TextView
    private lateinit var cardVpnStatus: MaterialCardView
    private lateinit var tvVpnStatus: TextView
    private lateinit var cardAccessibilityStatus: MaterialCardView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvPinStatus: TextView
    private lateinit var cardDeviceAdminStatus: MaterialCardView
    private lateinit var tvDeviceAdminStatus: TextView

    // Admin section
    private lateinit var adminSection: LinearLayout
    private lateinit var cardAdminUnlock: MaterialCardView

    // Timer views
    private lateinit var tvAllowedTime: TextView
    private lateinit var tvRemainingTime: TextView
    private lateinit var tvTimerStatus: TextView
    private lateinit var btnTimerStartStop: MaterialButton
    private lateinit var cardTimerSettings: MaterialCardView
    private lateinit var tvTimerSettingsStatus: TextView

    private var pendingEnableBlocking = false
    private var adminUnlocked = false
    private var pendingAdminUnlock = false
    private var initialSetupRunning = false
    private var setupCompletedAt: Long = 0L  // setup 완료 후 중복 VPN 방지용 타임스탬프

    // Timer handler
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerTickRunnable = object : Runnable {
        override fun run() {
            val remaining = prefsManager.getTimerRemainingSeconds()
            updateTimerDisplay(remaining)
            if (prefsManager.timerIsRunning) {
                if (remaining <= 0) {
                    handleTimerExpiredInForeground()
                } else {
                    timerHandler.postDelayed(this, 1000)
                }
            }
        }
    }

    // Activity result launchers
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Log.i(TAG, "VPN permission granted, initialSetupRunning=$initialSetupRunning, setupComplete=${prefsManager.initialSetupComplete}")
            if (!initialSetupRunning) {
                // setup 완료 직후에도 중복 VPN 시작 방지
                if (prefsManager.isBlocking) {
                    Log.i(TAG, "VPN permission granted but already blocking - skipping startVpnService")
                } else {
                    startVpnService()
                }
            }
        } else {
            Log.w(TAG, "VPN permission denied")
            if (!initialSetupRunning) {
                pendingEnableBlocking = false
                updateBlockingSwitchSilently(false)
            }
            Toast.makeText(this, getString(R.string.vpn_permission_required), Toast.LENGTH_LONG).show()
        }
    }

    private val vpnPermissionForTimerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnServiceForTimer()
        } else {
            Toast.makeText(this, getString(R.string.vpn_no_permission_timer), Toast.LENGTH_LONG).show()
        }
    }

    private val pinSetupLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == PinSetupActivity.RESULT_PIN_SET) {
            updatePinStatusDisplay()
            if (initialSetupRunning) {
                showAdminSection()
                // proceedSetupWizard()는 onResume()에서 호출되므로 중복 호출 제거
            } else {
                pendingAdminUnlock = false
                showAdminSection()
                Toast.makeText(this, getString(R.string.pin_set_admin_toast), Toast.LENGTH_SHORT).show()
            }
        } else {
            pendingAdminUnlock = false
        }
    }

    private val pinVerifyLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == PinVerifyActivity.RESULT_PIN_VERIFIED) {
            performDisableBlocking()
        } else {
            switchBlocking.isChecked = true
        }
    }

    private val pinVerifyForAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == PinVerifyActivity.RESULT_PIN_VERIFIED) {
            deactivateDeviceAdmin()
        }
    }

    private val pinVerifyForAdminMenuLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        pendingAdminUnlock = false
        if (result.resultCode == PinVerifyActivity.RESULT_PIN_VERIFIED) {
            showAdminSection()
        }
    }

    private val purchaseLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Purchase completed
            Toast.makeText(this, getString(R.string.purchase_success), Toast.LENGTH_SHORT).show()
        }
        // Proceed with setup after returning from PurchaseActivity
        proceedWithSetup()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, getString(R.string.notification_permission_warning), Toast.LENGTH_LONG).show()
        }
    }

    private val deviceAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Log.i(TAG, "Device admin enabled")
            prefsManager.deviceAdminEnabled = true
            Toast.makeText(this, getString(R.string.device_admin_enabled_toast), Toast.LENGTH_SHORT).show()
        } else {
            Log.w(TAG, "Device admin not granted")
            Toast.makeText(this, getString(R.string.device_admin_required), Toast.LENGTH_LONG).show()
        }
        updateAllStatusDisplays()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefsManager = PrefsManager.getInstance(this)
        pinManager = PinManager(this)
        devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponentName = ComponentName(this, TubeGuardDeviceAdminReceiver::class.java)
        trialManager = TrialManager(this)

        // Initialize billing and restore purchases
        billingManager = BillingManager(this,
            onPurchaseComplete = {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.purchase_restored), Toast.LENGTH_SHORT).show()
                }
            }
        )
        billingManager.startConnection()

        initViews()
        setupListeners()
        requestNotificationPermission()
        requestStoragePermissionIfNeeded()
        scheduleMidnightReset()

        // Check trial/purchase status before proceeding
        checkTrialAndPurchaseStatus()
    }

    private fun checkTrialAndPurchaseStatus() {
        // Already purchased → skip trial check
        if (prefsManager.isPremium) {
            billingManager.queryExistingPurchases()
            proceedWithSetup()
            return
        }

        trialManager.checkTrialStatus { isTrialActive, remainingDays ->
            runOnUiThread {
                trialCheckDone = true
                if (!isTrialActive) {
                    // Trial expired → must purchase
                    val intent = Intent(this, PurchaseActivity::class.java).apply {
                        putExtra(PurchaseActivity.EXTRA_TRIAL_EXPIRED, true)
                        putExtra(PurchaseActivity.EXTRA_REMAINING_DAYS, 0)
                    }
                    purchaseLauncher.launch(intent)
                } else if (remainingDays <= 2) {
                    // Trial ending soon → show purchase suggestion
                    val intent = Intent(this, PurchaseActivity::class.java).apply {
                        putExtra(PurchaseActivity.EXTRA_TRIAL_EXPIRED, false)
                        putExtra(PurchaseActivity.EXTRA_REMAINING_DAYS, remainingDays)
                    }
                    purchaseLauncher.launch(intent)
                } else {
                    proceedWithSetup()
                }
            }
        }
    }

    private fun proceedWithSetup() {
        if (!prefsManager.initialSetupComplete) {
            initialSetupRunning = true
            pendingAdminUnlock = true
            proceedSetupWizard()
        } else {
            showCrashlyticsConsentIfNeeded()
        }
    }

    private fun showCrashlyticsConsentIfNeeded() {
        val localPrefs = getPreferences(MODE_PRIVATE)
        if (localPrefs.getBoolean("crashlytics_consent_asked", false)) return
        AlertDialog.Builder(this)
            .setTitle("앱 개선 데이터 수집 동의")
            .setMessage("앱 안정성 개선을 위해 오류 발생 시 기기 정보와 오류 로그를 수집할 수 있습니다.\n\n개인 식별 정보(이름, 연락처 등)는 수집하지 않습니다.\n\n동의하지 않아도 앱의 모든 기능을 정상적으로 사용할 수 있습니다.")
            .setPositiveButton("동의") { _, _ ->
                localPrefs.edit()
                    .putBoolean("crashlytics_consent_asked", true)
                    .putBoolean("crashlytics_consent_granted", true)
                    .apply()
                FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true)
            }
            .setNegativeButton("거부") { _, _ ->
                localPrefs.edit()
                    .putBoolean("crashlytics_consent_asked", true)
                    .putBoolean("crashlytics_consent_granted", false)
                    .apply()
                FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(false)
            }
            .setCancelable(false)
            .show()
    }

    private fun requestStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 9001)
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return
            val localPrefs = getPreferences(MODE_PRIVATE)
            if (!localPrefs.getBoolean("notification_permission_asked", false)) {
                localPrefs.edit().putBoolean("notification_permission_asked", true).apply()
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun checkExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.exact_alarm_title))
                    .setMessage(getString(R.string.exact_alarm_message))
                    .setPositiveButton(getString(R.string.btn_go_settings)) { _, _ ->
                        startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                    }
                    .setNegativeButton(getString(R.string.btn_cancel), null)
                    .show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        prefsManager.checkMidnightReset()
        Log.i(TAG, "onResume: initialSetupRunning=$initialSetupRunning, setupComplete=${prefsManager.initialSetupComplete}, isBlocking=${prefsManager.isBlocking}")
        if (initialSetupRunning && !prefsManager.initialSetupComplete) {
            pendingAdminUnlock = true
            updateAllStatusDisplays()
            proceedSetupWizard()
        } else {
            initialSetupRunning = false
            pendingAdminUnlock = false
            updateAdminSectionVisibility()
            updateAllStatusDisplays()
        }
        startTimerTick()
    }

    override fun onPause() {
        super.onPause()
        timerHandler.removeCallbacks(timerTickRunnable)
        if (!pendingAdminUnlock) {
            adminUnlocked = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        billingManager.destroy()
    }

    private fun initViews() {
        switchBlocking = findViewById(R.id.switchBlocking)
        tvStatus = findViewById(R.id.tvStatus)
        tvBlockCount = findViewById(R.id.tvBlockCount)
        cardVpnStatus = findViewById(R.id.cardVpnStatus)
        tvVpnStatus = findViewById(R.id.tvVpnStatus)
        cardAccessibilityStatus = findViewById(R.id.cardAccessibilityStatus)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        tvPinStatus = findViewById(R.id.tvPinStatus)
        cardDeviceAdminStatus = findViewById(R.id.cardDeviceAdminStatus)
        tvDeviceAdminStatus = findViewById(R.id.tvDeviceAdminStatus)

        // Admin section
        adminSection = findViewById(R.id.adminSection)
        cardAdminUnlock = findViewById(R.id.cardAdminUnlock)

        // Timer views
        tvAllowedTime = findViewById(R.id.tvAllowedTime)
        tvRemainingTime = findViewById(R.id.tvRemainingTime)
        tvTimerStatus = findViewById(R.id.tvTimerStatus)
        btnTimerStartStop = findViewById(R.id.btnTimerStartStop)
        cardTimerSettings = findViewById(R.id.cardTimerSettings)
        tvTimerSettingsStatus = findViewById(R.id.tvTimerSettingsStatus)
    }

    private fun setupListeners() {
        switchBlocking.setOnCheckedChangeListener { _, isChecked ->
            Log.i(TAG, "switchBlocking changed to $isChecked (listener fired)")
            if (isChecked) {
                handleEnableBlocking()
            } else {
                handleDisableBlocking()
            }
        }

        cardAdminUnlock.setOnClickListener {
            if (pinManager.hasPin()) {
                pendingAdminUnlock = true
                pinVerifyForAdminMenuLauncher.launch(Intent(this, PinVerifyActivity::class.java))
            } else {
                pendingAdminUnlock = true
                ensurePinSetup()
            }
        }

        cardVpnStatus.setOnClickListener {
            if (adminUnlocked) pendingAdminUnlock = true
            openVpnPermission()
        }
        cardAccessibilityStatus.setOnClickListener {
            if (adminUnlocked) pendingAdminUnlock = true
            openAccessibilitySettings()
        }
        cardDeviceAdminStatus.setOnClickListener {
            if (adminUnlocked) pendingAdminUnlock = true
            handleDeviceAdminCard()
        }

        findViewById<MaterialCardView>(R.id.cardPinSetup).setOnClickListener {
            if (pinManager.hasPin()) {
                startActivity(Intent(this, PinSetupActivity::class.java).apply {
                    putExtra(PinSetupActivity.EXTRA_IS_CHANGE, true)
                })
            } else {
                pinSetupLauncher.launch(Intent(this, PinSetupActivity::class.java))
            }
        }

        // Timer settings (in admin section)
        cardTimerSettings.setOnClickListener {
            showAllowedTimePickerDialog()
        }

        btnTimerStartStop.setOnClickListener {
            if (prefsManager.timerIsRunning) {
                handleTimerStop()
            } else {
                handleTimerStart()
            }
        }
    }

    // ─────────────────────────── Admin Section ───────────────────────────

    private fun updateAdminSectionVisibility() {
        if (adminUnlocked) {
            adminSection.visibility = View.VISIBLE
            cardAdminUnlock.visibility = View.GONE
        } else {
            adminSection.visibility = View.GONE
            cardAdminUnlock.visibility = View.VISIBLE
        }
    }

    private fun showAdminSection() {
        adminUnlocked = true
        adminSection.visibility = View.VISIBLE
        cardAdminUnlock.visibility = View.GONE
    }

    private fun ensurePinSetup() {
        if (!pinManager.hasPin()) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.admin_pin_setup_title))
                .setMessage(getString(R.string.admin_pin_setup_message))
                .setPositiveButton(getString(R.string.btn_setup)) { _, _ ->
                    pinSetupLauncher.launch(Intent(this, PinSetupActivity::class.java))
                }
                .setCancelable(false)
                .show()
        }
    }

    private fun proceedSetupWizard() {
        // 이미 초기 설정이 완료된 경우 중복 실행 방지
        if (prefsManager.initialSetupComplete) {
            Log.i(TAG, "proceedSetupWizard: BLOCKED - setup already complete")
            initialSetupRunning = false
            return
        }

        Log.i(TAG, "proceedSetupWizard: starting checks")
        // VPN 확인
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.setup_vpn_title))
                .setMessage(getString(R.string.setup_vpn_message))
                .setPositiveButton(getString(R.string.btn_setup)) { _, _ ->
                    pendingAdminUnlock = true
                    vpnPermissionLauncher.launch(vpnIntent)
                }
                .setCancelable(false)
                .show()
            return
        }

        // 접근성 확인
        if (!isAccessibilityServiceEnabled()) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.setup_accessibility_title))
                .setMessage(getString(R.string.setup_accessibility_message))
                .setPositiveButton(getString(R.string.btn_go_settings)) { _, _ ->
                    pendingAdminUnlock = true
                    openAccessibilitySettings()
                }
                .setCancelable(false)
                .show()
            return
        }

        // 기기 관리자 확인
        if (!devicePolicyManager.isAdminActive(adminComponentName)) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.setup_device_admin_title))
                .setMessage(getString(R.string.setup_device_admin_message))
                .setPositiveButton(getString(R.string.btn_setup)) { _, _ ->
                    pendingAdminUnlock = true
                    requestDeviceAdmin()
                }
                .setCancelable(false)
                .show()
            return
        }

        // 정확한 알람 권한 확인
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.exact_alarm_title))
                    .setMessage(getString(R.string.exact_alarm_message))
                    .setPositiveButton(getString(R.string.btn_go_settings)) { _, _ ->
                        pendingAdminUnlock = true
                        startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                    }
                    .setCancelable(false)
                    .show()
                return
            }
        }

        // PIN 설정 (마지막 단계)
        if (!pinManager.hasPin()) {
            ensurePinSetup()
            return
        }

        // 모든 설정 완료
        Log.i(TAG, "Setup wizard COMPLETED - starting VPN directly")
        initialSetupRunning = false
        pendingAdminUnlock = false
        adminUnlocked = false
        prefsManager.initialSetupComplete = true
        setupCompletedAt = System.currentTimeMillis()
        // YouTube 차단 자동 활성화 (셋업에서 VPN 권한을 이미 받았으므로 바로 시작)
        pendingEnableBlocking = true
        startVpnService()
        updateAdminSectionVisibility()
        Toast.makeText(this, getString(R.string.setup_complete), Toast.LENGTH_LONG).show()
        // Crashlytics 동의는 차단 활성화 후 마지막에 표시
        showCrashlyticsConsentIfNeeded()
    }

    // ─────────────────────────── Timer Logic ───────────────────────────

    private fun handleTimerStart() {
        val remaining = prefsManager.getTimerRemainingSeconds()
        if (remaining <= 0) {
            Toast.makeText(this, getString(R.string.timer_no_remaining), Toast.LENGTH_SHORT).show()
            return
        }
        prefsManager.startTimerNow()
        scheduleTimerExpiryAlarm(remaining)
        performTimerDisableBlocking()
        startTimerTick()
        updateTimerButtons()
        Toast.makeText(this, getString(R.string.timer_started), Toast.LENGTH_SHORT).show()
    }

    private fun handleTimerStop() {
        prefsManager.pauseTimer()
        cancelTimerExpiryAlarm()
        timerHandler.removeCallbacks(timerTickRunnable)
        performTimerEnableBlocking()
        updateTimerDisplay(prefsManager.getTimerRemainingSeconds())
        updateTimerButtons()
        Toast.makeText(this, getString(R.string.timer_stopped), Toast.LENGTH_SHORT).show()
    }

    private fun handleTimerExpiredInForeground() {
        FileLogger.log(this, TAG, "포그라운드 타이머 만료 → 차단 활성화 시도")
        prefsManager.pauseTimer()
        cancelTimerExpiryAlarm()
        timerHandler.removeCallbacks(timerTickRunnable)
        updateTimerDisplay(0)
        updateTimerButtons()
        performTimerEnableBlocking()
        Toast.makeText(this, getString(R.string.timer_expired_toast), Toast.LENGTH_LONG).show()
    }

    private fun performTimerDisableBlocking() {
        prefsManager.isBlocking = false
        prefsManager.vpnEnabled = false
        val serviceIntent = Intent(this, TubeGuardVpnService::class.java).apply {
            action = TubeGuardVpnService.ACTION_STOP
        }
        startService(serviceIntent)
        updateBlockingSwitchSilently(false)
        updateAllStatusDisplays()
    }

    private fun performTimerEnableBlocking() {
        prefsManager.isBlocking = true
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnPermissionForTimerLauncher.launch(vpnIntent)
        } else {
            startVpnServiceForTimer()
        }
        // 접근성 서비스에 즉시 차단 신호 전송 (포그라운드/백그라운드 모두 처리)
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
            .sendBroadcast(android.content.Intent(
                com.safeguard.tubeguard.service.TubeGuardAccessibilityService.ACTION_FORCE_BLOCK_NOW
            ))
        Log.i(TAG, "performTimerEnableBlocking: sent ACTION_FORCE_BLOCK_NOW")
    }

    private fun startVpnServiceForTimer() {
        val serviceIntent = Intent(this, TubeGuardVpnService::class.java).apply {
            action = TubeGuardVpnService.ACTION_START
        }
        startForegroundService(serviceIntent)
        updateBlockingSwitchSilently(true)
        updateAllStatusDisplays()
    }

    private fun startTimerTick() {
        timerHandler.removeCallbacks(timerTickRunnable)
        timerHandler.post(timerTickRunnable)
    }

    private fun updateTimerDisplay(remaining: Long) {
        val totalAllowed = prefsManager.allowedSeconds
        val h = totalAllowed / 3600
        val m = (totalAllowed % 3600) / 60
        tvAllowedTime.text = getString(R.string.allowed_time_format, h, m)

        val rh = remaining / 3600
        val rm = (remaining % 3600) / 60
        val rs = remaining % 60
        tvRemainingTime.text = "${"%02d".format(rh)}:${"%02d".format(rm)}:${"%02d".format(rs)}"

        val colorRes = when {
            remaining <= 0 -> R.color.red_inactive
            remaining <= 300 -> R.color.orange_warning  // 5분 이하
            else -> R.color.green_active
        }
        tvRemainingTime.setTextColor(getColor(colorRes))

        tvTimerStatus.text = when {
            remaining <= 0 -> getString(R.string.timer_status_expired)
            prefsManager.timerIsRunning -> getString(R.string.timer_status_running)
            else -> getString(R.string.timer_status_waiting)
        }
        tvTimerStatus.setTextColor(
            if (prefsManager.timerIsRunning) getColor(R.color.green_active)
            else getColor(R.color.text_secondary)
        )
    }

    private fun updateTimerButtons() {
        val remaining = prefsManager.getTimerRemainingSeconds()
        val isRunning = prefsManager.timerIsRunning

        if (isRunning) {
            btnTimerStartStop.text = getString(R.string.timer_pause)
            btnTimerStartStop.setBackgroundColor(getColor(R.color.orange_warning))
        } else {
            btnTimerStartStop.text = getString(R.string.timer_start)
            btnTimerStartStop.setBackgroundColor(getColor(R.color.green_active))
        }
        btnTimerStartStop.isEnabled = remaining > 0 || isRunning
    }

    private fun showAllowedTimePickerDialog() {
        val currentAllowed = prefsManager.allowedSeconds
        val currentHours = (currentAllowed / 3600).toInt()
        val currentMinutes = ((currentAllowed % 3600) / 60).toInt()

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(32, 32, 32, 16)
        }

        val pickerHours = NumberPicker(this).apply {
            minValue = 0
            maxValue = 8
            value = currentHours
        }
        val pickerMinutes = NumberPicker(this).apply {
            minValue = 0
            maxValue = 59
            value = currentMinutes
        }

        val labelH = TextView(this).apply {
            text = getString(R.string.time_label_hours)
            setPadding(8, 0, 16, 0)
        }
        val labelM = TextView(this).apply {
            text = getString(R.string.time_label_minutes)
            setPadding(8, 0, 0, 0)
        }

        layout.addView(pickerHours)
        layout.addView(labelH)
        layout.addView(pickerMinutes)
        layout.addView(labelM)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.allowed_time_setting_title))
            .setView(layout)
            .setPositiveButton(getString(R.string.btn_save)) { _, _ ->
                val newSeconds = (pickerHours.value * 3600L) + (pickerMinutes.value * 60L)
                if (newSeconds == 0L) {
                    Toast.makeText(this, getString(R.string.min_time_error), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                prefsManager.allowedSeconds = newSeconds
                // 타이머가 실행 중이 아닐 때만 남은 시간도 갱신
                if (!prefsManager.timerIsRunning) {
                    prefsManager.timerSavedRemaining = newSeconds
                }
                updateTimerDisplay(prefsManager.getTimerRemainingSeconds())
                updateTimerButtons()
                Toast.makeText(this, getString(R.string.allowed_time_saved), Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(getString(R.string.btn_timer_reset)) { _, _ ->
                // 타이머 실행 중이면 정지 후 초기화
                if (prefsManager.timerIsRunning) {
                    prefsManager.pauseTimer()
                    cancelTimerExpiryAlarm()
                    timerHandler.removeCallbacks(timerTickRunnable)
                }
                prefsManager.timerSavedRemaining = prefsManager.allowedSeconds
                updateTimerDisplay(prefsManager.getTimerRemainingSeconds())
                updateTimerButtons()
                Toast.makeText(this, getString(R.string.timer_reset_done), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    // ─────────────────────────── AlarmManager ───────────────────────────

    private fun scheduleTimerExpiryAlarm(remainingSeconds: Long) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, TimerExpiredReceiver::class.java).apply {
            action = TimerExpiredReceiver.ACTION_TIMER_EXPIRED
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this, TIMER_EXPIRY_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = System.currentTimeMillis() + remainingSeconds * 1000L
        val canExact = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()
        if (canExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
        FileLogger.log(this, TAG, "알람 설정: ${remainingSeconds}초 후 | canExact=$canExact | triggerAt=$triggerAt")
    }

    private fun cancelTimerExpiryAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, TimerExpiredReceiver::class.java).apply {
            action = TimerExpiredReceiver.ACTION_TIMER_EXPIRED
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this, TIMER_EXPIRY_REQUEST_CODE, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
        }
    }

    private fun scheduleMidnightReset() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, TimerExpiredReceiver::class.java).apply {
            action = TimerExpiredReceiver.ACTION_MIDNIGHT_RESET
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this, MIDNIGHT_RESET_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val midnight = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, midnight, pendingIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, midnight, pendingIntent)
        }
    }

    // ─────────────────────────── Blocking Logic ───────────────────────────

    private fun handleEnableBlocking() {
        Log.i(TAG, "handleEnableBlocking: isBlocking=${prefsManager.isBlocking}, setupCompletedAt=$setupCompletedAt, elapsed=${System.currentTimeMillis() - setupCompletedAt}ms")

        // VPN이 이미 실행 중이거나 setup 완료 직후(30초 이내)이면 중복 VPN 다이얼로그 방지
        if (prefsManager.isBlocking) {
            Log.i(TAG, "handleEnableBlocking: BLOCKED - already blocking")
            updateBlockingSwitchSilently(true)
            return
        }
        if (setupCompletedAt > 0 && System.currentTimeMillis() - setupCompletedAt < 30_000L) {
            Log.i(TAG, "handleEnableBlocking: BLOCKED - setup just completed ${System.currentTimeMillis() - setupCompletedAt}ms ago")
            updateBlockingSwitchSilently(prefsManager.isBlocking)
            return
        }

        // If timer is running, stop it first
        if (prefsManager.timerIsRunning) {
            prefsManager.pauseTimer()
            cancelTimerExpiryAlarm()
            timerHandler.removeCallbacks(timerTickRunnable)
            updateTimerDisplay(prefsManager.getTimerRemainingSeconds())
            updateTimerButtons()
        }
        pendingEnableBlocking = true
        val vpnIntent = VpnService.prepare(this)
        Log.i(TAG, "handleEnableBlocking: VpnService.prepare() returned ${if (vpnIntent != null) "non-null (needs permission)" else "null (already granted)"}")
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            startVpnService()
        }
    }

    private fun handleDisableBlocking() {
        if (pinManager.hasPin()) {
            pinVerifyLauncher.launch(Intent(this, PinVerifyActivity::class.java))
        } else {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.disable_protection_title))
                .setMessage(getString(R.string.disable_protection_message))
                .setPositiveButton(getString(R.string.btn_disable)) { _, _ -> performDisableBlocking() }
                .setNegativeButton(getString(R.string.btn_cancel)) { _, _ -> switchBlocking.isChecked = true }
                .setCancelable(false)
                .show()
        }
    }

    private fun startVpnService() {
        prefsManager.isBlocking = true
        val serviceIntent = Intent(this, TubeGuardVpnService::class.java).apply {
            action = TubeGuardVpnService.ACTION_START
        }
        startForegroundService(serviceIntent)
        pendingEnableBlocking = false
        updateAllStatusDisplays()
        Toast.makeText(this, getString(R.string.blocking_enabled_toast), Toast.LENGTH_SHORT).show()
    }

    private fun performDisableBlocking() {
        prefsManager.isBlocking = false
        prefsManager.vpnEnabled = false
        val serviceIntent = Intent(this, TubeGuardVpnService::class.java).apply {
            action = TubeGuardVpnService.ACTION_STOP
        }
        startService(serviceIntent)
        switchBlocking.isChecked = false
        updateAllStatusDisplays()
        Toast.makeText(this, getString(R.string.blocking_disabled_toast), Toast.LENGTH_SHORT).show()
    }

    private fun updateBlockingSwitchSilently(checked: Boolean) {
        switchBlocking.setOnCheckedChangeListener(null)
        switchBlocking.isChecked = checked
        switchBlocking.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) handleEnableBlocking() else handleDisableBlocking()
        }
    }

    private fun updateAllStatusDisplays() {
        val isBlocking = prefsManager.isBlocking

        updateBlockingSwitchSilently(isBlocking)

        tvStatus.text = if (isBlocking) getString(R.string.status_blocking) else getString(R.string.status_not_blocking)
        tvStatus.setTextColor(
            if (isBlocking) getColor(R.color.green_active) else getColor(R.color.red_inactive)
        )

        val blockCount = prefsManager.blockCount
        tvBlockCount.text = getString(R.string.block_count_format, blockCount)

        updateVpnStatusDisplay()
        updateAccessibilityStatusDisplay()
        updateDeviceAdminStatusDisplay()
        updatePinStatusDisplay()
        updateTimerSettingsDisplay()
        updateTimerDisplay(prefsManager.getTimerRemainingSeconds())
        updateTimerButtons()
    }

    private fun updateVpnStatusDisplay() {
        val vpnIntent = VpnService.prepare(this)
        val hasPermission = vpnIntent == null
        val isRunning = prefsManager.vpnEnabled

        if (isRunning && hasPermission) {
            tvVpnStatus.text = getString(R.string.vpn_active)
            tvVpnStatus.setTextColor(getColor(R.color.green_active))
        } else if (hasPermission) {
            tvVpnStatus.text = getString(R.string.vpn_permission_granted)
            tvVpnStatus.setTextColor(getColor(R.color.green_active))
        } else {
            tvVpnStatus.text = getString(R.string.vpn_permission_needed)
            tvVpnStatus.setTextColor(getColor(R.color.red_inactive))
        }
    }

    private fun updateAccessibilityStatusDisplay() {
        val isEnabled = isAccessibilityServiceEnabled()
        if (isEnabled) {
            tvAccessibilityStatus.text = getString(R.string.accessibility_active)
            tvAccessibilityStatus.setTextColor(getColor(R.color.green_active))
        } else {
            tvAccessibilityStatus.text = getString(R.string.accessibility_needed)
            tvAccessibilityStatus.setTextColor(getColor(R.color.red_inactive))
        }
    }

    private fun updateDeviceAdminStatusDisplay() {
        // isAdminActive()는 비동기 해제 시 즉시 반영 안 됨 → prefsManager와 AND 조건으로 판단
        val isAdmin = devicePolicyManager.isAdminActive(adminComponentName) && prefsManager.deviceAdminEnabled
        if (isAdmin) {
            tvDeviceAdminStatus.text = getString(R.string.device_admin_active)
            tvDeviceAdminStatus.setTextColor(getColor(R.color.green_active))
        } else {
            tvDeviceAdminStatus.text = getString(R.string.device_admin_inactive)
            tvDeviceAdminStatus.setTextColor(getColor(R.color.red_inactive))
        }
    }

    private fun handleDeviceAdminCard() {
        val isAdmin = devicePolicyManager.isAdminActive(adminComponentName)
        if (isAdmin) {
            if (pinManager.hasPin()) {
                val verifyIntent = Intent(this, PinVerifyActivity::class.java).apply {
                    putExtra(PinVerifyActivity.EXTRA_PURPOSE, "device_admin_deactivate")
                }
                pinVerifyForAdminLauncher.launch(verifyIntent)
            } else {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.device_admin_deactivate_title))
                    .setMessage(getString(R.string.device_admin_deactivate_message))
                    .setPositiveButton(getString(R.string.btn_deactivate)) { _, _ -> deactivateDeviceAdmin() }
                    .setNegativeButton(getString(R.string.btn_cancel), null)
                    .show()
            }
        } else {
            requestDeviceAdmin()
        }
    }

    private fun requestDeviceAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponentName)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.device_admin_explanation)
            )
        }
        deviceAdminLauncher.launch(intent)
    }

    private fun deactivateDeviceAdmin() {
        devicePolicyManager.removeActiveAdmin(adminComponentName)
        prefsManager.deviceAdminEnabled = false
        // removeActiveAdmin이 비동기라 isAdminActive()가 즉시 반영 안 됨 → 직접 UI 업데이트
        tvDeviceAdminStatus.text = getString(R.string.device_admin_inactive)
        tvDeviceAdminStatus.setTextColor(getColor(R.color.red_inactive))
        Toast.makeText(this, getString(R.string.device_admin_deactivated), Toast.LENGTH_SHORT).show()
    }

    private fun updateTimerSettingsDisplay() {
        val totalAllowed = prefsManager.allowedSeconds
        val h = totalAllowed / 3600
        val m = (totalAllowed % 3600) / 60
        tvTimerSettingsStatus.text = getString(R.string.timer_settings_format, h, m)
    }

    private fun updatePinStatusDisplay() {
        tvPinStatus.text = if (pinManager.hasPin()) {
            getString(R.string.pin_set)
        } else {
            getString(R.string.pin_not_set)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val fullName = "$packageName/com.safeguard.tubeguard.service.TubeGuardAccessibilityService"
        val shortName = "$packageName/.service.TubeGuardAccessibilityService"
        return try {
            val enabled = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            enabled.split(":").any {
                it.equals(fullName, ignoreCase = true) || it.equals(shortName, ignoreCase = true)
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun openVpnPermission() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            Toast.makeText(this, getString(R.string.vpn_already_granted), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.accessibility_cannot_open), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_log -> {
                startActivity(Intent(this, LogActivity::class.java))
                true
            }
            R.id.action_timer_log -> {
                showTimerLogDialog()
                true
            }
            R.id.action_about -> {
                showAboutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showTimerLogDialog() {
        val content = FileLogger.getLogText(this).takeLast(3000)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.timer_debug_log_title))
            .setMessage(content)
            .setPositiveButton(getString(R.string.btn_ok), null)
            .show()
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.about_title))
            .setMessage(getString(R.string.about_message))
            .setPositiveButton(getString(R.string.btn_ok), null)
            .show()
    }

}

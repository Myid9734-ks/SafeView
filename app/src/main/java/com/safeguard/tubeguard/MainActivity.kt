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
import com.safeguard.tubeguard.receiver.TimerExpiredReceiver
import com.safeguard.tubeguard.receiver.TubeGuardDeviceAdminReceiver
import com.safeguard.tubeguard.service.TubeGuardVpnService
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
            Log.i(TAG, "VPN permission granted")
            if (!initialSetupRunning) {
                startVpnService()
            }
        } else {
            Log.w(TAG, "VPN permission denied")
            if (!initialSetupRunning) {
                pendingEnableBlocking = false
                switchBlocking.isChecked = false
            }
            Toast.makeText(this, "YouTube 차단을 위해 VPN 권한이 필요합니다", Toast.LENGTH_LONG).show()
        }
    }

    private val vpnPermissionForTimerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnServiceForTimer()
        } else {
            Toast.makeText(this, "VPN 권한이 없어 차단을 재개할 수 없습니다", Toast.LENGTH_LONG).show()
        }
    }

    private val pinSetupLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == PinSetupActivity.RESULT_PIN_SET) {
            updatePinStatusDisplay()
            if (initialSetupRunning) {
                showAdminSection()
                proceedSetupWizard()
            } else {
                pendingAdminUnlock = false
                showAdminSection()
                Toast.makeText(this, "PIN이 설정되었습니다.", Toast.LENGTH_SHORT).show()
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

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "알림 권한이 없으면 VPN 상태 알림이 표시되지 않습니다", Toast.LENGTH_LONG).show()
        }
    }

    private val deviceAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Log.i(TAG, "Device admin enabled")
            prefsManager.deviceAdminEnabled = true
            Toast.makeText(this, "앱 삭제 방지가 활성화되었습니다", Toast.LENGTH_SHORT).show()
        } else {
            Log.w(TAG, "Device admin not granted")
            Toast.makeText(this, "기기 관리자 권한이 필요합니다", Toast.LENGTH_LONG).show()
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

        initViews()
        setupListeners()
        requestNotificationPermission()
        requestStoragePermissionIfNeeded()
        scheduleMidnightReset()
        if (!prefsManager.initialSetupComplete) {
            ensurePinSetup()
        }
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
                    .setTitle("정확한 알람 권한 필요")
                    .setMessage("타이머가 정확히 동작하려면 '정확한 알람' 권한이 필요합니다.\n설정에서 허용해 주세요.")
                    .setPositiveButton("설정으로 이동") { _, _ ->
                        startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        prefsManager.checkMidnightReset()
        if (initialSetupRunning) {
            pendingAdminUnlock = true
            updateAllStatusDisplays()
            proceedSetupWizard()
        } else {
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
            initialSetupRunning = true
            pendingAdminUnlock = true
            AlertDialog.Builder(this)
                .setTitle("관리자 비밀번호 설정")
                .setMessage("앱을 사용하려면 관리자 비밀번호(PIN)를 먼저 설정해야 합니다.")
                .setPositiveButton("설정하기") { _, _ ->
                    pinSetupLauncher.launch(Intent(this, PinSetupActivity::class.java))
                }
                .setCancelable(false)
                .show()
        }
    }

    private fun proceedSetupWizard() {
        // VPN 확인
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            AlertDialog.Builder(this)
                .setTitle("초기 설정 (1/3)")
                .setMessage("YouTube 차단을 위해 VPN 권한을 허용해 주세요.")
                .setPositiveButton("설정") { _, _ ->
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
                .setTitle("초기 설정 (2/3)")
                .setMessage("YouTube 앱 차단을 위해 접근성 서비스를 활성화해 주세요.\n\n설정 → TubeGuard → 활성화")
                .setPositiveButton("설정으로 이동") { _, _ ->
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
                .setTitle("초기 설정 (3/3)")
                .setMessage("앱 삭제 방지를 위해 기기 관리자를 활성화해 주세요.")
                .setPositiveButton("설정") { _, _ ->
                    pendingAdminUnlock = true
                    requestDeviceAdmin()
                }
                .setCancelable(false)
                .show()
            return
        }

        // 모든 설정 완료
        initialSetupRunning = false
        pendingAdminUnlock = false
        adminUnlocked = false
        prefsManager.initialSetupComplete = true
        // YouTube 차단 자동 활성화
        handleEnableBlocking()
        updateAdminSectionVisibility()
        checkExactAlarmPermission()
        Toast.makeText(this, "초기 설정이 완료되었습니다! YouTube 차단이 활성화됩니다.", Toast.LENGTH_LONG).show()
    }

    // ─────────────────────────── Timer Logic ───────────────────────────

    private fun handleTimerStart() {
        val remaining = prefsManager.getTimerRemainingSeconds()
        if (remaining <= 0) {
            Toast.makeText(this, "오늘 허용 시간을 모두 사용했습니다", Toast.LENGTH_SHORT).show()
            return
        }
        prefsManager.startTimerNow()
        scheduleTimerExpiryAlarm(remaining)
        performTimerDisableBlocking()
        startTimerTick()
        updateTimerButtons()
        Toast.makeText(this, "타이머 시작됨", Toast.LENGTH_SHORT).show()
    }

    private fun handleTimerStop() {
        prefsManager.pauseTimer()
        cancelTimerExpiryAlarm()
        timerHandler.removeCallbacks(timerTickRunnable)
        performTimerEnableBlocking()
        updateTimerDisplay(prefsManager.getTimerRemainingSeconds())
        updateTimerButtons()
        Toast.makeText(this, "타이머 정지됨", Toast.LENGTH_SHORT).show()
    }

    private fun handleTimerExpiredInForeground() {
        FileLogger.log(this, TAG, "포그라운드 타이머 만료 → 차단 활성화 시도")
        prefsManager.pauseTimer()
        cancelTimerExpiryAlarm()
        timerHandler.removeCallbacks(timerTickRunnable)
        updateTimerDisplay(0)
        updateTimerButtons()
        performTimerEnableBlocking()
        Toast.makeText(this, "허용 시간이 모두 소진되었습니다", Toast.LENGTH_LONG).show()
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
        tvAllowedTime.text = "허용 시간: ${h}시간 ${"%02d".format(m)}분"

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
            remaining <= 0 -> "시간 소진"
            prefsManager.timerIsRunning -> "진행 중 - YouTube 허용됨"
            else -> "대기 중"
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
            btnTimerStartStop.text = "⏸  정지"
            btnTimerStartStop.setBackgroundColor(getColor(R.color.orange_warning))
        } else {
            btnTimerStartStop.text = "▶  시작"
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
            text = "시간"
            setPadding(8, 0, 16, 0)
        }
        val labelM = TextView(this).apply {
            text = "분"
            setPadding(8, 0, 0, 0)
        }

        layout.addView(pickerHours)
        layout.addView(labelH)
        layout.addView(pickerMinutes)
        layout.addView(labelM)

        AlertDialog.Builder(this)
            .setTitle("허용 시간 설정")
            .setView(layout)
            .setPositiveButton("저장") { _, _ ->
                val newSeconds = (pickerHours.value * 3600L) + (pickerMinutes.value * 60L)
                if (newSeconds == 0L) {
                    Toast.makeText(this, "최소 1분 이상 설정해야 합니다", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                prefsManager.allowedSeconds = newSeconds
                // 타이머가 실행 중이 아닐 때만 남은 시간도 갱신
                if (!prefsManager.timerIsRunning) {
                    prefsManager.timerSavedRemaining = newSeconds
                }
                updateTimerDisplay(prefsManager.getTimerRemainingSeconds())
                updateTimerButtons()
                Toast.makeText(this, "허용 시간이 설정되었습니다", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("타이머 초기화") { _, _ ->
                // 타이머 실행 중이면 정지 후 초기화
                if (prefsManager.timerIsRunning) {
                    prefsManager.pauseTimer()
                    cancelTimerExpiryAlarm()
                    timerHandler.removeCallbacks(timerTickRunnable)
                }
                prefsManager.timerSavedRemaining = prefsManager.allowedSeconds
                updateTimerDisplay(prefsManager.getTimerRemainingSeconds())
                updateTimerButtons()
                Toast.makeText(this, "타이머가 초기화되었습니다", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
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
                .setTitle("보호 해제")
                .setMessage("YouTube 차단을 비활성화하시겠습니까?")
                .setPositiveButton("해제") { _, _ -> performDisableBlocking() }
                .setNegativeButton("취소") { _, _ -> switchBlocking.isChecked = true }
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
        Toast.makeText(this, "YouTube 차단이 활성화되었습니다", Toast.LENGTH_SHORT).show()
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
        Toast.makeText(this, "YouTube 차단이 비활성화되었습니다", Toast.LENGTH_SHORT).show()
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

        tvStatus.text = if (isBlocking) "YouTube 차단 중" else "보호 비활성화"
        tvStatus.setTextColor(
            if (isBlocking) getColor(R.color.green_active) else getColor(R.color.red_inactive)
        )

        val blockCount = prefsManager.blockCount
        tvBlockCount.text = "총 차단 횟수: $blockCount"

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
            tvVpnStatus.text = "VPN: 활성화 - DNS 차단 중"
            tvVpnStatus.setTextColor(getColor(R.color.green_active))
        } else if (hasPermission) {
            tvVpnStatus.text = "VPN: 권한 허용됨 (탭하여 시작)"
            tvVpnStatus.setTextColor(getColor(R.color.orange_warning))
        } else {
            tvVpnStatus.text = "VPN: 탭하여 권한 허용"
            tvVpnStatus.setTextColor(getColor(R.color.red_inactive))
        }
    }

    private fun updateAccessibilityStatusDisplay() {
        val isEnabled = isAccessibilityServiceEnabled()
        if (isEnabled) {
            tvAccessibilityStatus.text = "접근성: 활성화 - 앱 차단 중"
            tvAccessibilityStatus.setTextColor(getColor(R.color.green_active))
        } else {
            tvAccessibilityStatus.text = "접근성: 탭하여 활성화 (필수)"
            tvAccessibilityStatus.setTextColor(getColor(R.color.red_inactive))
        }
    }

    private fun updateDeviceAdminStatusDisplay() {
        // isAdminActive()는 비동기 해제 시 즉시 반영 안 됨 → prefsManager와 AND 조건으로 판단
        val isAdmin = devicePolicyManager.isAdminActive(adminComponentName) && prefsManager.deviceAdminEnabled
        if (isAdmin) {
            tvDeviceAdminStatus.text = "기기 관리자: 활성화 - 앱 삭제 방지 중"
            tvDeviceAdminStatus.setTextColor(getColor(R.color.green_active))
        } else {
            tvDeviceAdminStatus.text = "기기 관리자: 비활성화 - 탭하여 삭제 방지 설정"
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
                    .setTitle("기기 관리자 비활성화")
                    .setMessage("기기 관리자를 비활성화하면 앱을 삭제할 수 있게 됩니다. 계속하시겠습니까?")
                    .setPositiveButton("비활성화") { _, _ -> deactivateDeviceAdmin() }
                    .setNegativeButton("취소", null)
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
                "TubeGuard를 기기 관리자로 등록하면 앱이 무단으로 삭제되는 것을 방지합니다."
            )
        }
        deviceAdminLauncher.launch(intent)
    }

    private fun deactivateDeviceAdmin() {
        devicePolicyManager.removeActiveAdmin(adminComponentName)
        prefsManager.deviceAdminEnabled = false
        // removeActiveAdmin이 비동기라 isAdminActive()가 즉시 반영 안 됨 → 직접 UI 업데이트
        tvDeviceAdminStatus.text = "기기 관리자: 비활성화 - 탭하여 삭제 방지 설정"
        tvDeviceAdminStatus.setTextColor(getColor(R.color.red_inactive))
        Toast.makeText(this, "기기 관리자가 비활성화되었습니다", Toast.LENGTH_SHORT).show()
    }

    private fun updateTimerSettingsDisplay() {
        val totalAllowed = prefsManager.allowedSeconds
        val h = totalAllowed / 3600
        val m = (totalAllowed % 3600) / 60
        tvTimerSettingsStatus.text = "허용 시간 설정: ${h}시간 ${"%02d".format(m)}분"
    }

    private fun updatePinStatusDisplay() {
        tvPinStatus.text = if (pinManager.hasPin()) {
            "PIN 보호: 활성화 - 탭하여 변경"
        } else {
            "PIN 보호: 미설정 - 탭하여 설정"
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
            Toast.makeText(this, "VPN 권한이 이미 허용되어 있습니다", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, "접근성 설정을 열 수 없습니다", Toast.LENGTH_SHORT).show()
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
            R.id.action_reset -> {
                showResetDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showTimerLogDialog() {
        val content = FileLogger.getLogText(this).takeLast(3000)
        AlertDialog.Builder(this)
            .setTitle("타이머 디버그 로그")
            .setMessage(content)
            .setPositiveButton("확인", null)
            .show()
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("TubeGuard 정보")
            .setMessage(
                "TubeGuard v1.0\n\n" +
                "YouTube 차단 방법:\n" +
                "• VPN을 통한 DNS 수준 차단\n" +
                "• 접근성 서비스를 통한 앱 감지 차단\n" +
                "• PIN 보호 설정\n\n" +
                "차단 도메인: youtube.com, youtu.be, googlevideo.com 등"
            )
            .setPositiveButton("확인", null)
            .show()
    }

    private fun showResetDialog() {
        if (pinManager.hasPin()) {
            pinVerifyLauncher.launch(Intent(this, PinVerifyActivity::class.java))
        } else {
            confirmReset()
        }
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("전체 설정 초기화")
            .setMessage("차단이 해제되고 PIN이 삭제됩니다. 계속하시겠습니까?")
            .setPositiveButton("초기화") { _, _ ->
                performDisableBlocking()
                pinManager.clearPin()
                prefsManager.blockCount = 0
                updateAllStatusDisplays()
                Toast.makeText(this, "설정이 초기화되었습니다", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }
}

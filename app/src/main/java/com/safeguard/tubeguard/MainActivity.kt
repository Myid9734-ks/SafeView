package com.safeguard.tubeguard

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import androidx.appcompat.widget.SwitchCompat
import com.safeguard.tubeguard.receiver.TubeGuardDeviceAdminReceiver
import com.safeguard.tubeguard.service.TubeGuardVpnService
import com.safeguard.tubeguard.util.PinManager
import com.safeguard.tubeguard.util.PrefsManager

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TubeGuardMain"
        private const val REQUEST_VPN_PERMISSION = 1001
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

    private var pendingEnableBlocking = false

    // Activity result launchers
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Log.i(TAG, "VPN permission granted")
            startVpnService()
        } else {
            Log.w(TAG, "VPN permission denied")
            pendingEnableBlocking = false
            switchBlocking.isChecked = false
            Toast.makeText(this, "VPN permission required to block YouTube", Toast.LENGTH_LONG).show()
        }
    }

    private val pinSetupLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == PinSetupActivity.RESULT_PIN_SET) {
            updatePinStatusDisplay()
            Toast.makeText(this, "PIN protection enabled", Toast.LENGTH_SHORT).show()
        }
    }

    private val pinVerifyLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == PinVerifyActivity.RESULT_PIN_VERIFIED) {
            // PIN verified - disable blocking
            performDisableBlocking()
        } else {
            // PIN not verified - keep blocking on
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
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateAllStatusDisplays()
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
    }

    private fun setupListeners() {
        switchBlocking.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                handleEnableBlocking()
            } else {
                handleDisableBlocking()
            }
        }

        cardVpnStatus.setOnClickListener {
            openVpnPermission()
        }

        cardAccessibilityStatus.setOnClickListener {
            openAccessibilitySettings()
        }

        cardDeviceAdminStatus.setOnClickListener {
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
    }

    private fun handleEnableBlocking() {
        pendingEnableBlocking = true

        // Request VPN permission if needed
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            startVpnService()
        }
    }

    private fun handleDisableBlocking() {
        if (pinManager.hasPin()) {
            // Require PIN to disable
            pinVerifyLauncher.launch(Intent(this, PinVerifyActivity::class.java))
        } else {
            // No PIN set, disable directly
            AlertDialog.Builder(this)
                .setTitle("Disable Protection?")
                .setMessage("Are you sure you want to disable YouTube blocking?")
                .setPositiveButton("Disable") { _, _ ->
                    performDisableBlocking()
                }
                .setNegativeButton("Cancel") { _, _ ->
                    switchBlocking.isChecked = true
                }
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
        Toast.makeText(this, "YouTube blocking enabled", Toast.LENGTH_SHORT).show()
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
        Toast.makeText(this, "YouTube blocking disabled", Toast.LENGTH_SHORT).show()
    }

    private fun updateAllStatusDisplays() {
        val isBlocking = prefsManager.isBlocking

        // Main switch (without triggering listener)
        switchBlocking.setOnCheckedChangeListener(null)
        switchBlocking.isChecked = isBlocking
        switchBlocking.setOnCheckedChangeListener { _, checked ->
            if (checked) handleEnableBlocking() else handleDisableBlocking()
        }

        // Status text
        tvStatus.text = if (isBlocking) "YouTube Blocked" else "Protection Off"
        tvStatus.setTextColor(
            if (isBlocking) getColor(R.color.green_active) else getColor(R.color.red_inactive)
        )

        // Block count
        val blockCount = prefsManager.blockCount
        tvBlockCount.text = "Total blocks: $blockCount"

        // VPN status
        updateVpnStatusDisplay()

        // Accessibility status
        updateAccessibilityStatusDisplay()

        // Device admin status
        updateDeviceAdminStatusDisplay()

        // PIN status
        updatePinStatusDisplay()
    }

    private fun updateVpnStatusDisplay() {
        val vpnIntent = VpnService.prepare(this)
        val hasPermission = vpnIntent == null
        val isRunning = prefsManager.vpnEnabled

        if (isRunning && hasPermission) {
            tvVpnStatus.text = "VPN: Active - DNS blocking enabled"
            tvVpnStatus.setTextColor(getColor(R.color.green_active))
        } else if (hasPermission) {
            tvVpnStatus.text = "VPN: Permission granted (tap to start)"
            tvVpnStatus.setTextColor(getColor(R.color.orange_warning))
        } else {
            tvVpnStatus.text = "VPN: Tap to grant permission"
            tvVpnStatus.setTextColor(getColor(R.color.red_inactive))
        }
    }

    private fun updateAccessibilityStatusDisplay() {
        val isEnabled = isAccessibilityServiceEnabled()
        if (isEnabled) {
            tvAccessibilityStatus.text = "Accessibility: Active - App blocking enabled"
            tvAccessibilityStatus.setTextColor(getColor(R.color.green_active))
        } else {
            tvAccessibilityStatus.text = "Accessibility: Tap to enable (required)"
            tvAccessibilityStatus.setTextColor(getColor(R.color.red_inactive))
        }
    }

    private fun updateDeviceAdminStatusDisplay() {
        val isAdmin = devicePolicyManager.isAdminActive(adminComponentName)
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
            // Require PIN to deactivate
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
        updateDeviceAdminStatusDisplay()
        Toast.makeText(this, "기기 관리자가 비활성화되었습니다", Toast.LENGTH_SHORT).show()
    }

    private fun updatePinStatusDisplay() {
        tvPinStatus.text = if (pinManager.hasPin()) {
            "PIN Protection: Enabled - tap to change"
        } else {
            "PIN Protection: Not set - tap to set up"
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "${packageName}/.service.TubeGuardAccessibilityService"
        return try {
            val enabled = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            enabled.split(":").any { it.equals(serviceName, ignoreCase = true) }
        } catch (e: Exception) {
            false
        }
    }

    private fun openVpnPermission() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            Toast.makeText(this, "VPN permission already granted", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open accessibility settings", Toast.LENGTH_SHORT).show()
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

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("About TubeGuard")
            .setMessage(
                "TubeGuard v1.0\n\n" +
                "Protects children from YouTube by:\n" +
                "• DNS-level blocking via VPN\n" +
                "• App detection via Accessibility Service\n" +
                "• PIN-protected settings\n\n" +
                "Blocked domains include youtube.com, youtu.be, googlevideo.com, and more."
            )
            .setPositiveButton("OK", null)
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
            .setTitle("Reset All Settings?")
            .setMessage("This will disable blocking and remove your PIN. Are you sure?")
            .setPositiveButton("Reset") { _, _ ->
                performDisableBlocking()
                pinManager.clearPin()
                prefsManager.blockCount = 0
                updateAllStatusDisplays()
                Toast.makeText(this, "Settings reset", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

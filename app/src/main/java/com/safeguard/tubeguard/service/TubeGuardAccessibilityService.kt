package com.safeguard.tubeguard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.safeguard.tubeguard.BlockedActivity
import com.safeguard.tubeguard.PinVerifyActivity
import com.safeguard.tubeguard.receiver.TubeGuardDeviceAdminReceiver
import com.safeguard.tubeguard.util.PrefsManager

class TubeGuardAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TubeGuardA11y"

        // YouTube app package names
        private val YOUTUBE_PACKAGES = setOf(
            "com.google.android.youtube",
            "com.google.android.youtube.tv",
            "com.google.android.apps.youtube.music"
        )

        // Browser packages where we also check for YouTube URLs
        private val BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.microsoft.emmx",
            "com.opera.browser",
            "com.brave.browser",
            "com.sec.android.app.sbrowser",
            "com.UCMobile.intl"
        )

        // URL bar resource IDs in browsers
        private val URL_BAR_IDS = setOf(
            "com.android.chrome:id/url_bar",
            "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "com.microsoft.emmx:id/url_bar",
            "com.opera.browser:id/url_field",
            "com.brave.browser:id/url_bar",
            "com.sec.android.app.sbrowser:id/location_bar_edit_text"
        )

        // YouTube domain keywords to check in browser URL bars
        private val YOUTUBE_URL_KEYWORDS = listOf(
            "youtube.com",
            "youtu.be",
            "youtube-nocookie.com"
        )

        // KakaoTalk game tab blocking
        private const val KAKAO_PACKAGE = "com.kakao.talk"
        // If the window className contains any of these → definitely the game section
        private val KAKAO_GAME_CLASS_KEYWORDS = listOf("game", "Game", "GameTab", "MoreGame")
        // Unique text visible only on the game tab page
        private const val KAKAO_GAME_UNIQUE_TEXT = "다양한 인기 게임"
        // Game category chips shown on the tab — 2+ matches = game tab is active
        private val KAKAO_GAME_CATEGORY_CHIPS = listOf(
            "퍼즐", "액션", "슈팅", "전략", "시뮬",
            "RPG", "어드벤처", "아케이드", "보드", "퀴즈", "스포츠", "레이싱"
        )

        // Settings screens that can deactivate device admin or force-stop the app
        private val BLOCKED_SETTINGS_ACTIVITIES = setOf(
            "com.android.settings.DeviceAdminAdd",          // 기기 관리자 해제 화면
            "com.android.settings.applications.AppInfoBase", // 앱 정보 (강제종료 버튼 있음)
            "com.android.settings.applications.InstalledAppDetailsTop"
        )
        private const val SETTINGS_PACKAGE = "com.android.settings"
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefsManager: PrefsManager
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponentName: ComponentName
    private var lastBlockedPackage: String = ""
    private var lastBlockTime: Long = 0L
    private val BLOCK_COOLDOWN_MS = 2000L // Prevent rapid repeated blocking
    private var kakaoGameTabClickedAt: Long = 0L
    private var previousKakaoClass: String = ""
    private var kakaoClassBeforeDialog: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefsManager = PrefsManager.getInstance(this)
        devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponentName = ComponentName(this, TubeGuardDeviceAdminReceiver::class.java)
        Log.i(TAG, "Accessibility service connected")

        // Configure service info programmatically as a fallback
        val info = serviceInfo
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        info.notificationTimeout = 100
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!prefsManager.isBlocking) return

        val packageName = event.packageName?.toString() ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (packageName == KAKAO_PACKAGE) {
                    Log.d(TAG, "Kakao STATE_CHANGED class=${event.className}")
                }
                handleWindowStateChanged(packageName, event)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (packageName in BROWSER_PACKAGES) {
                    handleBrowserContentChanged(packageName, event)
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (packageName == KAKAO_PACKAGE) {
                    val clickedText = event.text?.joinToString()?.trim() ?: ""
                    val contentDesc = event.contentDescription?.toString()?.trim() ?: ""
                    Log.d(TAG, "Kakao CLICK text='$clickedText' desc='$contentDesc' class=${event.className}")
                    // 탭 버튼은 라벨 텍스트가 정확히 "게임" — 채팅 메시지 클릭과 구별
                    if (clickedText == "게임" || contentDesc == "게임") {
                        Log.i(TAG, "KakaoTalk game tab clicked — blocking")
                        kakaoGameTabClickedAt = System.currentTimeMillis()
                        blockKakaoGameTab()
                    }
                }
            }
        }
    }

    private fun handleWindowStateChanged(packageName: String, event: AccessibilityEvent) {
        if (packageName in YOUTUBE_PACKAGES) {
            Log.i(TAG, "YouTube detected in foreground: $packageName")
            blockYouTubeApp(packageName)
        } else if (packageName in BROWSER_PACKAGES) {
            // Check URL bar on window change too
            handler.postDelayed({
                checkBrowserForYouTube(packageName)
            }, 300)
        } else if (packageName == KAKAO_PACKAGE) {
            val className = event.className?.toString() ?: ""
            when {
                // className에 game 키워드 포함
                KAKAO_GAME_CLASS_KEYWORDS.any { className.contains(it) } -> {
                    Log.i(TAG, "KakaoTalk game screen via className: $className")
                    blockKakaoGameTab()
                }
                // Dialog 직전 클래스 기록
                className == "android.app.Dialog" -> {
                    kakaoClassBeforeDialog = previousKakaoClass
                    Log.d(TAG, "Kakao Dialog: classBeforeDialog=$kakaoClassBeforeDialog")
                }
                // PlusFriendCustomWebViewActivity 진입 시 출처 판별
                // 게임탭: MainActivity → (Dialog) → PlusFriendCustomWebViewActivity
                // 채팅링크: ChatRoomHolderActivity → Dialog → PlusFriendCustomWebViewActivity
                className.contains("PlusFriendCustomWebViewActivity") -> {
                    val fromMain = previousKakaoClass.contains("MainActivity") ||
                            kakaoClassBeforeDialog.contains("MainActivity")
                    Log.d(TAG, "PlusFriendCustomWebView: prev=$previousKakaoClass beforeDialog=$kakaoClassBeforeDialog fromMain=$fromMain")
                    if (fromMain) {
                        Log.i(TAG, "KakaoTalk game WebView detected from MainActivity")
                        blockKakaoGameTab()
                    }
                }
            }
            previousKakaoClass = className
        } else if (packageName == SETTINGS_PACKAGE) {
            val className = event.className?.toString() ?: ""
            handleSettingsScreen(className)
        }
    }

    private fun handleSettingsScreen(className: String) {
        // Block device admin deactivation screen
        if (BLOCKED_SETTINGS_ACTIVITIES.any { className.contains(it.substringAfterLast('.')) }) {
            Log.i(TAG, "Blocked settings screen detected: $className")
            // If we're the target app info page, redirect to home
            handler.postDelayed({
                val root = rootInActiveWindow ?: return@postDelayed
                try {
                    // Check if screen mentions TubeGuard app
                    val screenText = extractAllText(root)
                    if (screenText.contains("TubeGuard", ignoreCase = true) ||
                        screenText.contains("tubeguard", ignoreCase = true)) {
                        Log.i(TAG, "TubeGuard settings page blocked")
                        // Redirect to PIN verify if PIN is set, else go home
                        if (prefsManager.deviceAdminEnabled) {
                            val intent = Intent(this, PinVerifyActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                putExtra(PinVerifyActivity.EXTRA_PURPOSE, "settings_block")
                            }
                            startActivity(intent)
                        } else {
                            performGlobalAction(GLOBAL_ACTION_HOME)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling settings screen: ${e.message}")
                } finally {
                    try { root.recycle() } catch (ignored: Exception) {}
                }
            }, 500)
        }
    }

    private fun extractAllText(node: AccessibilityNodeInfo?, depth: Int = 0): String {
        if (node == null || depth > 5) return ""
        val sb = StringBuilder()
        node.text?.let { sb.append(it) }
        node.contentDescription?.let { sb.append(it) }
        for (i in 0 until node.childCount) {
            try {
                val child = node.getChild(i)
                sb.append(extractAllText(child, depth + 1))
                try { child?.recycle() } catch (ignored: Exception) {}
            } catch (e: Exception) { /* skip */ }
        }
        return sb.toString()
    }

    private fun handleBrowserContentChanged(packageName: String, event: AccessibilityEvent) {
        // Throttle checks: only check once per second per browser
        val now = System.currentTimeMillis()
        if (now - lastBlockTime < 1000 && packageName == lastBlockedPackage) return
        checkBrowserForYouTube(packageName)
    }

    /**
     * Check if a browser is currently showing a YouTube URL.
     * Traverses the accessibility node tree looking for URL bar nodes.
     */
    private fun checkBrowserForYouTube(packageName: String) {
        if (!prefsManager.isBlocking) return
        val root = rootInActiveWindow ?: return

        try {
            // Check known URL bar resource IDs only (no full-tree fallback to avoid false positives)
            for (urlBarId in URL_BAR_IDS) {
                if (!urlBarId.startsWith(packageName)) continue
                val nodes = root.findAccessibilityNodeInfosByViewId(urlBarId)
                if (nodes != null && nodes.isNotEmpty()) {
                    val text = nodes[0].text?.toString() ?: ""
                    recycleNodes(nodes)
                    if (isYouTubeUrl(text)) {
                        Log.i(TAG, "YouTube URL detected in browser $packageName: $text")
                        blockBrowserYouTube(packageName)
                        return
                    }
                    return // Found the URL bar but it's not YouTube — stop checking
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking browser for YouTube: ${e.message}")
        } finally {
            try { root.recycle() } catch (ignored: Exception) {}
        }
    }

    private fun isYouTubeUrl(text: String): Boolean {
        if (text.isBlank()) return false
        val lowerText = text.lowercase()
        return YOUTUBE_URL_KEYWORDS.any { keyword -> lowerText.contains(keyword) }
    }

    /**
     * Block the YouTube app by showing the blocked screen.
     */
    private fun blockYouTubeApp(packageName: String) {
        val now = System.currentTimeMillis()
        if (packageName == lastBlockedPackage && now - lastBlockTime < BLOCK_COOLDOWN_MS) return

        lastBlockedPackage = packageName
        lastBlockTime = now
        prefsManager.incrementBlockCount()

        prefsManager.addBlockLog("앱 실행", "YouTube 앱이 실행됨: $packageName")

        handler.post {
            try {
                val intent = Intent(this, BlockedActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(BlockedActivity.EXTRA_BLOCKED_SOURCE, BlockedActivity.SOURCE_APP)
                }
                startActivity(intent)
                Log.i(TAG, "Launched BlockedActivity for YouTube app")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch BlockedActivity: ${e.message}")
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }

        // Remove YouTube from recent apps list
        handler.postDelayed({ dismissYouTubeFromRecents() }, 800)
    }

    private fun dismissYouTubeFromRecents() {
        try {
            performGlobalAction(GLOBAL_ACTION_RECENTS)
            handler.postDelayed({
                try {
                    val root = rootInActiveWindow ?: return@postDelayed
                    // Find and dismiss YouTube task card — try dismiss buttons near YouTube text
                    val youtubeNodes = root.findAccessibilityNodeInfosByText("YouTube")
                    if (youtubeNodes != null && youtubeNodes.isNotEmpty()) {
                        // Walk up to task card and find its dismiss/close button
                        var taskCard = youtubeNodes[0].parent
                        repeat(5) {
                            if (taskCard == null) return@repeat
                            // Try to find close button within this container
                            for (i in 0 until (taskCard?.childCount ?: 0)) {
                                val child = taskCard?.getChild(i) ?: continue
                                val desc = child.contentDescription?.toString()?.lowercase() ?: ""
                                val viewId = child.viewIdResourceName ?: ""
                                if (desc.contains("닫기") || desc.contains("close") ||
                                    viewId.contains("dismiss") || viewId.contains("close")) {
                                    child.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                    Log.i(TAG, "Dismissed YouTube from recents")
                                    try { child.recycle() } catch (ignored: Exception) {}
                                    recycleNodes(youtubeNodes)
                                    try { root.recycle() } catch (ignored: Exception) {}
                                    return@postDelayed
                                }
                                try { child.recycle() } catch (ignored: Exception) {}
                            }
                            taskCard = taskCard?.parent
                        }
                        recycleNodes(youtubeNodes)
                    }
                    try { root.recycle() } catch (ignored: Exception) {}
                    // Go back home after recents
                    performGlobalAction(GLOBAL_ACTION_HOME)
                } catch (e: Exception) {
                    Log.e(TAG, "Error dismissing YouTube from recents: ${e.message}")
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            }, 600)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening recents: ${e.message}")
        }
    }

    /**
     * Block YouTube content in a browser by showing the blocked screen.
     */
    private fun blockBrowserYouTube(packageName: String) {
        val now = System.currentTimeMillis()
        if (packageName == lastBlockedPackage && now - lastBlockTime < BLOCK_COOLDOWN_MS) return

        lastBlockedPackage = packageName
        lastBlockTime = now
        prefsManager.incrementBlockCount()

        prefsManager.addBlockLog("브라우저", "브라우저에서 YouTube 접속: $packageName")

        handler.post {
            try {
                val intent = Intent(this, BlockedActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(BlockedActivity.EXTRA_BLOCKED_SOURCE, BlockedActivity.SOURCE_BROWSER)
                }
                startActivity(intent)
                Log.i(TAG, "Launched BlockedActivity for browser YouTube")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch BlockedActivity: ${e.message}")
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }
        // Navigate browser back after BlockedActivity is shown, so back action doesn't close it
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 500)
    }

    /**
     * 카카오톡 게임탭이 활성화되어 있는지 콘텐츠 노드 트리로 확인.
     * 1순위: "다양한 인기 게임" 텍스트 존재 → 즉시 차단
     * 2순위: 게임 카테고리 칩 2개 이상 동시 감지 → 차단
     */
    private fun checkKakaoGameTabContent() {
        if (!prefsManager.isBlocking) return
        val root = rootInActiveWindow ?: return
        try {
            val currentPkg = root.packageName?.toString()
            Log.d(TAG, "checkKakaoGameTabContent: currentPkg=$currentPkg")
            if (currentPkg != KAKAO_PACKAGE) return

            // 1순위: 게임탭 고유 헤더 텍스트
            val uniqueNodes = root.findAccessibilityNodeInfosByText(KAKAO_GAME_UNIQUE_TEXT)
            Log.d(TAG, "Unique text '$KAKAO_GAME_UNIQUE_TEXT' found: ${!uniqueNodes.isNullOrEmpty()}")
            if (!uniqueNodes.isNullOrEmpty()) {
                recycleNodes(uniqueNodes)
                Log.i(TAG, "KakaoTalk game tab detected: unique text found")
                blockKakaoGameTab()
                return
            }

            // 2순위: 게임 카테고리 칩 2개 이상
            var chipMatchCount = 0
            val matchedChips = mutableListOf<String>()
            for (chip in KAKAO_GAME_CATEGORY_CHIPS) {
                val nodes = root.findAccessibilityNodeInfosByText(chip)
                if (!nodes.isNullOrEmpty()) {
                    chipMatchCount++
                    matchedChips.add(chip)
                    recycleNodes(nodes)
                }
            }
            Log.d(TAG, "Kakao chip matches ($chipMatchCount): $matchedChips")
            if (chipMatchCount >= 2) {
                Log.i(TAG, "KakaoTalk game tab detected: $chipMatchCount category chips")
                blockKakaoGameTab()
                return
            }

            // 3순위: 전체 텍스트 덤프 (원인 파악용)
            val allText = extractAllText(root)
            Log.d(TAG, "Kakao full text dump (first 300): ${allText.take(300)}")
        } catch (e: Exception) {
            Log.e(TAG, "Error checking KakaoTalk game tab: ${e.message}")
        } finally {
            try { root.recycle() } catch (ignored: Exception) {}
        }
    }

    private fun blockKakaoGameTab() {
        val now = System.currentTimeMillis()
        if (KAKAO_PACKAGE == lastBlockedPackage && now - lastBlockTime < BLOCK_COOLDOWN_MS) return

        lastBlockedPackage = KAKAO_PACKAGE
        lastBlockTime = now
        prefsManager.incrementBlockCount()
        prefsManager.addBlockLog("카카오톡 게임", "카카오톡 게임탭 접근 차단")

        handler.post {
            try {
                val intent = Intent(this, BlockedActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(BlockedActivity.EXTRA_BLOCKED_SOURCE, BlockedActivity.SOURCE_APP)
                }
                startActivity(intent)
                Log.i(TAG, "Launched BlockedActivity for KakaoTalk game tab")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch BlockedActivity for KakaoTalk: ${e.message}")
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }
    }

    private fun recycleNodes(nodes: List<AccessibilityNodeInfo>?) {
        nodes?.forEach {
            try { it.recycle() } catch (ignored: Exception) {}
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        Log.i(TAG, "Accessibility service destroyed")
        super.onDestroy()
    }
}

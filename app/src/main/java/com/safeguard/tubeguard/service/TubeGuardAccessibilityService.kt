package com.safeguard.tubeguard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.safeguard.tubeguard.BlockedActivity
import com.safeguard.tubeguard.PinVerifyActivity
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
        // Keywords that indicate an individual game page (chat link games)
        private val KAKAO_GAME_PAGE_KEYWORDS = listOf(
            "game.kakao.com",
            "게임 시작", "게임시작", "게임하기",
            "하트", "목숨", "랭킹 보기", "점수 등록",
            "카카오게임"
        )

        // 소식탭 차단
        // 소식탭에만 나타나는 고유 텍스트 (친구탭에는 없음)
        private val KAKAO_NEWS_TAB_UNIQUE_TEXTS = listOf(
            "친구 소식",
            "스토리 보기",
            "에 반응했습니다",
            "님이 사진을 올렸습니다",
            "님이 동영상을 올렸습니다",
            "피드"
        )

        const val ACTION_FORCE_BLOCK_NOW = "com.safeguard.tubeguard.FORCE_BLOCK_NOW"

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
    private var kakaoNewsTabLastCheckedAt: Long = 0L
    private var kakaoNewsTabClickBlockedAt: Long = 0L  // 클릭 차단 전용 cooldown

    private val forceBlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_FORCE_BLOCK_NOW) {
                val remaining = prefsManager.getTimerRemainingSeconds()
                Log.i(TAG, "ACTION_FORCE_BLOCK_NOW received | isBlocking=${prefsManager.isBlocking} | remaining=${remaining}s | timerIsRunning=${prefsManager.timerIsRunning}")
                forceBlockIfYouTubeVisible()
            }
        }
    }
    private var lastBlockedPackage: String = ""
    private var lastBlockTime: Long = 0L
    private val BLOCK_COOLDOWN_MS = 2000L // Prevent rapid repeated blocking
    private var kakaoGameTabClickedAt: Long = 0L
    private var previousKakaoClass: String = ""
    private var kakaoClassBeforeDialog: String = ""
    private var kakaoLastKilledAt: Long = 0L
    private var currentKakaoClass: String = ""
    // kill 후 Android의 자동 activity 복원을 1회만 억제하기 위한 플래그.
    // kill 시 false로 초기화, 첫 번째 복원 억제 후 true로 설정.
    private var kakaoRestorationHandled: Boolean = false
    // Settings 앱을 강제종료 목적으로 직접 열었을 때 true.
    // handleSettingsScreen의 간섭 방지용.
    private var isForceStopSettingsOpen: Boolean = false
    // InAppBrowserActivity가 마지막으로 감지된 시각.
    // currentKakaoClass가 stale해져도 최근에 열렸으면 YouTube 체크를 수행하기 위함.
    private var kakaoInAppBrowserLastSeenAt: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefsManager = PrefsManager.getInstance(this)
        Log.i(TAG, "Accessibility service connected")
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(forceBlockReceiver, IntentFilter(ACTION_FORCE_BLOCK_NOW))
        startKakaoGameWatcher()

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

        val packageName = event.packageName?.toString() ?: return

        // isBlocking 여부와 무관하게 항상 현재 클래스 추적
        // → 타이머 중에도 갱신되어 타이머 만료 시 Watcher가 즉시 감지 가능
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            && packageName == KAKAO_PACKAGE) {
            currentKakaoClass = event.className?.toString() ?: ""
        }

        if (!prefsManager.isBlocking) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                when {
                    packageName in YOUTUBE_PACKAGES ->
                        Log.w(TAG, "YouTube foreground but isBlocking=FALSE — skipping ($packageName)")
                    packageName in BROWSER_PACKAGES ->
                        Log.w(TAG, "Browser foreground but isBlocking=FALSE — skipping ($packageName)")
                    packageName == KAKAO_PACKAGE ->
                        Log.w(TAG, "KakaoTalk foreground but isBlocking=FALSE — skipping class=${event.className}")
                }
            }
            return
        }

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
                } else if (packageName == KAKAO_PACKAGE) {
                    val now = System.currentTimeMillis()
                    if (now - kakaoNewsTabLastCheckedAt > 600L) {
                        kakaoNewsTabLastCheckedAt = now
                        handler.postDelayed({ checkKakaoNewsTabActive() }, 200)
                    }
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (packageName == KAKAO_PACKAGE) {
                    val clickedText = event.text?.joinToString()?.trim() ?: ""
                    val contentDesc = event.contentDescription?.toString()?.trim() ?: ""
                    Log.d(TAG, "Kakao CLICK text='$clickedText' desc='$contentDesc' class=${event.className} | " +
                        "currentClass=$currentKakaoClass | prev=$previousKakaoClass")
                    // 탭 버튼은 라벨 텍스트가 정확히 "게임" — 채팅 메시지 클릭과 구별
                    if (clickedText == "소식" || contentDesc.contains("소식")) {
                        val now = System.currentTimeMillis()
                        if (now - kakaoNewsTabClickBlockedAt > 300L) {
                            kakaoNewsTabClickBlockedAt = now
                            Log.w(TAG, "KakaoTalk news tab CLICKED — force stopping")
                            blockKakaoNewsTab()
                        }
                    }
                    // 하단 "친구 탭" 클릭 시 → 소식 서브탭이 열려있을 수 있으므로
                    // 600ms 후 강제로 친구 서브탭 선택
                    if (contentDesc == "친구 탭" || contentDesc.contains("친구 탭")) {
                        Log.d(TAG, "친구 탭 (bottom nav) clicked — will force 친구 sub-tab")
                        handler.postDelayed({ forceSelectFriendSubTab() }, 600)
                    }
                    if (clickedText == "게임" || contentDesc == "게임") {
                        val now = System.currentTimeMillis()
                        val withinKillWindow = now - kakaoLastKilledAt < 10000L
                        Log.w(TAG, "KakaoTalk game tab CLICKED — withinKillWindow=$withinKillWindow " +
                            "(sinceKill=${now - kakaoLastKilledAt}ms) — will block")
                        kakaoGameTabClickedAt = now
                        blockKakaoGameTab()
                    }
                    if (clickedText == "설치없이 실행하기" || clickedText.contains("실행하기")) {
                        Log.w(TAG, "KakaoTalk chat game link CLICKED ('$clickedText') — blocking")
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
            handler.postDelayed({
                checkBrowserForYouTube(packageName)
            }, 300)
        } else if (packageName == KAKAO_PACKAGE) {
            val className = event.className?.toString() ?: ""
            currentKakaoClass = className
            Log.d(TAG, "Kakao STATE: class=$className | prev=$previousKakaoClass | " +
                "killedAt=$kakaoLastKilledAt | sinceKill=${System.currentTimeMillis() - kakaoLastKilledAt}ms")
            when {
                // className에 game 키워드 포함
                KAKAO_GAME_CLASS_KEYWORDS.any { className.contains(it) } -> {
                    Log.i(TAG, "KakaoTalk game screen via className: $className")
                    blockKakaoGameTab()
                }
                // Dialog 직전 클래스 기록
                className == "android.app.Dialog" -> {
                    Log.d(TAG, "Kakao Dialog — saving prev=$previousKakaoClass as kakaoClassBeforeDialog")
                    kakaoClassBeforeDialog = previousKakaoClass
                }
                // PlusFriendCustomWebViewActivity 진입 시 출처 판별
                // 게임탭: MainActivity → (Dialog) → PlusFriendCustomWebViewActivity
                // 채팅링크: ChatRoomHolderActivity → Dialog → PlusFriendCustomWebViewActivity
                // 카카오톡 인앱 브라우저에서 YouTube URL 감지
                className.contains("InAppBrowserActivity") -> {
                    kakaoInAppBrowserLastSeenAt = System.currentTimeMillis()
                    Log.d(TAG, "KakaoTalk InAppBrowserActivity detected — checking for YouTube URL")
                    handler.postDelayed({ checkKakaoInAppBrowserForYouTube() }, 300)
                }
                className.contains("PlusFriendCustomWebViewActivity") -> {
                    // 강제종료 후 10초 이내 + 아직 복원을 억제하지 않은 경우 → Android 자동 복원, 1회만 억제
                    // 이미 복원을 1회 억제했거나 kill window 밖이면 → 유저가 직접 게임 진입 → 차단
                    val now = System.currentTimeMillis()
                    val withinKillWindow = now - kakaoLastKilledAt < 10000L
                    val isRestoration = withinKillWindow && !kakaoRestorationHandled
                    Log.w(TAG, "PlusFriendCustomWebViewActivity decision: " +
                        "withinKillWindow=$withinKillWindow " +
                        "(sinceKill=${now - kakaoLastKilledAt}ms) | " +
                        "prev='$previousKakaoClass' | " +
                        "restorationHandled=$kakaoRestorationHandled | " +
                        "isRestoration=$isRestoration")
                    if (isRestoration) {
                        kakaoRestorationHandled = true
                        Log.i(TAG, "PlusFriendCustomWebViewActivity SUPPRESSED — first Android restoration after kill (handled once)")
                    } else {
                        Log.i(TAG, "KakaoTalk PlusFriendCustomWebViewActivity BLOCKED (prev=$previousKakaoClass, restorationHandled=$kakaoRestorationHandled)")
                        blockKakaoGameTab()
                    }
                }
            }
            // 소식탭 자동전환 감지: 강제종료 후 재시작 포함 모든 화면 전환 시 체크
            handler.postDelayed({ checkKakaoNewsTabActive() }, 400)
            // MainActivity/TaskRootActivity 진입 시 친구 서브탭 강제 선택
            if (className.contains("MainActivity") || className.contains("TaskRootActivity")) {
                handler.postDelayed({ forceSelectFriendSubTab() }, 700)
            }
            previousKakaoClass = className
        } else if (packageName == SETTINGS_PACKAGE) {
            val className = event.className?.toString() ?: ""
            handleSettingsScreen(className)
        }
    }

    private fun handleSettingsScreen(className: String) {
        // 강제종료를 위해 직접 열었을 때는 간섭하지 않음
        if (isForceStopSettingsOpen) {
            Log.d(TAG, "Settings screen skipped — opened for force stop (class=$className)")
            return
        }
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
            // YouTube 앱 프로세스 강제종료 (동영상 재생 중단)
            handler.postDelayed({
                Log.i(TAG, "Force stopping YouTube app: $packageName")
                forceStopViaSettings(packageName)
            }, 1500)
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
            if (root.packageName?.toString() != KAKAO_PACKAGE) return

            // 1순위: 게임탭 고유 헤더 텍스트
            val uniqueNodes = root.findAccessibilityNodeInfosByText(KAKAO_GAME_UNIQUE_TEXT)
            if (!uniqueNodes.isNullOrEmpty()) {
                recycleNodes(uniqueNodes)
                Log.i(TAG, "KakaoTalk game tab detected: unique text found")
                blockKakaoGameTab()
                return
            }

            // 2순위: 게임 카테고리 칩 2개 이상
            var chipMatchCount = 0
            for (chip in KAKAO_GAME_CATEGORY_CHIPS) {
                val nodes = root.findAccessibilityNodeInfosByText(chip)
                if (!nodes.isNullOrEmpty()) {
                    chipMatchCount++
                    recycleNodes(nodes)
                }
            }
            if (chipMatchCount >= 2) {
                Log.i(TAG, "KakaoTalk game tab detected: $chipMatchCount category chips")
                blockKakaoGameTab()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking KakaoTalk game tab: ${e.message}")
        } finally {
            try { root.recycle() } catch (ignored: Exception) {}
        }
    }

    private fun checkKakaoGamePageContent() {
        if (!prefsManager.isBlocking) return
        val root = rootInActiveWindow ?: return
        try {
            if (root.packageName?.toString() != KAKAO_PACKAGE) return
            val allText = extractAllText(root)
            val isGame = KAKAO_GAME_PAGE_KEYWORDS.any { allText.contains(it, ignoreCase = true) }
            if (isGame) {
                Log.i(TAG, "KakaoTalk chat game link detected via content")
                blockKakaoGameTab()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking KakaoTalk game page: ${e.message}")
        } finally {
            try { root.recycle() } catch (ignored: Exception) {}
        }
    }

    /**
     * 소식탭이 현재 활성화되어 있는지 확인.
     * 1순위: "소식" 탭 노드가 isSelected=true
     * 2순위: 소식탭 고유 텍스트 감지
     */
    private fun checkKakaoNewsTabActive() {
        if (!prefsManager.isBlocking) return
        val root = rootInActiveWindow ?: return
        try {
            if (root.packageName?.toString() != KAKAO_PACKAGE) return
            // 1순위: 탭 선택 상태로 판별
            if (isNewsTabSelected(root)) {
                Log.i(TAG, "KakaoTalk 소식 tab: isSelected=true — auto-switch detected")
                redirectKakaoNewsTab()
                return
            }
            // 2순위: 소식탭 고유 텍스트로 판별
            val allText = extractAllText(root)
            val matched = KAKAO_NEWS_TAB_UNIQUE_TEXTS.firstOrNull { allText.contains(it) }
            if (matched != null) {
                Log.i(TAG, "KakaoTalk 소식 tab detected via text: '$matched'")
                redirectKakaoNewsTab()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking KakaoTalk news tab: ${e.message}")
        } finally {
            try { root.recycle() } catch (ignored: Exception) {}
        }
    }

    /**
     * 노드 트리를 순회하며 "소식" 탭이 활성화됐는지 확인.
     * 탭이 CheckBox 위젯이므로 isChecked로 확인 (isSelected 아님).
     * contentDescription이 "소식, 탭" 형태이므로 contains로 매칭.
     */
    private fun isNewsTabSelected(node: AccessibilityNodeInfo?, depth: Int = 0): Boolean {
        if (node == null || depth > 8) return false
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val isNewsTab = text == "소식" || desc.contains("소식")
        if (isNewsTab && (node.isChecked || node.isSelected)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = isNewsTabSelected(child, depth + 1)
            try { child.recycle() } catch (ignored: Exception) {}
            if (found) return true
        }
        return false
    }

    /**
     * 소식탭 차단: 게임탭과 동일하게 BlockedActivity 표시 + 강제종료.
     * 리다이렉트 방식은 반복 클릭 시 뚫리므로 강제종료로 처리.
     */
    private fun blockKakaoNewsTab() {
        val now = System.currentTimeMillis()
        if (KAKAO_PACKAGE == lastBlockedPackage && now - lastBlockTime < 300L) {
            Log.d(TAG, "blockKakaoNewsTab: skipped (cooldown, ${now - lastBlockTime}ms)")
            return
        }
        Log.w(TAG, "blockKakaoNewsTab TRIGGERED — currentClass=$currentKakaoClass")
        lastBlockedPackage = KAKAO_PACKAGE
        lastBlockTime = now
        prefsManager.incrementBlockCount()
        prefsManager.addBlockLog("카카오톡 소식", "소식탭 접근 차단")

        handler.post {
            try {
                val intent = Intent(this, BlockedActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(BlockedActivity.EXTRA_BLOCKED_SOURCE, BlockedActivity.SOURCE_KAKAO_GAME)
                }
                startActivity(intent)
                Log.i(TAG, "Launched BlockedActivity for KakaoTalk news tab")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch BlockedActivity for news tab: ${e.message}")
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            handler.postDelayed({ dismissKakaoFromRecents() }, 500)
            handler.postDelayed({
                kakaoLastKilledAt = System.currentTimeMillis()
                lastBlockedPackage = ""
                lastBlockTime = 0L
                currentKakaoClass = ""
                previousKakaoClass = ""
                kakaoRestorationHandled = false
                Log.i(TAG, "Kakao state reset after news tab block. Force stopping.")
                forceStopViaSettings(KAKAO_PACKAGE)
            }, 1500)
        }
    }

    /** 자동전환 감지 시: 강제종료 없이 친구탭으로 이동 */
    private fun redirectKakaoNewsTab() {
        val now = System.currentTimeMillis()
        if (KAKAO_PACKAGE == lastBlockedPackage && now - lastBlockTime < 800L) return
        Log.w(TAG, "redirectKakaoNewsTab: auto-switch detected — navigating to 친구 tab")
        lastBlockedPackage = KAKAO_PACKAGE
        lastBlockTime = now
        prefsManager.addBlockLog("카카오톡 소식", "소식탭 자동전환 감지 → 친구탭으로 이동")
        handler.post {
            val root = rootInActiveWindow ?: return@post
            try {
                if (root.packageName?.toString() != KAKAO_PACKAGE) { performGlobalAction(GLOBAL_ACTION_HOME); return@post }
                val nodes = root.findAccessibilityNodeInfosByText("친구")
                val friendTab = nodes?.firstOrNull { it.isClickable && it.isEnabled }
                if (friendTab != null) {
                    friendTab.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, "Redirected to 친구 tab")
                } else {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
                recycleNodes(nodes)
            } catch (e: Exception) {
                performGlobalAction(GLOBAL_ACTION_HOME)
            } finally {
                try { root.recycle() } catch (ignored: Exception) {}
            }
        }
    }

    /**
     * 친구 탭(하단) 클릭 후 또는 카카오톡 시작 시 호출.
     * 소식 서브탭이 열려있을 경우 친구 서브탭으로 강제 이동.
     * isChecked 감지 없이 항상 친구 CheckBox를 클릭해 확실하게 처리.
     */
    private fun forceSelectFriendSubTab(retryCount: Int = 0) {
        if (!prefsManager.isBlocking) return
        try {
            // rootInActiveWindow는 포커스된 윈도우만 반환 — 서브탭이 다른 윈도우에 있을 수 있으므로
            // FLAG_RETRIEVE_INTERACTIVE_WINDOWS로 모든 윈도우 탐색
            val kakaoRoots = windows
                ?.mapNotNull { it.root }
                ?.filter { it.packageName?.toString() == KAKAO_PACKAGE }
                ?: listOfNotNull(rootInActiveWindow?.takeIf { it.packageName?.toString() == KAKAO_PACKAGE })

            Log.d(TAG, "forceSelectFriendSubTab: searching ${kakaoRoots.size} kakao windows (retry=$retryCount)")

            var found = false
            for (root in kakaoRoots) {
                val nodes = root.findAccessibilityNodeInfosByText("친구")
                // 하단 "친구 탭"(text='')과 구별: 서브탭은 text="친구"로 정확히 매칭
                val friendSubTab = nodes?.firstOrNull {
                    it.isClickable && it.isEnabled &&
                    it.text?.toString() == "친구"
                }
                if (friendSubTab != null) {
                    friendSubTab.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, "forceSelectFriendSubTab: clicked 친구 sub-tab (retry=$retryCount)")
                    recycleNodes(nodes)
                    found = true
                    break
                }
                recycleNodes(nodes)
                try { root.recycle() } catch (ignored: Exception) {}
            }

            if (!found) {
                if (retryCount < 4) {
                    Log.d(TAG, "forceSelectFriendSubTab: not found, retry ${retryCount + 1}/4")
                    handler.postDelayed({ forceSelectFriendSubTab(retryCount + 1) }, 700)
                } else {
                    Log.d(TAG, "forceSelectFriendSubTab: gave up (친구 tab may not be active)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "forceSelectFriendSubTab error: ${e.message}")
        }
    }

    private fun blockKakaoGameTab() {
        val now = System.currentTimeMillis()
        if (KAKAO_PACKAGE == lastBlockedPackage && now - lastBlockTime < 300L) {
            Log.d(TAG, "blockKakaoGameTab: skipped (cooldown, ${now - lastBlockTime}ms since last block)")
            return
        }

        Log.w(TAG, "blockKakaoGameTab TRIGGERED — currentClass=$currentKakaoClass | prev=$previousKakaoClass | " +
            "lastBlockedPkg=$lastBlockedPackage | sinceLastBlock=${now - lastBlockTime}ms")

        lastBlockedPackage = KAKAO_PACKAGE
        lastBlockTime = now
        prefsManager.incrementBlockCount()
        prefsManager.addBlockLog("카카오톡 게임", "카카오톡 게임탭 접근 차단")

        handler.post {
            try {
                val intent = Intent(this, BlockedActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(BlockedActivity.EXTRA_BLOCKED_SOURCE, BlockedActivity.SOURCE_KAKAO_GAME)
                }
                startActivity(intent)
                Log.i(TAG, "Launched BlockedActivity for KakaoTalk game tab")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch BlockedActivity for KakaoTalk: ${e.message}")
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            // 최근 앱에서 제거 후 Settings 강제종료
            handler.postDelayed({ dismissKakaoFromRecents() }, 500)
            // 상태 변수 초기화 후 강제종료
            handler.postDelayed({
                kakaoLastKilledAt = System.currentTimeMillis()
                lastBlockedPackage = ""
                lastBlockTime = 0L
                currentKakaoClass = ""
                previousKakaoClass = ""
                kakaoRestorationHandled = false
                Log.i(TAG, "Kakao state reset. Attempting force stop via Settings.")
                forceStopViaSettings(KAKAO_PACKAGE)
            }, 1500)
        }
    }

    /**
     * Settings 앱의 앱 상세 정보 화면을 열고 접근성 이벤트로 강제종료 버튼을 클릭한다.
     */
    private fun forceStopViaSettings(packageName: String) {
        isForceStopSettingsOpen = true
        try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
            Log.i(TAG, "Opened app info settings for force stop: $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app info settings: ${e.message}")
            isForceStopSettingsOpen = false
            return
        }
        handler.postDelayed({ clickForceStopButton(attempt = 1) }, 1500)
    }

    private fun clickForceStopButton(attempt: Int) {
        val maxAttempts = 4
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString() ?: ""

        Log.d(TAG, "ForceStop attempt=$attempt pkg=$pkg")

        if (root == null || pkg.isEmpty()) {
            Log.w(TAG, "ForceStop attempt=$attempt: rootInActiveWindow is null — retrying")
            root?.recycle()
            scheduleForceStopRetry(attempt, maxAttempts)
            return
        }

        // 삼성/AOSP 둘 다 허용
        val isSettings = pkg == SETTINGS_PACKAGE || pkg.contains("settings", ignoreCase = true)
        if (!isSettings) {
            Log.w(TAG, "ForceStop attempt=$attempt: settings not in foreground (pkg=$pkg) — retrying")
            root.recycle()
            scheduleForceStopRetry(attempt, maxAttempts)
            return
        }

        try {
            // "강제 종료" 또는 "Force stop" 버튼 탐색
            val candidates = listOf("강제 중지", "강제 종료", "강제종료", "Force stop", "Force Stop")
            for (label in candidates) {
                val nodes = root.findAccessibilityNodeInfosByText(label)
                if (!nodes.isNullOrEmpty()) {
                    val btn = nodes[0]
                    val enabled = btn.isEnabled
                    Log.i(TAG, "ForceStop button found ('$label') enabled=$enabled on attempt=$attempt")
                    if (enabled) {
                        btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.i(TAG, "ForceStop button clicked")
                        handler.postDelayed({ confirmForceStopDialog() }, 500)
                    } else {
                        Log.w(TAG, "ForceStop button disabled — already stopped")
                        isForceStopSettingsOpen = false
                        performGlobalAction(GLOBAL_ACTION_HOME)
                    }
                    recycleNodes(nodes)
                    return
                }
            }
            // 버튼을 못 찾은 경우: 화면에 있는 텍스트를 덤프해서 로그
            val screenText = extractAllText(root).take(300)
            Log.w(TAG, "ForceStop attempt=$attempt: button not found. Screen text: $screenText")
            scheduleForceStopRetry(attempt, maxAttempts)
        } catch (e: Exception) {
            Log.e(TAG, "ForceStop attempt=$attempt error: ${e.message}")
            scheduleForceStopRetry(attempt, maxAttempts)
        } finally {
            try { root.recycle() } catch (ignored: Exception) {}
        }
    }

    private fun scheduleForceStopRetry(attempt: Int, maxAttempts: Int) {
        if (attempt < maxAttempts) {
            Log.d(TAG, "ForceStop scheduling retry ${attempt + 1}/$maxAttempts")
            handler.postDelayed({ clickForceStopButton(attempt + 1) }, 1000)
        } else {
            Log.w(TAG, "ForceStop gave up after $maxAttempts attempts")
            isForceStopSettingsOpen = false
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun confirmForceStopDialog() {
        val root = rootInActiveWindow ?: return
        try {
            // 1순위: 텍스트로 찾기
            val candidates = listOf("확인", "OK", "강제 종료", "Force stop", "강제종료", "예", "Yes")
            for (label in candidates) {
                val nodes = root.findAccessibilityNodeInfosByText(label)
                if (!nodes.isNullOrEmpty()) {
                    nodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, "ForceStop confirm dialog: clicked '$label'")
                    recycleNodes(nodes)
                    handler.postDelayed({
                        isForceStopSettingsOpen = false
                        Log.i(TAG, "KakaoTalk force stopped via Settings")
                        dismissSettingsFromRecents()
                    }, 300)
                    return
                }
            }
            // 2순위: 텍스트 매칭 실패 시 클릭 가능한 버튼 노드 직접 탐색
            val screenText = extractAllText(root).take(200)
            Log.w(TAG, "ForceStop confirm dialog: text match failed. Screen: $screenText — trying button scan")
            val button = findClickableButton(root)
            if (button != null) {
                val btnText = button.text?.toString() ?: button.contentDescription?.toString() ?: "unknown"
                button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.i(TAG, "ForceStop confirm dialog: clicked button '$btnText' via scan")
                try { button.recycle() } catch (ignored: Exception) {}
                handler.postDelayed({
                    isForceStopSettingsOpen = false
                    Log.i(TAG, "KakaoTalk force stopped via Settings")
                    dismissSettingsFromRecents()
                }, 300)
            } else {
                Log.w(TAG, "ForceStop confirm dialog: no clickable button found")
                isForceStopSettingsOpen = false
                dismissSettingsFromRecents()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error confirming force stop: ${e.message}")
            isForceStopSettingsOpen = false
            dismissSettingsFromRecents()
        } finally {
            try { root.recycle() } catch (ignored: Exception) {}
        }
    }

    private fun findClickableButton(node: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (node == null || depth > 6) return null
        val className = node.className?.toString() ?: ""
        if (node.isClickable && node.isEnabled &&
            (className.contains("Button") || className.contains("TextView"))) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findClickableButton(child, depth + 1)
            if (found != null) {
                try { child.recycle() } catch (ignored: Exception) {}
                return found
            }
            try { child.recycle() } catch (ignored: Exception) {}
        }
        return null
    }

    private fun dismissKakaoFromRecents() {
        try {
            performGlobalAction(GLOBAL_ACTION_RECENTS)
            handler.postDelayed({
                try {
                    val root = rootInActiveWindow ?: return@postDelayed
                    val kakaoNodes = root.findAccessibilityNodeInfosByText("카카오톡")
                        .takeIf { !it.isNullOrEmpty() }
                        ?: root.findAccessibilityNodeInfosByText("KakaoTalk")
                    if (!kakaoNodes.isNullOrEmpty()) {
                        var taskCard = kakaoNodes[0].parent
                        repeat(6) {
                            if (taskCard == null) return@repeat
                            for (i in 0 until (taskCard?.childCount ?: 0)) {
                                val child = taskCard?.getChild(i) ?: continue
                                val desc = child.contentDescription?.toString()?.lowercase() ?: ""
                                val viewId = child.viewIdResourceName ?: ""
                                if (desc.contains("닫기") || desc.contains("close") ||
                                    viewId.contains("dismiss") || viewId.contains("close")) {
                                    child.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                    Log.i(TAG, "KakaoTalk dismissed from recents")
                                    try { child.recycle() } catch (ignored: Exception) {}
                                    recycleNodes(kakaoNodes)
                                    try { root.recycle() } catch (ignored: Exception) {}
                                    handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, 300)
                                    return@postDelayed
                                }
                                try { child.recycle() } catch (ignored: Exception) {}
                            }
                            taskCard = taskCard?.parent
                        }
                        recycleNodes(kakaoNodes)
                    }
                    try { root.recycle() } catch (ignored: Exception) {}
                    performGlobalAction(GLOBAL_ACTION_HOME)
                } catch (e: Exception) {
                    Log.e(TAG, "Error dismissing KakaoTalk from recents: ${e.message}")
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            }, 600)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening recents: ${e.message}")
        }
    }

    private fun dismissSettingsFromRecents() {
        // Settings 앱 정보 화면이 포그라운드에 있는 동안 BACK을 눌러 액티비티 종료
        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_BACK)
            handler.postDelayed({
                performGlobalAction(GLOBAL_ACTION_HOME)
                Log.i(TAG, "Settings closed and returned to home")
            }, 200)
        }, 300)
    }

    private fun recycleNodes(nodes: List<AccessibilityNodeInfo>?) {
        nodes?.forEach {
            try { it.recycle() } catch (ignored: Exception) {}
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    private fun startKakaoGameWatcher() {
        val watcherRunnable = object : Runnable {
            override fun run() {
                val pkg = rootInActiveWindow?.packageName?.toString() ?: ""
                if (!prefsManager.isBlocking && (pkg in YOUTUBE_PACKAGES || pkg == KAKAO_PACKAGE)) {
                    Log.w(TAG, "Watcher: isBlocking=FALSE but targeted app in foreground: $pkg")
                }
                if (prefsManager.isBlocking) {
                    val pkg = rootInActiveWindow?.packageName?.toString()
                    if (pkg == KAKAO_PACKAGE) {
                        val now = System.currentTimeMillis()
                        val withinKillWindow = now - kakaoLastKilledAt < 10000L
                        val isGame = currentKakaoClass.contains("PlusFriendCustomWebViewActivity") ||
                            KAKAO_GAME_CLASS_KEYWORDS.any { currentKakaoClass.contains(it) }
                        if (isGame) {
                            Log.d(TAG, "Watcher: game class detected=$currentKakaoClass | withinKillWindow=$withinKillWindow " +
                                "(sinceKill=${now - kakaoLastKilledAt}ms) | prev=$previousKakaoClass")
                        }
                        if (isGame && !withinKillWindow) {
                            Log.w(TAG, "Watcher: KakaoTalk game BLOCKING ($currentKakaoClass)")
                            blockKakaoGameTab()
                        } else if (isGame && withinKillWindow) {
                            Log.w(TAG, "Watcher: game detected but SUPPRESSED by kill window — " +
                                "currentClass=$currentKakaoClass, prev=$previousKakaoClass. " +
                                "If user manually navigated here this is a bug!")
                        }
                        // 소식탭 자동전환 감지 (watcher 보조 체크)
                        val nowNews = System.currentTimeMillis()
                        if (nowNews - kakaoNewsTabLastCheckedAt > 1000L) {
                            kakaoNewsTabLastCheckedAt = nowNews
                            checkKakaoNewsTabActive()
                        }
                    }
                }
                handler.postDelayed(this, 500)
            }
        }
        handler.postDelayed(watcherRunnable, 500)
    }

    private fun forceBlockIfYouTubeVisible() {
        val pkg = rootInActiveWindow?.packageName?.toString() ?: run {
            Log.w(TAG, "Force block: rootInActiveWindow is null")
            return
        }
        val remaining = prefsManager.getTimerRemainingSeconds()
        Log.i(TAG, "Force block check — foreground=$pkg | isBlocking=${prefsManager.isBlocking} | remaining=${remaining}s | currentKakaoClass=$currentKakaoClass")
        when {
            pkg in YOUTUBE_PACKAGES -> {
                Log.i(TAG, "Force block: YouTube app in foreground → blocking")
                blockYouTubeApp(pkg)
            }
            pkg in BROWSER_PACKAGES -> {
                Log.i(TAG, "Force block: browser in foreground → checking URL")
                handler.postDelayed({ checkBrowserForYouTube(pkg) }, 200)
            }
            pkg == KAKAO_PACKAGE -> {
                Log.i(TAG, "Force block: KakaoTalk in foreground → checking game/browser (currentClass=$currentKakaoClass)")
                handler.postDelayed({
                    checkKakaoGameTabContent()
                    checkKakaoGamePageContent()
                    checkKakaoInAppBrowserForYouTube()
                    checkKakaoNewsTabActive()
                }, 200)
            }
            else -> Log.i(TAG, "Force block: foreground app not targeted ($pkg) — no action")
        }
    }

    /**
     * 카카오톡 InAppBrowserActivity에서 YouTube URL이 열려 있는지 확인.
     * URL 바 노드가 없을 경우 전체 텍스트에서 youtube 키워드를 탐색.
     */
    private fun checkKakaoInAppBrowserForYouTube() {
        if (!prefsManager.isBlocking) return
        val root = rootInActiveWindow ?: return
        try {
            if (root.packageName?.toString() != KAKAO_PACKAGE) return

            // WebView 노드 존재 여부로 InAppBrowserActivity 판별
            // (currentKakaoClass는 stale할 수 있으므로 사용하지 않음)
            val hasWebView = findWebView(root)
            if (!hasWebView) {
                Log.d(TAG, "KakaoTalk InAppBrowser: no WebView found — skipping")
                return
            }

            val allText = extractAllText(root)
            // 1순위: URL 텍스트에서 youtube.com 키워드
            val isYouTubeUrl = YOUTUBE_URL_KEYWORDS.any { allText.contains(it, ignoreCase = true) }
            // 2순위: 동영상 재생 중 URL이 숨겨질 때 → YouTube 브랜드 텍스트 감지
            val isYouTubeBrand = !isYouTubeUrl && listOf("YouTube", "유튜브").any { allText.contains(it) }

            Log.d(TAG, "KakaoTalk InAppBrowser YouTube check: hasWebView=true urlMatch=$isYouTubeUrl brandMatch=$isYouTubeBrand class=$currentKakaoClass")

            if (isYouTubeUrl || isYouTubeBrand) {
                Log.i(TAG, "YouTube detected in KakaoTalk InAppBrowserActivity (url=$isYouTubeUrl brand=$isYouTubeBrand) — blocking + force stopping")
                blockKakaoGameTab()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking KakaoTalk InAppBrowser for YouTube: ${e.message}")
        } finally {
            try { root.recycle() } catch (ignored: Exception) {}
        }
    }

    private fun findWebView(node: AccessibilityNodeInfo?, depth: Int = 0): Boolean {
        if (node == null || depth > 8) return false
        if (node.className?.toString()?.contains("WebView") == true) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findWebView(child, depth + 1)
            try { child.recycle() } catch (ignored: Exception) {}
            if (found) return true
        }
        return false
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(forceBlockReceiver)
        handler.removeCallbacksAndMessages(null)
        Log.i(TAG, "Accessibility service destroyed")
        super.onDestroy()
    }
}

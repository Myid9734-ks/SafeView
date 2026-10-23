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
import android.view.inputmethod.InputMethodManager
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.safeguard.tubeguard.BlockedActivity
import com.safeguard.tubeguard.PinVerifyActivity
import com.safeguard.tubeguard.util.FileLogger
import com.safeguard.tubeguard.util.PinManager
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

        // TikTok 앱 패키지 (지역별 빌드 + Lite) — 타이머 연동 차단
        private val TIKTOK_PACKAGES = setOf(
            "com.zhiliaoapp.musically",         // TikTok (글로벌)
            "com.ss.android.ugc.trill",         // TikTok (아시아 지역)
            "com.zhiliaoapp.musically.go",      // TikTok Lite
            "com.ss.android.ugc.tiktok.lite"    // TikTok Lite (일부 지역)
        )

        // Instagram 앱 패키지 — 타이머 연동 차단
        private val INSTAGRAM_PACKAGES = setOf(
            "com.instagram.android",
            "com.instagram.lite"
        )

        // Facebook 앱 패키지 — 타이머 연동 차단
        private val FACEBOOK_PACKAGES = setOf(
            "com.facebook.katana",
            "com.facebook.lite"
        )

        // X (Twitter) 앱 패키지 — 타이머 연동 차단
        private val X_PACKAGES = setOf(
            "com.twitter.android",
            "com.twitter.android.lite"
        )

        // Snapchat — 타이머 연동 차단
        private val SNAPCHAT_PACKAGES = setOf(
            "com.snapchat.android"
        )

        // Twitch — 타이머 연동 차단
        private val TWITCH_PACKAGES = setOf(
            "tv.twitch.android.app"
        )

        // AfreecaTV — 타이머 연동 차단
        private val AFREECA_PACKAGES = setOf(
            "kr.co.iphone.afreecatv"
        )

        // 치지직 (Chzzk) — 타이머 연동 차단
        private val CHZZK_PACKAGES = setOf(
            "com.navercorp.game.android.community"
        )

        // Discord — 타이머 연동 차단
        private val DISCORD_PACKAGES = setOf(
            "com.discord"
        )

        // Reddit — 타이머 연동 차단
        private val REDDIT_PACKAGES = setOf(
            "com.reddit.frontpage"
        )

        // Pinterest — 타이머 연동 차단
        private val PINTEREST_PACKAGES = setOf(
            "com.pinterest"
        )

        // BeReal — 타이머 연동 차단
        private val BEREAL_PACKAGES = setOf(
            "com.bereal.ft"
        )

        // Browser packages where we also check for YouTube URLs
        private val BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.microsoft.emmx",
            "com.opera.browser",
            "com.brave.browser",
            "com.sec.android.app.sbrowser",
            "com.UCMobile.intl",
            // 검색 앱도 웹 페이지를 열 수 있으므로 브라우저로 취급
            "com.google.android.googlequicksearchbox",  // Google 앱
            "com.google.android.apps.searchlite",       // Google Go
            "com.nhn.android.search",                   // 네이버
            "com.naver.whale",                          // 웨일
            "net.daum.android.daum"                     // 다음
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

        // 카카오톡 인앱 브라우저에서 차단할 TikTok URL 키워드
        // (브랜드 텍스트는 뉴스 기사 등에도 등장해 오탐이 생기므로 URL로만 판별)
        private val TIKTOK_URL_KEYWORDS = listOf(
            "tiktok.com",
            "tiktokv.com"
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

        // 소식 피드 게시물에만 있는 버튼 뷰 ID (uiautomator dump로 확인)
        private val KAKAO_NEWS_FEED_VIEW_IDS = listOf(
            "like_container", "comment_container", "bookmark_container"
        )

        // 친구 프로필을 눌러 들어가는 피드 화면 (소식과 같은 게시물 피드)
        private const val KAKAO_PROFILE_FEED_CLASS = "ProfileFeedActivity"

        // 숏폼탭 차단 (탭 선택 상태로만 판별 — 칩 텍스트는 다른 탭과 겹쳐 오탐 발생)

        // 채팅탭 상단 "ChatGPT" 버튼 → 별도 Activity(com.kakao.talk.chatgpt.komi.ChatGptWebActivity)로 열림
        private const val KAKAO_CHATGPT_CLASS_KEYWORD = ".chatgpt."

        const val ACTION_FORCE_BLOCK_NOW = "com.safeguard.tubeguard.FORCE_BLOCK_NOW"

        // Settings screens that can deactivate device admin or force-stop the app
        private val BLOCKED_SETTINGS_ACTIVITIES = setOf(
            "com.android.settings.DeviceAdminAdd",          // 기기 관리자 해제 화면
            "com.android.settings.applications.AppInfoBase", // 앱 정보 (강제종료 버튼 있음)
            "com.android.settings.applications.InstalledAppDetailsTop",
            "ChannelNotificationSettings",                   // 알림 카테고리 (VPN 알림 끄기 방지)
            "AppNotificationSettings",                       // 앱 알림 설정
            "ChannelGroupNotificationSettings"               // 알림 그룹 설정
        )
        private const val SETTINGS_PACKAGE = "com.android.settings"

        // 설정 앱 패키지 — PIN 입력 후에만 접속 허용
        private val SETTINGS_PACKAGES = setOf(
            "com.android.settings",
            "com.samsung.android.app.routines",      // 빅스비 루틴 (자동화로 우회 가능)
            "com.samsung.accessibility"              // 접근성 설정 바로가기
        )

        // 앱 스토어: 타이머(isBlocking)와 무관하게 항상 PIN 입력 후에만 접속 허용
        private val STORE_PACKAGES = setOf(
            "com.android.vending",               // Google Play 스토어
            "com.sec.android.app.samsungapps"    // Galaxy Store
        )
        // 스토어 사용 중 잠깐 앞에 뜨는 패키지 — 이 패키지로 전환돼도 "스토어를 떠난 것"으로 보지 않음
        private val STORE_TRANSIENT_PACKAGES = setOf(
            "android",                            // 시스템 대화상자
            "com.android.systemui",
            "com.google.android.gms",             // Google 로그인/결제
            "com.osp.app.signin",                 // 삼성 계정 로그인
            "com.samsung.android.mobileservice"
        )

        // PIN 인증으로 잠금 해제된 스토어 패키지. 다른 앱으로 이동하면 다시 잠김.
        @Volatile
        var unlockedStorePackage: String? = null

        // 스토어 PIN 프롬프트 쿨다운 — PinVerifyActivity에서 취소 시에도 갱신하여 연타 방지
        @Volatile
        var storePinPromptedAt: Long = 0L

        // PIN 인증으로 잠금 해제된 설정 앱. 다른 앱으로 이동하면 다시 잠김.
        @Volatile
        var isSettingsUnlocked: Boolean = false

        // 설정 PIN 프롬프트 쿨다운
        @Volatile
        var settingsPinPromptedAt: Long = 0L
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefsManager: PrefsManager
    private var kakaoNewsTabLastCheckedAt: Long = 0L
    private var kakaoNewsTabClickBlockedAt: Long = 0L  // 클릭 차단 전용 cooldown
    // 소식 차단(강제종료) 후 카카오톡 재실행 시 마지막 화면(소식 피드)이 복원됨
    // → 재실행 직후 1회는 차단 대신 친구탭으로 이동시키기 위한 상태
    // 재부팅/서비스 재시작 후에도 카카오톡은 소식 피드를 복원하므로 디스크에 저장
    private var kakaoNewsRestorePending: Boolean
        get() = getSharedPreferences("kakao_state", MODE_PRIVATE).getBoolean("news_restore_pending", false)
        set(value) {
            getSharedPreferences("kakao_state", MODE_PRIVATE).edit().putBoolean("news_restore_pending", value).apply()
        }
    private var kakaoRelaunchedAt: Long = 0L
    private var kakaoShortformTabLastCheckedAt: Long = 0L
    private var kakaoShortformTabClickBlockedAt: Long = 0L
    private var kakaoProfileFeedBlockedAt: Long = 0L

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
    // 기기에 설치된 브라우저 (http 링크를 여는 앱) — 차단 모드에서 실행 자체를 차단
    private var installedBrowsers: Set<String> = emptySet()
    private var installedBrowsersRefreshedAt: Long = 0L
    private var browserCheckPending: Boolean = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefsManager = PrefsManager.getInstance(this)
        Log.i(TAG, "Accessibility service connected")
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(forceBlockReceiver, IntentFilter(ACTION_FORCE_BLOCK_NOW))
        startKakaoGameWatcher()
        refreshInstalledBrowsers()

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

        // 스토어/설정 잠금은 타이머와 무관하게 항상 적용
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handleStoreGate(packageName)
            handleSettingsGate(packageName)
        }

        if (!prefsManager.isBlocking) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                when {
                    packageName in YOUTUBE_PACKAGES ->
                        Log.w(TAG, "YouTube foreground but isBlocking=FALSE — skipping ($packageName)")
                    packageName in TIKTOK_PACKAGES ->
                        Log.w(TAG, "TikTok foreground but isBlocking=FALSE — skipping ($packageName)")
                    isBrowser(packageName) ->
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
                    if (event.className?.toString()?.contains(KAKAO_PROFILE_FEED_CLASS) == true) {
                        blockKakaoProfileFeed()
                    }
                    if (event.className?.toString()?.contains(KAKAO_CHATGPT_CLASS_KEYWORD) == true) {
                        blockKakaoChatGpt()
                    }
                    // 클릭 이벤트 없이(알림 등으로) ChatGPT for Kakao 채팅방이 열린 경우
                    if (event.className?.toString()?.contains("ChatRoom") == true) {
                        handler.postDelayed({ checkKakaoChatGptRoom() }, 400)
                    }
                }
                handleWindowStateChanged(packageName, event)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (isBrowser(packageName)) {
                    // 차단 시작 시점에 이미 브라우저가 열려 있던 경우 대비
                    blockBrowserApp(packageName)
                } else if (packageName == KAKAO_PACKAGE) {
                    val now = System.currentTimeMillis()
                    if (now - kakaoNewsTabLastCheckedAt > 600L) {
                        kakaoNewsTabLastCheckedAt = now
                        handler.postDelayed({ checkKakaoNewsTabActive() }, 200)
                    }
                    if (now - kakaoShortformTabLastCheckedAt > 600L) {
                        kakaoShortformTabLastCheckedAt = now
                        handler.postDelayed({ checkKakaoShortformTabActive() }, 200)
                        handler.postDelayed({ checkKakaoChatGptContent() }, 200)
                    }
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (packageName == KAKAO_PACKAGE) {
                    val clickedText = event.text?.joinToString()?.trim() ?: ""
                    val contentDesc = event.contentDescription?.toString()?.trim() ?: ""
                    Log.d(TAG, "Kakao CLICK text='$clickedText' desc='$contentDesc' class=${event.className} | " +
                        "currentClass=$currentKakaoClass | prev=$previousKakaoClass")
                    FileLogger.log(this, TAG, "Kakao CLICK text='$clickedText' desc='$contentDesc' " +
                        "class=${event.className} src='${sourceText(event).take(80)}'")
                    // 클릭 이벤트 정보만으로 판별이 안 되는 경우 대비: 화면 전환 후 소식탭 상태 재확인
                    handler.postDelayed({ checkKakaoNewsTabActive() }, 500)
                    handler.postDelayed({ checkKakaoNewsTabActive() }, 1200)
                    // 탭 버튼은 라벨 텍스트가 정확히 "게임" — 채팅 메시지 클릭과 구별
                    if (clickedText == "소식" || contentDesc.contains("소식")) {
                        val now = System.currentTimeMillis()
                        if (now - kakaoNewsTabClickBlockedAt > 300L) {
                            kakaoNewsTabClickBlockedAt = now
                            Log.w(TAG, "KakaoTalk news tab CLICKED — force stopping")
                            blockKakaoNewsTab()
                        }
                    }
                    // 업데이트한 친구 섹션의 "더보기" → 소식탭으로 연결됨
                    if ((clickedText == "더보기" || contentDesc == "더보기") &&
                        (currentKakaoClass.contains("MainActivity") || currentKakaoClass.contains("TaskRootActivity"))) {
                        val now = System.currentTimeMillis()
                        if (now - kakaoNewsTabClickBlockedAt > 300L) {
                            kakaoNewsTabClickBlockedAt = now
                            Log.w(TAG, "KakaoTalk 업데이트한 친구 더보기 CLICKED (→소식탭) — blocking")
                            blockKakaoNewsTab()
                        }
                    }
                    // 업데이트 프로필 섹션의 "소식보기" (→ 소식탭으로 연결됨)
                    // 화살표 아이콘만 눌린 경우 event.text가 비어 있으므로 클릭된 노드 하위도 확인
                    if (clickedText.contains("소식보기") || contentDesc.contains("소식보기") ||
                        sourceContainsText(event, "소식보기")) {
                        val now = System.currentTimeMillis()
                        if (now - kakaoNewsTabClickBlockedAt > 300L) {
                            kakaoNewsTabClickBlockedAt = now
                            Log.w(TAG, "KakaoTalk 소식보기 CLICKED (→소식탭) — blocking")
                            blockKakaoNewsTab()
                        }
                    }
                    // 채팅탭 상단 "ChatGPT" 버튼 클릭 (채팅방 이름 "ChatGPT for Kakao"와는 정확히 일치하지 않음)
                    // 채팅 목록의 "ChatGPT for Kakao" 채팅방 클릭도 동일하게 차단
                    if (clickedText == "ChatGPT" || contentDesc == "ChatGPT" ||
                        clickedText.startsWith("ChatGPT for Kakao") || contentDesc.startsWith("ChatGPT for Kakao")) {
                        Log.w(TAG, "KakaoTalk ChatGPT tab CLICKED — blocking")
                        blockKakaoChatGpt()
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
                    // 하단 "지금 탭" (숏폼+오픈채팅) 클릭 감지
                    if (clickedText == "숏폼" || contentDesc.contains("지금 탭")) {
                        val now = System.currentTimeMillis()
                        if (now - kakaoShortformTabClickBlockedAt > 300L) {
                            kakaoShortformTabClickBlockedAt = now
                            Log.w(TAG, "KakaoTalk shortform tab CLICKED — blocking")
                            blockKakaoShortformTab()
                        }
                    }
                }
            }
        }
    }

    private fun handleWindowStateChanged(packageName: String, event: AccessibilityEvent) {
        if (packageName in YOUTUBE_PACKAGES) {
            Log.i(TAG, "YouTube detected in foreground: $packageName")
            blockYouTubeApp(packageName)
        } else if (packageName in TIKTOK_PACKAGES) {
            Log.i(TAG, "TikTok detected in foreground: $packageName")
            blockTikTokApp(packageName)
        } else if (packageName in INSTAGRAM_PACKAGES) {
            Log.i(TAG, "Instagram detected in foreground: $packageName")
            blockInstagramApp(packageName)
        } else if (packageName in FACEBOOK_PACKAGES) {
            Log.i(TAG, "Facebook detected in foreground: $packageName")
            blockFacebookApp(packageName)
        } else if (packageName in X_PACKAGES) {
            Log.i(TAG, "X (Twitter) detected in foreground: $packageName")
            blockXApp(packageName)
        } else if (packageName in SNAPCHAT_PACKAGES) {
            Log.i(TAG, "Snapchat detected in foreground: $packageName")
            blockSnapchatApp(packageName)
        } else if (packageName in TWITCH_PACKAGES) {
            Log.i(TAG, "Twitch detected in foreground: $packageName")
            blockTwitchApp(packageName)
        } else if (packageName in AFREECA_PACKAGES) {
            Log.i(TAG, "AfreecaTV detected in foreground: $packageName")
            blockAfreecaApp(packageName)
        } else if (packageName in CHZZK_PACKAGES) {
            Log.i(TAG, "Chzzk detected in foreground: $packageName")
            blockChzzkApp(packageName)
        } else if (packageName in DISCORD_PACKAGES) {
            Log.i(TAG, "Discord detected in foreground: $packageName")
            blockDiscordApp(packageName)
        } else if (packageName in REDDIT_PACKAGES) {
            Log.i(TAG, "Reddit detected in foreground: $packageName")
            blockRedditApp(packageName)
        } else if (packageName in PINTEREST_PACKAGES) {
            Log.i(TAG, "Pinterest detected in foreground: $packageName")
            blockPinterestApp(packageName)
        } else if (packageName in BEREAL_PACKAGES) {
            Log.i(TAG, "BeReal detected in foreground: $packageName")
            blockBeRealApp(packageName)
        } else if (isBrowser(packageName)) {
            Log.i(TAG, "Browser detected in foreground: $packageName")
            blockBrowserApp(packageName)
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
            // 소식 차단 후 강제종료가 끝난 뒤 처음 들어온 카카오톡 화면 = 재실행 시점
            if (kakaoNewsRestorePending && kakaoRelaunchedAt == 0L && !isForceStopSettingsOpen &&
                System.currentTimeMillis() - kakaoLastKilledAt > 2500L) {
                kakaoRelaunchedAt = System.currentTimeMillis()
                Log.i(TAG, "KakaoTalk relaunched after news block — restored 소식 feed will be redirected")
            }
            // 소식탭/숏폼탭 자동전환 감지: 강제종료 후 재시작 포함 모든 화면 전환 시 체크
            handler.postDelayed({ checkKakaoNewsTabActive() }, 400)
            handler.postDelayed({ checkKakaoShortformTabActive() }, 450)
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

    /**
     * 스토어 진입 시 PIN 요구. PIN 인증 후에는 다른 앱으로 이동할 때까지 허용.
     */
    private fun handleStoreGate(packageName: String) {
        if (packageName in STORE_PACKAGES) {
            if (unlockedStorePackage == packageName) return
            unlockedStorePackage = null
            promptStorePin(packageName)
        } else if (unlockedStorePackage != null && !isStoreTransientPackage(packageName)) {
            Log.i(TAG, "Left store ($unlockedStorePackage → $packageName) — relocking")
            unlockedStorePackage = null
        }
    }

    /**
     * 설정 앱 진입 시 PIN 요구. PIN 인증 후에는 다른 앱으로 이동할 때까지 허용.
     */
    private fun handleSettingsGate(packageName: String) {
        if (packageName in SETTINGS_PACKAGES) {
            if (isSettingsUnlocked) return
            // 강제종료를 위해 직접 열었을 때는 간섭하지 않음
            if (isForceStopSettingsOpen) return
            promptSettingsPin(packageName)
        } else if (isSettingsUnlocked && !isSettingsTransientPackage(packageName)) {
            Log.i(TAG, "Left settings → relocking")
            isSettingsUnlocked = false
        }
    }

    private fun isSettingsTransientPackage(packageName: String): Boolean {
        if (packageName == this.packageName) return true
        if (packageName in SETTINGS_PACKAGES) return true
        if (packageName == "android" || packageName == "com.android.systemui") return true
        // 키보드(IME)
        return try {
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.enabledInputMethodList?.any { it.packageName == packageName } == true
        } catch (e: Exception) {
            false
        }
    }

    private fun promptSettingsPin(packageName: String) {
        if (!PinManager(this).hasPin()) return
        val now = System.currentTimeMillis()

        if (now - settingsPinPromptedAt < 3000L) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }
        settingsPinPromptedAt = now

        Log.i(TAG, "Settings opened ($packageName) — requiring PIN")
        prefsManager.addBlockLog("설정", "설정 실행 → 비밀번호 요청: $packageName")

        performGlobalAction(GLOBAL_ACTION_HOME)
        handler.postDelayed({
            try {
                val intent = Intent(this, PinVerifyActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(PinVerifyActivity.EXTRA_PURPOSE, PinVerifyActivity.PURPOSE_SETTINGS_UNLOCK)
                    putExtra(PinVerifyActivity.EXTRA_TARGET_PACKAGE, packageName)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch PIN screen for settings: ${e.message}")
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }, 300)
    }

    private fun isStoreTransientPackage(packageName: String): Boolean {
        if (packageName == this.packageName || packageName in STORE_TRANSIENT_PACKAGES) return true
        // 키보드(IME) 윈도우도 스토어 사용 중 뜨므로 제외
        return try {
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.enabledInputMethodList?.any { it.packageName == packageName } == true
        } catch (e: Exception) {
            false
        }
    }

    private fun promptStorePin(packageName: String) {
        if (!PinManager(this).hasPin()) {
            Log.w(TAG, "Store opened ($packageName) but no PIN set — allowing")
            return
        }
        val now = System.currentTimeMillis()

        // 쿨다운 중이라도 스토어가 보이지 않도록 무조건 홈으로 이동
        if (now - storePinPromptedAt < 3000L) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }
        storePinPromptedAt = now

        Log.i(TAG, "Store opened ($packageName) — requiring PIN")
        prefsManager.addBlockLog("스토어", "스토어 실행 → 비밀번호 요청: $packageName")

        // 먼저 홈으로 보낸 뒤 PIN 화면을 띄움 (스토어가 잠깐이라도 보이지 않게)
        performGlobalAction(GLOBAL_ACTION_HOME)
        handler.postDelayed({
            try {
                val intent = Intent(this, PinVerifyActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(PinVerifyActivity.EXTRA_PURPOSE, PinVerifyActivity.PURPOSE_STORE_UNLOCK)
                    putExtra(PinVerifyActivity.EXTRA_TARGET_PACKAGE, packageName)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch PIN screen for store: ${e.message}")
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }, 300)
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
                    if (screenText.contains("SafeScreen", ignoreCase = true) ||
                        screenText.contains("safescreen", ignoreCase = true)) {
                        Log.i(TAG, "SafeScreen settings page blocked")
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
        if (node == null || depth > 8) return ""
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

    /**
     * TikTok 앱 차단: 차단 화면 표시 + 강제종료 (영상 재생 중단).
     */
    private fun blockTikTokApp(packageName: String) {
        val now = System.currentTimeMillis()
        if (packageName == lastBlockedPackage && now - lastBlockTime < BLOCK_COOLDOWN_MS) return
        if (isForceStopSettingsOpen) return

        lastBlockedPackage = packageName
        lastBlockTime = now
        prefsManager.incrementBlockCount()
        prefsManager.addBlockLog("앱 실행", "TikTok 앱이 실행됨: $packageName")

        handler.post {
            try {
                val intent = Intent(this, BlockedActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(BlockedActivity.EXTRA_BLOCKED_SOURCE, BlockedActivity.SOURCE_TIKTOK)
                }
                startActivity(intent)
                Log.i(TAG, "Launched BlockedActivity for TikTok app")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch BlockedActivity for TikTok: ${e.message}")
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            handler.postDelayed({
                Log.i(TAG, "Force stopping TikTok app: $packageName")
                forceStopViaSettings(packageName)
            }, 1500)
        }
    }

    /**
     * Instagram 앱 차단: 차단 화면 표시 + 강제종료.
     */
    private fun blockInstagramApp(packageName: String) {
        val now = System.currentTimeMillis()
        if (packageName == lastBlockedPackage && now - lastBlockTime < BLOCK_COOLDOWN_MS) return
        if (isForceStopSettingsOpen) return

        lastBlockedPackage = packageName
        lastBlockTime = now
        prefsManager.incrementBlockCount()
        prefsManager.addBlockLog("앱 실행", "Instagram 앱이 실행됨: $packageName")

        handler.post {
            try {
                val intent = Intent(this, BlockedActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(BlockedActivity.EXTRA_BLOCKED_SOURCE, BlockedActivity.SOURCE_INSTAGRAM)
                }
                startActivity(intent)
                Log.i(TAG, "Launched BlockedActivity for Instagram app")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch BlockedActivity for Instagram: ${e.message}")
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            handler.postDelayed({
                Log.i(TAG, "Force stopping Instagram app: $packageName")
                forceStopViaSettings(packageName)
            }, 1500)
        }
    }

    /**
     * Facebook 앱 차단: 차단 화면 표시 + 강제종료.
     */
    private fun blockFacebookApp(packageName: String) {
        val now = System.currentTimeMillis()
        if (packageName == lastBlockedPackage && now - lastBlockTime < BLOCK_COOLDOWN_MS) return
        if (isForceStopSettingsOpen) return

        lastBlockedPackage = packageName
        lastBlockTime = now
        prefsManager.incrementBlockCount()
        prefsManager.addBlockLog("앱 실행", "Facebook 앱이 실행됨: $packageName")

        handler.post {
            try {
                val intent = Intent(this, BlockedActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(BlockedActivity.EXTRA_BLOCKED_SOURCE, BlockedActivity.SOURCE_FACEBOOK)
                }
                startActivity(intent)
                Log.i(TAG, "Launched BlockedActivity for Facebook app")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch BlockedActivity for Facebook: ${e.message}")
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            handler.postDelayed({
                Log.i(TAG, "Force stopping Facebook app: $packageName")
                forceStopViaSettings(packageName)
            }, 1500)
        }
    }

    /**
     * X (Twitter) 앱 차단: 차단 화면 표시 + 강제종료.
     */
    private fun blockXApp(packageName: String) {
        val now = System.currentTimeMillis()
        if (packageName == lastBlockedPackage && now - lastBlockTime < BLOCK_COOLDOWN_MS) return
        if (isForceStopSettingsOpen) return

        lastBlockedPackage = packageName
        lastBlockTime = now
        prefsManager.incrementBlockCount()
        prefsManager.addBlockLog("앱 실행", "X(Twitter) 앱이 실행됨: $packageName")

        handler.post {
            try {
                val intent = Intent(this, BlockedActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(BlockedActivity.EXTRA_BLOCKED_SOURCE, BlockedActivity.SOURCE_X)
                }
                startActivity(intent)
                Log.i(TAG, "Launched BlockedActivity for X app")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch BlockedActivity for X: ${e.message}")
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            handler.postDelayed({
                Log.i(TAG, "Force stopping X app: $packageName")
                forceStopViaSettings(packageName)
            }, 1500)
        }
    }

    private fun blockSnapchatApp(packageName: String) {
        val now = System.currentTimeMillis()
        if (packageName == lastBlockedPackage && now - lastBlockTime < BLOCK_COOLDOWN_MS) return
        if (isForceStopSettingsOpen) return
        lastBlockedPackage = packageName; lastBlockTime = now
        prefsManager.incrementBlockCount()
        prefsManager.addBlockLog("앱 실행", "Snapchat 앱이 실행됨: $packageName")
        handler.post {
            try {
                startActivity(Intent(this, BlockedActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(BlockedActivity.EXTRA_BLOCKED_SOURCE, BlockedActivity.SOURCE_SNAPCHAT)
                })
            } catch (e: Exception) { performGlobalAction(GLOBAL_ACTION_HOME) }
            handler.postDelayed({ forceStopViaSettings(packageName) }, 1500)
        }
    }

    private fun blockTwitchApp(packageName: String) {
        val now = System.currentTimeMillis()
        if (packageName == lastBlockedPackage && now - lastBlockTime < BLOCK_COOLDOWN_MS) return
        if (isForceStopSettingsOpen) return
        lastBlockedPackage = packageName; lastBlockTime = now
        prefsManager.incrementBlockCount()
        prefsManager.addBlockLog("앱 실행", "Twitch 앱이 실행됨: $packageName")
        handler.post {
            try {
                startActivity(Intent(this, BlockedActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(BlockedActivity.EXTRA_BLOCKED_SOURCE, BlockedActivity.SOURCE_TWITCH)
                })
            } catch (e: Exception) { performGlobalAction(GLOBAL_ACTION_HOME) }
            handler.postDelayed({ forceStopViaSettings(packageName) }, 1500)
        }
    }

    private fun blockAfreecaApp(packageName: String) {
        val now = System.currentTimeMillis()
        if (packageName == lastBlockedPackage && now - lastBlockTime < BLOCK_COOLDOWN_MS) return
        if (isForceStopSettingsOpen) return
        lastBlockedPackage = packageName; lastBlockTime = now
        prefsManager.incrementBlockCount()
        prefsManager.addBlockLog("앱 실행", "AfreecaTV 앱이 실행됨: $packageName")
        handler.post {
            try {
                startActivity(Intent(this, BlockedActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(BlockedActivity.EXTRA_BLOCKED_SOURCE, BlockedActivity.SOURCE_AFREECA)
                })
            } catch (e: Exception) { performGlobalAction(GLOBAL_ACTION_HOME) }
            handler.postDelayed({ forceStopViaSettings(packageName) }, 1500)
        }
    }

    private fun blockChzzkApp(packageName: String) {
        val now = System.currentTimeMillis()
        if (packageName == lastBlockedPackage && now - lastBlockTime < BLOCK_COOLDOWN_MS) return
        if (isForceStopSettingsOpen) return
        lastBlockedPackage = packageName; lastBlockTime = now
        prefsManager.incrementBlockCount()
        prefsManager.addBlockLog("앱 실행", "치지직 앱이 실행됨: $packageName")
        handler.post {
            try {
                startActivity(Intent(this, BlockedActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(BlockedActivity.EXTRA_BLOCKED_SOURCE, BlockedActivity.SOURCE_CHZZK)
                })
            } catch (e: Exception) { performGlobalAction(GLOBAL_ACTION_HOME) }
            handler.postDelayed({ forceStopViaSettings(packageName) }, 1500)
        }
    }

    private fun blockDiscordApp(packageName: String) {
        val now = System.currentTimeMillis()
        if (packageName == lastBlockedPackage && now - lastBlockTime < BLOCK_COOLDOWN_MS) return
        if (isForceStopSettingsOpen) return
        lastBlockedPackage = packageName; lastBlockTime = now
        prefsManager.incrementBlockCount()
        prefsManager.addBlockLog("앱 실행", "Discord 앱이 실행됨: $packageName")
        handler.post {
            try {
                startActivity(Intent(this, BlockedActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(BlockedActivity.EXTRA_BLOCKED_SOURCE, BlockedActivity.SOURCE_DISCORD)
                })
            } catch (e: Exception) { performGlobalAction(GLOBAL_ACTION_HOME) }
            handler.postDelayed({ forceStopViaSettings(packageName) }, 1500)
        }
    }

    private fun blockRedditApp(packageName: String) {
        val now = System.currentTimeMillis()
        if (packageName == lastBlockedPackage && now - lastBlockTime < BLOCK_COOLDOWN_MS) return
        if (isForceStopSettingsOpen) return
        lastBlockedPackage = packageName; lastBlockTime = now
        prefsManager.incrementBlockCount()
        prefsManager.addBlockLog("앱 실행", "Reddit 앱이 실행됨: $packageName")
        handler.post {
            try {
                startActivity(Intent(this, BlockedActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(BlockedActivity.EXTRA_BLOCKED_SOURCE, BlockedActivity.SOURCE_REDDIT)
                })
            } catch (e: Exception) { performGlobalAction(GLOBAL_ACTION_HOME) }
            handler.postDelayed({ forceStopViaSettings(packageName) }, 1500)
        }
    }

    private fun blockPinterestApp(packageName: String) {
        val now = System.currentTimeMillis()
        if (packageName == lastBlockedPackage && now - lastBlockTime < BLOCK_COOLDOWN_MS) return
        if (isForceStopSettingsOpen) return
        lastBlockedPackage = packageName; lastBlockTime = now
        prefsManager.incrementBlockCount()
        prefsManager.addBlockLog("앱 실행", "Pinterest 앱이 실행됨: $packageName")
        handler.post {
            try {
                startActivity(Intent(this, BlockedActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(BlockedActivity.EXTRA_BLOCKED_SOURCE, BlockedActivity.SOURCE_PINTEREST)
                })
            } catch (e: Exception) { performGlobalAction(GLOBAL_ACTION_HOME) }
            handler.postDelayed({ forceStopViaSettings(packageName) }, 1500)
        }
    }

    private fun blockBeRealApp(packageName: String) {
        val now = System.currentTimeMillis()
        if (packageName == lastBlockedPackage && now - lastBlockTime < BLOCK_COOLDOWN_MS) return
        if (isForceStopSettingsOpen) return
        lastBlockedPackage = packageName; lastBlockTime = now
        prefsManager.incrementBlockCount()
        prefsManager.addBlockLog("앱 실행", "BeReal 앱이 실행됨: $packageName")
        handler.post {
            try {
                startActivity(Intent(this, BlockedActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(BlockedActivity.EXTRA_BLOCKED_SOURCE, BlockedActivity.SOURCE_BEREAL)
                })
            } catch (e: Exception) { performGlobalAction(GLOBAL_ACTION_HOME) }
            handler.postDelayed({ forceStopViaSettings(packageName) }, 1500)
        }
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

    private fun isBrowser(packageName: String): Boolean {
        if (packageName in BROWSER_PACKAGES) return true
        // 새 브라우저 설치 대비: 10분마다 목록 갱신
        if (System.currentTimeMillis() - installedBrowsersRefreshedAt > 10 * 60 * 1000L) {
            refreshInstalledBrowsers()
        }
        return packageName in installedBrowsers
    }

    /** 임의의 http/https 링크를 열 수 있는 앱 = 브라우저로 간주 (특정 도메인 전용 앱은 매칭되지 않음) */
    private fun refreshInstalledBrowsers() {
        installedBrowsersRefreshedAt = System.currentTimeMillis()
        try {
            val result = mutableSetOf<String>()
            for (scheme in listOf("http", "https")) {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("$scheme://www.example.com"))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_ALL)
                    .mapTo(result) { it.activityInfo.packageName }
            }
            result.remove(packageName) // 자기 자신
            result.remove(KAKAO_PACKAGE)
            result.removeAll(YOUTUBE_PACKAGES)
            result.removeAll(TIKTOK_PACKAGES)
            installedBrowsers = result
            Log.i(TAG, "Installed browsers: $result")
            FileLogger.log(this, TAG, "브라우저 목록: $result")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query installed browsers: ${e.message}")
        }
    }

    /** 차단 모드에서 브라우저 실행 차단: 차단 화면 표시 + 강제종료 */
    private fun blockBrowserApp(packageName: String, verified: Boolean = false) {
        val now = System.currentTimeMillis()
        // 강제종료(설정 화면 조작)가 끝날 때까지 중복 실행 방지
        if (packageName == lastBlockedPackage && now - lastBlockTime < 5000L) return
        if (isForceStopSettingsOpen) return

        // Google 앱 등은 강제종료 직후 백그라운드 서비스/위젯이 재시작되며 이벤트를 보냄
        // → 실제 포그라운드(활성 윈도우)일 때만 차단해야 무한 차단 루프가 생기지 않음
        if (!verified) {
            if (browserCheckPending) return
            browserCheckPending = true
            handler.postDelayed({
                browserCheckPending = false
                blockBrowserApp(packageName, verified = true)
            }, 300)
            return
        }
        val activeRoot = rootInActiveWindow
        val activePackage = activeRoot?.packageName?.toString()
        try { activeRoot?.recycle() } catch (ignored: Exception) {}
        if (activePackage != packageName) {
            Log.d(TAG, "blockBrowserApp skipped — $packageName not in foreground (active=$activePackage)")
            return
        }

        Log.w(TAG, "blockBrowserApp TRIGGERED — $packageName")
        lastBlockedPackage = packageName
        lastBlockTime = now
        prefsManager.incrementBlockCount()
        prefsManager.addBlockLog("브라우저", "브라우저 실행 차단: $packageName")

        handler.post {
            try {
                val intent = Intent(this, BlockedActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(BlockedActivity.EXTRA_BLOCKED_SOURCE, BlockedActivity.SOURCE_BROWSER_APP)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch BlockedActivity for browser: ${e.message}")
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            // 브라우저 프로세스 강제종료 (백그라운드 영상/음악 재생 중단)
            handler.postDelayed({ forceStopViaSettings(packageName) }, 1500)
        }
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
        // 프로필 피드 화면이 (복원 등으로) 떠 있는 경우도 차단
        if (currentKakaoClass.contains(KAKAO_PROFILE_FEED_CLASS)) {
            blockKakaoProfileFeed()
            return
        }
        // 1순위: 탭 선택 상태로 판별 — 서브탭이 활성 윈도우가 아닐 수 있으므로 카카오 윈도우 전체 탐색
        val kakaoRoots = try {
            windows?.mapNotNull { it.root }?.filter { it.packageName?.toString() == KAKAO_PACKAGE }
        } catch (e: Exception) { null }
        if (!kakaoRoots.isNullOrEmpty()) {
            val newsSelected = kakaoRoots.any { isNewsTabSelected(it) }
            // "소식보기" 등으로 전환되면 클릭 이벤트도, 칩 선택 상태(checked/selected)도 오지 않음
            // → 소식 피드에만 있는 뷰 ID(좋아요/댓글/북마크 버튼)가 보이는지로 판별
            val feedVisible = !newsSelected && kakaoRoots.any { isNewsFeedVisible(it) }
            kakaoRoots.forEach { try { it.recycle() } catch (ignored: Exception) {} }
            if (feedVisible) {
                val now = System.currentTimeMillis()
                // 차단 후 재실행 직후(5초 이내)에 보이는 소식 피드 = 카카오톡이 복원한 화면 → 친구탭으로 이동
                if (kakaoNewsRestorePending && kakaoRelaunchedAt != 0L && now - kakaoRelaunchedAt < 5000L) {
                    kakaoNewsRestorePending = false
                    kakaoRelaunchedAt = 0L
                    // 친구탭으로 넘어가기 전 직후 재감지로 차단되지 않도록 3초 차단 가드 적용
                    kakaoNewsTabClickBlockedAt = now
                    Log.i(TAG, "Restored 소식 feed after relaunch — redirecting to 친구 tab")
                    FileLogger.log(this, TAG, "재실행 시 복원된 소식 피드 → 친구탭으로 이동")
                    redirectKakaoNewsTab()
                    return
                }
                // "소식보기"로 들어온 소식 피드 → 소식탭 클릭과 동일하게 차단 + 강제종료
                // 강제종료가 끝나기 전(~3초) 반복 감지로 차단이 여러 번 실행되지 않게 함
                if (now - kakaoNewsTabClickBlockedAt < 3000L) return
                kakaoNewsTabClickBlockedAt = now
                Log.i(TAG, "KakaoTalk 소식 feed detected (소식보기) — blocking")
                FileLogger.log(this, TAG, "소식 피드 감지 (소식보기) → 차단")
                blockKakaoNewsTab()
                return
            }
            if (newsSelected) {
                Log.i(TAG, "KakaoTalk 소식 tab detected (selected=true)")
                FileLogger.log(this, TAG, "소식 화면 감지 (selected=true) → 친구탭으로 이동")
                redirectKakaoNewsTab()
                return
            }
        }
        val root = rootInActiveWindow ?: return
        try {
            if (root.packageName?.toString() != KAKAO_PACKAGE) return
            // 2순위: 소식탭 고유 텍스트로 판별
            val allText = extractAllText(root)
            Log.d(TAG, "checkKakaoNews allText(500)=${allText.take(500)}")
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
     * 숏폼탭이 현재 활성화되어 있는지 확인.
     * 1순위: "숏폼" 탭 노드가 isChecked/isSelected=true
     * 2순위: 숏폼탭 고유 텍스트 또는 필터 칩 2개 이상 감지
     */
    private fun checkKakaoShortformTabActive() {
        if (!prefsManager.isBlocking) return
        // 채팅방 안에서는 하단 탭 상태가 stale하게 남아 오탐 발생 → 스킵
        if (currentKakaoClass.contains("ChatRoom") || currentKakaoClass.contains("Chat")) return
        val root = rootInActiveWindow ?: return
        try {
            if (root.packageName?.toString() != KAKAO_PACKAGE) return
            // 탭 선택 상태로만 판별 (칩 텍스트 감지는 오탐 발생으로 제거)
            if (isShortformTabSelected(root)) {
                Log.i(TAG, "KakaoTalk 지금 tab: isSelected=true — redirecting to 채팅 tab")
                redirectKakaoShortformTab()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking KakaoTalk shortform tab: ${e.message}")
        } finally {
            try { root.recycle() } catch (ignored: Exception) {}
        }
    }

    /**
     * 노드 트리를 순회하며 "숏폼" 탭이 활성화됐는지 확인.
     */
    private fun isShortformTabSelected(node: AccessibilityNodeInfo?, depth: Int = 0): Boolean {
        if (node == null || depth > 8) return false
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        // 실제 탭 이름은 "지금 탭" (description: "지금 탭, 숏폼과 오픈채팅을 포함합니다.")
        val isShortformTab = text == "지금" || desc.contains("지금 탭")
        if (isShortformTab && (node.isChecked || node.isSelected)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = isShortformTabSelected(child, depth + 1)
            try { child.recycle() } catch (ignored: Exception) {}
            if (found) return true
        }
        return false
    }

    /** 자동 감지 시(앱 재실행 등): 강제종료 없이 채팅탭으로 이동 */
    private fun redirectKakaoShortformTab() {
        val now = System.currentTimeMillis()
        if (KAKAO_PACKAGE == lastBlockedPackage && now - lastBlockTime < 800L) return
        Log.w(TAG, "redirectKakaoShortformTab: auto-detected — navigating to 채팅 tab")
        lastBlockedPackage = KAKAO_PACKAGE
        lastBlockTime = now
        prefsManager.addBlockLog("카카오톡 지금", "지금탭 자동전환 감지 → 채팅탭으로 이동")
        handler.post {
            val root = rootInActiveWindow ?: return@post
            try {
                if (root.packageName?.toString() != KAKAO_PACKAGE) { performGlobalAction(GLOBAL_ACTION_HOME); return@post }
                // 하단 네비게이션의 채팅 탭은 contentDescription으로 식별 ("채팅 탭" 포함)
                // findAccessibilityNodeInfosByText는 text만 검색하므로 트리 직접 순회
                val chatNavTab = findBottomNavTab(root, "채팅 탭")
                if (chatNavTab != null) {
                    chatNavTab.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, "Redirected to 채팅 tab (bottom nav)")
                } else {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            } catch (e: Exception) {
                performGlobalAction(GLOBAL_ACTION_HOME)
            } finally {
                try { root.recycle() } catch (ignored: Exception) {}
            }
        }
    }

    /** 하단 네비게이션에서 contentDescription이 keyword를 포함하는 탭 버튼을 찾는다. */
    private fun findBottomNavTab(node: AccessibilityNodeInfo?, keyword: String, depth: Int = 0): AccessibilityNodeInfo? {
        if (node == null || depth > 8) return null
        val desc = node.contentDescription?.toString() ?: ""
        if (desc.contains(keyword) && node.isClickable && node.isEnabled) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findBottomNavTab(child, keyword, depth + 1)
            if (found != null) return found
            try { child.recycle() } catch (ignored: Exception) {}
        }
        return null
    }

    /**
     * 숏폼탭 차단: BlockedActivity 표시 + 강제종료.
     */
    private fun blockKakaoShortformTab() {
        val now = System.currentTimeMillis()
        if (KAKAO_PACKAGE == lastBlockedPackage && now - lastBlockTime < 300L) {
            Log.d(TAG, "blockKakaoShortformTab: skipped (cooldown, ${now - lastBlockTime}ms)")
            return
        }
        Log.w(TAG, "blockKakaoShortformTab TRIGGERED — currentClass=$currentKakaoClass")
        lastBlockedPackage = KAKAO_PACKAGE
        lastBlockTime = now
        prefsManager.incrementBlockCount()
        prefsManager.addBlockLog("카카오톡 숏폼", "숏폼탭 접근 차단")

        handler.post {
            try {
                val intent = Intent(this, BlockedActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(BlockedActivity.EXTRA_BLOCKED_SOURCE, BlockedActivity.SOURCE_KAKAO_SHORTFORM)
                }
                startActivity(intent)
                Log.i(TAG, "Launched BlockedActivity for KakaoTalk shortform tab")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch BlockedActivity for shortform tab: ${e.message}")
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
                Log.i(TAG, "Kakao state reset after shortform tab block. Force stopping.")
                forceStopViaSettings(KAKAO_PACKAGE)
            }, 1500)
        }
    }

    /**
     * 친구탭(하단) 안의 소식 피드가 화면에 보이는지 확인.
     * 친구/소식 칩(chip_feed)이 있는 메인 화면에서 피드 전용 버튼 ID가 보이면 소식 화면.
     * (ProfileFeedActivity 등 칩이 없는 화면은 제외)
     */
    private fun isNewsFeedVisible(root: AccessibilityNodeInfo): Boolean {
        val chips = root.findAccessibilityNodeInfosByViewId("$KAKAO_PACKAGE:id/chip_feed")
        val hasChip = chips?.any { it.isVisibleToUser } == true
        recycleNodes(chips)
        if (!hasChip) return false
        for (id in KAKAO_NEWS_FEED_VIEW_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId("$KAKAO_PACKAGE:id/$id")
            val visible = nodes?.any { it.isVisibleToUser } == true
            recycleNodes(nodes)
            if (visible) return true
        }
        return false
    }

    /** 디버그 로그용: 클릭된 source 노드 하위의 text/description을 이어붙인 문자열. */
    private fun sourceText(event: AccessibilityEvent): String {
        val source = event.source ?: return ""
        return try {
            extractAllText(source)
        } catch (e: Exception) {
            ""
        } finally {
            try { source.recycle() } catch (ignored: Exception) {}
        }
    }

    /** 클릭 이벤트의 source 노드(및 하위 노드)의 text/description에 keyword가 있는지 확인. */
    private fun sourceContainsText(event: AccessibilityEvent, keyword: String): Boolean {
        val source = event.source ?: return false
        return try {
            nodeContainsText(source, keyword)
        } catch (e: Exception) {
            false
        } finally {
            try { source.recycle() } catch (ignored: Exception) {}
        }
    }

    private fun nodeContainsText(node: AccessibilityNodeInfo, keyword: String, depth: Int = 0): Boolean {
        if (depth > 4) return false
        if (node.text?.toString()?.contains(keyword) == true) return true
        if (node.contentDescription?.toString()?.contains(keyword) == true) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = nodeContainsText(child, keyword, depth + 1)
            try { child.recycle() } catch (ignored: Exception) {}
            if (found) return true
        }
        return false
    }

    /**
     * 노드 트리를 순회하며 "소식" 탭이 활성화됐는지 확인.
     * 탭이 CheckBox 위젯이므로 isChecked로 확인 (isSelected 아님).
     * contentDescription이 "소식, 탭" 형태이므로 contains로 매칭.
     */
    private fun isNewsTabSelected(node: AccessibilityNodeInfo?, depth: Int = 0): Boolean {
        // 친구/소식 서브탭은 트리 깊숙이 있을 수 있으므로 깊이 제한을 넉넉히 둔다
        if (node == null || depth > 30) return false
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        // 서브탭만 매칭 (text="소식", desc="소식, 탭") — 채팅 메시지 속 "소식" 문구와 "소식보기" 버튼 제외
        val isNewsTab = text == "소식" || desc == "소식, 탭" || desc == "소식 탭"
        if (isNewsTab) {
            Log.d(TAG, "isNewsTabSelected: found 소식 node text='$text' desc='$desc' isChecked=${node.isChecked} isSelected=${node.isSelected} depth=$depth cls=${node.className}")
        }
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
                    putExtra(BlockedActivity.EXTRA_BLOCKED_SOURCE, BlockedActivity.SOURCE_KAKAO_NEWS)
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
                kakaoNewsRestorePending = true
                kakaoRelaunchedAt = 0L
                Log.i(TAG, "Kakao state reset after news tab block. Force stopping.")
                forceStopViaSettings(KAKAO_PACKAGE)
            }, 1500)
        }
    }

    /** 프로필 피드(소식보기, 별도 Activity) 감지 시: 소식탭과 동일하게 차단 화면 + 강제종료 */
    private fun blockKakaoProfileFeed() {
        val now = System.currentTimeMillis()
        if (now - kakaoProfileFeedBlockedAt < 800L) return
        kakaoProfileFeedBlockedAt = now
        Log.w(TAG, "KakaoTalk ProfileFeedActivity detected — blocking")
        FileLogger.log(this, TAG, "프로필 피드 감지 → 차단")
        // 강제종료 전까지 stale 클래스로 차단이 반복 실행되지 않도록 초기화
        currentKakaoClass = ""
        blockKakaoNewsTab()
    }

    /**
     * ChatGPT탭 차단: 소식탭과 동일하게 BlockedActivity 표시 + 강제종료.
     */
    private fun blockKakaoChatGpt() {
        if (!prefsManager.isBlocking) return
        val now = System.currentTimeMillis()
        if (KAKAO_PACKAGE == lastBlockedPackage && now - lastBlockTime < 300L) {
            Log.d(TAG, "blockKakaoChatGpt: skipped (cooldown, ${now - lastBlockTime}ms)")
            return
        }
        Log.w(TAG, "blockKakaoChatGpt TRIGGERED — currentClass=$currentKakaoClass")
        FileLogger.log(this, TAG, "ChatGPT 화면 감지 → 차단")
        lastBlockedPackage = KAKAO_PACKAGE
        lastBlockTime = now
        prefsManager.incrementBlockCount()
        prefsManager.addBlockLog("카카오톡 ChatGPT", "ChatGPT탭 접근 차단")

        handler.post {
            try {
                val intent = Intent(this, BlockedActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(BlockedActivity.EXTRA_BLOCKED_SOURCE, BlockedActivity.SOURCE_KAKAO_AI)
                }
                startActivity(intent)
                Log.i(TAG, "Launched BlockedActivity for KakaoTalk ChatGPT tab")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch BlockedActivity for ChatGPT tab: ${e.message}")
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
                Log.i(TAG, "Kakao state reset after ChatGPT tab block. Force stopping.")
                forceStopViaSettings(KAKAO_PACKAGE)
            }, 1500)
        }
    }

    /**
     * STATE_CHANGED 없이 ChatGPT 화면이 다시 보이는 경우 대비: 활성 윈도우 내용으로 판별.
     * 상단 제목 "ChatGPT for Kakao" + "접어두기" 버튼이 함께 있으면 ChatGPT 화면
     * (채팅 목록의 "ChatGPT for Kakao" 채팅방 이름만으로는 매칭되지 않음).
     */
    private fun isKakaoChatGptContentVisible(): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            if (root.packageName?.toString() != KAKAO_PACKAGE) return false
            val titles = root.findAccessibilityNodeInfosByText("ChatGPT for Kakao")
            val hasTitle = titles?.any { it.text?.toString() == "ChatGPT for Kakao" } == true
            recycleNodes(titles)
            if (!hasTitle) return false
            val foldButtons = root.findAccessibilityNodeInfosByText("접어두기")
            val hasFold = !foldButtons.isNullOrEmpty()
            recycleNodes(foldButtons)
            hasFold
        } catch (e: Exception) {
            false
        } finally {
            try { root.recycle() } catch (ignored: Exception) {}
        }
    }

    /** 채팅방 화면에 제목이 정확히 "ChatGPT for Kakao"인 노드가 있으면 ChatGPT 채팅방 → 차단 */
    private fun checkKakaoChatGptRoom() {
        if (!prefsManager.isBlocking) return
        val root = rootInActiveWindow ?: return
        try {
            if (root.packageName?.toString() != KAKAO_PACKAGE) return
            val nodes = root.findAccessibilityNodeInfosByText("ChatGPT for Kakao")
            // 채팅 목록의 채팅방 이름(id/name)은 제외 — 다른 방을 여는 중 목록이 아직 활성일 때 오탐 방지
            val isChatGptRoom = nodes?.any {
                it.text?.toString() == "ChatGPT for Kakao" &&
                    it.viewIdResourceName != "$KAKAO_PACKAGE:id/name"
            } == true
            recycleNodes(nodes)
            if (isChatGptRoom) {
                Log.w(TAG, "KakaoTalk ChatGPT for Kakao chat room detected — blocking")
                blockKakaoChatGpt()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking ChatGPT chat room: ${e.message}")
        } finally {
            try { root.recycle() } catch (ignored: Exception) {}
        }
    }

    private fun checkKakaoChatGptContent() {
        if (!prefsManager.isBlocking) return
        if (isKakaoChatGptContentVisible()) blockKakaoChatGpt()
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
                // 친구 서브탭 칩(chip_friend)을 우선 클릭, 없으면 "친구" 텍스트 노드
                val nodes = root.findAccessibilityNodeInfosByViewId("$KAKAO_PACKAGE:id/chip_friend")
                    ?.takeIf { it.isNotEmpty() }
                    ?: root.findAccessibilityNodeInfosByText("친구")
                val friendTab = nodes?.firstOrNull { it.isClickable && it.isEnabled && it.text?.toString() == "친구" }
                    ?: nodes?.firstOrNull { it.isClickable && it.isEnabled }
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
                    val pkg = rootInActiveWindow?.packageName?.toString() ?: ""
                    // 모든 차단 대상 앱 폴링: 타이머 만료 시점에 이미 포그라운드에 있는 앱 즉시 차단
                    when {
                        pkg in YOUTUBE_PACKAGES -> blockYouTubeApp(pkg)
                        pkg in TIKTOK_PACKAGES -> blockTikTokApp(pkg)
                        pkg in INSTAGRAM_PACKAGES -> blockInstagramApp(pkg)
                        pkg in FACEBOOK_PACKAGES -> blockFacebookApp(pkg)
                        pkg in X_PACKAGES -> blockXApp(pkg)
                        pkg in SNAPCHAT_PACKAGES -> blockSnapchatApp(pkg)
                        pkg in TWITCH_PACKAGES -> blockTwitchApp(pkg)
                        pkg in AFREECA_PACKAGES -> blockAfreecaApp(pkg)
                        pkg in CHZZK_PACKAGES -> blockChzzkApp(pkg)
                        pkg in DISCORD_PACKAGES -> blockDiscordApp(pkg)
                        pkg in REDDIT_PACKAGES -> blockRedditApp(pkg)
                        pkg in PINTEREST_PACKAGES -> blockPinterestApp(pkg)
                        pkg in BEREAL_PACKAGES -> blockBeRealApp(pkg)
                        pkg.isNotEmpty() && isBrowser(pkg) -> blockBrowserApp(pkg)
                    }
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
                        // 숏폼탭 자동전환 감지 (watcher 보조 체크)
                        val nowShortform = System.currentTimeMillis()
                        if (nowShortform - kakaoShortformTabLastCheckedAt > 1000L) {
                            kakaoShortformTabLastCheckedAt = nowShortform
                            checkKakaoShortformTabActive()
                            checkKakaoChatGptContent()
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
            pkg in TIKTOK_PACKAGES -> {
                Log.i(TAG, "Force block: TikTok app in foreground → blocking")
                blockTikTokApp(pkg)
            }
            isBrowser(pkg) -> {
                Log.i(TAG, "Force block: browser in foreground → blocking")
                blockBrowserApp(pkg)
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
            val isTikTokUrl = TIKTOK_URL_KEYWORDS.any { allText.contains(it, ignoreCase = true) }

            Log.d(TAG, "KakaoTalk InAppBrowser check: hasWebView=true urlMatch=$isYouTubeUrl brandMatch=$isYouTubeBrand tiktok=$isTikTokUrl class=$currentKakaoClass")

            if (isYouTubeUrl || isYouTubeBrand) {
                Log.i(TAG, "YouTube detected in KakaoTalk InAppBrowserActivity (url=$isYouTubeUrl brand=$isYouTubeBrand) — blocking + force stopping")
                blockKakaoGameTab()
            } else if (isTikTokUrl) {
                Log.i(TAG, "TikTok detected in KakaoTalk InAppBrowserActivity — blocking + force stopping")
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

package com.safeguard.tubeguard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView

class BlockedActivity : Activity() {

    companion object {
        const val EXTRA_BLOCKED_SOURCE = "blocked_source"
        const val SOURCE_APP = "app"
        const val SOURCE_BROWSER = "browser"
        const val SOURCE_BROWSER_APP = "browser_app"
        const val SOURCE_KAKAO_GAME = "kakao_game"
        const val SOURCE_TIKTOK = "tiktok"
        const val SOURCE_INSTAGRAM = "instagram"
        const val SOURCE_FACEBOOK = "facebook"
        const val SOURCE_X = "x"
        const val SOURCE_SNAPCHAT = "snapchat"
        const val SOURCE_TWITCH = "twitch"
        const val SOURCE_AFREECA = "afreeca"
        const val SOURCE_CHZZK = "chzzk"
        const val SOURCE_DISCORD = "discord"
        const val SOURCE_REDDIT = "reddit"
        const val SOURCE_PINTEREST = "pinterest"
        const val SOURCE_BEREAL = "bereal"
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var source: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blocked)

        source = intent.getStringExtra(EXTRA_BLOCKED_SOURCE) ?: SOURCE_APP
        val tvMessage = findViewById<TextView>(R.id.tvBlockedMessage)

        tvMessage.text = when (source) {
            SOURCE_BROWSER -> getString(R.string.blocked_message_browser)
            SOURCE_BROWSER_APP -> getString(R.string.blocked_message_browser_app)
            SOURCE_KAKAO_GAME -> getString(R.string.blocked_message_kakao_game)
            SOURCE_TIKTOK -> getString(R.string.blocked_message_tiktok)
            SOURCE_INSTAGRAM -> getString(R.string.blocked_message_instagram)
            SOURCE_FACEBOOK -> getString(R.string.blocked_message_facebook)
            SOURCE_X -> getString(R.string.blocked_message_x)
            SOURCE_SNAPCHAT -> getString(R.string.blocked_message_snapchat)
            SOURCE_TWITCH -> getString(R.string.blocked_message_twitch)
            SOURCE_AFREECA -> getString(R.string.blocked_message_afreeca)
            SOURCE_CHZZK -> getString(R.string.blocked_message_chzzk)
            SOURCE_DISCORD -> getString(R.string.blocked_message_discord)
            SOURCE_REDDIT -> getString(R.string.blocked_message_reddit)
            SOURCE_PINTEREST -> getString(R.string.blocked_message_pinterest)
            SOURCE_BEREAL -> getString(R.string.blocked_message_bereal)
            else -> getString(R.string.blocked_message_app)
        }

        findViewById<Button>(R.id.btnGoHome).setOnClickListener {
            handler.removeCallbacksAndMessages(null)
            goHome()
        }

        handler.postDelayed({ goHome() }, 5000)
    }

    private fun goHome() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        finish()
    }

    override fun onBackPressed() {
        handler.removeCallbacksAndMessages(null)
        goHome()
    }
}

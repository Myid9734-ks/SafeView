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
        const val SOURCE_KAKAO_GAME = "kakao_game"
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var source: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blocked)

        source = intent.getStringExtra(EXTRA_BLOCKED_SOURCE) ?: SOURCE_APP
        val tvMessage = findViewById<TextView>(R.id.tvBlockedMessage)

        tvMessage.text = when (source) {
            SOURCE_BROWSER -> "브라우저에서 YouTube 접속이 차단되었습니다."
            SOURCE_KAKAO_GAME -> "카카오톡 게임이 차단되었습니다."
            else -> "YouTube 앱 실행이 차단되었습니다."
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

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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blocked)

        val source = intent.getStringExtra(EXTRA_BLOCKED_SOURCE) ?: SOURCE_APP
        val tvMessage = findViewById<TextView>(R.id.tvBlockedMessage)

        tvMessage.text = if (source == SOURCE_BROWSER) {
            "브라우저에서 YouTube 접속이 차단되었습니다."
        } else {
            "YouTube 앱 실행이 차단되었습니다."
        }

        findViewById<Button>(R.id.btnGoHome).setOnClickListener {
            goHome()
        }

        // Auto-dismiss after 5 seconds
        Handler(Looper.getMainLooper()).postDelayed({ goHome() }, 5000)
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
        goHome()
    }
}

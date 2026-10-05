package com.safeguard.tubeguard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.safeguard.tubeguard.util.BlockLogEntry
import com.safeguard.tubeguard.util.PrefsManager

class LogActivity : AppCompatActivity() {

    private lateinit var prefsManager: PrefsManager
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: LogAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        supportActionBar?.apply {
            title = "차단 로그"
            setDisplayHomeAsUpEnabled(true)
        }

        prefsManager = PrefsManager.getInstance(this)
        recyclerView = findViewById(R.id.recyclerLog)
        tvEmpty = findViewById(R.id.tvLogEmpty)

        adapter = LogAdapter(emptyList())
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<View>(R.id.btnClearLog).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("로그 삭제")
                .setMessage("모든 차단 로그를 삭제하시겠습니까?")
                .setPositiveButton("삭제") { _, _ ->
                    prefsManager.clearBlockLogs()
                    loadLogs()
                    Toast.makeText(this, "로그가 삭제되었습니다", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("취소", null)
                .show()
        }

        loadLogs()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadLogs() {
        val logs = prefsManager.getBlockLogs()
        adapter.updateData(logs)
        if (logs.isEmpty()) {
            recyclerView.visibility = View.GONE
            tvEmpty.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            tvEmpty.visibility = View.GONE
        }
    }

    class LogAdapter(private var items: List<BlockLogEntry>) :
        RecyclerView.Adapter<LogAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvTimestamp: TextView = view.findViewById(R.id.tvLogTimestamp)
            val tvType: TextView = view.findViewById(R.id.tvLogType)
            val tvDetail: TextView = view.findViewById(R.id.tvLogDetail)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_log, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvTimestamp.text = item.timestamp
            holder.tvType.text = item.type
            holder.tvDetail.text = item.detail

            val typeColor = when (item.type) {
                "앱 실행" -> 0xFFF44336.toInt()
                "브라우저" -> 0xFFFF9800.toInt()
                else -> 0xFFB0B0B0.toInt()
            }
            holder.tvType.setTextColor(typeColor)
        }

        override fun getItemCount() = items.size

        fun updateData(newItems: List<BlockLogEntry>) {
            items = newItems
            notifyDataSetChanged()
        }
    }
}

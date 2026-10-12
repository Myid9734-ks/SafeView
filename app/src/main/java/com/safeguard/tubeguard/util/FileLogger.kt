package com.safeguard.tubeguard.util

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import com.safeguard.tubeguard.BuildConfig
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileLogger {

    private const val LOG_FILE = "timer_debug.txt"
    private const val MAX_BYTES = 200 * 1024L

    fun log(context: Context, tag: String, msg: String) {
        if (!BuildConfig.DEBUG) return
        try {
            val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            val line = "$time  $tag: $msg\n"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appendViaMediaStore(context, line)
            } else {
                val file = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    LOG_FILE
                )
                if (file.exists() && file.length() > MAX_BYTES) file.delete()
                file.appendText(line)
            }
        } catch (e: Exception) {}
    }

    fun getLogText(context: Context): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                readViaMediaStore(context) ?: "로그 없음"
            } else {
                val file = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    LOG_FILE
                )
                if (file.exists()) file.readText() else "로그 없음"
            }
        } catch (e: Exception) { "로그 읽기 실패" }
    }

    fun getLogFile(context: Context): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), LOG_FILE)

    private fun appendViaMediaStore(context: Context, text: String) {
        val resolver = context.contentResolver
        val uri = findOrCreateMediaStoreEntry(context) ?: return
        val size = getMediaStoreSize(context, uri)
        // 크기 초과 시 "wt"(truncate)로 열어 내용 비운 후 새 로그 기록 (파일 재생성 없이)
        val mode = if (size > MAX_BYTES) "wt" else "wa"
        resolver.openOutputStream(uri, mode)?.use { it.write(text.toByteArray()) }
    }

    private fun getMediaStoreSize(context: Context, uri: android.net.Uri): Long {
        val projection = arrayOf(MediaStore.Downloads.SIZE)
        return context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        } ?: 0L
    }

    private fun readViaMediaStore(context: Context): String? {
        val resolver = context.contentResolver
        val uri = findMediaStoreEntry(context) ?: return null
        return resolver.openInputStream(uri)?.use {
            it.readBytes().takeLast((MAX_BYTES / 2).toInt()).toByteArray().toString(Charsets.UTF_8)
        }
    }

    private fun findMediaStoreEntry(context: Context): android.net.Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.SIZE)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf(LOG_FILE, Environment.DIRECTORY_DOWNLOADS + "/")
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection, selection, selectionArgs, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
            }
        }
        return null
    }

    private fun findOrCreateMediaStoreEntry(context: Context): android.net.Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.SIZE)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf(LOG_FILE, Environment.DIRECTORY_DOWNLOADS + "/")
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection, selection, selectionArgs, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                // 크기와 무관하게 기존 항목 재사용 (truncate는 appendViaMediaStore에서 처리)
                return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
            }
        }
        // 새로 생성
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, LOG_FILE)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    }
}

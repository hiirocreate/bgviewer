package com.hono.bgviewer

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 想定外の例外でアプリが落ちたとき、その内容をファイルに保存しておき、次回起動時に
 * コピー可能なダイアログで表示するための仕組み。
 * このサンドボックス環境からは実機のlogcat等を直接見られないため、問題が起きたときに
 * 正確な原因（スタックトレース）を教えてもらうための診断用ツールとして用意している。
 */
object CrashLogger {
    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                File(appContext.filesDir, FILE_NAME).writeText(sw.toString())
            } catch (e: Exception) {
                // 無視（クラッシュ処理中にさらに例外を起こさないようにする）
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    fun readAndClear(context: Context): String? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return try {
            val text = file.readText()
            file.delete()
            text
        } catch (e: Exception) {
            null
        }
    }
}

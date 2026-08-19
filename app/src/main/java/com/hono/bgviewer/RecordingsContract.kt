package com.hono.bgviewer

import android.net.Uri

/**
 * BGRecorder側のRecordingsProviderと合わせておく契約（authority・カラム名）。
 * 別アプリなのでクラスは共有できないため、文字列の取り決めだけを両側で一致させている。
 */
object RecordingsContract {
    const val AUTHORITY = "com.hono.bgrecorder.provider"
    val BASE_URI: Uri = Uri.parse("content://$AUTHORITY/recordings")

    const val COL_ID = "_id"
    const val COL_DISPLAY_NAME = "display_name"
    const val COL_SIZE = "size_bytes"
    const val COL_DATE_ADDED = "date_added"
    const val COL_DURATION_MS = "duration_ms"

    fun uriFor(fileName: String): Uri = BASE_URI.buildUpon().appendPath(fileName).build()
}

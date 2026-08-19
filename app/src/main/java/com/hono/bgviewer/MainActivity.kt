package com.hono.bgviewer

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Locale

data class RecordingItem(
    val fileName: String,
    val sizeBytes: Long,
    val dateAdded: Long,
    val durationMs: Long,
)

class MainActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var emptyView: View
    private lateinit var notInstalledView: View
    private lateinit var recordingsAdapter: RecordingsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        recordingsAdapter = RecordingsAdapter(this, mutableListOf())
        listView = ListView(this).apply {
            setBackgroundColor(Color.BLACK)
            divider = null
            dividerHeight = 0
            setAdapter(recordingsAdapter)
            onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
                val item = recordingsAdapter.getItem(position) ?: return@OnItemClickListener
                openPlayer(item)
            }
            setOnItemLongClickListener { _, _, position, _ ->
                val item = recordingsAdapter.getItem(position) ?: return@setOnItemLongClickListener true
                confirmDelete(item)
                true
            }
        }
        root.addView(listView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        emptyView = buildMessageView(getString(R.string.empty_list_title), getString(R.string.empty_list_hint))
        emptyView.visibility = View.GONE
        root.addView(emptyView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        notInstalledView = buildMessageView(getString(R.string.not_installed_title), getString(R.string.not_installed_hint))
        notInstalledView.visibility = View.GONE
        root.addView(notInstalledView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        return root
    }

    private fun buildMessageView(title: String, hint: String): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        container.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
        })
        container.addView(TextView(this).apply {
            text = hint
            setTextColor(Color.LTGRAY)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 0)
        })
        return container
    }

    private fun isRecorderInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo("com.hono.bgrecorder", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun refresh() {
        if (!isRecorderInstalled()) {
            listView.visibility = View.GONE
            emptyView.visibility = View.GONE
            notInstalledView.visibility = View.VISIBLE
            return
        }

        val items = mutableListOf<RecordingItem>()
        try {
            contentResolver.query(RecordingsContract.BASE_URI, null, null, null, null)?.use { cursor ->
                val nameCol = cursor.getColumnIndexOrThrow(RecordingsContract.COL_DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(RecordingsContract.COL_SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(RecordingsContract.COL_DATE_ADDED)
                val durationCol = cursor.getColumnIndexOrThrow(RecordingsContract.COL_DURATION_MS)
                while (cursor.moveToNext()) {
                    items.add(
                        RecordingItem(
                            fileName = cursor.getString(nameCol),
                            sizeBytes = cursor.getLong(sizeCol),
                            dateAdded = cursor.getLong(dateCol),
                            durationMs = cursor.getLong(durationCol),
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            // BGRecorderが別の鍵で入り直された等、権限が合わない場合
            listView.visibility = View.GONE
            emptyView.visibility = View.GONE
            notInstalledView.visibility = View.VISIBLE
            return
        } catch (e: Exception) {
            Toast.makeText(this, "録画一覧の取得に失敗しました", Toast.LENGTH_SHORT).show()
        }

        notInstalledView.visibility = View.GONE
        if (items.isEmpty()) {
            listView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            emptyView.visibility = View.GONE
            listView.visibility = View.VISIBLE
            recordingsAdapter.replaceAll(items)
        }
    }

    private fun openPlayer(item: RecordingItem) {
        val uri: Uri = RecordingsContract.uriFor(item.fileName)
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_URI, uri.toString())
            putExtra(PlayerActivity.EXTRA_TITLE, item.fileName)
        }
        startActivity(intent)
    }

    private fun confirmDelete(item: RecordingItem) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_confirm_title))
            .setMessage(getString(R.string.delete_confirm_message))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                val uri = RecordingsContract.uriFor(item.fileName)
                try {
                    contentResolver.delete(uri, null, null)
                } catch (e: Exception) {
                    Toast.makeText(this, "削除に失敗しました", Toast.LENGTH_SHORT).show()
                }
                refresh()
            }
            .show()
    }
}

private class RecordingsAdapter(
    private val activity: MainActivity,
    private val items: MutableList<RecordingItem>,
) : ArrayAdapter<RecordingItem>(activity, 0, items) {

    fun replaceAll(newItems: List<RecordingItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    private val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN)

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / 1024.0 / 1024.0
        return String.format(Locale.US, "%.1f MB", mb)
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val item = getItem(position)!!
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }
        row.addView(TextView(activity).apply {
            text = dateFormat.format(java.util.Date(item.dateAdded))
            setTextColor(Color.WHITE)
            textSize = 16f
        })
        row.addView(TextView(activity).apply {
            text = "${formatDuration(item.durationMs)} ・ ${formatSize(item.sizeBytes)}"
            setTextColor(Color.LTGRAY)
            textSize = 13f
            setPadding(0, 4, 0, 0)
        })
        row.addView(View(activity).apply {
            setBackgroundColor(Color.parseColor("#333333"))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { topMargin = 20 })
        return row
    }
}

package com.hono.bgviewer

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity

/**
 * 動画再生画面。
 *
 * FLAG_SECUREを付けているため、この画面はスクリーンショットにも他アプリの画面録画にも写らない
 * （Android自体の仕組みで、システムがこのウィンドウの内容をキャプチャさせない）。
 * また、共有・保存・エクスポートの導線を一切用意していない
 * （用意すると、そこから他アプリへ内容が渡ってしまい「このアプリからしか見られない」という前提が崩れるため）。
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_TITLE = "extra_title"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // スクリーンショット・画面録画からの保護
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        val uriString = intent.getStringExtra(EXTRA_URI)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""

        if (uriString == null) {
            finish()
            return
        }

        setContentView(buildUi(Uri.parse(uriString), title))
    }

    private fun buildUi(uri: Uri, title: String): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        val videoView = VideoView(this)
        root.addView(videoView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))

        val titleView = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(24, 24, 24, 24)
        }
        root.addView(titleView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))

        val controller = MediaController(this)
        controller.setAnchorView(videoView)
        videoView.setMediaController(controller)

        videoView.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, "再生に失敗しました", Toast.LENGTH_SHORT).show()
            true
        }

        try {
            videoView.setVideoURI(uri)
            videoView.setOnPreparedListener { mp ->
                mp.isLooping = false
                videoView.start()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "再生に失敗しました", Toast.LENGTH_SHORT).show()
        }

        return root
    }
}

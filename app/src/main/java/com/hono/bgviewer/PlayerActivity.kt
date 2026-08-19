package com.hono.bgviewer

import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 動画再生画面。
 *
 * FLAG_SECUREを付けているため、この画面はスクリーンショットにも他アプリの画面録画にも写らない
 * （Android自体の仕組みで、システムがこのウィンドウの内容をキャプチャさせない）。
 *
 * 「アルバムに保存」ボタンのみ例外的に共有導線を用意している。これはユーザー本人が明示的に
 * 選んだ動画1本だけを、ユーザー自身の操作で公開領域（MediaStore）へコピーする機能であり、
 * 「Z以外のアプリからは閲覧できない」という設計（署名レベル権限＋非公開ストレージ）を破るものではない。
 * ボタンを押さない限り、録画データはこれまで通りZ以外の誰からも見えない。
 *
 * 操作方法：
 * - 画面を軽くタップ：再生バー（シークバー・早送り/早戻し・再生停止）の表示/非表示を切り替え
 * - 画面右側を上下にスワイプ：音量調整
 * - 画面左側を上下にスワイプ：画面の明るさ調整
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_TITLE = "extra_title"
        private const val SEEK_STEP_MS = 10_000
        private const val AUTO_HIDE_DELAY_MS = 3_000L
        private const val INDICATOR_HIDE_DELAY_MS = 700L
        private const val MIN_ZOOM_SCALE = 1f
        private const val MAX_ZOOM_SCALE = 4f
    }

    private lateinit var videoView: VideoView
    private lateinit var controlBar: LinearLayout
    private lateinit var seekBar: SeekBar
    private lateinit var playPauseBtn: Button
    private lateinit var currentTimeText: TextView
    private lateinit var durationText: TextView
    private lateinit var indicatorText: TextView

    private lateinit var audioManager: AudioManager
    private var maxVolume = 1
    private var volumeFraction = 0f
    private var brightnessFraction = 0.5f

    private var isPrepared = false
    private var isUserSeeking = false
    private var isExporting = false

    // ピンチ操作による拡大・縮小（動画は元のサイズ・向きのまま表示され、これで自由に拡大縮小できる）
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var zoomScale = 1f
    private var zoomTranslationX = 0f
    private var zoomTranslationY = 0f

    private lateinit var currentUri: Uri
    private var currentTitle: String = ""

    private val mainHandler = Handler(Looper.getMainLooper())

    private val positionUpdater = object : Runnable {
        override fun run() {
            if (isPrepared && !isUserSeeking) {
                val pos = videoView.currentPosition
                seekBar.progress = pos
                currentTimeText.text = formatTime(pos)
            }
            mainHandler.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // スクリーンショット・画面録画からの保護
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        volumeFraction = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume
        brightnessFraction = readSystemBrightnessFraction()

        val uriString = intent.getStringExtra(EXTRA_URI)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""

        if (uriString == null) {
            finish()
            return
        }

        currentUri = Uri.parse(uriString)
        currentTitle = title
        setContentView(buildUi(currentUri, title))
        mainHandler.post(positionUpdater)
    }

    private fun readSystemBrightnessFraction(): Float {
        return try {
            val value = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            (value / 255f).coerceIn(0.05f, 1f)
        } catch (e: Exception) {
            0.5f
        }
    }

    // ---- 画面構築 ----

    private fun buildUi(uri: Uri, title: String): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        videoView = VideoView(this)
        root.addView(videoView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))

        // タップでコントロール表示切替、左右スワイプで明るさ・音量を調整する透明レイヤー
        val gestureLayer = View(this)
        root.addView(gestureLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setupGestureLayer(gestureLayer)

        val titleView = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(24, 24, 24, 24)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        root.addView(titleView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))

        val exportBtn = Button(this).apply {
            text = "⬇ アルバムに保存"
            textSize = 11f
            setOnClickListener { exportToGallery() }
        }
        root.addView(exportBtn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = 16
            marginEnd = 16
        })

        indicatorText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(32, 16, 32, 16)
            setBackgroundColor(Color.parseColor("#99000000"))
            visibility = View.GONE
        }
        root.addView(indicatorText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        controlBar = buildControlBar()
        root.addView(controlBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setControlsEnabled(false)

        videoView.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, "再生に失敗しました", Toast.LENGTH_SHORT).show()
            true
        }

        try {
            videoView.setVideoURI(uri)
            videoView.setOnPreparedListener { mp ->
                mp.isLooping = false
                isPrepared = true
                seekBar.max = videoView.duration.coerceAtLeast(0)
                durationText.text = formatTime(videoView.duration)
                setControlsEnabled(true)
                videoView.start()
                playPauseBtn.text = "❚❚"
                scheduleAutoHide()
            }
            videoView.setOnCompletionListener {
                playPauseBtn.text = "▶"
                showControls(autoHide = false)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "再生に失敗しました", Toast.LENGTH_SHORT).show()
        }

        return root
    }

    private fun buildControlBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(24, 16, 24, 24)
        }

        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        currentTimeText = TextView(this).apply {
            text = "0:00"
            setTextColor(Color.WHITE)
            textSize = 12f
        }
        durationText = TextView(this).apply {
            text = "0:00"
            setTextColor(Color.WHITE)
            textSize = 12f
        }
        seekBar = SeekBar(this)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) currentTimeText.text = formatTime(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {
                isUserSeeking = true
                cancelAutoHide()
            }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                videoView.seekTo(sb?.progress ?: 0)
                isUserSeeking = false
                scheduleAutoHide()
            }
        })
        seekRow.addView(currentTimeText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = 12 })
        seekRow.addView(seekBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        seekRow.addView(durationText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = 12 })
        bar.addView(seekRow)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val rewindBtn = Button(this).apply {
            text = "⏪ 10秒"
            setOnClickListener {
                videoView.seekTo((videoView.currentPosition - SEEK_STEP_MS).coerceAtLeast(0))
                scheduleAutoHide()
            }
        }
        playPauseBtn = Button(this).apply {
            text = "▶"
            setOnClickListener {
                if (videoView.isPlaying) {
                    videoView.pause()
                    text = "▶"
                } else {
                    videoView.start()
                    text = "❚❚"
                }
                scheduleAutoHide()
            }
        }
        val forwardBtn = Button(this).apply {
            text = "10秒 ⏩"
            setOnClickListener {
                val dur = videoView.duration.coerceAtLeast(0)
                videoView.seekTo((videoView.currentPosition + SEEK_STEP_MS).coerceAtMost(dur))
                scheduleAutoHide()
            }
        }
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 8, 8, 0) }
        btnRow.addView(rewindBtn, lp)
        btnRow.addView(playPauseBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 8, 8, 0) })
        btnRow.addView(forwardBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 8, 8, 0) })
        bar.addView(btnRow)

        return bar
    }

    private fun setControlsEnabled(enabled: Boolean) {
        controlBar.alpha = if (enabled) 1f else 0.4f
        controlBar.isEnabled = enabled
        for (i in 0 until controlBar.childCount) {
            setViewGroupEnabledRecursive(controlBar.getChildAt(i), enabled)
        }
    }

    private fun setViewGroupEnabledRecursive(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                setViewGroupEnabledRecursive(view.getChildAt(i), enabled)
            }
        }
    }

    // ---- タップでコントロール表示切替 / 縦スワイプで明るさ・音量調整 ----

    private fun setupGestureLayer(view: View) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var isDragging = false
        var gestureIsVolume = false
        var startFraction = 0f
        var multiTouchOccurred = false

        var lastFocusX = 0f
        var lastFocusY = 0f
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY
                return true
            }
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomScale = (zoomScale * detector.scaleFactor).coerceIn(MIN_ZOOM_SCALE, MAX_ZOOM_SCALE)
                // 2本指の動きに合わせて表示位置も動かす（拡大した状態で見たい場所を動かせるように）
                zoomTranslationX += detector.focusX - lastFocusX
                zoomTranslationY += detector.focusY - lastFocusY
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY
                applyZoomTransform()
                return true
            }
        })

        view.setOnTouchListener { v, event ->
            scaleGestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    isDragging = false
                    multiTouchOccurred = false
                    gestureIsVolume = downX > v.width / 2f
                    startFraction = if (gestureIsVolume) volumeFraction else brightnessFraction
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    // 2本目の指が触れたらピンチ操作とみなし、音量/明るさのドラッグ判定は行わない
                    multiTouchOccurred = true
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount > 1 || multiTouchOccurred) {
                        // ピンチ中は拡大縮小のみ（ScaleGestureDetectorが処理済み）
                        return@setOnTouchListener true
                    }
                    val dy = downY - event.y
                    if (!isDragging && abs(dy) > touchSlop) {
                        isDragging = true
                        cancelAutoHide()
                    }
                    if (isDragging && v.height > 0) {
                        val delta = dy / v.height.toFloat()
                        val newFraction = (startFraction + delta).coerceIn(0f, 1f)
                        if (gestureIsVolume) {
                            volumeFraction = newFraction
                            applyVolume(newFraction)
                            showIndicator("音量", newFraction)
                        } else {
                            brightnessFraction = newFraction
                            applyBrightness(newFraction)
                            showIndicator("明るさ", newFraction)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isDragging && !multiTouchOccurred) {
                        toggleControls()
                    } else {
                        hideIndicatorDelayed()
                        scheduleAutoHide()
                    }
                    true
                }
                else -> false
            }
        }
    }

    /** ピンチによる拡大率・移動量を動画表示（VideoView）へ反映する */
    private fun applyZoomTransform() {
        videoView.scaleX = zoomScale
        videoView.scaleY = zoomScale
        val maxTransX = (videoView.width * (zoomScale - 1f)) / 2f
        val maxTransY = (videoView.height * (zoomScale - 1f)) / 2f
        zoomTranslationX = zoomTranslationX.coerceIn(-maxTransX, maxTransX)
        zoomTranslationY = zoomTranslationY.coerceIn(-maxTransY, maxTransY)
        videoView.translationX = zoomTranslationX
        videoView.translationY = zoomTranslationY
    }

    private fun applyVolume(fraction: Float) {
        val level = (fraction * maxVolume).roundToInt().coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
    }

    private fun applyBrightness(fraction: Float) {
        val attrs = window.attributes
        attrs.screenBrightness = fraction.coerceIn(0.02f, 1f)
        window.attributes = attrs
    }

    private fun showIndicator(label: String, fraction: Float) {
        indicatorText.text = "$label ${(fraction * 100).roundToInt()}%"
        indicatorText.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideIndicatorRunnable)
    }

    private val hideIndicatorRunnable = Runnable { indicatorText.visibility = View.GONE }

    private fun hideIndicatorDelayed() {
        mainHandler.removeCallbacks(hideIndicatorRunnable)
        mainHandler.postDelayed(hideIndicatorRunnable, INDICATOR_HIDE_DELAY_MS)
    }

    // ---- コントロールバーの表示/非表示 ----

    private fun toggleControls() {
        if (controlBar.visibility == View.VISIBLE) {
            hideControls()
        } else {
            showControls()
        }
    }

    private fun showControls(autoHide: Boolean = true) {
        controlBar.visibility = View.VISIBLE
        if (autoHide) scheduleAutoHide() else cancelAutoHide()
    }

    private fun hideControls() {
        controlBar.visibility = View.GONE
        cancelAutoHide()
    }

    private val autoHideRunnable = Runnable { hideControls() }

    private fun scheduleAutoHide() {
        mainHandler.removeCallbacks(autoHideRunnable)
        mainHandler.postDelayed(autoHideRunnable, AUTO_HIDE_DELAY_MS)
    }

    private fun cancelAutoHide() {
        mainHandler.removeCallbacks(autoHideRunnable)
    }

    // ---- アルバム（MediaStore）への書き出し ----
    // ユーザーが明示的にボタンを押した動画1本だけを公開領域へコピーする。
    // Zの非公開ストレージ自体からは何も消えず、Z以外から見えないという前提はそのまま。
    private fun exportToGallery() {
        if (isExporting) return
        isExporting = true
        Toast.makeText(this, "保存を開始しました…", Toast.LENGTH_SHORT).show()

        val sourceUri = currentUri
        val displayName = if (currentTitle.isNotBlank()) {
            if (currentTitle.endsWith(".mp4", ignoreCase = true)) currentTitle else "$currentTitle.mp4"
        } else {
            "BGViewer_${System.currentTimeMillis()}.mp4"
        }

        Thread {
            var success = false
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/BGViewer")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val destUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                if (destUri != null) {
                    contentResolver.openInputStream(sourceUri)?.use { input ->
                        contentResolver.openOutputStream(destUri)?.use { output ->
                            input.copyTo(output)
                            success = true
                        }
                    }
                    val doneValues = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    contentResolver.update(destUri, doneValues, null, null)
                    if (!success) {
                        contentResolver.delete(destUri, null, null)
                    }
                }
            } catch (e: Exception) {
                success = false
            }

            val finalSuccess = success
            mainHandler.post {
                isExporting = false
                Toast.makeText(
                    this,
                    if (finalSuccess) "アルバム（ムービー/BGViewer）に保存しました" else "保存に失敗しました",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    private fun formatTime(ms: Int): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format(java.util.Locale.US, "%d:%02d", m, s)
    }

    override fun onPause() {
        super.onPause()
        if (::videoView.isInitialized && videoView.isPlaying) {
            videoView.pause()
            if (::playPauseBtn.isInitialized) playPauseBtn.text = "▶"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
    }
}

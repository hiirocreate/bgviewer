package com.hono.bgviewer

import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
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
 * 再生には標準のVideoView（内部はSurfaceView）ではなく、TextureViewを使っている。
 * SurfaceViewは表示内容が別レイヤーで合成されるため、拡大縮小（ピンチズーム）などの
 * View変形が効かない・当たり判定と見た目がズレるといった問題があり、ピンチ操作に対応できない。
 * TextureViewは通常のViewとして描画されるため、変形（回転・拡大縮小・平行移動）を自前で
 * 完全にコントロールできる。そのため、動画の向き（縦動画かどうか）・アスペクト比の補正・
 * ピンチズームのいずれも、このActivityがMatrixを使って自分で計算して適用している。
 *
 * 操作方法：
 * - 画面を軽くタップ：再生バー（シークバー・早送り/早戻し・再生停止）の表示/非表示を切り替え
 * - 画面右側を上下にスワイプ：音量調整
 * - 画面左側を上下にスワイプ：画面の明るさ調整
 * - ピンチ（2本指）：拡大・縮小、拡大中は2本指のまま動かすと表示位置を移動
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

    private lateinit var textureView: TextureView
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

    private var mediaPlayer: MediaPlayer? = null
    private var isPrepared = false
    private var isUserSeeking = false
    private var isExporting = false

    // 動画ファイル自体が持つ「回転して表示すべき角度」と、回転前の実サイズ。
    // MediaPlayer/TextureViewはこの回転を自動では適用してくれないため、自分でMatrixを組んで反映する。
    private var videoRotationDegrees = 0
    private var videoNaturalWidth = 0
    private var videoNaturalHeight = 0

    // TextureViewの回転・アスペクト比補正だけを反映した基準となる変形（ピンチズームは含まない）
    private var baseTransform = Matrix()

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
                val pos = mediaPlayer?.currentPosition ?: 0
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
        readVideoOrientation(currentUri)
        setContentView(buildUi(currentUri, title))
        mainHandler.post(positionUpdater)
    }

    /**
     * 動画ファイル自体に埋め込まれている「回転して表示すべき角度」と、回転前の実サイズを読み取る。
     * MediaPlayer/TextureViewでの再生時にはこの情報が自動で使われないため、ここで先に読んでおき、
     * 自分でTextureViewへの変形（Matrix）として反映する。
     */
    private fun readVideoOrientation(uri: Uri) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            videoRotationDegrees = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            videoNaturalWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            videoNaturalHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            // 読み取れなくても回転なし・サイズ不明のまま再生自体は続行できるようにする
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // 無視
            }
        }
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

        textureView = TextureView(this)
        root.addView(textureView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))

        // タップでコントロール表示切替、上下スワイプで明るさ・音量、ピンチで拡大縮小する透明レイヤー
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

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                startPlayback(uri, Surface(surface))
                configureBaseTransform()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                configureBaseTransform()
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                releasePlayer()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        return root
    }

    private fun startPlayback(uri: Uri, surface: Surface) {
        try {
            val mp = MediaPlayer()
            mediaPlayer = mp
            mp.setSurface(surface)
            mp.setDataSource(this, uri)
            mp.isLooping = false
            mp.setOnPreparedListener { player ->
                isPrepared = true
                if (videoNaturalWidth == 0 || videoNaturalHeight == 0) {
                    // メタデータから読めなかった場合の保険（回転は無しとして扱う）
                    videoNaturalWidth = player.videoWidth
                    videoNaturalHeight = player.videoHeight
                }
                configureBaseTransform()
                seekBar.max = player.duration.coerceAtLeast(0)
                durationText.text = formatTime(player.duration)
                setControlsEnabled(true)
                player.start()
                playPauseBtn.text = "❚❚"
                scheduleAutoHide()
            }
            mp.setOnCompletionListener {
                playPauseBtn.text = "▶"
                showControls(autoHide = false)
            }
            mp.setOnErrorListener { _, _, _ ->
                Toast.makeText(this, "再生に失敗しました", Toast.LENGTH_SHORT).show()
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Toast.makeText(this, "再生に失敗しました", Toast.LENGTH_SHORT).show()
        }
    }

    private fun releasePlayer() {
        try {
            mediaPlayer?.release()
        } catch (e: Exception) {
            // 無視
        }
        mediaPlayer = null
        isPrepared = false
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
                mediaPlayer?.seekTo(sb?.progress ?: 0)
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
                val player = mediaPlayer ?: return@setOnClickListener
                player.seekTo((player.currentPosition - SEEK_STEP_MS).coerceAtLeast(0))
                scheduleAutoHide()
            }
        }
        playPauseBtn = Button(this).apply {
            text = "▶"
            setOnClickListener {
                val player = mediaPlayer ?: return@setOnClickListener
                if (player.isPlaying) {
                    player.pause()
                    text = "▶"
                } else {
                    player.start()
                    text = "❚❚"
                }
                scheduleAutoHide()
            }
        }
        val forwardBtn = Button(this).apply {
            text = "10秒 ⏩"
            setOnClickListener {
                val player = mediaPlayer ?: return@setOnClickListener
                val dur = player.duration.coerceAtLeast(0)
                player.seekTo((player.currentPosition + SEEK_STEP_MS).coerceAtMost(dur))
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

    // ---- 動画の向き・アスペクト比の補正、ピンチズーム ----

    /**
     * 動画の回転メタデータとTextureViewの実サイズから、「回転を打ち消し、正しい向き・
     * アスペクト比で、画面に収まるように」表示するための基準変形を計算する。
     * TextureViewは何もしないと動画のバッファをView全体に引き伸ばして表示してしまうため、
     * まずその引き伸ばしを打ち消して実際の縦横比に戻し、次に必要な角度だけ回転させ、
     * 最後に画面（View）に収まるよう縮小・拡大する（切り取らず全体が見えるようにする）。
     */
    private fun configureBaseTransform() {
        if (!::textureView.isInitialized) return
        val viewWidth = textureView.width
        val viewHeight = textureView.height
        if (viewWidth <= 0 || viewHeight <= 0 || videoNaturalWidth <= 0 || videoNaturalHeight <= 0) return

        val rotated = videoRotationDegrees == 90 || videoRotationDegrees == 270
        val displayWidth = if (rotated) videoNaturalHeight else videoNaturalWidth
        val displayHeight = if (rotated) videoNaturalWidth else videoNaturalHeight

        val fitScale = minOf(
            viewWidth.toFloat() / displayWidth.toFloat(),
            viewHeight.toFloat() / displayHeight.toFloat()
        )

        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f

        val matrix = Matrix()
        // TextureViewのデフォルトの引き伸ばし（バッファを常にView全体に合わせて伸縮させる挙動）を打ち消す
        matrix.setScale(
            videoNaturalWidth.toFloat() / viewWidth.toFloat(),
            videoNaturalHeight.toFloat() / viewHeight.toFloat(),
            centerX,
            centerY
        )
        matrix.postRotate(videoRotationDegrees.toFloat(), centerX, centerY)
        matrix.postScale(fitScale, fitScale, centerX, centerY)

        baseTransform = matrix
        applyCombinedTransform()
    }

    /** 基準変形（回転・アスペクト比補正）にピンチズームの拡大率・移動量を重ねてTextureViewへ反映する */
    private fun applyCombinedTransform() {
        if (!::textureView.isInitialized) return
        val viewWidth = textureView.width
        val viewHeight = textureView.height
        if (viewWidth <= 0 || viewHeight <= 0) return

        val combined = Matrix(baseTransform)
        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f
        combined.postScale(zoomScale, zoomScale, centerX, centerY)
        combined.postTranslate(zoomTranslationX, zoomTranslationY)
        textureView.setTransform(combined)
    }

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
                applyCombinedTransform()
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
        val player = mediaPlayer
        if (player != null && isPrepared) {
            try {
                if (player.isPlaying) {
                    player.pause()
                    if (::playPauseBtn.isInitialized) playPauseBtn.text = "▶"
                }
            } catch (e: Exception) {
                // 無視（すでに解放されている等）
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        releasePlayer()
    }
}

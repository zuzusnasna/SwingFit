package com.example.swingfit

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentSkipListMap

class MainActivity : AppCompatActivity() {

    companion object { private const val TAG = "MainAct" }

    private lateinit var playerView: PlayerView
    private lateinit var overlay: OverlayView
    private lateinit var pickBtn: Button
    private lateinit var analyzeBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var status: TextView

    private var player: ExoPlayer? = null
    private val results = ConcurrentSkipListMap<Long, Detection>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var analyzer: BallAnalyzer
    private var selectedUri: Uri? = null
    private var currentRoi = RoiConfig()

    private val pickVideo = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { onVideoPicked(it) } }

    private fun ensurePermission(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        val granted = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        if (!granted) ActivityCompat.requestPermissions(this, arrayOf(p), 100)
        return granted
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(req, perms, res)
        if (req == 100 && res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) {
            pickBtn.performClick()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.playerView)
        overlay   = findViewById(R.id.overlayView)
        pickBtn   = findViewById(R.id.btnPick)
        analyzeBtn= findViewById(R.id.btnReplay)
        progressBar = findViewById(R.id.progress)
        status    = findViewById(R.id.status)

        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        analyzer = BallAnalyzer(this)

        overlay.isClickable = true
        overlay.bringToFront()
        overlay.setOnRoiChangedListener { roi ->
            currentRoi = roi.clampPortrait()
            overlay.setRoi(currentRoi)
            status.text = "ROI 지정됨. [분석] 버튼을 누르세요."
        }
        analyzeBtn.text = "분석"

        pickBtn.setOnClickListener {
            if (!ensurePermission()) return@setOnClickListener
            pickVideo.launch(arrayOf("video/*"))
        }
        analyzeBtn.setOnClickListener {
            val uri = selectedUri
            if (uri == null) {
                Toast.makeText(this, "먼저 영상을 선택하세요.", Toast.LENGTH_SHORT).show()
            } else {
                runAnalysis(uri)
            }
        }
    }

    private fun onVideoPicked(uri: Uri) {
        selectedUri = uri
        player?.release()
        player = ExoPlayer.Builder(this).build().also { p ->
            playerView.player = p
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {}
            p.setMediaItem(MediaItem.fromUri(uri))
            p.prepare()
            p.repeatMode = Player.REPEAT_MODE_ONE
            p.playWhenReady = true
            p.play()
        }
        // 이전 분석 결과 초기화
        results.clear()
        overlay.setDetections(results)
        overlay.setResult(null) // 이전 비행 데이터도 초기화
        overlay.setRoi(currentRoi)

        status.text = "영상 로드됨. 화면에서 ROI 드래그 후 [분석]을 누르세요."
        startUiTicker()
    }

    private fun runAnalysis(uri: Uri) {
        results.clear()
        overlay.setDetections(results)
        overlay.setResult(null) // 이전 비행 데이터 초기화
        analyzeBtn.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        status.text = "분석 시작..."



        val mainHandler = Handler(Looper.getMainLooper())
        scope.launch {
            val tStart = SystemClock.elapsedRealtime()

            analyzer.analyzeVideo(
                uri = uri,
                initialRoi = currentRoi,
                onMeta = { w, h, _, durUs ->
                    mainHandler.post {
                        overlay.setVideoInfo(w, h)
                        overlay.setVideoRotation(0)
                        Log.d(TAG, "onMeta w=$w h=$h dur=%.3fs".format(durUs/1_000_000.0))
                    }
                },
                onProgress = { cur, total ->
                    val pct = if (total > 0) ((cur * 100L) / total).toInt().coerceIn(0, 100) else 0
                    mainHandler.post {
                        progressBar.progress = pct
                        status.text = "분석 중... $pct%"
                    }
                },
                onDetection = { ts, det ->
                    results[ts] = det
                },
                onRoiFixed = { roi ->
                    currentRoi = roi
                    mainHandler.post {
                        overlay.setRoi(roi)
                    }
                },
                onImpact = { tImpact ->
                    Log.d(TAG, "IMPACT at %.3fs".format(tImpact/1_000_000.0))
                    mainHandler.post {
                        Toast.makeText(this@MainActivity, "임팩트: %.3fs".format(tImpact/1_000_000.0), Toast.LENGTH_SHORT).show()
                    }
                },
                // ★★★ [수정] 새로 추가된 onResult 콜백 구현 ★★★
                onResult = { flightData ->
                    Log.d(TAG, "SUCCESS: Flight data calculated. Carry: ${flightData.estimatedCarryMeters}m")
                    mainHandler.post {
                        // OverlayView에 비행 데이터를 전달하여 화면에 그리도록 함
                        overlay.setResult(flightData)
                    }
                },
                onError = { msg ->
                    val elapsedMs = SystemClock.elapsedRealtime() - tStart

                    val sec = elapsedMs / 1000.0

                    Log.e(TAG, "analyze error: $msg")
                    mainHandler.post {
                        progressBar.visibility = View.GONE
                        status.text = "에러: $msg (로그캣 확인)"
                        analyzeBtn.isEnabled = true
                    }
                }
            )

            // 분석이 정상적으로 모두 끝난 후 실행

            val elapsedMs = SystemClock.elapsedRealtime() - tStart
            val sec = elapsedMs / 1000.0
            Log.i(TAG, "분석 완료: ${results.size}건, ${elapsedMs}ms (${String.format("%.2f", sec)}s)")

            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                status.text = "분석 완료: 검출 ${results.size}건"
                Toast.makeText(this@MainActivity, "분석 완료", Toast.LENGTH_SHORT).show()
                overlay.setDetections(results) // 최종 detecion 맵으로 한번 더 업데이트
                overlay.invalidate()
                analyzeBtn.isEnabled = true
            }
        }
    }

    private fun startUiTicker() {
        val handler = Handler(Looper.getMainLooper())
        val r = object : Runnable {
            override fun run() {
                val posUs = (player?.currentPosition ?: 0L) * 1000L
                overlay.setCurrentTimestamp(posUs)
                overlay.invalidate()
                handler.postDelayed(this, 16L) // 약 60fps
            }
        }
        handler.post(r)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        player?.release()
    }
}
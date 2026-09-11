package com.example.swingfit

import android.content.Context
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.*

import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate

class BallAnalyzer(
    private val ctx: Context,
    private val modelAsset: String = "ball-fp16.tflite",
    private val inputSize: Int = 640,
    private var confThresh: Float = 0.10f,
    private val maxTrail: Int = 40,
    private val cpuThreads: Int = 4,
    private val gpuEnabled: Boolean = true
) {
    companion object {
        private const val TAG = "BallDemo"
        // 골프공 표준 직경 (미터 단위)
        private const val GOLF_BALL_DIAMETER_METERS = 0.04267f
        // 골프공 비행 특성을 반영한 유효 중력 가속도 (공기저항 등 포함)
        private const val EFFECTIVE_GRAVITY = 10.5f
    }

    private val SKIP_INITIAL_US = 2_000_000L
    private val SUFFICIENT_TRAIL_SIZE = 5

    private val tflite: Interpreter by lazy { createInterpreter() }
    private val inputBuf: ByteBuffer = ByteBuffer.allocateDirect((1L * inputSize * inputSize * 3 * 4).toInt()).order(ByteOrder.nativeOrder())
    private val outTensor: Array<Array<FloatArray>> = Array(1) { Array(5) { FloatArray(8400) } }
    private val outputsMap: HashMap<Int, Any> = hashMapOf(0 to outTensor)
    private val letterboxBitmap: Bitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
    private val letterboxCanvas = Canvas(letterboxBitmap)
    private val letterboxPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val cropRect = Rect()
    private val dstRect = Rect()
    private val pixelsBuf = IntArray(inputSize * inputSize)

    private class FrameRetriever(ctx: Context, uri: Uri, videoW: Int, videoH: Int, targetWidth: Int) : AutoCloseable {
        private val mmr = MediaMetadataRetriever()
        private val scaledW: Int
        private val scaledH: Int
        init {
            mmr.setDataSource(ctx, uri)
            if (videoW > 0 && videoH > 0) {
                scaledW = targetWidth
                scaledH = (videoH.toLong() * targetWidth / videoW).toInt()
            } else {
                scaledW = targetWidth
                scaledH = targetWidth
            }
        }
        fun getFrame(tsUs: Long, keyframePriority: Boolean): Bitmap? {
            val option = if (keyframePriority) MediaMetadataRetriever.OPTION_PREVIOUS_SYNC else MediaMetadataRetriever.OPTION_CLOSEST
            return try {
                mmr.getScaledFrameAtTime(tsUs, option, scaledW, scaledH)
            } catch (e: Throwable) {
                try { mmr.getFrameAtTime(tsUs, option) } catch (_: Throwable) { null }
            }
        }
        override fun close() = mmr.release()
    }

    private fun mapAssetToBuffer(assetName: String): ByteBuffer {
        val fd = ctx.assets.openFd(assetName)
        FileInputStream(fd.fileDescriptor).use { fis ->
            val ch = fis.channel
            return ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length)
        }
    }


    private fun createInterpreter(): Interpreter {
        val bb = mapAssetToBuffer(modelAsset)

        val opts = Interpreter.Options().apply {
            setNumThreads(cpuThreads)
            setUseXNNPACK(true)
            @Suppress("DEPRECATION")
            setAllowFp16PrecisionForFp32(true)
        }

        if (gpuEnabled) {
            try {
                val compatList = CompatibilityList()
                if (compatList.isDelegateSupportedOnThisDevice) {
                    val delegateOptions = compatList.bestOptionsForThisDevice
                    val gpuDelegate = GpuDelegate(delegateOptions)
                    opts.addDelegate(gpuDelegate)
                    Log.i(TAG, "✅ GPU delegate successfully enabled.")
                } else {
                    Log.w(TAG, "⚠️ Device does NOT support GPU delegate. Falling back to CPU.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ GPU delegate failed. Fallback to CPU.", e)
            }
        } else {
            Log.d(TAG, "GPU is manually disabled. Using CPU.")
        }

        return try {
            Interpreter(bb, opts)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Interpreter creation failed with GPU delegate. Retrying on CPU...", e)

            val fallbackOpts = Interpreter.Options().apply {
                setNumThreads(cpuThreads)
                setUseXNNPACK(true)
                @Suppress("DEPRECATION")
                setAllowFp16PrecisionForFp32(true)
            }

            Interpreter(bb, fallbackOpts).also {
                Log.i(TAG, "✅ CPU interpreter fallback succeeded.")
            }
        }
    }
    data class InitInfo(val vw: Int, val vh: Int, val rot: Int, val durUs: Long)
    private fun meta(uri: Uri): InitInfo {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(ctx, uri)
            val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val r = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val dMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val vw = if (r % 180 == 0) w else h
            val vh = if (r % 180 == 0) h else w
            return InitInfo(vw, vh, r, dMs * 1000L)
        } finally {
            mmr.release()
        }
    }

    private fun clampRoi(r: RoiConfig): RoiConfig = RoiConfig(r.x0, r.y0, r.x1, r.y1).clampPortrait()

    suspend fun analyzeVideo(
        uri: Uri,
        initialRoi: RoiConfig,
        onMeta: (w: Int, h: Int, rotationDeg: Int, durationUs: Long) -> Unit,
        onProgress: (curUs: Long, totalUs: Long) -> Unit,
        onDetection: (tsUs: Long, det: Detection) -> Unit,
        onRoiFixed: (RoiConfig) -> Unit,
        onImpact: (impactUs: Long) -> Unit,
        onResult: (flightData: FlightData) -> Unit,
        onError: (String) -> Unit
    ) {
        var pixelsPerMeter = -1f

        try {
            val info = meta(uri)
            val forcedRot = 0
            onMeta(info.vw, info.vh, forcedRot, info.durUs)
            val roi = clampRoi(initialRoi)
            onRoiFixed(roi)
            val totalUs = info.durUs
            if (totalUs <= SKIP_INITIAL_US) {
                onProgress(totalUs, totalUs)
                return
            }

            FrameRetriever(ctx, uri, info.vw, info.vh, inputSize).use { frameRetriever ->
                var impactUs: Long? = null
                var lastDetCenterY = 1f
                var baseCenter: PointF? = null
                val trail: ArrayDeque<PointF> = ArrayDeque()

                // 1) 그리드 스캔으로 임팩트 지점 찾기
                // ★★★ [수정] scanStepUs 변수를 사용하기 전에 선언합니다. ★★★
                val scanStepUs = 300_000L
                val times = mutableListOf<Long>().apply {
                    var t = SKIP_INITIAL_US
                    while (t <= totalUs) { add(t); t += scanStepUs }
                    if (isEmpty() || last() < totalUs) add(totalUs)
                }

                val relaxedConf = max(0.06f, confThresh * 0.4f)
                val baseRadius = 0.06f
                var baseIdx = -1
                var lastPresentIdx = -1

                loop@ for ((idx, ts) in times.withIndex()) {
                    onProgress(ts, totalUs)
                    val bmp = frameRetriever.getFrame(ts, keyframePriority = true)
                    var det: Detection? = null
                    if (bmp != null) {
                        det = if (baseIdx < 0) detectTop1(bmp, roi, confThresh, false) else detectTop1(bmp, roi, relaxedConf, false)
                        bmp.recycle()
                    }

                    if (baseIdx < 0) {
                        if (det != null) {
                            val baseConfMin = max(0.55f, confThresh)
                            val confirmConfMin = max(0.45f, confThresh * 0.8f)
                            val confirmRadius = 0.04f
                            if (det.conf >= baseConfMin) {
                                val nextTs = if (idx + 1 <= times.lastIndex) times[idx + 1] else (ts + scanStepUs).coerceAtMost(totalUs)
                                var confirmed = false
                                var cCenter: PointF? = null
                                val bmp2 = frameRetriever.getFrame(nextTs, true)
                                if (bmp2 != null) {
                                    val det2 = detectTop1(bmp2, roi, confirmConfMin, false)
                                    bmp2.recycle()
                                    if (det2 != null && hypot(det2.center.x - det.center.x, det2.center.y - det.center.y) <= confirmRadius) {
                                        confirmed = true
                                        cCenter = det2.center
                                    }
                                }
                                if (confirmed) {
                                    baseCenter = cCenter?.let { PointF((det.center.x + it.x)/2f, (det.center.y + it.y)/2f) } ?: det.center
                                    det.bbox?.let {
                                        val bboxHeightPixels = it.height() * info.vh
                                        if (bboxHeightPixels > 0) {
                                            pixelsPerMeter = bboxHeightPixels / GOLF_BALL_DIAMETER_METERS
                                            Log.d(TAG, "Auto-calibration complete: Base ball height is ${"%.1f".format(bboxHeightPixels)}px. Calculated PIXELS_PER_METER = ${"%.1f".format(pixelsPerMeter)}")
                                        }
                                    }
                                    baseIdx = idx
                                    lastPresentIdx = idx
                                    lastDetCenterY = baseCenter.y
                                    trail.addLast(baseCenter)
                                    if (trail.size > maxTrail) trail.removeFirst()
                                    onDetection(ts, Detection(ts, det.conf, baseCenter, det.bbox, trail.toMutableList()))
                                }
                            }
                        }
                    } else {
                        var usedDet: Detection? = null
                        if (det != null && baseCenter != null && hypot(det.center.x - baseCenter.x, det.center.y - baseCenter.y) <= baseRadius) {
                            usedDet = det
                        }
                        if (usedDet != null) {
                            lastPresentIdx = idx
                            lastDetCenterY = usedDet.center.y
                            trail.addLast(usedDet.center)
                            if (trail.size > maxTrail) trail.removeFirst()
                            onDetection(ts, Detection(ts, usedDet.conf, usedDet.center, usedDet.bbox, trail.toMutableList()))
                        } else {
                            if (impactUs == null) {
                                impactUs = ts
                                onImpact(impactUs!!)
                                break@loop
                            }
                        }
                    }
                }

                if (impactUs == null) {
                    if (baseIdx >= 0 && lastPresentIdx in baseIdx until times.lastIndex) {
                        impactUs = times[lastPresentIdx + 1]
                        onImpact(impactUs!!)
                    }
                }

                if (impactUs != null && baseCenter != null) {
                    var refinedImpact = impactUs!!
                    val presenceConf = max(0.06f, confThresh * 0.4f)
                    val hWindowCoarse = 400_000L; val sCoarse = 100_000L
                    val w0 = (refinedImpact - hWindowCoarse).coerceAtLeast(SKIP_INITIAL_US)
                    val w1 = (refinedImpact + hWindowCoarse).coerceAtMost(totalUs)
                    refineImpactByStep(frameRetriever, w0, w1, sCoarse, baseCenter, roi, totalUs, presenceConf, 0.06f, 2)?.let { refinedImpact = it }
                    val hWindowFine = 100_000L; val sFine = 50_000L
                    val f0 = (refinedImpact - hWindowFine).coerceAtLeast(SKIP_INITIAL_US)
                    val f1 = (refinedImpact + hWindowFine).coerceAtMost(totalUs)
                    refineImpactByStep(frameRetriever, f0, f1, sFine, baseCenter, roi, totalUs, presenceConf, 0.06f, 2)?.let { refinedImpact = it }
                    if (refinedImpact != impactUs) {
                        impactUs = refinedImpact
                        onImpact(impactUs!!)
                    }
                }

                var finalDetections: List<Detection>? = null
                if (impactUs != null) {
                    val estStepUs = MediaMetadataRetriever().use { metaMmr -> metaMmr.setDataSource(ctx, uri); estimateFrameStepUs(metaMmr) }.coerceIn(5_000L, 33_000L)
                    val trackEndUs = (impactUs!! + 200_000L).coerceAtMost(totalUs)
                    var curUs = impactUs!!
                    var miss = 0
                    val MISS_LIMIT = 18
                    var prevY = lastDetCenterY
                    var prevX = baseCenter?.x ?: 0.5f
                    var bestY = prevY
                    val detectedPoints = mutableListOf<Detection>()

                    while (curUs <= trackEndUs && miss < MISS_LIMIT) {
                        onProgress(curUs, totalUs)
                        var got = false
                        val bmp = frameRetriever.getFrame(curUs, false)
                        if (bmp != null) {
                            val elapsedUs = (curUs - impactUs!!).coerceAtLeast(0L)
                            val dynRoi = baseCenter?.let { buildDynamicPostImpactRoi(roi, it, elapsedUs) } ?: roi
                            var best: Detection? = null
                            var listUse = detectMany(bmp, dynRoi, 5, postImpactConfThreshold(elapsedUs))
                            if (listUse.isEmpty()) {
                                listUse = detectMany(bmp, dynRoi, 5, postImpactConfThreshold(elapsedUs), true)
                            }
                            var bestScore = -1f
                            for (cand in listUse) {
                                if ((cand.bbox?.width() ?: 0f) <= 0f || (cand.bbox?.height() ?: 0f) <= 0f) continue
                                if (!((prevY - cand.center.y) >= 0f)) continue
                                val score = cand.conf + ((prevY - cand.center.y).coerceAtLeast(0f) * 0.2f)
                                if (score > bestScore) {
                                    best = cand
                                    bestScore = score
                                }
                            }
                            if (best == null && miss >= 1) {
                                best = findBrightStreakByPixelAnalysis(bmp, dynRoi, info.vw, info.vh, prevY)
                            }
                            bmp.recycle()

                            if (best != null) {
                                bestY = min(bestY, best.center.y)
                                val alpha = 0.7f
                                prevY = min(prevY, alpha * best.center.y + (1f - alpha) * prevY)
                                prevX = alpha * best.center.x + (1f - alpha) * prevX
                                trail.addLast(best.center)
                                if (trail.size > maxTrail) trail.removeFirst()
                                val newDetection = Detection(curUs, best.conf, best.center, best.bbox, trail.toMutableList())
                                detectedPoints.add(newDetection)
                                onDetection(curUs, newDetection)
                                got = true
                                miss = 0
                                if (trail.size >= SUFFICIENT_TRAIL_SIZE) {
                                    break
                                }
                            }
                        }
                        if (!got) miss++
                        curUs += estStepUs
                    }
                    finalDetections = detectedPoints
                }
                onProgress(totalUs, totalUs)

                if (pixelsPerMeter > 0f) {
                    finalDetections?.let {
                        if (it.size > 5) {
                            val flightData = calculateFlightData(it, info.vw, info.vh, pixelsPerMeter)
                            onResult(flightData)
                        }
                    }
                } else {
                    Log.w(TAG, "Could not calibrate pixels-per-meter. Skipping flight calculation.")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "analyzeVideo failed", t)
            onError(t.message ?: "unknown error")
        }
    }

    private fun calculateFlightData(detections: List<Detection>, videoW: Int, videoH: Int, pixelsPerMeter: Float): FlightData {
        val pointsToAnalyze = detections.take(5)
        val firstDet = pointsToAnalyze.first()
        val lastDet = pointsToAnalyze.last()
        val deltaTimeSec = (lastDet.tsUs - firstDet.tsUs) / 1_000_000f
        if (deltaTimeSec <= 0) return FlightData(0f, 0f, 0f, Path())

        val deltaXPixels = (lastDet.center.x - firstDet.center.x) * videoW
        val deltaYPixels = (firstDet.center.y - lastDet.center.y) * videoH
        val velocityPixelsPerSec = hypot(deltaXPixels, deltaYPixels) / deltaTimeSec
        val launchAngleScreenDeg = atan2(deltaYPixels, deltaXPixels) * (180f / PI.toFloat())
        val initialSpeedMps = velocityPixelsPerSec / pixelsPerMeter
        val launchAngleRad = Math.toRadians(launchAngleScreenDeg.toDouble())
        val estimatedCarryMeters = (initialSpeedMps.pow(2) * sin(2 * launchAngleRad)) / EFFECTIVE_GRAVITY

        val flightPath = Path()
        val startPointPx = PointF(firstDet.center.x * videoW, firstDet.center.y * videoH)
        flightPath.moveTo(startPointPx.x, startPointPx.y)
        val totalFlightTime = (2 * initialSpeedMps * sin(launchAngleRad)) / EFFECTIVE_GRAVITY
        val timeStep = 0.02f
        var t = timeStep.toDouble()
        while (t < totalFlightTime) {
            val dx_mps = initialSpeedMps * cos(launchAngleRad) * t
            val dy_mps = (initialSpeedMps * sin(launchAngleRad) * t) - (0.5f * EFFECTIVE_GRAVITY * t.pow(2))
            val nextX_px = startPointPx.x + (dx_mps * pixelsPerMeter).toFloat()
            val nextY_px = startPointPx.y - (dy_mps * pixelsPerMeter).toFloat()
            flightPath.lineTo(nextX_px, nextY_px)
            t += timeStep
        }
        return FlightData(initialSpeedMps, launchAngleScreenDeg, estimatedCarryMeters.toFloat(), flightPath)
    }

    private fun findBrightStreakByPixelAnalysis(frame: Bitmap, roiIn: RoiConfig, videoW: Int, videoH: Int, prevY: Float): Detection? {
        val LUMINANCE_THRESHOLD = 210
        val MIN_PIXEL_COUNT = 15
        val MAX_ASPECT_RATIO = 0.35f
        val roi = clampRoi(roiIn)
        val vw = frame.width.toFloat()
        val vh = frame.height.toFloat()
        val rx = (roi.x0 * vw).toInt()
        val ry = (roi.y0 * vh).toInt()
        val rw = ((roi.x1 - roi.x0) * vw).toInt()
        val rh = ((roi.y1 - roi.y0) * vh).toInt()
        if (rw <= 0 || rh <= 0) return null
        val roiPixels = IntArray(rw * rh)
        try {
            frame.getPixels(roiPixels, 0, rw, rx, ry, rw, rh)
        } catch (e: Exception) { return null }
        val brightPixels = mutableListOf<Point>()
        for (y in 0 until rh) {
            for (x in 0 until rw) {
                val p = roiPixels[y * rw + x]
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                if (lum >= LUMINANCE_THRESHOLD) brightPixels.add(Point(rx + x, ry + y))
            }
        }
        if (brightPixels.size < MIN_PIXEL_COUNT) return null
        var sumX = 0L; var sumY = 0L
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
        for (p in brightPixels) {
            sumX += p.x; sumY += p.y
            minX = min(minX, p.x); minY = min(minY, p.y)
            maxX = max(maxX, p.x); maxY = max(maxY, p.y)
        }
        val boxW = (maxX - minX).toFloat(); val boxH = (maxY - minY).toFloat()
        if (boxW <= 0f || boxH <= 0f) return null
        val normCenterY = (sumY.toFloat() / brightPixels.size) / videoH.toFloat()
        if (normCenterY >= prevY) return null
        val aspect = if (boxH > boxW) boxW / boxH else boxH / boxW
        if (aspect > MAX_ASPECT_RATIO) return null
        val normCenterX = (sumX.toFloat() / brightPixels.size) / videoW.toFloat()
        val center = PointF(normCenterX, normCenterY)
        val bbox = RectF(minX / vw, minY / vh, maxX / vw, maxY / vh)
        return Detection(0L, 0.99f, center, bbox, mutableListOf(center))
    }

    private fun detectTop1(frame: Bitmap, roiIn: RoiConfig, confMin: Float, invertColors: Boolean): Detection? {
        val list = detectMany(frame, roiIn, 1, confMin, invertColors)
        return list.firstOrNull()
    }

    private fun detectMany(frame: Bitmap, roiIn: RoiConfig, wantTopK: Int = 5, confMin: Float = this.confThresh, invertColors: Boolean = false): List<Detection> {
        val roi = clampRoi(roiIn)
        val vw = frame.width; val vh = frame.height
        val rx = (roi.x0 * vw).toInt().coerceIn(0, max(0, vw - 1))
        val ry = (roi.y0 * vh).toInt().coerceIn(0, max(0, vh - 1))
        val rw = ((roi.x1 - roi.x0) * vw).toInt().coerceAtLeast(2).coerceAtMost(vw - rx)
        val rh = ((roi.y1 - roi.y0) * vh).toInt().coerceAtLeast(2).coerceAtMost(vh - ry)
        cropRect.set(rx, ry, rx + rw, ry + rh)
        letterboxCanvas.drawColor(Color.BLACK)
        val s = min(inputSize.toFloat() / rw, inputSize.toFloat() / rh)
        val nw = (rw * s).roundToInt(); val nh = (rh * s).roundToInt()
        val left = (inputSize - nw) / 2; val top  = (inputSize - nh) / 2
        dstRect.set(left, top, left + nw, top + nh)
        letterboxCanvas.drawBitmap(frame, cropRect, dstRect, letterboxPaint)
        inputBuf.rewind()
        letterboxBitmap.getPixels(pixelsBuf, 0, inputSize, 0, 0, inputSize, inputSize)
        var pIdx = 0
        val inv = if (invertColors) 1f else 0f
        while (pIdx < pixelsBuf.size) {
            val p = pixelsBuf[pIdx++]
            val r = ((p ushr 16) and 0xFF) / 255f; val g = ((p ushr 8) and 0xFF) / 255f; val b = (p and 0xFF) / 255f
            if (inv == 0f) { inputBuf.putFloat(r); inputBuf.putFloat(g); inputBuf.putFloat(b) }
            else { inputBuf.putFloat(1f - r); inputBuf.putFloat(1f - g); inputBuf.putFloat(1f - b) }
        }
        tflite.runForMultipleInputsOutputs(arrayOf(inputBuf as Any), outputsMap)
        val scoreArr = outTensor[0][4]; val cxArr = outTensor[0][0]; val cyArr = outTensor[0][1]
        val wArr  = outTensor[0][2]; val hArr  = outTensor[0][3]
        val rx0 = roi.x0; val ry0 = roi.y0
        val rW = (roi.x1 - roi.x0); val rH = (roi.y1 - roi.y0)

        if (wantTopK <= 1) {
            var bestScore = -1f; var bestCenterX = 0f; var bestCenterY = 0f; var bestW = 0f; var bestH = 0f
            var i = 0
            while (i < 8400) {
                val sc = scoreArr[i]
                if (sc >= confMin && sc > bestScore) {
                    val scale = if ((cxArr[i] in 0f..1f)) 1f else 1f / inputSize
                    bestCenterX = rx0 + (cxArr[i] * scale).coerceIn(0f, 1f) * rW
                    bestCenterY = ry0 + (cyArr[i] * scale).coerceIn(0f, 1f) * rH
                    bestW = (wArr[i] * scale).coerceIn(0f, 1f) * rW
                    bestH = (hArr[i] * scale).coerceIn(0f, 1f) * rH
                    bestScore = sc
                }
                i++
            }
            if (bestScore >= 0f) {
                val box = RectF((bestCenterX - bestW/2f), (bestCenterY - bestH/2f), (bestCenterX + bestW/2f), (bestCenterY + bestH/2f))
                val center = PointF(bestCenterX, bestCenterY)
                return listOf(Detection(0L, bestScore, center, box, mutableListOf(center)))
            }
            return emptyList()
        }
        val cand = ArrayList<DetectionCandidate>()
        var i = 0
        while (i < 8400) {
            if (scoreArr[i] >= confMin) {
                val scale = if ((cxArr[i] in 0f..1f)) 1f else 1f / inputSize
                val vX = rx0 + (cxArr[i] * scale).coerceIn(0f, 1f) * rW
                val vY = ry0 + (cyArr[i] * scale).coerceIn(0f, 1f) * rH
                val vW = (wArr[i] * scale).coerceIn(0f, 1f) * rW
                val vH = (hArr[i] * scale).coerceIn(0f, 1f) * rH
                val box = RectF((vX - vW/2f), (vY - vH/2f), (vX + vW/2f), (vY + vH/2f))
                cand.add(DetectionCandidate(PointF(vX, vY), box, scoreArr[i]))
            }
            i++
        }
        if (cand.isEmpty()) return emptyList()
        cand.sortByDescending { it.score }
        val keep = ArrayList<DetectionCandidate>()
        val used = BooleanArray(cand.size)
        i = 0
        while (i < cand.size && keep.size < wantTopK) {
            if (!used[i]) {
                val a = cand[i]
                keep.add(a)
                var j = i + 1
                while (j < cand.size) {
                    if (!used[j] && iou(a.box, cand[j].box) > 0.45f) used[j] = true
                    j++
                }
            }
            i++
        }
        return keep.map { c -> Detection(0L, c.score, c.center, c.box, mutableListOf(c.center)) }
    }

    private data class DetectionCandidate(val center: PointF, val box: RectF, val score: Float)

    private fun iou(a: RectF, b: RectF): Float {
        val ix = max(0f, min(a.right, b.right) - max(a.left, b.left))
        val iy = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        val inter = ix * iy
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }

    private fun estimateFrameStepUs(mmr: MediaMetadataRetriever): Long {
        return try {
            val fpsStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            val fps = fpsStr?.toFloatOrNull()?.takeIf { it in 0.1f..240f }
            if (fps != null) (1_000_000f / fps).toLong().coerceIn(5_000L, 50_000L) else 16667L
        } catch (_: Throwable) { 16667L }
    }

    private fun refineImpactByStep(frameRetriever: FrameRetriever, startUs: Long, endUs: Long, stepUs: Long, baseCenter: PointF?, roi: RoiConfig, durUs: Long, conf: Float, radius: Float, needMiss: Int = 2): Long? {
        val center = baseCenter ?: return null
        var t = max(startUs, SKIP_INITIAL_US)
        var consecutiveMiss = 0
        while (t <= endUs) {
            val bmp = frameRetriever.getFrame(t.coerceIn(0L, durUs), false)
            var present = false
            if (bmp != null) {
                val list = detectMany(bmp, roi, 5, conf, false)
                bmp.recycle()
                present = list.any { hypot(it.center.x - center.x, it.center.y - center.y) <= radius }
            }
            if (present) consecutiveMiss = 0 else {
                consecutiveMiss++
                if (consecutiveMiss >= needMiss) return (t - (needMiss - 1) * stepUs).coerceIn(startUs, endUs)
            }
            t += stepUs
        }
        return null
    }

    private fun intersectRoi(a: RoiConfig, b: RoiConfig): RoiConfig {
        val x0 = max(a.x0, b.x0); val y0 = max(a.y0, b.y0)
        val x1 = min(a.x1, b.x1); val y1 = min(a.y1, b.y1)
        return RoiConfig(x0.coerceIn(0f,1f),y0.coerceIn(0f,1f),max(x1, x0 + 0.01f).coerceIn(0f,1f),max(y1, y0 + 0.01f).coerceIn(0f,1f)).clampPortrait()
    }

    private fun buildDynamicPostImpactRoi(main: RoiConfig, base: PointF, elapsedUs: Long): RoiConfig {
        val t = elapsedUs / 1_000_000f
        val rise = (0.42f * t).coerceAtLeast(0f)
        val halfX = (0.14f + 0.28f * t).coerceIn(0.14f, 0.36f)
        val x0 = (base.x - halfX).coerceIn(0f, 1f)
        val x1 = (base.x + halfX).coerceIn(0f, 1f)
        val y1 = main.y1
        val y0 = (base.y - (0.22f + rise)).coerceIn(0f, y1 - 0.02f)
        val dyn = RoiConfig(x0, y0, x1, y1).clampPortrait()
        return intersectRoi(main, dyn)
    }

    private fun postImpactConfThreshold(elapsedUs: Long): Float {
        return when {
            elapsedUs < 120_000L -> max(0.02f, confThresh * 0.18f)
            elapsedUs < 200_000L -> max(0.03f, confThresh * 0.22f)
            else -> max(0.04f, confThresh * 0.28f)
        }
    }
}
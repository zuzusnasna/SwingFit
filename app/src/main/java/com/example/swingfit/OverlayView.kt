package com.example.swingfit

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.NavigableMap
import java.util.concurrent.ConcurrentSkipListMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OverlayView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private val roiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 5f
        pathEffect = DashPathEffect(floatArrayOf(20f, 12f), 0f)
    }
    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.FILL
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLUE
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        style = Paint.Style.FILL
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }
    private val flightPathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 255, 0)
        style = Paint.Style.STROKE
        strokeWidth = 4f
        pathEffect = DashPathEffect(floatArrayOf(15f, 10f), 0f)
    }

    private var detections: NavigableMap<Long, Detection> = ConcurrentSkipListMap()
    private var currentTsUs: Long = 0L
    private var videoW = 0
    private var videoH = 0
    private var rotationDeg = 0
    private var roi: RoiConfig = RoiConfig()
    private val contentRect = RectF()
    private var dragStart: PointF? = null
    private var roiListener: ((RoiConfig) -> Unit)? = null
    private var flightData: FlightData? = null

    fun setOnRoiChangedListener(l: ((RoiConfig) -> Unit)?) { roiListener = l }
    fun setVideoInfo(w: Int, h: Int) {
        videoW = w; videoH = h
        computeContentRect(width, height)
        invalidate()
    }
    fun setVideoRotation(@Suppress("UNUSED_PARAMETER") deg: Int) {
        rotationDeg = 0
        computeContentRect(width, height)
        invalidate()
    }
    fun setDetections(map: NavigableMap<Long, Detection>) {
        detections = map
        invalidate()
    }
    fun setCurrentTimestamp(tsUs: Long) {
        currentTsUs = tsUs
        invalidate()
    }
    fun setRoi(cfg: RoiConfig) {
        roi = cfg
        invalidate()
    }
    fun setResult(data: FlightData?) {
        this.flightData = data
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeContentRect(w, h)
    }

    private fun computeContentRect(viewW: Int, viewH: Int) {
        if (videoW <= 0 || videoH <= 0) {
            contentRect.set(0f, 0f, viewW.toFloat(), viewH.toFloat())
            return
        }
        val vw = videoW.toFloat()
        val vh = videoH.toFloat()
        val scale = min(viewW / vw, viewH / vh)
        val cw = vw * scale
        val ch = vh * scale
        val left = (viewW - cw) / 2f
        val top = (viewH - ch) / 2f
        contentRect.set(left, top, left + cw, top + ch)
    }

    private fun mapNormToView(px: Float, py: Float): PointF {
        val x = contentRect.left + px * contentRect.width()
        val y = contentRect.top + py * contentRect.height()
        return PointF(x, y)
    }
    private fun mapViewToNorm(vx: Float, vy: Float): PointF {
        val nx = ((vx - contentRect.left) / max(1f, contentRect.width())).coerceIn(0f, 1f)
        val ny = ((vy - contentRect.top) / max(1f, contentRect.height())).coerceIn(0f, 1f)
        return PointF(nx, ny)
    }
    private fun mapVideoPxToView(vx: Float, vy: Float): PointF {
        val nx = vx / max(1f, videoW.toFloat())
        val ny = vy / max(1f, videoH.toFloat())
        return mapNormToView(nx, ny)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val roiRectVideo = roi.clampPortrait().toRectF(videoW, videoH)
        val lt = mapVideoPxToView(roiRectVideo.left, roiRectVideo.top)
        val rb = mapVideoPxToView(roiRectVideo.right, roiRectVideo.bottom)
        canvas.drawRect(RectF(lt.x, lt.y, rb.x, rb.y), roiPaint)

        // 현재 시각의 궤적 그리기
        val det = detections.floorEntry(currentTsUs)?.value
        det?.trail?.let {
            if (it.size >= 2) {
                val path = Path()
                val p0 = mapNormToView(it[0].x, it[0].y)
                path.moveTo(p0.x, p0.y)
                for (i in 1 until it.size) {
                    val p = mapNormToView(it[i].x, it[i].y)
                    path.lineTo(p.x, p.y)
                }
                canvas.drawPath(path, trailPaint)
            }
        }
        // 현재 공 위치 그리기
        det?.let {
            val c = mapNormToView(it.center.x, it.center.y)
            canvas.drawCircle(c.x, c.y, 10f, ballPaint)
        }

        // 비행 데이터 및 예상 탄도 그리기
        flightData?.let { data ->
            val matrix = Matrix()
            matrix.postScale(contentRect.width() / videoW, contentRect.height() / videoH)
            matrix.postTranslate(contentRect.left, contentRect.top)
            val pathInView = Path()
            data.flightPath.transform(matrix, pathInView)
            canvas.drawPath(pathInView, flightPathPaint)

            val speedKmh = data.initialSpeedMps * 3.6f
            val text1 = "Speed: %.1f km/h  Angle: %.1f°".format(speedKmh, data.launchAngleDeg)
            val text2 = "Carry: %.1f m".format(data.estimatedCarryMeters)

            textPaint.textSize = 42f
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(text1, contentRect.right - 20f, contentRect.top + 50f, textPaint)
            textPaint.textSize = 56f
            canvas.drawText(text2, contentRect.right - 20f, contentRect.top + 110f, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
        }

        // 하단 정보 텍스트
        textPaint.textSize = 32f
        canvas.drawText(
            "video=${videoW}x${videoH} ts=${currentTsUs/1_000_000f}s detN=${detections.size}",
            contentRect.left + 12f, contentRect.bottom - 12f, textPaint
        )
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!isClickable) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStart = PointF(ev.x, ev.y)
                return true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val s = dragStart ?: return false
                val e = PointF(ev.x, ev.y)
                val sn = mapViewToNorm(s.x, s.y)
                val en = mapViewToNorm(e.x, e.y)
                val newRoi = RoiConfig(sn.x, sn.y, en.x, en.y).clampPortrait()
                setRoi(newRoi)
                roiListener?.invoke(newRoi)
                if (ev.actionMasked == MotionEvent.ACTION_UP) dragStart = null
                return true
            }
        }
        return super.onTouchEvent(ev)
    }
}
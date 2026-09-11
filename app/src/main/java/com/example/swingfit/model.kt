package com.example.swingfit

import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF

/** 프레임별 탐지 결과 (정규화 좌표 0..1, 세로영상 기준) */
data class Detection(
    val tsUs: Long,
    val conf: Float,
    val center: PointF,
    val bbox: RectF? = null,
    val trail: MutableList<PointF> = mutableListOf()
)

/** ROI: 정규화 좌표 (0..1), 세로영상 기준 */
data class RoiConfig(
    var x0: Float = 0f,
    var y0: Float = 0.7f,
    var x1: Float = 1f,
    var y1: Float = 1f
) {
    fun clampPortrait(): RoiConfig {
        if (x0 > x1) { val t = x0; x0 = x1; x1 = t }
        if (y0 > y1) { val t = y0; y0 = y1; y1 = t }
        x0 = x0.coerceIn(0f, 1f)
        y0 = y0.coerceIn(0f, 1f)
        x1 = x1.coerceIn(0f, 1f)
        y1 = y1.coerceIn(0f, 1f)
        return this
    }
    fun toRectF(videoW: Int, videoH: Int): RectF {
        val l = (x0 * videoW).coerceIn(0f, videoW.toFloat())
        val t = (y0 * videoH).coerceIn(0f, videoH.toFloat())
        val r = (x1 * videoW).coerceIn(0f, videoW.toFloat())
        val b = (y1 * videoH).coerceIn(0f, videoH.toFloat())
        return RectF(l, t, r, b)
    }
}

/**
 * 비행 분석 최종 결과 데이터 클래스
 * @param initialSpeedMps 초속 (m/s)
 * @param launchAngleDeg 지면 대비 발사각 (degrees)
 * @param estimatedCarryMeters 추정된 캐리 비거리 (meters)
 * @param flightPath 시뮬레이션된 비행 궤적 (View 좌표계)
 */
data class FlightData(
    val initialSpeedMps: Float,
    val launchAngleDeg: Float,
    val estimatedCarryMeters: Float,
    val flightPath: Path
)
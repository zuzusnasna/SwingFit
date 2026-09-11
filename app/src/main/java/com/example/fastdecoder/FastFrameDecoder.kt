package com.example.fastdecoder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.*
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * HW 디코더 (CCodec) 기반 프레임 그랩버
 * - 운영속도(operatingRate) / 저지연(low-latency) / 프리롤 적용
 * - Surface + ImageReader(YUV_420_888) -> SW RGBA 변환(간단/안전)
 *   : 실제로는 libyuv/NEON 으로 더 빠르게 만들 수 있음. 여기선 안정/호환 우선
 */
class FastFrameDecoder(
    private val ctx: Context,
    private val uri: Uri,
    private val outW: Int,
    private val outH: Int,
    private val preferHardware: Boolean
) {

    companion object { private const val TAG = "FastFrameDecoder" }

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var format: MediaFormat? = null
    private var trackIdx: Int = -1

    private val imageReader: ImageReader
    private val surface: Surface

    private val warmed = booleanArrayOf(false)

    init {
        extractor = MediaExtractor()
        extractor!!.setDataSource(ctx, uri, null)
        // 비디오 트랙 선택
        for (i in 0 until extractor!!.trackCount) {
            val fmt = extractor!!.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("video/")) { trackIdx = i; format = fmt; break }
        }
        if (trackIdx < 0) throw IllegalStateException("No video track")
        extractor!!.selectTrack(trackIdx)

        // 출력 사이즈(approx crop/scale by HW)
        val srcW = format!!.getInteger(MediaFormat.KEY_WIDTH)
        val srcH = format!!.getInteger(MediaFormat.KEY_HEIGHT)
        format!!.setInteger(MediaFormat.KEY_MAX_WIDTH, outW)
        format!!.setInteger(MediaFormat.KEY_MAX_HEIGHT, (srcH.toLong() * outW / srcW).toInt())

        // ImageReader (YUV420)
        imageReader = ImageReader.newInstance(outW, (srcH.toLong() * outW / srcW).toInt(), ImageFormat.YUV_420_888, /*max*/6)
        surface = imageReader.surface

        val mime = format!!.getString(MediaFormat.KEY_MIME)!!
        codec = MediaCodec.createDecoderByType(mime)

        // 저지연 옵션
        if (Build.VERSION.SDK_INT >= 23) {
            format!!.setInteger(MediaFormat.KEY_PRIORITY, 0) // normal
            try { format!!.setFloat(MediaFormat.KEY_OPERATING_RATE, 120f) } catch (_: Throwable) {}
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try { format!!.setInteger(MediaFormat.KEY_LOW_LATENCY, 1) } catch (_: Throwable) {}
        }

        codec!!.configure(format, surface, null, 0)
        codec!!.start()

        Log.d(TAG, "init ok: out=${outW}x${imageReader.height}, preferHardware=$preferHardware, format=YUV_420_888")
        warmed[0] = false
    }

    fun getFrameAtUs(tsUs: Long, keyframe: Boolean): Bitmap? {
        val ex = extractor ?: return null
        val mc = codec ?: return null

        // 탐색 (키프레임 우선)
        try {
            ex.seekTo(tsUs, if (keyframe) MediaExtractor.SEEK_TO_PREVIOUS_SYNC else MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        } catch (_: Throwable) {}

        // 프리롤: 디코더가 안정화될 시간(몇 개 프레임) 공급
        val timeoutUs = 10_000L
        val startNs = System.nanoTime()
        var dequeued: Bitmap? = null

        var fed = 0
        while (System.nanoTime() - startNs < 1_500_000_000L) { // ~1.5s 한도
            // input
            val inIdx = mc.dequeueInputBuffer(timeoutUs)
            if (inIdx >= 0) {
                val ib = mc.getInputBuffer(inIdx)!!
                val sampleSize = ex.readSampleData(ib, 0)
                if (sampleSize < 0) {
                    mc.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                } else {
                    val pts = ex.sampleTime
                    val flags = ex.sampleFlags
                    mc.queueInputBuffer(inIdx, 0, sampleSize, pts, flags)
                    ex.advance()
                    fed++
                }
            }

            // output (ImageReader acquire)
            val info = MediaCodec.BufferInfo()
            val outIdx = mc.dequeueOutputBuffer(info, timeoutUs)
            if (outIdx >= 0) {
                mc.releaseOutputBuffer(outIdx, true) // render to Surface(ImageReader)

                // acquireLatestImage -> Bitmap
                val img = imageReader.acquireLatestImage()
                if (img != null) {
                    val bmp = yuv420ToBitmap(img) // 간단 변환
                    img.close()
                    // 워밍업 동안 첫 2~3개는 폐기
                    if (!warmed[0] && fed < 3) {
                        bmp.recycle()
                    } else {
                        warmed[0] = true
                        dequeued = bmp
                        break
                    }
                }
            }
        }
        if (dequeued == null) Log.w(TAG, "decode timeout (~1500ms), no image")
        return dequeued
    }

    fun release() {
        try { codec?.stop() } catch (_: Throwable) {}
        try { codec?.release() } catch (_: Throwable) {}
        try { extractor?.release() } catch (_: Throwable) {}
        try { imageReader.close() } catch (_: Throwable) {}
        try { surface.release() } catch (_: Throwable) {}
    }

    // 매우 단순한 YUV -> ARGB 변환 (성능보다 호환 우선)
    private fun yuv420ToBitmap(img: Image): Bitmap {
        val w = img.width; val h = img.height
        val y = img.planes[0].buffer
        val u = img.planes[1].buffer
        val v = img.planes[2].buffer
        val yRow = img.planes[0].rowStride
        val uRow = img.planes[1].rowStride
        val vRow = img.planes[2].rowStride
        val uPix = img.planes[1].pixelStride
        val vPix = img.planes[2].pixelStride

        val out = IntArray(w * h)
        var oy = 0
        var py = 0
        while (py < h) {
            var px = 0
            while (px < w) {
                val yi = (py * yRow) + px
                val uvRow = (py / 2)
                val ui = uvRow * uRow + (px / 2) * uPix
                val vi = uvRow * vRow + (px / 2) * vPix
                val Y = (y.get(yi).toInt() and 0xFF)
                val U = (u.get(ui).toInt() and 0xFF) - 128
                val V = (v.get(vi).toInt() and 0xFF) - 128
                var r = (Y + 1.402f * V).roundToInt()
                var g = (Y - 0.344f * U - 0.714f * V).roundToInt()
                var b = (Y + 1.772f * U).roundToInt()
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255
                out[oy + px] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                px++
            }
            oy += w
            py++
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, w, 0, 0, w, h)
        return bmp
    }
}
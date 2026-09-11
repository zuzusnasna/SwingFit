package com.example.swingfit

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlin.math.max

class SimpleVideoReader(ctx: Context, uri: Uri) {
    private val mmr = MediaMetadataRetriever()
    val durationUs: Long

    init {
        mmr.setDataSource(ctx, uri)
        var durUs = runCatching {
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()?.times(1000L)
        }.getOrNull() ?: 0L

        if (durUs <= 0L) {
            val ex = MediaExtractor()
            runCatching {
                ex.setDataSource(ctx, uri, null)
                var best = 0L
                repeat(ex.trackCount) { i ->
                    val f: MediaFormat = ex.getTrackFormat(i)
                    if (f.containsKey(MediaFormat.KEY_DURATION)) best = max(best, f.getLong(MediaFormat.KEY_DURATION))
                }
                durUs = best
            }
            runCatching { ex.release() }
        }
        if (durUs <= 0L) durUs = 200 * 33_000L
        durationUs = durUs
    }

    fun getFrameAtUs(timeUs: Long): Bitmap? {
        return runCatching { mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST) }.getOrNull()
            ?: runCatching { mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }.getOrNull()
            ?: runCatching { mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST) }.getOrNull()
    }

    fun release() { runCatching { mmr.release() } }
}
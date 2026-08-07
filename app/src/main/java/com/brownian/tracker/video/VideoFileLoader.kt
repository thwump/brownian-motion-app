package com.brownian.tracker.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.nio.ByteBuffer

class VideoFileLoader(private val context: Context) {

    fun extractFrameAtTime(uri: Uri, timeUs: Long): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            retriever.release()
        }
    }

    fun bitmapToYBuffer(bitmap: Bitmap): Pair<ByteBuffer, Int> {
        val width = bitmap.width
        val height = bitmap.height
        val totalPixels = width * height

        val pixels = IntArray(totalPixels)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val yBuffer = ByteBuffer.allocateDirect(totalPixels)
        for (i in 0 until totalPixels) {
            val color = pixels[i]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            // Grayscale Rec. 601 formula
            val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            yBuffer.put(lum.toByte())
        }
        yBuffer.rewind()
        return Pair(yBuffer, width)
    }
}

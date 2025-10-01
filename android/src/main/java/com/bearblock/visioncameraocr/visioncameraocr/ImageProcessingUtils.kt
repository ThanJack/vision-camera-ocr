package com.bearblock.visioncameraocr.visioncameraocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.graphics.ImageFormat
import com.mrousavy.camera.frameprocessors.Frame
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Utility class for image processing operations to improve OCR accuracy
 */
class ImageProcessingUtils {
    companion object {
        /**
         * Convert Frame to Bitmap with proper rotation
         */
        fun frameToBitmap(frame: Frame): Bitmap {
            val image = frame.image
            val planes = image.planes
            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val data = ByteArray(ySize + uSize + vSize)
            yBuffer.get(data, 0, ySize)
            uBuffer.get(data, ySize, uSize)
            vBuffer.get(data, ySize + uSize, vSize)

            val yuvImage = YuvImage(data, android.graphics.ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)
            val imageBytes = out.toByteArray()
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            
            // Apply rotation based on frame info
            val matrix = Matrix()
            matrix.postRotate(frame.imageProxy.imageInfo.rotationDegrees.toFloat())
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }

        /**
         * Enhance image for better OCR
         */
        fun enhanceImageForOcr(bitmap: Bitmap): Bitmap {
            // Create a mutable copy of the bitmap
            val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            
            // Convert to grayscale for better text recognition
            val canvas = Canvas(result)
            val paint = Paint()
            val colorMatrix = ColorMatrix()
            colorMatrix.setSaturation(0f) // Grayscale
            paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
            canvas.drawBitmap(result, 0f, 0f, paint)

            // Increase contrast
            enhanceContrast(result, 1.5f)
            
            return result
        }
        
        /**
         * Adjust contrast of the bitmap
         */
        private fun enhanceContrast(bitmap: Bitmap, contrast: Float) {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            
            for (i in pixels.indices) {
                val pixel = pixels[i]
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                
                // Apply contrast adjustment
                val newR = min(255, max(0, ((r.toFloat() - 128) * contrast + 128).toInt()))
                val newG = min(255, max(0, ((g.toFloat() - 128) * contrast + 128).toInt()))
                val newB = min(255, max(0, ((b.toFloat() - 128) * contrast + 128).toInt()))
                
                pixels[i] = Color.rgb(newR, newG, newB)
            }
            
            bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }

        /**
         * Try different processing settings and return the best result
         */
        fun tryMultipleProcessingMethods(bitmap: Bitmap): List<Bitmap> {
            val results = mutableListOf<Bitmap>()
            
            // Original bitmap
            bitmap.config?.let {
                results.add(bitmap.copy(it, true))
                
                // Grayscale with contrast
                val grayscale = bitmap.copy(it, true)
                val canvas = Canvas(grayscale)
                val paint = Paint()
                val colorMatrix = ColorMatrix()
                colorMatrix.setSaturation(0f)
                paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
                canvas.drawBitmap(grayscale, 0f, 0f, paint)
                enhanceContrast(grayscale, 1.5f)
                results.add(grayscale)
                
                // High contrast version
                val highContrast = bitmap.copy(it, true)
                enhanceContrast(highContrast, 2f)
                results.add(highContrast)
            } ?: run {
                // Fallback if config is null - create a copy in ARGB_8888 format
                val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                results.add(copy)
            }

            // Different resolutions can help with recognition
            val scaled = Bitmap.createScaledBitmap(bitmap, bitmap.width * 2, bitmap.height * 2, true)
            results.add(scaled)

            return results
        }
    }
}
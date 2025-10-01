package com.bearblock.visioncameraocr.visioncameraocr

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.media.Image
import android.util.Log
import com.facebook.react.bridge.WritableNativeArray
import com.facebook.react.bridge.WritableNativeMap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.mrousavy.camera.frameprocessors.Frame
import com.mrousavy.camera.frameprocessors.FrameProcessorPlugin
import com.mrousavy.camera.frameprocessors.VisionCameraProxy
import java.util.HashMap
import kotlin.math.max
import com.bearblock.visioncameraocr.visioncameraocr.ImageProcessingUtils

class OcrFrameProcessorPlugin(
  proxy: VisionCameraProxy,
  options: Map<String, Any>?
) : FrameProcessorPlugin() {

  // Create custom recognizer with optimized settings
  private val recognizerOptions = TextRecognizerOptions.Builder()
    // Use the most accurate model available
    .build()
  
  private val recognizer = TextRecognition.getClient(recognizerOptions)

  override fun callback(frame: Frame, arguments: Map<String, Any>?): HashMap<String, Any>? {
    val data = WritableNativeMap()

    // Read call-time arguments
    val includeBoxes = (arguments?.get("includeBoxes") as? Boolean) ?: false
    val includeConfidence = (arguments?.get("includeConfidence") as? Boolean) ?: false
    val useImageProcessing = (arguments?.get("useImageProcessing") as? Boolean) ?: true
    val multipleAttempts = (arguments?.get("multipleAttempts") as? Boolean) ?: true
    Log.d("OcrDetector", "Args includeBoxes=$includeBoxes includeConfidence=$includeConfidence useImageProcessing=$useImageProcessing")

    try {
      // Get bitmap from frame for image processing
      var resultText: Text? = null
      var bestConfidence = 0.0
      
      if (useImageProcessing) {
        // Convert frame to bitmap for enhanced processing
        val originalBitmap = ImageProcessingUtils.frameToBitmap(frame)
        
        if (multipleAttempts) {
          // Try multiple image processing methods and select the best result
          val processedImages = ImageProcessingUtils.tryMultipleProcessingMethods(originalBitmap)
          Log.d("OcrDetector", "Trying ${processedImages.size} different processing methods")
          
          for (processedBitmap in processedImages) {
            val processedImage = InputImage.fromBitmap(processedBitmap, 0)
            val currentText = Tasks.await(recognizer.process(processedImage))
            
            // Simple heuristic: longer text recognition is typically more accurate
            val confidence = calculateConfidence(currentText)
            Log.d("OcrDetector", "Processing method confidence: $confidence, text length: ${currentText.text.length}")
            
            if (resultText == null || confidence > bestConfidence) {
              resultText = currentText
              bestConfidence = confidence
            }
            
            // If we get a good result, no need to try other methods
            if (bestConfidence > 0.8) {
              break
            }
          }
        } else {
          // Just use enhanced processing on the original image
          val enhancedBitmap = ImageProcessingUtils.enhanceImageForOcr(originalBitmap)
          val processedImage = InputImage.fromBitmap(enhancedBitmap, 0)
          resultText = Tasks.await(recognizer.process(processedImage))
        }
      } else {
        // Use original method with direct media image
        val mediaImage: Image = frame.image
        val image = InputImage.fromMediaImage(mediaImage, frame.imageProxy.imageInfo.rotationDegrees)
        resultText = Tasks.await(recognizer.process(image))
      }

      // If no text was detected with any method
      if (resultText == null || resultText.text.isEmpty()) {
        return null
      }

      data.putString("text", resultText!!.text)
      
      // Add overall confidence score
      if (includeConfidence) {
        data.putDouble("confidence", bestConfidence)
      }

      if (includeBoxes) {
        val blocksArray = WritableNativeArray()
        for (block in resultText!!.textBlocks) {
          val blockMap = WritableNativeMap()
          blockMap.putString("text", block.text)
          
          // Calculate block-level confidence
          val blockConfidence = calculateBlockConfidence(block)
          if (includeConfidence) {
            blockMap.putDouble("confidence", blockConfidence)
          }

          val blockRect: Rect? = block.boundingBox
          if (blockRect != null) {
            val boxMap = WritableNativeMap()
            boxMap.putDouble("x", blockRect.left.toDouble())
            boxMap.putDouble("y", blockRect.top.toDouble())
            boxMap.putDouble("width", (blockRect.right - blockRect.left).toDouble())
            boxMap.putDouble("height", (blockRect.bottom - blockRect.top).toDouble())
            blockMap.putMap("box", boxMap)
          }

          val linesArray = WritableNativeArray()
          for (line in block.lines) {
            val lineMap = WritableNativeMap()
            lineMap.putString("text", line.text)

            val lineRect: Rect? = line.boundingBox
            if (lineRect != null) {
              val lbox = WritableNativeMap()
              lbox.putDouble("x", lineRect.left.toDouble())
              lbox.putDouble("y", lineRect.top.toDouble())
              lbox.putDouble("width", (lineRect.right - lineRect.left).toDouble())
              lbox.putDouble("height", (lineRect.bottom - lineRect.top).toDouble())
              lineMap.putMap("box", lbox)
            }

            val wordsArray = WritableNativeArray()
            for (element in line.elements) {
              val wordMap = WritableNativeMap()
              wordMap.putString("text", element.text)
              val wRect: Rect? = element.boundingBox
              if (wRect != null) {
                val wbox = WritableNativeMap()
                wbox.putDouble("x", wRect.left.toDouble())
                wbox.putDouble("y", wRect.top.toDouble())
                wbox.putDouble("width", (wRect.right - wRect.left).toDouble())
                wbox.putDouble("height", (wRect.bottom - wRect.top).toDouble())
                wordMap.putMap("box", wbox)
              }
              wordsArray.pushMap(wordMap)
            }
            if (wordsArray.size() > 0) {
              lineMap.putArray("words", wordsArray)
            }
            linesArray.pushMap(lineMap)
          }
          if (linesArray.size() > 0) {
            blockMap.putArray("lines", linesArray)
          }
          blocksArray.pushMap(blockMap)
        }
        data.putArray("blocks", blocksArray)
      }

      @Suppress("UNCHECKED_CAST")
      return data.toHashMap() as HashMap<String, Any>
    } catch (e: Exception) {
      Log.e("OcrDetector", "OCR recognition error: ${e.localizedMessage}")
      e.printStackTrace()
      return null
    }
  }
  
  /**
   * Calculate confidence score for text recognition result
   * Since MLKit doesn't provide confidence scores directly, we use heuristics:
   * 1. Text length - longer text usually indicates better recognition
   * 2. Number of blocks and lines - more structured text usually means better recognition
   * 3. Presence of bounding boxes - if MLKit can detect boxes, recognition is typically better
   */
  private fun calculateConfidence(text: Text): Double {
    if (text.text.isEmpty()) {
      return 0.0
    }
    
    var confidence = 0.0
    
    // Base confidence on text length
    confidence += min(text.text.length / 50.0, 0.5)
    
    // More blocks and lines usually indicates better structure recognition
    val numBlocks = text.textBlocks.size
    confidence += min(numBlocks / 10.0, 0.2)
    
    // Check if we have proper bounding boxes (better detection)
    var hasBoxes = 0
    for (block in text.textBlocks) {
      if (block.boundingBox != null) {
        hasBoxes++
      }
    }
    confidence += if (hasBoxes > 0) min(hasBoxes / numBlocks.toDouble(), 0.3) else 0.0
    
    // Add bonus for consistent formatting
    if (numBlocks >= 3 && hasConsistentFormatting(text)) {
      confidence += 0.1
    }
    
    return min(confidence, 1.0)
  }
  
  /**
   * Calculate confidence for a specific text block
   */
  private fun calculateBlockConfidence(block: Text.TextBlock): Double {
    if (block.text.isEmpty()) {
      return 0.0
    }
    
    var confidence = 0.5 // Start with base confidence
    
    // More lines usually means better recognition
    confidence += min(block.lines.size / 10.0, 0.2)
    
    // Check for presence of bounding box
    if (block.boundingBox != null) {
      confidence += 0.1
    }
    
    // Word count affects confidence
    var wordCount = 0
    for (line in block.lines) {
      wordCount += line.elements.size
    }
    confidence += min(wordCount / 20.0, 0.2)
    
    return min(confidence, 1.0)
  }
  
  /**
   * Check if text blocks have consistent formatting (likely indicates good recognition)
   */
  private fun hasConsistentFormatting(text: Text): Boolean {
    val blocks = text.textBlocks
    if (blocks.size < 2) return true
    
    // Check for consistent line counts and heights
    val lineCountPattern = mutableListOf<Int>()
    val heightPattern = mutableListOf<Int>()
    
    for (block in blocks) {
      lineCountPattern.add(block.lines.size)
      block.boundingBox?.let { heightPattern.add(it.height()) }
    }
    
    // Simple variance check
    return hasLowVariance(lineCountPattern) || hasLowVariance(heightPattern)
  }
  
  /**
   * Check if a list of integers has low variance (consistent values)
   */
  private fun hasLowVariance(values: List<Int>): Boolean {
    if (values.isEmpty()) return true
    
    val mean = values.average()
    var variance = 0.0
    
    for (value in values) {
      variance += (value - mean) * (value - mean)
    }
    
    variance /= values.size
    
    // If coefficient of variation is less than 0.5, consider it consistent
    return Math.sqrt(variance) / mean < 0.5
  }
  
  private fun min(a: Double, b: Double): Double = if (a < b) a else b
}

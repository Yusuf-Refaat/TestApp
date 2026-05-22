package com.example

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

object GeminiImageDetector {

    private const val TAG = "GeminiImageDetector"
    private const val MODEL_NAME = "gemini-3.5-flash"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_NAME:generateContent"

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Data class to hold Gemini logo detection bounding box.
     * Coordinate fields are normalized [0.0..1.0] relative to bitmap dimension.
     */
    data class DetectionResult(
        val hasWatermark: Boolean,
        val ymin: Float = 0f,
        val xmin: Float = 0f,
        val ymax: Float = 0f,
        val xmax: Float = 0f,
        val label: String = ""
    )

    /**
     * Converts a Bitmap to compressed Base64 string.
     */
    private fun Bitmap.toBase64(): String {
        val outputStream = ByteArrayOutputStream()
        // Resize large images slightly for faster network uploads and less token consumption
        val scale = if (width > 1024 || height > 1024) {
            val ratio = width.toFloat() / height.toFloat()
            if (width > height) {
                Pair(1024, (1024 / ratio).toInt())
            } else {
                Pair((1024 * ratio).toInt(), 1024)
            }
        } else {
            Pair(width, height)
        }

        val resized = Bitmap.createScaledBitmap(this, scale.first, scale.second, true)
        resized.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * Performs a direct REST call to Gemini 3.5 Flash to automatically detect the logo bounding box
     */
    suspend fun detectLogoBoundingBox(bitmap: Bitmap): DetectionResult = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            Log.w(TAG, "Gemini API Key is empty or placeholder!")
            return@withContext DetectionResult(false, label = "API Key is missing")
        }

        try {
            val base64Image = bitmap.toBase64()

            // Build request object with native JSONObject
            val partText = JSONObject().apply {
                put("text", """
                    Analyze this image and identify the exact location/bounding coordinates of any visible Gemini logo, Google logo, or overlay watermark text (such as "Generated with Gemini", "Made by Gemini", "Google AI", or the star/sparkle Gemini emblem).
                    You must locate the watermark.
                    Return a JSON object in this format:
                    {
                      "hasWatermark": true,
                      "box": {
                        "ymin": 0.85,
                        "xmin": 0.05,
                        "ymax": 0.95,
                        "xmax": 0.35
                      },
                      "label": "Gemini Watermark"
                    }
                    All coordinate values are floats between 0.0 and 1.0 (where 0.0,0.0 is top-left, and 1.0,1.0 is bottom-right).
                    If no logo or watermark is present, return:
                    {
                      "hasWatermark": false
                    }
                """.trimIndent())
            }

            val partImage = JSONObject().apply {
                put("inlineData", JSONObject().apply {
                    put("mimeType", "image/jpeg")
                    put("data", base64Image)
                })
            }

            val partsArray = JSONArray().apply {
                put(partText)
                put(partImage)
            }

            val contentObject = JSONObject().apply {
                put("parts", partsArray)
            }

            val contentsArray = JSONArray().apply {
                put(contentObject)
            }

            // Demand structured JSON output configuration
            val generationConfig = JSONObject().apply {
                put("responseMimeType", "application/json")
                put("temperature", 0.1) // Low temperature for higher accuracy in detection
            }

            val mainRequestBody = JSONObject().apply {
                put("contents", contentsArray)
                put("generationConfig", generationConfig)
            }

            val requestBodyString = mainRequestBody.toString()
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = requestBodyString.toRequestBody(mediaType)

            val url = "$BASE_URL?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .header("Content-Type", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                Log.e(TAG, "Request failed with code ${response.code}: $errBody")
                return@withContext DetectionResult(false, label = "HTTP Error ${response.code}")
            }

            val responseBody = response.body?.string()
            if (responseBody.isNullOrEmpty()) {
                return@withContext DetectionResult(false, label = "Empty Server Response")
            }

            // Parse response
            val root = JSONObject(responseBody)
            val candidates = root.optJSONArray("candidates")
            val firstCandidate = candidates?.optJSONObject(0)
            val content = firstCandidate?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            val firstPart = parts?.optJSONObject(0)
            val rawText = firstPart?.optString("text")

            if (rawText.isNullOrEmpty()) {
                return@withContext DetectionResult(false, label = "No analysis text found")
            }

            Log.d(TAG, "Gemini Response Raw Text: $rawText")

            val jsonOutput = JSONObject(rawText.trim())
            val hasWatermark = jsonOutput.optBoolean("hasWatermark", false)

            if (hasWatermark) {
                val box = jsonOutput.optJSONObject("box")
                if (box != null) {
                    val ymin = box.optDouble("ymin", 0.0).toFloat()
                    val xmin = box.optDouble("xmin", 0.0).toFloat()
                    val ymax = box.optDouble("ymax", 0.0).toFloat()
                    val xmax = box.optDouble("xmax", 0.0).toFloat()
                    val label = jsonOutput.optString("label", "Gemini Watermark")
                    return@withContext DetectionResult(
                        hasWatermark = true,
                        ymin = ymin,
                        xmin = xmin,
                        ymax = ymax,
                        xmax = xmax,
                        label = label
                    )
                }
            }

            return@withContext DetectionResult(false, label = "No watermark located")

        } catch (e: Exception) {
            Log.e(TAG, "Detection calculation exception", e)
            return@withContext DetectionResult(false, label = "Error: ${e.localizedMessage}")
        }
    }
}

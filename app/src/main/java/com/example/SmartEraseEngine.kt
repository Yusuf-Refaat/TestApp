package com.example

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import kotlin.math.sqrt

object SmartEraseEngine {

    /**
     * Erases the masked area in [original] by interpolating from unmasked boundary pixels.
     * Uses an optimized Shepard's Inverse Distance Weighting interpolation.
     */
    fun smartInpaint(original: Bitmap, mask: Bitmap): Bitmap {
        val width = original.width
        val height = original.height
        val output = original.copy(Bitmap.Config.ARGB_8888, true)

        // Find the bounding box of the mask to optimize search and processing space
        var minX = width
        var maxX = 0
        var minY = height
        var maxY = 0
        var hasMask = false

        val maskPixels = IntArray(width * height)
        mask.getPixels(maskPixels, 0, width, 0, 0, width, height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val maskColor = maskPixels[y * width + x]
                // If red or opaque (depending on mask painting)
                val isMasked = AndroidColor.alpha(maskColor) > 50 && AndroidColor.red(maskColor) > 100
                if (isMasked) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    hasMask = true
                }
            }
        }

        if (!hasMask) return output

        // Add some padding to the bounding box
        val pad = 15
        minX = (minX - pad).coerceAtLeast(0)
        maxX = (maxX + pad).coerceAtMost(width - 1)
        minY = (minY - pad).coerceAtLeast(0)
        maxY = (maxY + pad).coerceAtMost(height - 1)

        val outputPixels = IntArray(width * height)
        output.getPixels(outputPixels, 0, width, 0, 0, width, height)

        // Precompute whether pixels are masked inside the bounding box
        val isMaskedArray = BooleanArray(width * height)
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val idx = y * width + x
                val mColor = maskPixels[idx]
                isMaskedArray[idx] = AndroidColor.alpha(mColor) > 50 && AndroidColor.red(mColor) > 100
            }
        }

        // We will perform multi-directional search for unmasked boundary colors
        // Directions: [dx, dy]
        val dirs = arrayOf(
            Pair(0, -1),   // Up
            Pair(0, 1),    // Down
            Pair(-1, 0),   // Left
            Pair(1, 0),    // Right
            Pair(-1, -1),  // Up-Left
            Pair(1, -1),   // Up-Right
            Pair(-1, 1),   // Down-Left
            Pair(1, 1)     // Down-Right
        )

        val maxDist = 32 // Max search search radius for speed and precision

        // For each masked pixel, compute the inverse-distance weighted average of boundary pixels
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val idx = y * width + x
                if (isMaskedArray[idx]) {
                    var totalWeight = 0.0
                    var sumR = 0.0
                    var sumG = 0.0
                    var sumB = 0.0

                    // Find unmasked boundary pixel along 8 radial directions
                    for (dir in dirs) {
                        val dx = dir.first
                        val dy = dir.second
                        var step = 1
                        var found = false
                        while (step <= maxDist) {
                            val nx = x + dx * step
                            val ny = y + dy * step
                            if (nx in 0 until width && ny in 0 until height) {
                                val nIdx = ny * width + nx
                                if (!isMaskedArray[nIdx]) {
                                    val dist = sqrt((dx * step * dx * step + dy * step * dy * step).toFloat()).toDouble()
                                    val weight = 1.0 / (dist * dist) // Inverse distance squared
                                    val color = outputPixels[nIdx]

                                    sumR += AndroidColor.red(color) * weight
                                    sumG += AndroidColor.green(color) * weight
                                    sumB += AndroidColor.blue(color) * weight
                                    totalWeight += weight

                                    found = true
                                    break
                                }
                            } else {
                                break // Out of bounds
                            }
                            step++
                        }
                        
                        // Fallback to nearest border if no unmasked pixel found along this line
                        if (!found) {
                            // Find any neighboring coordinates to patch
                            val nx = (x + dx * maxDist).coerceIn(0, width - 1)
                            val ny = (y + dy * maxDist).coerceIn(0, height - 1)
                            val nIdx = ny * width + nx
                            if (!isMaskedArray[nIdx]) {
                                val weight = 1.0 / (maxDist * maxDist)
                                val color = outputPixels[nIdx]
                                sumR += AndroidColor.red(color) * weight
                                sumG += AndroidColor.green(color) * weight
                                sumB += AndroidColor.blue(color) * weight
                                totalWeight += weight
                            }
                        }
                    }

                    if (totalWeight > 0) {
                        val outR = (sumR / totalWeight).toInt().coerceIn(0, 255)
                        val outG = (sumG / totalWeight).toInt().coerceIn(0, 255)
                        val outB = (sumB / totalWeight).toInt().coerceIn(0, 255)
                        outputPixels[idx] = AndroidColor.rgb(outR, outG, outB)
                    }
                }
            }
        }

        output.setPixels(outputPixels, 0, width, 0, 0, width, height)
        return output
    }

    /**
     * Blurs the masked areas using a simple, fast Box Blur.
     */
    fun smartBlur(original: Bitmap, mask: Bitmap, blurRadius: Int = 12): Bitmap {
        val width = original.width
        val height = original.height
        val output = original.copy(Bitmap.Config.ARGB_8888, true)

        val maskPixels = IntArray(width * height)
        mask.getPixels(maskPixels, 0, width, 0, 0, width, height)

        val outputPixels = IntArray(width * height)
        output.getPixels(outputPixels, 0, width, 0, 0, width, height)

        val originalPixels = IntArray(width * height)
        original.getPixels(originalPixels, 0, width, 0, 0, width, height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val mColor = maskPixels[idx]
                val isMasked = AndroidColor.alpha(mColor) > 50 && AndroidColor.red(mColor) > 100

                if (isMasked) {
                    var sumR = 0
                    var sumG = 0
                    var sumB = 0
                    var count = 0

                    for (ky in -blurRadius..blurRadius) {
                        for (kx in -blurRadius..blurRadius) {
                            val px = (x + kx).coerceIn(0, width - 1)
                            val py = (y + ky).coerceIn(0, height - 1)
                            val pColor = originalPixels[py * width + px]
                            sumR += AndroidColor.red(pColor)
                            sumG += AndroidColor.green(pColor)
                            sumB += AndroidColor.blue(pColor)
                            count++
                        }
                    }

                    if (count > 0) {
                        outputPixels[idx] = AndroidColor.rgb(sumR / count, sumG / count, sumB / count)
                    }
                }
            }
        }

        output.setPixels(outputPixels, 0, width, 0, 0, width, height)
        return output
    }

    /**
     * Fills the masked areas with a solid color.
     */
    fun solidFill(original: Bitmap, mask: Bitmap, fillArgb: Int): Bitmap {
        val width = original.width
        val height = original.height
        val output = original.copy(Bitmap.Config.ARGB_8888, true)

        val maskPixels = IntArray(width * height)
        mask.getPixels(maskPixels, 0, width, 0, 0, width, height)

        val outputPixels = IntArray(width * height)
        output.getPixels(outputPixels, 0, width, 0, 0, width, height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val mColor = maskPixels[idx]
                val isMasked = AndroidColor.alpha(mColor) > 50 && AndroidColor.red(mColor) > 100

                if (isMasked) {
                    outputPixels[idx] = fillArgb
                }
            }
        }

        output.setPixels(outputPixels, 0, width, 0, 0, width, height)
        return output
    }
}

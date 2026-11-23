package com.headmon.app.thermal

import com.headmon.app.util.AppLogger
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

data class ThermalBoundingBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = (right - left + 1).coerceAtLeast(0)
    val height: Int get() = (bottom - top + 1).coerceAtLeast(0)
}

data class ThermalMetrics(
    val yawDeg: Float,
    val pitchDeg: Float,
    val rollDeg: Float,
    val dxNorm: Float,
    val dyNorm: Float,
    val confidence: Float,
    val areaRatio: Float,
    val bbox: ThermalBoundingBox,
    val meanBrightness: Float,
    val threshold: Float
)

sealed interface ThermalAnalysisResult {
    data class Detected(val metrics: ThermalMetrics) : ThermalAnalysisResult
    data object NoHead : ThermalAnalysisResult
    data object Ffc : ThermalAnalysisResult
    data class Error(val message: String) : ThermalAnalysisResult
}

/**
 * MVP-анализатор тепловизионного кадра.
 *
 * Пайплайн:
 *  1. выделение Y-канала и downscale;
 *  2. детекция FFC (глобального скачка яркости);
 *  3. пороговая сегментация самого яркого устойчивого пятна;
 *  4. центр, bounding box, ориентация и угловые прокси;
 *  5. сглаживание.
 *
 * OpenCV на этом этапе не используется — алгоритм намеренно простой,
 * чтобы проверить работу камеры и оценить качество на реальных кадрах.
 */
class ThermalFrameAnalyzer(
    private val targetWidth: Int = 160,
    private val horizontalFovDeg: Float = 50f,
    private val ffcMeanJump: Float = 10f,
    private val ffcSkipFrames: Int = 5,
    private val smoothingAlpha: Float = 0.35f
) {
    private class GrayFrame(
        val width: Int,
        val height: Int,
        val pixels: ByteArray
    )

    private data class FrameStats(
        val mean: Float,
        val std: Float,
        val histogram: IntArray
    )

    private data class Component(
        val count: Int,
        val sumX: Long,
        val sumY: Long,
        val sumValue: Long,
        val sumXX: Long,
        val sumYY: Long,
        val sumXY: Long,
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int,
        val score: Float
    )

    private var emaMean = -1f
    private var emaStd = 0f

    private var previousGray: ByteArray? = null
    private var previousWidth = 0
    private var previousHeight = 0

    private var skipFrames = 0
    private var missedFrames = 0

    private var hasSmoothing = false
    private var smoothYaw = 0f
    private var smoothPitch = 0f
    private var smoothRoll = 0f
    private var smoothDx = 0f
    private var smoothDy = 0f

    private var lastLogMs = 0L

    fun analyze(frame: ThermalFrame): ThermalAnalysisResult {
        val gray = downsampleLuma(frame)
            ?: return ThermalAnalysisResult.Error("Некорректный размер NV21-кадра")

        val stats = computeStats(gray)
        val ffcDetected = isFfc(gray, stats)

        if (ffcDetected) {
            updateEma(stats, hardReset = true)
            storePrevious(gray)
            skipFrames = ffcSkipFrames
            resetSmoothing()
            AppLogger.log(
                "THERMAL_FFC",
                "FFC detected: mean=${"%.1f".format(stats.mean)}, " +
                    "emaMean=${"%.1f".format(emaMean)}"
            )
            return ThermalAnalysisResult.Ffc
        }

        updateEma(stats, hardReset = false)
        storePrevious(gray)

        if (skipFrames > 0) {
            skipFrames--
            return ThermalAnalysisResult.Ffc
        }

        val blob = findBrightestBlob(gray, stats)
        if (blob == null) {
            missedFrames++
            if (missedFrames >= 3) {
                resetSmoothing()
            }
            return ThermalAnalysisResult.NoHead
        }

        missedFrames = 0
        val metrics = buildMetrics(gray, blob, stats)
        val smoothed = smooth(metrics)

        val now = System.currentTimeMillis()
        if (now - lastLogMs > 2_000) {
            lastLogMs = now
            AppLogger.log(
                "THERMAL_DETECT",
                "yaw=${"%.1f".format(smoothed.yawDeg)}, pitch=${"%.1f".format(smoothed.pitchDeg)}, " +
                    "roll=${"%.1f".format(smoothed.rollDeg)}, conf=${"%.2f".format(smoothed.confidence)}, " +
                    "area=${"%.3f".format(smoothed.areaRatio)}"
            )
        }

        return ThermalAnalysisResult.Detected(smoothed)
    }

    fun reset() {
        emaMean = -1f
        emaStd = 0f
        previousGray = null
        previousWidth = 0
        previousHeight = 0
        skipFrames = 0
        missedFrames = 0
        resetSmoothing()
    }

    private fun downsampleLuma(frame: ThermalFrame): GrayFrame? {
        val yPlaneSize = frame.width * frame.height
        if (frame.width <= 0 || frame.height <= 0 || frame.data.size < yPlaneSize) {
            return null
        }

        val step = max(1, frame.width / targetWidth)
        val width = frame.width / step
        val height = frame.height / step
        if (width <= 0 || height <= 0) return null

        val out = ByteArray(width * height)
        var dst = 0
        var srcY = 0
        while (srcY < frame.height) {
            var srcX = 0
            while (srcX < frame.width) {
                out[dst++] = frame.data[srcY * frame.width + srcX]
                srcX += step
            }
            srcY += step
        }
        return GrayFrame(width, height, out)
    }

    private fun computeStats(gray: GrayFrame): FrameStats {
        val pixels = gray.pixels
        val histogram = IntArray(256)
        var sum = 0.0
        var sumSq = 0.0

        for (value in pixels) {
            val v = value.toInt() and 0xFF
            histogram[v]++
            sum += v
            sumSq += v * v
        }

        val n = pixels.size.toDouble()
        val mean = (sum / n).toFloat()
        val variance = (sumSq / n - mean * mean).coerceAtLeast(0.0)
        return FrameStats(mean, sqrt(variance).toFloat(), histogram)
    }

    private fun isFfc(gray: GrayFrame, stats: FrameStats): Boolean {
        val prev = previousGray
        if (emaMean < 0f || prev == null || prev.size != gray.pixels.size ||
            previousWidth != gray.width || previousHeight != gray.height
        ) {
            return false
        }

        val meanJump = abs(stats.mean - emaMean)
        if (meanJump <= ffcMeanJump) {
            return false
        }

        var absDiffSum = 0.0
        var absDiffSqSum = 0.0
        val current = gray.pixels
        for (i in current.indices) {
            val diff = abs((current[i].toInt() and 0xFF) - (prev[i].toInt() and 0xFF)).toDouble()
            absDiffSum += diff
            absDiffSqSum += diff * diff
        }

        val n = current.size.toDouble()
        val absDiffMean = (absDiffSum / n).toFloat()
        val absDiffVariance = (absDiffSqSum / n - absDiffMean * absDiffMean).coerceAtLeast(0.0)
        val absDiffStd = sqrt(absDiffVariance).toFloat()

        val uniformShift = absDiffMean > 5f && absDiffStd < max(3f, absDiffMean * 0.35f)
        val largeUniformJump = meanJump > ffcMeanJump * 2f &&
            absDiffMean > 8f &&
            absDiffStd < max(6f, absDiffMean * 0.6f)

        return uniformShift || largeUniformJump
    }

    private fun updateEma(stats: FrameStats, hardReset: Boolean) {
        if (hardReset || emaMean < 0f) {
            emaMean = stats.mean
            emaStd = stats.std
            return
        }
        emaMean = emaMean * 0.8f + stats.mean * 0.2f
        emaStd = emaStd * 0.8f + stats.std * 0.2f
    }

    private fun storePrevious(gray: GrayFrame) {
        previousGray = gray.pixels
        previousWidth = gray.width
        previousHeight = gray.height
    }

    private fun findBrightestBlob(gray: GrayFrame, stats: FrameStats): Component? {
        val pixels = gray.pixels
        val width = gray.width
        val height = gray.height
        val total = pixels.size

        val threshold = computeThreshold(stats, total)
        val thresholdInt = threshold.toInt()

        val visited = BooleanArray(total)
        val stack = IntArray(total)
        val minArea = max(12, total / 1_500)
        val maxArea = (total * 0.7f).toInt()

        var best: Component? = null

        for (index in 0 until total) {
            if (visited[index]) continue
            val value = pixels[index].toInt() and 0xFF
            if (value < thresholdInt) continue

            var stackTop = 0
            stack[stackTop++] = index
            visited[index] = true

            var count = 0
            var sumX = 0L
            var sumY = 0L
            var sumValue = 0L
            var sumXX = 0L
            var sumYY = 0L
            var sumXY = 0L
            var minX = width
            var maxX = -1
            var minY = height
            var maxY = -1

            while (stackTop > 0) {
                val current = stack[--stackTop]
                val x = current % width
                val y = current / width
                val v = pixels[current].toInt() and 0xFF

                count++
                sumX += x
                sumY += y
                sumValue += v
                sumXX += (x * x).toLong()
                sumYY += (y * y).toLong()
                sumXY += (x * y).toLong()
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y

                if (x > 0) {
                    val neighbor = current - 1
                    if (!visited[neighbor] && (pixels[neighbor].toInt() and 0xFF) >= thresholdInt) {
                        visited[neighbor] = true
                        stack[stackTop++] = neighbor
                    }
                }
                if (x < width - 1) {
                    val neighbor = current + 1
                    if (!visited[neighbor] && (pixels[neighbor].toInt() and 0xFF) >= thresholdInt) {
                        visited[neighbor] = true
                        stack[stackTop++] = neighbor
                    }
                }
                if (y > 0) {
                    val neighbor = current - width
                    if (!visited[neighbor] && (pixels[neighbor].toInt() and 0xFF) >= thresholdInt) {
                        visited[neighbor] = true
                        stack[stackTop++] = neighbor
                    }
                }
                if (y < height - 1) {
                    val neighbor = current + width
                    if (!visited[neighbor] && (pixels[neighbor].toInt() and 0xFF) >= thresholdInt) {
                        visited[neighbor] = true
                        stack[stackTop++] = neighbor
                    }
                }
            }

            if (count < minArea || count > maxArea) {
                continue
            }

            val meanValue = sumValue.toFloat() / count
            val score = count * (meanValue - threshold + 1f)
            if (score <= 0f) continue

            val component = Component(
                count = count,
                sumX = sumX,
                sumY = sumY,
                sumValue = sumValue,
                sumXX = sumXX,
                sumYY = sumYY,
                sumXY = sumXY,
                minX = minX,
                maxX = maxX,
                minY = minY,
                maxY = maxY,
                score = score
            )
            if (best == null || component.score > best.score) {
                best = component
            }
        }

        return best
    }

    private fun buildMetrics(
        gray: GrayFrame,
        component: Component,
        stats: FrameStats
    ): ThermalMetrics {
        val width = gray.width
        val height = gray.height
        val total = width * height

        val cx = component.sumX.toFloat() / component.count
        val cy = component.sumY.toFloat() / component.count
        val meanValue = component.sumValue.toFloat() / component.count

        val mu20 = component.sumXX.toFloat() / component.count - cx * cx
        val mu02 = component.sumYY.toFloat() / component.count - cy * cy
        val mu11 = component.sumXY.toFloat() / component.count - cx * cy
        val rollRad = 0.5 * atan2(2.0 * mu11.toDouble(), (mu20 - mu02).toDouble())
        val rollDeg = Math.toDegrees(rollRad).toFloat()

        val dxNorm = cx / width - 0.5f
        val dyNorm = cy / height - 0.5f

        val hfovRad = Math.toRadians(horizontalFovDeg.toDouble())
        val vfovRad = 2.0 * atan(tan(hfovRad / 2.0) * height / width)
        val yawDeg = Math.toDegrees(
            atan(dxNorm * 2.0 * tan(hfovRad / 2.0))
        ).toFloat()
        val pitchDeg = Math.toDegrees(
            atan(dyNorm * 2.0 * tan(vfovRad / 2.0))
        ).toFloat()

        val areaRatio = component.count.toFloat() / total
        val threshold = computeThreshold(stats, total)
        val contrastScore = ((meanValue - threshold) / 30f).coerceIn(0f, 1f)
        val areaScore = (component.count / (total * 0.02f)).coerceIn(0f, 1f)
        val confidence = (0.4f * contrastScore + 0.6f * areaScore).coerceIn(0f, 1f)

        return ThermalMetrics(
            yawDeg = yawDeg,
            pitchDeg = pitchDeg,
            rollDeg = rollDeg,
            dxNorm = dxNorm,
            dyNorm = dyNorm,
            confidence = confidence,
            areaRatio = areaRatio,
            bbox = ThermalBoundingBox(
                left = component.minX,
                top = component.minY,
                right = component.maxX,
                bottom = component.maxY
            ),
            meanBrightness = meanValue,
            threshold = threshold
        )
    }

    private fun smooth(metrics: ThermalMetrics): ThermalMetrics {
        if (!hasSmoothing) {
            smoothYaw = metrics.yawDeg
            smoothPitch = metrics.pitchDeg
            smoothRoll = metrics.rollDeg
            smoothDx = metrics.dxNorm
            smoothDy = metrics.dyNorm
            hasSmoothing = true
        } else {
            smoothYaw = smoothYaw + (metrics.yawDeg - smoothYaw) * smoothingAlpha
            smoothPitch = smoothPitch + (metrics.pitchDeg - smoothPitch) * smoothingAlpha
            smoothRoll = smoothRoll + (metrics.rollDeg - smoothRoll) * smoothingAlpha
            smoothDx = smoothDx + (metrics.dxNorm - smoothDx) * smoothingAlpha
            smoothDy = smoothDy + (metrics.dyNorm - smoothDy) * smoothingAlpha
        }

        return metrics.copy(
            yawDeg = smoothYaw,
            pitchDeg = smoothPitch,
            rollDeg = smoothRoll,
            dxNorm = smoothDx,
            dyNorm = smoothDy
        )
    }

    private fun resetSmoothing() {
        hasSmoothing = false
        smoothYaw = 0f
        smoothPitch = 0f
        smoothRoll = 0f
        smoothDx = 0f
        smoothDy = 0f
    }

    private fun computeThreshold(stats: FrameStats, total: Int): Float {
        val percentile80 = percentile(stats.histogram, total, 0.80f).toFloat()
        return max(
            max(percentile80, stats.mean + stats.std * 0.8f),
            stats.mean + 6f
        ).coerceIn(1f, 250f)
    }

    private fun percentile(histogram: IntArray, total: Int, fraction: Float): Int {
        val target = (total * fraction).toInt().coerceIn(0, total)
        var cumulative = 0
        for (value in histogram.indices) {
            cumulative += histogram[value]
            if (cumulative >= target) {
                return value
            }
        }
        return 255
    }
}

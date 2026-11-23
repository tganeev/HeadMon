package com.headmon.app.thermal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalFrameAnalyzerTest {

    private val width = 160
    private val height = 120

    @Test
    fun detectsBrightBlobAndCentroid() {
        val data = uniformNv21(background = 10)
        fillRect(data, left = 70, top = 50, right = 89, bottom = 69, value = 200)
        val analyzer = ThermalFrameAnalyzer(targetWidth = width)

        val result = analyzer.analyze(frame(data))

        assertTrue("Ожидалось обнаружение пятна, получено $result", result is ThermalAnalysisResult.Detected)
        val metrics = (result as ThermalAnalysisResult.Detected).metrics
        assertEquals(70, metrics.bbox.left)
        assertEquals(50, metrics.bbox.top)
        assertEquals(89, metrics.bbox.right)
        assertEquals(69, metrics.bbox.bottom)
        assertTrue("dx должен быть около нуля: ${metrics.dxNorm}", kotlin.math.abs(metrics.dxNorm) < 0.02f)
        assertTrue("dy должен быть около нуля: ${metrics.dyNorm}", kotlin.math.abs(metrics.dyNorm) < 0.02f)
        assertTrue("Площадь пятна должна быть около 2%: ${metrics.areaRatio}", metrics.areaRatio in 0.015f..0.03f)
    }

    @Test
    fun detectsFfcAsGlobalBrightnessJump() {
        val first = uniformNv21(background = 10)
        fillRect(first, left = 70, top = 50, right = 89, bottom = 69, value = 200)

        val second = uniformNv21(background = 60)
        fillRect(second, left = 70, top = 50, right = 89, bottom = 69, value = 250)

        val analyzer = ThermalFrameAnalyzer(targetWidth = width)
        assertTrue(analyzer.analyze(frame(first)) is ThermalAnalysisResult.Detected)

        val result = analyzer.analyze(frame(second))
        assertEquals(ThermalAnalysisResult.Ffc, result)
    }

    @Test
    fun doesNotTreatLargeLocalizedChangeAsFfc() {
        val first = uniformNv21(background = 10)
        val second = uniformNv21(background = 10)
        for (y in 0 until height) {
            for (x in 0 until width / 2) {
                second[y * width + x] = 200.toByte()
            }
        }

        val analyzer = ThermalFrameAnalyzer(targetWidth = width)
        assertEquals(ThermalAnalysisResult.NoHead, analyzer.analyze(frame(first)))

        val result = analyzer.analyze(frame(second))
        assertTrue("Локальное изменение не должно считаться FFC: $result", result is ThermalAnalysisResult.Detected)
    }

    @Test
    fun returnsNoHeadOnUniformFrame() {
        val data = uniformNv21(background = 100)
        val analyzer = ThermalFrameAnalyzer(targetWidth = width)

        val result = analyzer.analyze(frame(data))
        assertEquals(ThermalAnalysisResult.NoHead, result)
    }

    private fun frame(data: ByteArray): ThermalFrame {
        return ThermalFrame(
            data = data,
            width = width,
            height = height,
            timestampMs = System.currentTimeMillis(),
            deviceName = "test"
        )
    }

    private fun uniformNv21(background: Int): ByteArray {
        return ByteArray(width * height * 3 / 2) { background.toByte() }
    }

    private fun fillRect(data: ByteArray, left: Int, top: Int, right: Int, bottom: Int, value: Int) {
        for (y in top..bottom) {
            for (x in left..right) {
                data[y * width + x] = value.toByte()
            }
        }
    }
}

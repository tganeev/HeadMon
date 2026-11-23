package com.headmon.app.thermal

/**
 * Кадр с тепловизора в формате NV21.
 *
 * [data] содержит Y-плоскость (width * height) и UV-плоскость.
 * Для MVP-анализа используется только яркостный канал Y.
 */
class ThermalFrame(
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val timestampMs: Long,
    val deviceName: String?
)

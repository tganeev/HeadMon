package com.headmon.app.tracking

/**
 * Унифицированный результат оценки положения головы.
 *
 * Для RGB-режима углы приходят из Face/Pose Landmarker.
 * Для тепловизора yaw/pitch являются угловыми прокси по смещению
 * центра теплового пятна, а roll — ориентацией пятна.
 */
data class HeadPoseFrame(
    val yaw: Float?,
    val pitch: Float?,
    val roll: Float?,
    val dx: Float?,
    val dy: Float?,
    val confidence: Float,
    val timestampMs: Long,
    val source: Source = Source.THERMAL
) {
    enum class Source {
        RGB,
        THERMAL
    }
}

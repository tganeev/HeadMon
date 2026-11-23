package com.headmon.app.tracking

/**
 * Источник данных о положении головы.
 *
 * Реализации: тепловизор (UVC), позже — RGB-камера через CameraX.
 * Позволяет вынести общую логику калибровки и тревог из UI.
 */
interface HeadTrackingSource {
    fun start()

    fun stop()

    fun setListener(listener: ((HeadPoseFrame?) -> Unit)?)
}

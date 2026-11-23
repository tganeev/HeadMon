package com.headmon.app.thermal

import android.content.Context
import android.hardware.usb.UsbDevice
import android.os.SystemClock
import com.headmon.app.util.AppLogger
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.utils.CameraUtils
import com.jiangdg.ausbc.widget.IAspectRatio
import com.jiangdg.usb.USBMonitor

/**
 * Обёртка над UVC-тепловизором из libausbc.
 *
 * Отвечает только за:
 *  - поиск USB-устройства и запрос разрешения;
 *  - открытие/закрытие камеры;
 *  - получение NV21-кадров;
 *  - сообщение о состоянии.
 *
 * Не занимается анализом изображения и UI.
 */
class ThermalCameraController(
    private val context: Context,
    private val listener: Listener,
    private val targetPreviewWidth: Int = 640,
    private val targetPreviewHeight: Int = 480
) {
    interface Listener {
        fun onFrame(frame: ThermalFrame)
        fun onState(state: State, message: String? = null)
        fun onDevice(deviceName: String?)
    }

    enum class State {
        IDLE,
        WAITING_FOR_DEVICE,
        REQUESTING_PERMISSION,
        OPENING,
        STREAMING,
        STOPPED,
        ERROR
    }

    private var client: MultiCameraClient? = null
    private val cameras = linkedMapOf<Int, MultiCameraClient.ICamera>()
    private var currentDeviceId: Int? = null
    private var previewView: IAspectRatio? = null

    @Volatile
    private var currentDeviceName: String? = null

    @Volatile
    private var isRunning = false

    private var lastFrameLogMs = 0L

    private val previewCallback = object : IPreviewDataCallBack {
        override fun onPreviewData(
            data: ByteArray?,
            width: Int,
            height: Int,
            format: IPreviewDataCallBack.DataFormat
        ) {
            if (!isRunning || data == null || width <= 0 || height <= 0) {
                return
            }
            if (data.size < width * height) {
                return
            }

            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameLogMs > 5_000) {
                lastFrameLogMs = now
                AppLogger.log("THERMAL_FRAME", "frame ${width}x${height}, format=$format")
            }

            listener.onFrame(
                ThermalFrame(
                    data = data,
                    width = width,
                    height = height,
                    timestampMs = now,
                    deviceName = currentDeviceName
                )
            )
        }
    }

    private val stateCallback = object : ICameraStateCallBack {
        override fun onCameraState(
            self: MultiCameraClient.ICamera,
            code: ICameraStateCallBack.State,
            msg: String?
        ) {
            val request = self.getCameraRequest()
            when (code) {
                ICameraStateCallBack.State.OPENED -> {
                    val size = request?.let { "${it.previewWidth}x${it.previewHeight}" } ?: "unknown"
                    request?.let { previewView?.setAspectRatio(it.previewWidth, it.previewHeight) }
                    AppLogger.log("THERMAL", "Camera opened: $size")
                    listener.onState(State.STREAMING, size)
                }

                ICameraStateCallBack.State.CLOSED -> {
                    AppLogger.log("THERMAL", "Camera closed")
                    listener.onState(State.STOPPED)
                }

                ICameraStateCallBack.State.ERROR -> {
                    AppLogger.log("THERMAL", "Camera error: ${msg ?: "unknown"}")
                    listener.onState(State.ERROR, msg)
                }
            }
        }
    }

    private val deviceCallback = object : IDeviceConnectCallBack {
        override fun onAttachDev(device: UsbDevice?) {
            if (!isRunning) return
            device ?: return
            if (!isUsbCamera(device)) return

            val existing = cameras[device.deviceId]
            if (existing != null) {
                if (currentDeviceId == null) {
                    currentDeviceId = device.deviceId
                    requestPermission(device)
                }
                return
            }

            AppLogger.log(
                "THERMAL_USB",
                "Attached: ${device.productName ?: device.deviceName} " +
                    "(VID=${device.vendorId}, PID=${device.productId})"
            )
            cameras[device.deviceId] = CameraUVC(context, device)
            currentDeviceName = device.productName ?: device.deviceName
            listener.onDevice(currentDeviceName)

            if (currentDeviceId == null) {
                currentDeviceId = device.deviceId
                requestPermission(device)
            }
        }

        override fun onDetachDec(device: UsbDevice?) {
            device ?: return
            AppLogger.log("THERMAL_USB", "Detached: ${device.productName ?: device.deviceName}")
            cleanupCamera(device.deviceId)
        }

        override fun onConnectDev(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
            if (!isRunning) return
            device ?: return
            ctrlBlock ?: return
            if (device.deviceId != currentDeviceId) {
                AppLogger.log("THERMAL_USB", "Ignore connect for non-current device")
                return
            }

            val camera = cameras[device.deviceId] ?: CameraUVC(context, device).also {
                cameras[device.deviceId] = it
            }
            if (camera.isCameraOpened()) return

            AppLogger.log("THERMAL_USB", "USB permission granted, opening camera")
            camera.setUsbControlBlock(ctrlBlock)
            camera.setCameraStateCallBack(stateCallback)
            camera.addPreviewDataCallBack(previewCallback)
            listener.onState(State.OPENING)
            camera.openCamera(previewView, buildRequest())
        }

        override fun onDisConnectDec(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
            device ?: return
            AppLogger.log("THERMAL_USB", "Disconnected: ${device.productName ?: device.deviceName}")
            cleanupCamera(device.deviceId)
        }

        override fun onCancelDev(device: UsbDevice?) {
            device ?: return
            AppLogger.log("THERMAL_USB", "USB permission denied")
            if (device.deviceId == currentDeviceId) {
                currentDeviceId = null
            }
            cameras.remove(device.deviceId)
            listener.onState(State.ERROR, "Нет разрешения на доступ к тепловизору")
        }
    }

    fun start(view: IAspectRatio? = null) {
        if (isRunning) {
            previewView = view ?: previewView
            return
        }

        previewView = view
        isRunning = true
        listener.onState(State.WAITING_FOR_DEVICE)

        val newClient = MultiCameraClient(context, deviceCallback)
        client = newClient
        newClient.register()

        val devices = newClient.getDeviceList()
        if (devices.isNullOrEmpty()) {
            AppLogger.log("THERMAL_USB", "No USB devices found")
            listener.onDevice(null)
            return
        }

        devices.forEach { device ->
            if (isUsbCamera(device)) {
                deviceCallback.onAttachDev(device)
            }
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        AppLogger.log("THERMAL", "Stopping thermal camera")

        cameras.values.forEach { camera ->
            camera.setCameraStateCallBack(null)
            camera.removePreviewDataCallBack(previewCallback)
            camera.closeCamera()
        }
        cameras.clear()
        currentDeviceId = null
        currentDeviceName = null

        client?.unRegister()
        client?.destroy()
        client = null

        listener.onState(State.STOPPED)
    }

    fun release() {
        stop()
    }

    private fun requestPermission(device: UsbDevice) {
        listener.onState(State.REQUESTING_PERMISSION)
        val requested = client?.requestPermission(device) ?: false
        if (!requested) {
            listener.onState(State.ERROR, "Не удалось запросить доступ к USB-устройству")
        }
    }

    private fun cleanupCamera(deviceId: Int) {
        val camera = cameras.remove(deviceId)
        camera?.setCameraStateCallBack(null)
        camera?.removePreviewDataCallBack(previewCallback)
        camera?.closeCamera()

        if (currentDeviceId == deviceId) {
            currentDeviceId = null
            currentDeviceName = null
        }
        listener.onState(State.STOPPED)
        listener.onDevice(null)
    }

    private fun isUsbCamera(device: UsbDevice): Boolean {
        return CameraUtils.isUsbCamera(device) || CameraUtils.isFilterDevice(context, device)
    }

    private fun buildRequest(): CameraRequest {
        return CameraRequest.Builder()
            .setPreviewWidth(targetPreviewWidth)
            .setPreviewHeight(targetPreviewHeight)
            .setRenderMode(CameraRequest.RenderMode.NORMAL)
            .setAspectRatioShow(true)
            .setRawPreviewData(true)
            .setCaptureRawImage(false)
            .setDefaultRotateType(RotateType.ANGLE_0)
            .setAudioSource(CameraRequest.AudioSource.NONE)
            .create()
    }
}

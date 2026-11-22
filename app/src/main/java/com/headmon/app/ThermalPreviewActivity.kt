package com.headmon.app

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.headmon.app.util.AppLogger
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.base.CameraActivity
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio

class ThermalPreviewActivity : CameraActivity() {

    private lateinit var cameraContainer: FrameLayout
    private lateinit var cameraView: IAspectRatio

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.log("THERMAL", "ThermalPreviewActivity started")
    }

    override fun getRootView(layoutInflater: LayoutInflater): View {
        cameraContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        cameraView = AspectRatioTextureView(this)
        return cameraContainer
    }

    override fun getCameraView(): IAspectRatio = cameraView

    override fun getCameraViewContainer(): ViewGroup = cameraContainer

    override fun getGravity(): Int = Gravity.CENTER

    override fun getCameraRequest(): CameraRequest {
        return CameraRequest.Builder()
            .setPreviewWidth(640)
            .setPreviewHeight(480)
            .setDefaultRotateType(RotateType.ANGLE_0)
            .setAudioSource(CameraRequest.AudioSource.NONE)
            .create()
    }

    override fun onCameraState(
        self: MultiCameraClient.ICamera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {
        AppLogger.log("THERMAL", "Camera state: $code, msg=${msg ?: ""}")
    }
}

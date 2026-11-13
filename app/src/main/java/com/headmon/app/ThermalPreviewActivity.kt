package com.headmon.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.jiangdg.ausbc.CameraActivity
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio
import com.headmon.app.util.AppLogger

class ThermalPreviewActivity : CameraActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.log("THERMAL", "ThermalPreviewActivity started")
    }

    // 1. Корневой View (контейнер)
    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View {
        val frameLayout = FrameLayout(this)
        frameLayout.id = View.generateViewId()
        return frameLayout
    }

    // 2. View для отображения картинки (TextureView)
    override fun getCameraView(): IAspectRatio {
        return AspectRatioTextureView(this)
    }

    // 3. Контейнер для камеры
    override fun getCameraViewContainer(): ViewGroup {
        return findViewById(android.R.id.content)
    }

    // 4. Настройки камеры (упрощенные, без RenderMode)
    override fun getCameraRequest(): CameraRequest {
        return CameraRequest.Builder()
            .setPreviewWidth(640)
            .setPreviewHeight(480)
            .setDefaultRotateType(RotateType.ANGLE_0)
            .create()
    }
}
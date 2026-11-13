package com.headmon.app.util

import android.content.Context
import android.hardware.usb.UsbManager

object ThermalDeviceChecker {
    // ID вашего тепловизора A-Bf RX-450 (нужно уточнить в инструкции или через тест)
    // Пока используем заглушку, если ID неизвестен, будем искать по имени или просто выводить список.
    // Обычно у таких устройств есть Vendor ID и Product ID.
    // private const val VENDOR_ID = 0x1234
    // private const val PRODUCT_ID = 0x5678

    /**
     * Проверяет, есть ли подключенные USB-устройства.
     * Возвращает список имен найденных устройств.
     */
    fun getConnectedUsbDevices(context: Context): List<String> {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val deviceList = usbManager.deviceList
        val result = mutableListOf<String>()

        for (device in deviceList.values) {
            val productName = device.productName ?: "Unknown"
            val manufacturerName = device.manufacturerName ?: "Unknown"
            val vendorId = device.vendorId
            val productId = device.productId

            result.add("$manufacturerName $productName (VID: $vendorId, PID: $productId)")
        }
        return result
    }

    /**
     * Проверяет, найдено ли устройство, похожее на тепловизор.
     * (Упрощенная проверка: ищем по имени или ID, если известны).
     */
    fun isThermalCameraConnected(context: Context): Boolean {
        val devices = getConnectedUsbDevices(context)
        // Пока просто проверяем, что есть хоть какие-то USB устройства,
        // но в идеале нужно найти RX-450 по VID/PID.
        // Вы можете заменить условие на devices.any { it.contains("RX-450") } или подобное.
        return devices.isNotEmpty()
    }
}
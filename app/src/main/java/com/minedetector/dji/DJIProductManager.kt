package com.minedetector.dji

import android.util.Log
import dji.sdk.base.BaseProduct
import dji.sdk.products.Aircraft
import dji.sdk.sdkmanager.DJISDKManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class DJIProductManager {

    companion object { private const val TAG = "DJIProductManager" }

    private val _productName = MutableStateFlow("Unknown")
    val productName: StateFlow<String> = _productName

    private val _productConnected = MutableStateFlow(false)
    val productConnected: StateFlow<Boolean> = _productConnected

    private val _firmwareVersion = MutableStateFlow("")
    val firmwareVersion: StateFlow<String> = _firmwareVersion

    fun init() {
        try {
            val product: BaseProduct? = DJISDKManager.getInstance().product
            val connected = product?.isConnected == true
            _productConnected.value = connected

            if (connected) {
                _productName.value = product?.model?.displayName ?: "DJI Aircraft"
                _firmwareVersion.value = "" // v4: оставим пустым, если нужно — реализуешь конкретным API под твою модель
                Log.d(TAG, "Product: ${_productName.value}, Connected: $connected")
            } else {
                _productName.value = "Unknown"
                _firmwareVersion.value = ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing product manager", e)
        }
    }

    fun getProductInfo(): Map<String, String> = mapOf(
        "name" to _productName.value,
        "connected" to _productConnected.value.toString(),
        "firmware" to _firmwareVersion.value
    )

    fun isConnected(): Boolean = _productConnected.value

    fun refresh() = init()
}

package com.minedetector.dji

import android.content.Context
import android.util.Log
import dji.common.error.DJIError
import dji.sdk.base.BaseComponent
import dji.sdk.base.BaseProduct
import dji.sdk.flightcontroller.FlightController
import dji.sdk.products.Aircraft
import dji.sdk.sdkmanager.DJISDKInitEvent
import dji.sdk.sdkmanager.DJISDKManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class DJIConnectionManager(context: Context) {
    // Store applicationContext to prevent Activity leak
    private val appContext: Context = context.applicationContext

    companion object {
        private const val TAG = "DJIConnectionManager"
    }

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _product = MutableStateFlow<Aircraft?>(null)
    val product: StateFlow<Aircraft?> = _product

    // Telemetry manager for accessing flight data
    val telemetryManager = DJITelemetryManager()

    fun registerApp(onSuccess: () -> Unit, onError: (String) -> Unit) {
        try {
            Log.d(TAG, "🔌 Registering DJI SDK...")

            DJISDKManager.getInstance().registerApp(appContext, object : DJISDKManager.SDKManagerCallback {

                // ✅ ПРАВИЛЬНАЯ СИГНАТУРА для DJI SDK v4.16.4
                // Принимает DJIError, а не DJISDKInitEvent!
                override fun onRegister(error: DJIError?) {
                    // ── DJI SDK QUIRK ─────────────────────────────────────────────────────────
                    // On first install, success → error == null.
                    // On every subsequent launch the SAME app key is already registered in the
                    // DJI backend, so DJI fires onRegister with a NON-NULL DJIError whose
                    // description is "API Key successfully registered".
                    // This looks like an error but IS a success — treat it as such.
                    // ─────────────────────────────────────────────────────────────────────────
                    val desc = error?.description ?: ""
                    val isSuccess = error == null ||
                        desc.contains("successfully registered", ignoreCase = true)

                    if (isSuccess) {
                        Log.d(TAG, "✅ SDK registration succeeded (msg=${desc.ifEmpty { "no error" }})")
                        DJISDKManager.getInstance().startConnectionToProduct()

                        // ── CRITICAL FIX: re-connect probe ───────────────────────────────────
                        // DJI SDK is a SINGLETON.  If a product was already connected under a
                        // PREVIOUS SDKManagerCallback (e.g. Activity recreation, second launch),
                        // the SDK does NOT re-fire onProductConnect for the new callback.
                        // The old FlightController GPS callback keeps running → DJITelemetry
                        // logs appear, but our NEW _isConnected StateFlow stays false →
                        // Activity's startConnectionMonitoring() never receives true →
                        // telemetryManager stays null → "Drone not connected".
                        //
                        // Fix: immediately check DJISDKManager.product after registration.
                        // If it's already set, manually call onProductConnect so _isConnected
                        // becomes true and telemetryManager.startListening() is called fresh.
                        try {
                            val existingProduct = DJISDKManager.getInstance().product
                            if (existingProduct != null) {
                                Log.d(TAG, "🔌 Existing product found at registerApp: ${existingProduct.model}" +
                                    " — manually triggering onProductConnect")
                                onProductConnect(existingProduct)
                            } else {
                                Log.d(TAG, "🔌 No existing product at registerApp — waiting for onProductConnect")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "⚠️ Existing product probe failed: ${e.message}")
                        }
                        // ─────────────────────────────────────────────────────────────────────

                        onSuccess()
                    } else {
                        // Настоящая ошибка регистрации (неверный ключ, нет сети и т.п.)
                        Log.e(TAG, "❌ SDK registration failed: $desc")
                        onError(desc.ifEmpty { "Unknown error" })
                    }
                }

                override fun onProductDisconnect() {
                    Log.d(TAG, "📴 Product disconnected")
                    _isConnected.value = false
                    _product.value = null

                    // Stop telemetry listening
                    telemetryManager.stopListening()
                }

                override fun onProductConnect(baseProduct: BaseProduct?) {
                    Log.d(TAG, "📡 Product connected: ${baseProduct?.model}")
                    _isConnected.value = true
                    val aircraft = baseProduct as? Aircraft
                    _product.value = aircraft

                    // Start telemetry listening
                    aircraft?.let {
                        telemetryManager.startListening(it)
                        Log.d(TAG, "✅ Telemetry listening started")
                    }
                }

                override fun onProductChanged(baseProduct: BaseProduct?) {
                    Log.d(TAG, "🔄 Product changed: ${baseProduct?.model}")
                    _product.value = baseProduct as? Aircraft
                }

                override fun onComponentChange(
                    componentKey: BaseProduct.ComponentKey?,
                    oldComponent: BaseComponent?,
                    newComponent: BaseComponent?
                ) {
                    Log.d(TAG, "🔧 Component changed: $componentKey" +
                        " old=${oldComponent?.javaClass?.simpleName}" +
                        " new=${newComponent?.javaClass?.simpleName}")

                    // FlightController may not be ready when onProductConnect fires.
                    // When it becomes available, rebind the GPS callback using the
                    // DIRECT component reference (newComponent) rather than going
                    // through aircraft.flightController — avoids any caching/timing issues.
                    if (componentKey == BaseProduct.ComponentKey.FLIGHT_CONTROLLER
                        && newComponent != null) {
                        val fc = newComponent as? FlightController
                        if (fc != null) {
                            Log.d(TAG, "🔁 FlightController available — rebinding GPS callback directly")
                            telemetryManager.rebindFlightController(fc)
                        } else {
                            // Fallback: full restart via aircraft reference
                            val aircraft = _product.value
                            if (aircraft != null) {
                                Log.d(TAG, "🔁 FlightController cast failed — restarting via aircraft")
                                telemetryManager.stopListening()
                                telemetryManager.startListening(aircraft)
                            }
                        }
                    }
                }

                override fun onInitProcess(event: DJISDKInitEvent?, totalProcess: Int) {
                    Log.d(TAG, "⏳ Init process: $totalProcess%")
                }

                override fun onDatabaseDownloadProgress(current: Long, total: Long) {
                    val progress = if (total > 0) (current * 100 / total).toInt() else 0
                    Log.d(TAG, "📥 Database download: $progress%")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to register SDK", e)
            onError("SDK registration exception: ${e.message}")
        }
    }

    /**
     * Вызывается при физическом переподключении USB (кабель отключили / подключили снова).
     *
     * ВАЖНО: startConnectionToProduct() одного недостаточно — он работает только когда
     * DJI-сервис уже запущен. При физическом disconnect/reconnect сервис может упасть
     * ("Service Not Connected" в логах). registerApp() заново привязывает сервис,
     * и внутри onRegister() уже вызывается startConnectionToProduct().
     * startConnectionMonitoring() повторно не нужен — existingStateFlow уже слушается.
     */
    fun reconnect() {
        Log.d(TAG, "🔁 reconnect(): re-binding DJI service via registerApp()")
        registerApp(
            onSuccess = { Log.d(TAG, "🔁 reconnect: DJI service re-bound successfully") },
            onError  = { msg -> Log.e(TAG, "🔁 reconnect: DJI service re-bind failed: $msg") }
        )
    }

    fun disconnect() {
        try {
            telemetryManager.stopListening()
            DJISDKManager.getInstance().stopConnectionToProduct()
            _isConnected.value = false
            _product.value = null
            Log.d(TAG, "🔌 Disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting", e)
        }
    }
}
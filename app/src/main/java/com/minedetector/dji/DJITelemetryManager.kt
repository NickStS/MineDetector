package com.minedetector.dji

import android.util.Log
import dji.common.camera.ExposureSettings
import dji.common.camera.SettingsDefinitions
import dji.common.flightcontroller.FlightControllerState
import dji.common.gimbal.GimbalState
import dji.sdk.camera.Camera
import dji.sdk.flightcontroller.FlightController
import dji.sdk.gimbal.Gimbal
import dji.sdk.products.Aircraft
import com.minedetector.data.models.Telemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.sqrt

class DJITelemetryManager {

    companion object {
        private const val TAG = "DJITelemetry"
    }

    private val _telemetryFlow = MutableStateFlow(Telemetry())
    val telemetryFlow: StateFlow<Telemetry> = _telemetryFlow

    private val _cameraSettingsFlow = MutableStateFlow(CameraSettings())
    val cameraSettingsFlow: StateFlow<CameraSettings> = _cameraSettingsFlow

    private val _gimbalPitchFlow = MutableStateFlow(0f)
    val gimbalPitchFlow: StateFlow<Float> = _gimbalPitchFlow

    private var aircraft: Aircraft? = null
    // Track the exact FlightController instance we called setStateCallback() on.
    // stopListening() must clear the callback on THIS instance (not aircraft.flightController
    // which may be a different object if components were rebound via onComponentChange).
    private var boundFlightController: FlightController? = null
    private var homeLatitude: Double = 0.0
    private var homeLongitude: Double = 0.0
    @Volatile
    private var isHomeSet: Boolean = false
    @Volatile
    private var isFlying: Boolean = false

    @Volatile
    private var lastValidBattery: Int = 0

    // GPS drift filtering
    private var lastReportedDistance: Float = 0f
    private val GPS_DRIFT_THRESHOLD = 3f // meters - ignore changes smaller than this when on ground

    // Throttle GPS diagnostic logs (1 per 5 seconds to avoid spam)
    private var lastGpsLogTime = 0L

    data class CameraSettings(
        val iso: Int = 0,
        val shutterSpeed: String = "--",
        val aperture: String = "--",
        val ev: String = "0",
        val resolution: String = "4K"
    )

    fun startListening(aircraft: Aircraft) {
        this.aircraft = aircraft

        // Diagnostic: confirm FlightController is non-null at bind time.
        // If null here → setStateCallback is never registered → GPS stays 0.0.
        // onComponentChange in DJIConnectionManager will call rebindFlightController()
        // when the FlightController component becomes available.
        val fc = aircraft.flightController
        Log.d(TAG, "startListening: flightController=$fc")

        if (fc != null) {
            setupFlightControllerCallback(fc)
        } else {
            Log.w(TAG, "⚠️ FlightController null at startListening — GPS callback deferred to onComponentChange")
        }

        // Battery state — thread-safe update
        aircraft.battery?.setStateCallback { batteryState ->
            val percent = batteryState.chargeRemainingInPercent

            if (percent > 0 || lastValidBattery == 0) {
                // Логируем ТОЛЬКО при изменении уровня заряда (не каждую секунду)
                if (percent != lastValidBattery) {
                    Log.d(TAG, "Battery changed: $lastValidBattery% → $percent%")
                }
                lastValidBattery = percent
                _telemetryFlow.update { current ->
                    current.copy(batteryPercent = percent)
                }
            } else {
                // Battery reports 0%, use last valid value
                Log.w(TAG, "Battery reported 0%, using last valid: $lastValidBattery%")
                _telemetryFlow.update { current ->
                    current.copy(batteryPercent = lastValidBattery)
                }
            }
        }

        aircraft.camera?.let { camera ->
            setupCameraListeners(camera)
        }

        aircraft.gimbal?.let { gimbal ->
            setupGimbalListener(gimbal)
        }

        Log.d(TAG, "Started telemetry listening")
    }

    /**
     * Registers the FlightControllerState callback on [fc].
     * Shared by startListening() and rebindFlightController().
     *
     * Using a direct FlightController reference (instead of aircraft.flightController)
     * ensures the callback is registered on the correct component instance even if
     * the Aircraft object has not yet reflected the new component.
     */
    private fun setupFlightControllerCallback(fc: FlightController) {
        boundFlightController = fc
        fc.setStateCallback { state: FlightControllerState ->
            val lat = state.aircraftLocation?.latitude ?: Double.NaN
            val lon = state.aircraftLocation?.longitude ?: Double.NaN
            val altitude = state.aircraftLocation?.altitude?.toFloat() ?: 0f

            val now = System.currentTimeMillis()
            if (now - lastGpsLogTime > 5000L) {
                lastGpsLogTime = now
                Log.d(TAG, "🌍 GPS callback: lat=$lat lon=$lon alt=$altitude" +
                    " sat=${state.satelliteCount} loc=${state.aircraftLocation}")
            }

            isFlying = altitude > 0.5f

            if (!isHomeSet && !isFlying && !lat.isNaN() && !lon.isNaN() && lat != 0.0 && lon != 0.0) {
                homeLatitude = lat
                homeLongitude = lon
                isHomeSet = true
                lastReportedDistance = 0f
                Log.d(TAG, "🏠 Home point set: $homeLatitude, $homeLongitude")
            }

            var distanceFromHome = if (isHomeSet && !lat.isNaN() && !lon.isNaN()) {
                calculateDistance(homeLatitude, homeLongitude, lat, lon)
            } else 0f

            if (!isFlying && isHomeSet) {
                val distanceChange = kotlin.math.abs(distanceFromHome - lastReportedDistance)
                if (distanceChange < GPS_DRIFT_THRESHOLD) {
                    distanceFromHome = lastReportedDistance
                } else {
                    lastReportedDistance = distanceFromHome
                }
            } else if (isFlying) {
                lastReportedDistance = distanceFromHome
            }

            val horizontalSpeed = sqrt(
                state.velocityX * state.velocityX + state.velocityY * state.velocityY
            )

            _telemetryFlow.update { current ->
                current.copy(
                    latitude = if (lat.isNaN()) 0.0 else lat,
                    longitude = if (lon.isNaN()) 0.0 else lon,
                    altitude = altitude,
                    satelliteCount = state.satelliteCount,
                    speed = horizontalSpeed,
                    verticalSpeed = -state.velocityZ,
                    heading = state.aircraftHeadDirection.toFloat(),
                    flightMode = state.flightMode.name,
                    distanceFromHome = distanceFromHome,
                    timestamp = now
                )
            }
        }
        Log.d(TAG, "✅ FlightController state callback registered on $fc")
    }

    /**
     * Re-registers the GPS callback using a freshly available FlightController instance.
     * Called from DJIConnectionManager.onComponentChange(FLIGHT_CONTROLLER) when the
     * component becomes available AFTER onProductConnect already ran.
     */
    fun rebindFlightController(fc: FlightController) {
        Log.d(TAG, "🔄 rebindFlightController: $fc")
        setupFlightControllerCallback(fc)
    }

    private fun setupCameraListeners(camera: Camera) {
        try {
            camera.setExposureSettingsCallback { exposureSettings: ExposureSettings ->
                val iso = exposureSettings.iso
                val shutterSpeed = formatShutterSpeed(exposureSettings.shutterSpeed)
                val aperture = formatAperture(exposureSettings.aperture)
                val ev = formatEV(exposureSettings.exposureCompensation)

                _cameraSettingsFlow.update { current ->
                    current.copy(
                        iso = iso,
                        shutterSpeed = shutterSpeed,
                        aperture = aperture,
                        ev = ev
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "setExposureSettingsCallback not supported on this camera: ${e.javaClass.simpleName}")
        }
    }

    private fun setupGimbalListener(gimbal: Gimbal) {
        try {
            gimbal.setStateCallback { gimbalState: GimbalState ->
                _gimbalPitchFlow.value = gimbalState.attitudeInDegrees.pitch
            }
        } catch (e: Exception) {
            Log.w(TAG, "gimbal.setStateCallback not supported: ${e.javaClass.simpleName}")
        }
    }

    private fun formatShutterSpeed(speed: SettingsDefinitions.ShutterSpeed?): String {
        if (speed == null) return "--"
        val name = speed.name
        return try {
            if (name.contains("_")) {
                val parts = name.replace("SHUTTER_SPEED_", "").split("_")
                if (parts.size >= 2) {
                    "1/${parts[1]}"
                } else {
                    parts[0]
                }
            } else "--"
        } catch (e: Exception) {
            "--"
        }
    }

    private fun formatAperture(aperture: SettingsDefinitions.Aperture?): String {
        if (aperture == null) return "--"
        return try {
            aperture.name.replace("F_", "f/").replace("_", ".")
        } catch (e: Exception) {
            "--"
        }
    }

    private fun formatEV(ev: SettingsDefinitions.ExposureCompensation?): String {
        if (ev == null) return "0"
        return try {
            val name = ev.name.replace("N_", "-").replace("P_", "+").replace("_", ".")
            if (name == "ZERO") "0" else name
        } catch (e: Exception) {
            "0"
        }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return (R * c).toFloat()
    }

    /**
     * Возвращает координаты home point если они установлены
     */
    fun getHomePoint(): Pair<Double, Double>? {
        return if (isHomeSet && homeLatitude != 0.0 && homeLongitude != 0.0) {
            Pair(homeLatitude, homeLongitude)
        } else {
            null
        }
    }

    fun stopListening() {
        // Clear FC callback on the EXACT instance we registered it on (not aircraft.flightController
        // which may differ if the component was rebound via onComponentChange).
        try { boundFlightController?.setStateCallback(null) } catch (e: Exception) {
            Log.w(TAG, "clearFCCallback error: ${e.javaClass.simpleName}")
        }
        boundFlightController = null

        try { aircraft?.battery?.setStateCallback(null) } catch (e: Exception) {
            Log.w(TAG, "clearBatteryCallback error: ${e.javaClass.simpleName}")
        }
        // Enterprise cameras may throw UnsupportedOperationException on setExposureSettingsCallback(null)
        try { aircraft?.camera?.setExposureSettingsCallback(null) } catch (e: Exception) {
            Log.w(TAG, "clearCameraCallback error: ${e.javaClass.simpleName}")
        }
        try { aircraft?.gimbal?.setStateCallback(null) } catch (e: Exception) {
            Log.w(TAG, "clearGimbalCallback error: ${e.javaClass.simpleName}")
        }

        aircraft = null
        isHomeSet = false
        isFlying = false
        lastValidBattery = 0
        lastReportedDistance = 0f
        lastGpsLogTime = 0L
        Log.d(TAG, "Stopped telemetry listening")
    }

    /**
     * Manually reset home point (e.g., after landing)
     */
    fun resetHomePoint() {
        isHomeSet = false
        lastReportedDistance = 0f
        Log.d(TAG, "Home point reset")
    }
}

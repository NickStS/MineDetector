package com.minedetector.ui.components

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import android.widget.TextView
import com.minedetector.R
import com.minedetector.data.models.Telemetry

class TelemetryPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private lateinit var tvBattery: TextView
    private lateinit var tvGPS: TextView
    private lateinit var tvAltitude: TextView
    private lateinit var tvSatellites: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvFlightMode: TextView

    init {
        inflate(context, R.layout.view_telemetry_panel, this)
        orientation = VERTICAL
        initViews()
    }

    private fun initViews() {
        tvBattery = findViewById(R.id.tv_battery)
        tvGPS = findViewById(R.id.tv_gps)
        tvAltitude = findViewById(R.id.tv_altitude)
        tvSatellites = findViewById(R.id.tv_satellites)
        tvSpeed = findViewById(R.id.tv_speed)
        tvFlightMode = findViewById(R.id.tv_flight_mode)
    }

    fun updateTelemetry(telemetry: Telemetry) {
        tvBattery.text = "🔋 ${telemetry.batteryPercent}%"
        tvGPS.text = "📍 ${String.format("%.6f", telemetry.latitude)}, ${String.format("%.6f", telemetry.longitude)}"
        tvAltitude.text = "⬆️ ${String.format("%.1f", telemetry.altitude)}m"
        tvSatellites.text = "🛰️ ${telemetry.satelliteCount}"
        tvSpeed.text = "⚡ ${String.format("%.1f", telemetry.speed)} m/s"
        tvFlightMode.text = "✈️ ${telemetry.flightMode}"
    }
}
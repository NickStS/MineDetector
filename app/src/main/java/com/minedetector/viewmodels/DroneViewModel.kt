package com.minedetector.viewmodels

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minedetector.data.models.DroneState
import com.minedetector.data.models.Telemetry
import kotlinx.coroutines.launch

class DroneViewModel : ViewModel() {

    private val _connectionState = MutableLiveData<Boolean>(false)
    val connectionState: LiveData<Boolean> = _connectionState

    private val _droneState = MutableLiveData<DroneState>(DroneState())
    val droneState: LiveData<DroneState> = _droneState

    private val _telemetryData = MutableLiveData<Telemetry>(Telemetry())
    val telemetryData: LiveData<Telemetry> = _telemetryData

    private val _signalLost = MutableLiveData<Boolean>(false)
    val signalLost: LiveData<Boolean> = _signalLost

    fun updateConnectionState(isConnected: Boolean) {
        _connectionState.postValue(isConnected)
    }

    fun updateDroneState(state: DroneState) {
        _droneState.postValue(state)
    }

    fun updateTelemetry(telemetry: Telemetry) {
        _telemetryData.postValue(telemetry)
    }

    fun updateSignalState(isLost: Boolean) {
        _signalLost.postValue(isLost)
    }
}
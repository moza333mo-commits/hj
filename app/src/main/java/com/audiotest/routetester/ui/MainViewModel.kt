package com.audiotest.routetester.ui

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.audiotest.routetester.audio.AudioRouteDiagnosticEngine
import com.audiotest.routetester.model.AudioDeviceClassifier
import com.audiotest.routetester.model.AudioEndpointDevice
import com.audiotest.routetester.model.DiagnosticLogEntry
import com.audiotest.routetester.model.RouteVerificationReport
import com.audiotest.routetester.service.AudioBridgeForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val engine = AudioRouteDiagnosticEngine(application.applicationContext)

    val inputDevices: StateFlow<List<AudioEndpointDevice>> = engine.inputDevices
    val outputDevices: StateFlow<List<AudioEndpointDevice>> = engine.outputDevices
    val currentAudioMode: StateFlow<String> = engine.currentAudioMode
    val activeCommDevice: StateFlow<AudioEndpointDevice?> = engine.activeCommDevice
    val liveMicRmsDb: StateFlow<Double> = engine.liveMicRmsDb
    val liveMicPeak: StateFlow<Int> = engine.liveMicPeak
    val isLoopbackRunning: StateFlow<Boolean> = engine.isLoopbackRunning
    val latestReport: StateFlow<RouteVerificationReport?> = engine.latestReport
    val logs: StateFlow<List<DiagnosticLogEntry>> = engine.logs

    private val _selectedInputId = MutableStateFlow<Int?>(null)
    val selectedInputId: StateFlow<Int?> = _selectedInputId.asStateFlow()

    private val _selectedOutputId = MutableStateFlow<Int?>(null)
    val selectedOutputId: StateFlow<Int?> = _selectedOutputId.asStateFlow()

    private val _useCommModeInBridge = MutableStateFlow(false)
    val useCommModeInBridge: StateFlow<Boolean> = _useCommModeInBridge.asStateFlow()

    private val _injectTone = MutableStateFlow(false)
    val injectTone: StateFlow<Boolean> = _injectTone.asStateFlow()

    private val _isCallGuardEnabled = MutableStateFlow(false)
    val isCallGuardEnabled: StateFlow<Boolean> = _isCallGuardEnabled.asStateFlow()

    init {
        autoSelectSmartDefaults()
    }

    fun refreshHardwareList() {
        engine.refreshDevices()
        autoSelectSmartDefaults()
    }

    /**
     * اختيار ذكي تلقائي:
     * يبحث في المدخلات عن سماعة الجكة السلكية (Wired Headset) أو USB Headset،
     * وفي المخرجات عن سماعة البلوتوث (A2DP / SCO / BLE).
     */
    private fun autoSelectSmartDefaults() {
        val inputs = inputDevices.value
        val outputs = outputDevices.value

        if (_selectedInputId.value == null || inputs.none { it.id == _selectedInputId.value }) {
            val preferredMic = inputs.firstOrNull { AudioDeviceClassifier.isWiredJackOrUsbMic(it.typeCode) }
                ?: inputs.firstOrNull()
            _selectedInputId.value = preferredMic?.id
        }

        if (_selectedOutputId.value == null || outputs.none { it.id == _selectedOutputId.value }) {
            val preferredSpeaker = outputs.firstOrNull { AudioDeviceClassifier.isBluetoothOutput(it.typeCode) }
                ?: outputs.firstOrNull()
            _selectedOutputId.value = preferredSpeaker?.id
        }
    }

    fun selectInputDevice(id: Int) {
        _selectedInputId.value = id
    }

    fun selectOutputDevice(id: Int) {
        _selectedOutputId.value = id
    }

    fun setUseCommModeInBridge(enabled: Boolean) {
        _useCommModeInBridge.value = enabled
    }

    fun setInjectTone(enabled: Boolean) {
        _injectTone.value = enabled
    }

    fun toggleSplitBridgeTest() {
        if (isLoopbackRunning.value) {
            engine.stopActiveBridge()
        } else {
            val inId = _selectedInputId.value ?: return
            val outId = _selectedOutputId.value ?: return
            engine.startSplitStreamLoopbackTest(
                scope = viewModelScope,
                preferredInputId = inId,
                preferredOutputId = outId,
                useCommunicationMode = _useCommModeInBridge.value,
                injectTestToneWithMic = _injectTone.value
            )
        }
    }

    fun runCallModeCouplingCheck() {
        val inId = _selectedInputId.value ?: return
        val outId = _selectedOutputId.value ?: return
        viewModelScope.launch {
            engine.runCommunicationDeviceCouplingProbe(
                targetCommDeviceId = inId,
                desiredSecondaryOutputId = outId
            )
        }
    }

    fun toggleCallGuardService(enable: Boolean) {
        val app = getApplication<Application>()
        val intent = Intent(app, AudioBridgeForegroundService::class.java).apply {
            action = if (enable) {
                AudioBridgeForegroundService.ACTION_START_WATCHDOG
            } else {
                AudioBridgeForegroundService.ACTION_STOP_WATCHDOG
            }
            putExtra(AudioBridgeForegroundService.EXTRA_PREFER_WIRED_JACK, true)
        }
        if (enable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
        } else {
            app.startService(intent)
        }
        _isCallGuardEnabled.value = enable
        engine.appendLog(
            "GUARD",
            if (enable) "تم تفعيل حارس المكالمات الخلفي لإجبار مايك الجكة عند بدء مكالمة واتساب."
            else "تم إيقاف حارس المكالمات الخلفي."
        )
    }

    fun clearLogs() {
        engine.clearLogs()
    }

    override fun onCleared() {
        engine.release()
        super.onCleared()
    }
}

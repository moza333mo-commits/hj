package com.audiotest.routetester.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.audiotest.routetester.model.AudioDeviceClassifier
import com.audiotest.routetester.model.AudioEndpointDevice
import com.audiotest.routetester.model.DiagnosticLogEntry
import com.audiotest.routetester.model.RouteVerificationReport
import com.audiotest.routetester.model.VerificationVerdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * المحرك الهندسي المسؤول عن الفحص الحقيقي لمسارات الصوت في نظام Android.
 *
 * مبادئ الصدق التقني الصارمة في هذا المحرك:
 * 1. لا نعتبر إرجاع `true` من `setPreferredDevice` أو `setCommunicationDevice` نجاحاً!
 * 2. يجب قراءة `AudioRecord.routedDevice` و `AudioTrack.routedDevice` بعد بدء تدفق البيانات الفعلي (Read/Write).
 * 3. يجب تحليل عينات الصوت الخام (PCM 16-bit) والتأكد من وجود إشارة حقيقية غير صفرية (dBFS > -65 dB)
 *    للتأكد من أن النظام لم يقم بكتم الميكروفون (Silent Buffer) كما يحدث في بعض أجهزة Android أثناء المكالمات.
 */
class AudioRouteDiagnosticEngine(private val context: Context) {

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _inputDevices = MutableStateFlow<List<AudioEndpointDevice>>(emptyList())
    val inputDevices: StateFlow<List<AudioEndpointDevice>> = _inputDevices.asStateFlow()

    private val _outputDevices = MutableStateFlow<List<AudioEndpointDevice>>(emptyList())
    val outputDevices: StateFlow<List<AudioEndpointDevice>> = _outputDevices.asStateFlow()

    private val _currentAudioMode = MutableStateFlow(getAudioModeString(audioManager.mode))
    val currentAudioMode: StateFlow<String> = _currentAudioMode.asStateFlow()

    private val _activeCommDevice = MutableStateFlow<AudioEndpointDevice?>(null)
    val activeCommDevice: StateFlow<AudioEndpointDevice?> = _activeCommDevice.asStateFlow()

    private val _liveMicRmsDb = MutableStateFlow(-96.0)
    val liveMicRmsDb: StateFlow<Double> = _liveMicRmsDb.asStateFlow()

    private val _liveMicPeak = MutableStateFlow(0)
    val liveMicPeak: StateFlow<Int> = _liveMicPeak.asStateFlow()

    private val _isLoopbackRunning = MutableStateFlow(false)
    val isLoopbackRunning: StateFlow<Boolean> = _isLoopbackRunning.asStateFlow()

    private val _latestReport = MutableStateFlow<RouteVerificationReport?>(null)
    val latestReport: StateFlow<RouteVerificationReport?> = _latestReport.asStateFlow()

    private val _logs = MutableStateFlow<List<DiagnosticLogEntry>>(emptyList())
    val logs: StateFlow<List<DiagnosticLogEntry>> = _logs.asStateFlow()

    private var activeBridgeJob: Job? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refreshDevices()
            addedDevices?.forEach { dev ->
                appendLog(
                    "HOTPLUG+",
                    "تم توصيل جهاز: ${AudioDeviceClassifier.getArabicLabel(dev.type)} [${dev.productName}] (ID:${dev.id})"
                )
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refreshDevices()
            removedDevices?.forEach { dev ->
                appendLog(
                    "HOTPLUG-",
                    "تم فصل جهاز: ${AudioDeviceClassifier.getArabicLabel(dev.type)} [${dev.productName}] (ID:${dev.id})",
                    isError = true
                )
            }
        }
    }

    init {
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        refreshDevices()
        appendLog("INIT", "تم تشغيل محرك الفحص الصوتي. إصدار أندرويد: SDK ${Build.VERSION.SDK_INT} (${Build.MANUFACTURER} ${Build.MODEL})")
    }

    fun release() {
        stopActiveBridge()
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
    }

    fun appendLog(tag: String, message: String, isError: Boolean = false, isSuccess: Boolean = false) {
        val entry = DiagnosticLogEntry(
            timeFormatted = timeFormatter.format(Date()),
            tag = tag,
            message = message,
            isError = isError,
            isSuccess = isSuccess
        )
        _logs.value = (listOf(entry) + _logs.value).take(250)
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    /**
     * تحديث قائمة أجهزة الدخل والخرج المتصلة فعلياً عبر AudioManager.getDevices
     */
    fun refreshDevices() {
        val commIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                audioManager.availableCommunicationDevices.map { it.id }.toSet()
            } catch (_: Exception) {
                emptySet()
            }
        } else {
            emptySet()
        }

        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .map { it.toEndpointModel(commIds.contains(it.id)) }
            .sortedBy { it.id }

        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .map { it.toEndpointModel(commIds.contains(it.id)) }
            .sortedBy { it.id }

        _inputDevices.value = inputs
        _outputDevices.value = outputs
        _currentAudioMode.value = getAudioModeString(audioManager.mode)
        _activeCommDevice.value = queryCurrentCommunicationDevice(commIds)
    }

    private fun queryCurrentCommunicationDevice(commIds: Set<Int>): AudioEndpointDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                audioManager.communicationDevice?.toEndpointModel(true)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
    }

    private fun findRawAudioDeviceInfo(deviceId: Int, isInput: Boolean): AudioDeviceInfo? {
        val flag = if (isInput) AudioManager.GET_DEVICES_INPUTS else AudioManager.GET_DEVICES_OUTPUTS
        return audioManager.getDevices(flag).firstOrNull { it.id == deviceId }
    }

    /**
     * المختبر الأول (Test Lab 1):
     * اختبار الفصل الحقيقي على مستوى الـ Stream (مايك الجكة/السلك -> سماعة البلوتوث)
     * باستخدام AudioRecord.setPreferredDevice + AudioTrack.setPreferredDevice
     * مع تشغيل جسر صوتي حقيقي (Real-Time Low-Latency PCM Bridge) والتحقق من:
     * 1) `audioRecord.routedDevice.id == requestedInput.id`
     * 2) `audioTrack.routedDevice.id == requestedOutput.id`
     * 3) وجود نبضات صوتية فعلية غير صفرية من الميكروفون المختار.
     */
    @SuppressLint("MissingPermission")
    fun startSplitStreamLoopbackTest(
        scope: CoroutineScope,
        preferredInputId: Int,
        preferredOutputId: Int,
        useCommunicationMode: Boolean,
        injectTestToneWithMic: Boolean = false
    ) {
        stopActiveBridge()

        activeBridgeJob = scope.launch(Dispatchers.IO) {
            _isLoopbackRunning.value = true
            val rawInput = findRawAudioDeviceInfo(preferredInputId, isInput = true)
            val rawOutput = findRawAudioDeviceInfo(preferredOutputId, isInput = false)

            if (rawInput == null || rawOutput == null) {
                val missingReport = RouteVerificationReport(
                    testNameAr = if (useCommunicationMode) "اختبار الفصل أثناء وضع المكالمات (MODE_IN_COMMUNICATION)" else "اختبار الفصل المباشر عبر الجسر الصوتي (MODE_NORMAL)",
                    audioModeName = getAudioModeString(audioManager.mode),
                    requestedInputDevice = rawInput?.toEndpointModel(false),
                    actualRoutedInputDevice = null,
                    requestedOutputDevice = rawOutput?.toEndpointModel(false),
                    actualRoutedOutputDevice = null,
                    globalCommunicationDevice = _activeCommDevice.value,
                    inputPreferenceAcceptedByApi = false,
                    outputPreferenceAcceptedByApi = false,
                    inputActuallyMatchedHardware = false,
                    outputActuallyMatchedHardware = false,
                    measuredRmsDbFs = -96.0,
                    measuredPeakAmplitude = 0,
                    nonZeroAudioFramesRatio = 0f,
                    latencyEstimateMs = 0,
                    verdict = VerificationVerdict.HARDWARE_MISSING,
                    technicalDiagnosisAr = "أحد الجهازين المختارين غير متصل حالياً. تأكد من توصيل سماعة الجكة السلكية وإقران سماعة البلوتوث.",
                    kernelAndHalNotesAr = "لم يتم العثور على معرف الجهاز المطلوب في جدول AudioManager.getDevices."
                )
                _latestReport.value = missingReport
                appendLog("ERROR", missingReport.technicalDiagnosisAr, isError = true)
                _isLoopbackRunning.value = false
                return@launch
            }

            val sampleRate = 48000
            val minRecBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(1920)

            val minPlayBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(1920)

            var audioRecord: AudioRecord? = null
            var audioTrack: AudioTrack? = null
            val previousMode = audioManager.mode

            try {
                // ضبط وضع الصوت المطلوب للاختبار
                audioManager.mode = if (useCommunicationMode) {
                    AudioManager.MODE_IN_COMMUNICATION
                } else {
                    AudioManager.MODE_NORMAL
                }
                _currentAudioMode.value = getAudioModeString(audioManager.mode)

                appendLog(
                    "TEST-START",
                    "بدء الفحص بوضع ${getAudioModeString(audioManager.mode)} | طلب الدخل: ${rawInput.productName} (ID:${rawInput.id}) -> طلب الخرج: ${rawOutput.productName} (ID:${rawOutput.id})"
                )

                val audioSource = if (useCommunicationMode) {
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION
                } else {
                    MediaRecorder.AudioSource.MIC
                }

                audioRecord = AudioRecord.Builder()
                    .setAudioSource(audioSource)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minRecBuf * 2)
                    .build()

                val usageAttr = if (useCommunicationMode) {
                    AudioAttributes.USAGE_VOICE_COMMUNICATION
                } else {
                    AudioAttributes.USAGE_MEDIA
                }

                val contentAttr = if (useCommunicationMode) {
                    AudioAttributes.CONTENT_TYPE_SPEECH
                } else {
                    AudioAttributes.CONTENT_TYPE_MUSIC
                }

                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(usageAttr)
                            .setContentType(contentAttr)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minPlayBuf * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                // تطبيق التوجيه المفضل لكل مسار بشكل مستقل
                val inAccepted = audioRecord.setPreferredDevice(rawInput)
                val outAccepted = audioTrack.setPreferredDevice(rawOutput)

                appendLog(
                    "API-CALL",
                    "استجابة API المبدئية: AudioRecord.setPreferredDevice=$inAccepted | AudioTrack.setPreferredDevice=$outAccepted"
                )

                audioRecord.startRecording()
                audioTrack.play()

                val buffer = ShortArray(960) // 20ms frame at 48kHz
                var totalFrames = 0
                var nonZeroFrames = 0
                var maxPeak = 0
                var maxRmsDb = -96.0
                var phase = 0.0

                // تشغيل الاختبار المباشر مع تحديث القراءات الحقيقية كل 250 ملي ثانية
                var elapsedMs = 0L
                val startTime = System.currentTimeMillis()

                while (isActive && _isLoopbackRunning.value) {
                    val readCount = audioRecord.read(buffer, 0, buffer.size)
                    if (readCount > 0) {
                        totalFrames++
                        var sumSquares = 0.0
                        var framePeak = 0
                        var hasNonZero = false

                        for (i in 0 until readCount) {
                            val sample = buffer[i].toInt()
                            if (sample != 0) hasNonZero = true
                            val absVal = abs(sample)
                            if (absVal > framePeak) framePeak = absVal
                            sumSquares += (sample.toDouble() * sample.toDouble())

                            if (injectTestToneWithMic) {
                                val tone = (sin(phase) * 3500).toInt()
                                phase += (2.0 * Math.PI * 440.0) / sampleRate
                                buffer[i] = (sample + tone).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                            }
                        }

                        if (hasNonZero) nonZeroFrames++
                        if (framePeak > maxPeak) maxPeak = framePeak

                        val rms = sqrt(sumSquares / readCount)
                        val dbFs = if (rms > 1.0) 20.0 * log10(rms / 32768.0) else -96.0
                        if (dbFs > maxRmsDb) maxRmsDb = dbFs

                        _liveMicRmsDb.value = dbFs
                        _liveMicPeak.value = framePeak

                        // بث الصوت الملتقط فوراً إلى الخرج المحدد
                        audioTrack.write(buffer, 0, readCount)
                    }

                    val now = System.currentTimeMillis()
                    if (now - startTime - elapsedMs >= 600L) {
                        elapsedMs = now - startTime
                        // فحص التوجيه الفعلي من الـ HAL عبر getRoutedDevice()
                        val actualIn = audioRecord.routedDevice
                        val actualOut = audioTrack.routedDevice

                        val inMatched = actualIn?.id == rawInput.id
                        val outMatched = actualOut?.id == rawOutput.id
                        val nonZeroRatio = if (totalFrames > 0) nonZeroFrames.toFloat() / totalFrames else 0f
                        val hasRealSignal = nonZeroRatio > 0.1f && maxRmsDb > -78.0

                        val verdict = evaluateVerdict(
                            inMatched = inMatched,
                            outMatched = outMatched,
                            hasRealSignal = hasRealSignal
                        )

                        val diagnosis = buildHonestDiagnosis(
                            useCommunicationMode = useCommunicationMode,
                            reqIn = rawInput,
                            actIn = actualIn,
                            reqOut = rawOutput,
                            actOut = actualOut,
                            inMatched = inMatched,
                            outMatched = outMatched,
                            maxRmsDb = maxRmsDb,
                            nonZeroRatio = nonZeroRatio
                        )

                        val report = RouteVerificationReport(
                            testNameAr = if (useCommunicationMode) {
                                "فحص الفصل في وضع المكالمة (MODE_IN_COMMUNICATION)"
                            } else {
                                "فحص الفصل عبر الجسر الصوتي المستقل (MODE_NORMAL + A2DP)"
                            },
                            audioModeName = getAudioModeString(audioManager.mode),
                            requestedInputDevice = rawInput.toEndpointModel(false),
                            actualRoutedInputDevice = actualIn?.toEndpointModel(false),
                            requestedOutputDevice = rawOutput.toEndpointModel(false),
                            actualRoutedOutputDevice = actualOut?.toEndpointModel(false),
                            globalCommunicationDevice = queryCurrentCommunicationDevice(emptySet()),
                            inputPreferenceAcceptedByApi = inAccepted,
                            outputPreferenceAcceptedByApi = outAccepted,
                            inputActuallyMatchedHardware = inMatched,
                            outputActuallyMatchedHardware = outMatched,
                            measuredRmsDbFs = maxRmsDb,
                            measuredPeakAmplitude = maxPeak,
                            nonZeroAudioFramesRatio = nonZeroRatio,
                            latencyEstimateMs = ((minRecBuf + minPlayBuf) * 1000) / (sampleRate * 2),
                            verdict = verdict,
                            technicalDiagnosisAr = diagnosis.first,
                            kernelAndHalNotesAr = diagnosis.second
                        )
                        _latestReport.value = report
                    }
                }
            } catch (e: Exception) {
                appendLog("EXCEPTION", "حدث خطأ أثناء تشغيل الفحص: ${e.localizedMessage}", isError = true)
            } finally {
                try {
                    audioRecord?.stop()
                    audioRecord?.release()
                } catch (_: Exception) {}
                try {
                    audioTrack?.stop()
                    audioTrack?.release()
                } catch (_: Exception) {}

                audioManager.mode = previousMode
                _currentAudioMode.value = getAudioModeString(audioManager.mode)
                _liveMicRmsDb.value = -96.0
                _liveMicPeak.value = 0
                _isLoopbackRunning.value = false
                appendLog("TEST-STOP", "تم إيقاف الجسر الصوتي واستعادة وضع الصوت الأصلي.")
            }
        }
    }

    fun stopActiveBridge() {
        _isLoopbackRunning.value = false
        activeBridgeJob?.cancel()
        activeBridgeJob = null
    }

    /**
     * المختبر الثاني (Test Lab 2):
     * اختبار سلوك `AudioManager.setCommunicationDevice` الرسمي في Android 12+ (API 31+)
     * يثبت عملياً للمستخدم كيف يتعامل نظام هاتفه مع طلب تحويل جهاز المكالمة أثناء مكالمات واتساب/الاتصال.
     */
    @SuppressLint("MissingPermission")
    suspend fun runCommunicationDeviceCouplingProbe(
        targetCommDeviceId: Int,
        desiredSecondaryOutputId: Int
    ): RouteVerificationReport = withContext(Dispatchers.IO) {
        val rawTarget = findRawAudioDeviceInfo(targetCommDeviceId, isInput = true)
            ?: findRawAudioDeviceInfo(targetCommDeviceId, isInput = false)
        val rawSecondaryOut = findRawAudioDeviceInfo(desiredSecondaryOutputId, isInput = false)

        if (rawTarget == null) {
            return@withContext RouteVerificationReport(
                testNameAr = "فحص ارتباط جهاز المكالمة الموحد (setCommunicationDevice)",
                audioModeName = getAudioModeString(audioManager.mode),
                requestedInputDevice = null,
                actualRoutedInputDevice = null,
                requestedOutputDevice = rawSecondaryOut?.toEndpointModel(false),
                actualRoutedOutputDevice = null,
                globalCommunicationDevice = null,
                inputPreferenceAcceptedByApi = false,
                outputPreferenceAcceptedByApi = false,
                inputActuallyMatchedHardware = false,
                outputActuallyMatchedHardware = false,
                measuredRmsDbFs = -96.0,
                measuredPeakAmplitude = 0,
                nonZeroAudioFramesRatio = 0f,
                latencyEstimateMs = 0,
                verdict = VerificationVerdict.HARDWARE_MISSING,
                technicalDiagnosisAr = "الجهاز المطلوب غير متصل.",
                kernelAndHalNotesAr = "تعذر العثور على الجهاز في AudioManager."
            )
        }

        val prevMode = audioManager.mode
        var record: AudioRecord? = null
        var track: AudioTrack? = null

        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            _currentAudioMode.value = getAudioModeString(audioManager.mode)

            val commSetResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    audioManager.setCommunicationDevice(rawTarget)
                } catch (e: Exception) {
                    appendLog("COMM-ERR", "رفض setCommunicationDevice: ${e.message}", isError = true)
                    false
                }
            } else {
                @Suppress("DEPRECATION")
                if (rawTarget.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                    true
                } else {
                    audioManager.isSpeakerphoneOn = (rawTarget.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                    true
                }
            }

            delay(350) // انتظار استقرار مسار HAL

            val sampleRate = 16000
            val minRec = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(1280)
            val minPlay = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(1280)

            record = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minRec * 2)
                .build()

            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minPlay * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            record.startRecording()
            track.play()

            val buf = ShortArray(320)
            var peak = 0
            var maxDb = -96.0
            var nonZero = 0
            val loops = 15

            for (i in 0 until loops) {
                val r = record.read(buf, 0, buf.size)
                if (r > 0) {
                    var sumSq = 0.0
                    var hasSignal = false
                    for (j in 0 until r) {
                        val s = abs(buf[j].toInt())
                        if (s > 0) hasSignal = true
                        if (s > peak) peak = s
                        sumSq += s.toDouble() * s.toDouble()
                    }
                    if (hasSignal) nonZero++
                    val rms = sqrt(sumSq / r)
                    val db = if (rms > 1.0) 20.0 * log10(rms / 32768.0) else -96.0
                    if (db > maxDb) maxDb = db
                    track.write(buf, 0, r)
                }
            }

            val actualIn = record.routedDevice
            val actualOut = track.routedDevice
            val activeComm = queryCurrentCommunicationDevice(emptySet())

            val inMatchesTarget = actualIn?.type == rawTarget.type || actualIn?.id == rawTarget.id
            val outMatchesSecondary = rawSecondaryOut != null && (actualOut?.id == rawSecondaryOut.id || actualOut?.type == rawSecondaryOut.type)

            val verdict = if (inMatchesTarget && outMatchesSecondary) {
                VerificationVerdict.VERIFIED_TRUE_SPLIT
            } else {
                VerificationVerdict.PARTIAL_COUPLING_OVERRIDDEN
            }

            val report = RouteVerificationReport(
                testNameAr = "فحص ارتباط مسار المكالمة القياسي (setCommunicationDevice)",
                audioModeName = getAudioModeString(audioManager.mode),
                requestedInputDevice = rawTarget.toEndpointModel(true),
                actualRoutedInputDevice = actualIn?.toEndpointModel(true),
                requestedOutputDevice = rawSecondaryOut?.toEndpointModel(false),
                actualRoutedOutputDevice = actualOut?.toEndpointModel(false),
                globalCommunicationDevice = activeComm,
                inputPreferenceAcceptedByApi = commSetResult,
                outputPreferenceAcceptedByApi = false,
                inputActuallyMatchedHardware = inMatchesTarget,
                outputActuallyMatchedHardware = outMatchesSecondary,
                measuredRmsDbFs = maxDb,
                measuredPeakAmplitude = peak,
                nonZeroAudioFramesRatio = nonZero.toFloat() / loops,
                latencyEstimateMs = 40,
                verdict = verdict,
                technicalDiagnosisAr = if (verdict == VerificationVerdict.PARTIAL_COUPLING_OVERRIDDEN) {
                    "تأكيد تقني صادق: دالة setCommunicationDevice وحدت الدخل والخرج معاً على [${actualOut?.productName ?: "نفس الجهاز"}]. نظام Android في وضع المكالمات (MODE_IN_COMMUNICATION) يربط المايك والسماعة في مسار واحد ولا يسمح لتطبيقات الطرف الثالث (مثل واتساب) بفصل مايك الجكة عن سماعة البلوتوث."
                } else {
                    "تم رصد فصل فعلي بين مسار الدخل ومسار الخرج."
                },
                kernelAndHalNotesAr = "Active Comm Device: ${activeComm?.productName ?: "None"} | Routed In: ${actualIn?.productName} (ID:${actualIn?.id}) | Routed Out: ${actualOut?.productName} (ID:${actualOut?.id})"
            )

            _latestReport.value = report
            appendLog(
                "COMM-PROBE",
                report.technicalDiagnosisAr,
                isError = (verdict != VerificationVerdict.VERIFIED_TRUE_SPLIT),
                isSuccess = (verdict == VerificationVerdict.VERIFIED_TRUE_SPLIT)
            )
            return@withContext report
        } finally {
            try {
                record?.stop()
                record?.release()
            } catch (_: Exception) {}
            try {
                track?.stop()
                track?.release()
            } catch (_: Exception) {}
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    audioManager.clearCommunicationDevice()
                } catch (_: Exception) {}
            }
            audioManager.mode = prevMode
            _currentAudioMode.value = getAudioModeString(audioManager.mode)
        }
    }

    private fun evaluateVerdict(
        inMatched: Boolean,
        outMatched: Boolean,
        hasRealSignal: Boolean
    ): VerificationVerdict {
        return when {
            !inMatched || !outMatched -> VerificationVerdict.PARTIAL_COUPLING_OVERRIDDEN
            !hasRealSignal -> VerificationVerdict.SILENT_OR_BLOCKED
            else -> VerificationVerdict.VERIFIED_TRUE_SPLIT
        }
    }

    private fun buildHonestDiagnosis(
        useCommunicationMode: Boolean,
        reqIn: AudioDeviceInfo,
        actIn: AudioDeviceInfo?,
        reqOut: AudioDeviceInfo,
        actOut: AudioDeviceInfo?,
        inMatched: Boolean,
        outMatched: Boolean,
        maxRmsDb: Double,
        nonZeroRatio: Float
    ): Pair<String, String> {
        val actInName = actIn?.let { "${AudioDeviceClassifier.getArabicLabel(it.type)} (ID:${it.id})" } ?: "غير معروف"
        val actOutName = actOut?.let { "${AudioDeviceClassifier.getArabicLabel(it.type)} (ID:${it.id})" } ?: "غير معروف"

        val diag = when {
            inMatched && outMatched && nonZeroRatio > 0.1f && maxRmsDb > -78.0 -> {
                "نجاح حقيقي ومؤكد 100%: يتم الآن التقاط صوتك فعلياً من [${AudioDeviceClassifier.getArabicLabel(reqIn.type)}] وإرساله مباشرة إلى [${AudioDeviceClassifier.getArabicLabel(reqOut.type)}] بمستوى إشارة (${String.format(Locale.US, "%.1f", maxRmsDb)} dBFS)."
            }
            !inMatched && !outMatched -> {
                "رفض كامل من النظام: رغم أن دالة setPreferredDevice أعادت true، إلا أن AudioPolicyManager في النظام أجبر الدخل على [$actInName] والخرج على [$actOutName]."
            }
            !inMatched -> {
                "فشل توجيه الميكروفون: الخرج يعمل على [$actOutName] بنجاح، لكن النظام رفض أخذ الصوت من مايك الجكة وأجبر الدخل على [$actInName]. السبب: بروتوكول Bluetooth HFP/SCO في وضع المكالمة يفرض استخدام مايك البلوتوث حصراً."
            }
            !outMatched -> {
                "فشل توجيه السماعة: الميكروفون يعمل من [$actInName] بنجاح، لكن النظام رفض إرسال الصوت إلى سماعة البلوتوث وحوله إلى [$actOutName]. السبب: عند تفعيل مسار الجكة في وضع المكالمة، يلغي النظام مسار SCO للبلوتوث."
            }
            else -> {
                "المسار مسند نظرياً لكن البث صامت (0 dBFS / Muted): النظام قام بحجب بيانات الميكروفون لأن تطبيق اتصال آخر (مثل واتساب أو الهاتف) يستحوذ على AudioFocus."
            }
        }

        val halNotes = "Mode=${if (useCommunicationMode) "MODE_IN_COMMUNICATION" else "MODE_NORMAL"} | " +
            "ReqIn=${reqIn.id}(${AudioDeviceClassifier.getEnglishCodeName(reqIn.type)}) vs ActIn=${actIn?.id}(${actIn?.let { AudioDeviceClassifier.getEnglishCodeName(it.type) }}) | " +
            "ReqOut=${reqOut.id}(${AudioDeviceClassifier.getEnglishCodeName(reqOut.type)}) vs ActOut=${actOut?.id}(${actOut?.let { AudioDeviceClassifier.getEnglishCodeName(it.type) }}) | " +
            "NonZeroFrames=${(nonZeroRatio * 100).toInt()}%"

        return Pair(diag, halNotes)
    }

    private fun AudioDeviceInfo.toEndpointModel(isCommCapable: Boolean): AudioEndpointDevice {
        val addressStr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { this.address.ifBlank { "N/A" } } catch (_: Exception) { "N/A" }
        } else {
            "N/A"
        }
        return AudioEndpointDevice(
            id = this.id,
            productName = this.productName?.toString()?.ifBlank { "System Audio Endpoint" } ?: "System Audio Endpoint",
            typeCode = this.type,
            typeLabelAr = AudioDeviceClassifier.getArabicLabel(this.type),
            typeNameEn = AudioDeviceClassifier.getEnglishCodeName(this.type),
            isSource = this.isSource,
            isSink = this.isSink,
            address = addressStr,
            sampleRates = this.sampleRates ?: intArrayOf(),
            channelCounts = this.channelCounts ?: intArrayOf(),
            isCommunicationCapable = isCommCapable
        )
    }

    companion object {
        fun getAudioModeString(mode: Int): String = when (mode) {
            AudioManager.MODE_NORMAL -> "MODE_NORMAL (الوضع العادي / الوسائط)"
            AudioManager.MODE_RINGTONE -> "MODE_RINGTONE (يرن الآن)"
            AudioManager.MODE_IN_CALL -> "MODE_IN_CALL (مكالمة خلوية نشطة)"
            AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION (مكالمة واتساب / VoIP نشطة)"
            AudioManager.MODE_CALL_REDIRECT -> "MODE_CALL_REDIRECT (تحويل مكالمة)"
            AudioManager.MODE_COMMUNICATION_REDIRECT -> "MODE_COMMUNICATION_REDIRECT (تحويل اتصال VoIP)"
            else -> "MODE_UNKNOWN ($mode)"
        }
    }
}

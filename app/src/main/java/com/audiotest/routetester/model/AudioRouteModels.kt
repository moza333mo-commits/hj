package com.audiotest.routetester.model

import android.media.AudioDeviceInfo

/**
 * يمثل معلومات جهاز صوتي متصل (سواء كان دخل Source أو خرج Sink)
 * مع تفاصيله التقنية الحقيقية من النظام بدون أي تزييف.
 */
data class AudioEndpointDevice(
    val id: Int,
    val productName: String,
    val typeCode: Int,
    val typeLabelAr: String,
    val typeNameEn: String,
    val isSource: Boolean,
    val isSink: Boolean,
    val address: String,
    val sampleRates: IntArray,
    val channelCounts: IntArray,
    val isCommunicationCapable: Boolean
) {
    val displayTitle: String
        get() = "$typeLabelAr ($productName - ID:$id)"
}

/**
 * حالات نتيجة الاختبار الصارمة (بدون أي نتائج نجاح وهمية).
 */
enum class VerificationVerdict(val titleAr: String, val badgeText: String) {
    IDLE("بانتظار بدء الفحص", "IDLE"),
    RUNNING("جاري القياس الفعلي...", "TESTING"),
    VERIFIED_TRUE_SPLIT("نجاح فعلي مؤكد: تم فصل مسار المايك عن السماعة مع تدفق صوتي حقيقي", "PASS (TRUE SPLIT)"),
    PARTIAL_COUPLING_OVERRIDDEN("رفض من النظام: قام Android بدمج المسارين أو تجاهل الجهاز المفضل", "FAIL (OS COUPLED)"),
    SILENT_OR_BLOCKED("فشل التقاط الصوت: الجهاز مسند لكن البث صامت أو محجوب بواسطة المكالمة", "FAIL (MIC MUTED/BLOCKED)"),
    HARDWARE_MISSING("نقص في العتاد: يرجى توصيل الجهاز المطلوب قبل الاختبار", "MISSING DEVICE"),
    ERROR("خطأ برمجي أو صلاحيات ناقصة", "ERROR")
}

/**
 * تقرير مفصل لكل عملية فحص يوضح ما تم طلبه مقابل ما نفذه نظام Android فعلياً.
 */
data class RouteVerificationReport(
    val timestampMs: Long = System.currentTimeMillis(),
    val testNameAr: String,
    val audioModeName: String,
    val requestedInputDevice: AudioEndpointDevice?,
    val actualRoutedInputDevice: AudioEndpointDevice?,
    val requestedOutputDevice: AudioEndpointDevice?,
    val actualRoutedOutputDevice: AudioEndpointDevice?,
    val globalCommunicationDevice: AudioEndpointDevice?,
    val inputPreferenceAcceptedByApi: Boolean,
    val outputPreferenceAcceptedByApi: Boolean,
    val inputActuallyMatchedHardware: Boolean,
    val outputActuallyMatchedHardware: Boolean,
    val measuredRmsDbFs: Double,
    val measuredPeakAmplitude: Int,
    val nonZeroAudioFramesRatio: Float,
    val latencyEstimateMs: Int,
    val verdict: VerificationVerdict,
    val technicalDiagnosisAr: String,
    val kernelAndHalNotesAr: String
)

/**
 * سجل تشخيصي لحظي (Telemetry Log Entry)
 */
data class DiagnosticLogEntry(
    val id: Long = System.nanoTime(),
    val timeFormatted: String,
    val tag: String,
    val message: String,
    val isError: Boolean = false,
    val isSuccess: Boolean = false
)

/**
 * تصنيف أنواع أجهزة الصوت وفق ثوابت AudioDeviceInfo في Android
 */
object AudioDeviceClassifier {
    fun getArabicLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "سماعة الأذن الداخلية للهاتف (Earpiece)"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "مكبر الصوت الخارجي للهاتف (Speaker)"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "ميكروفون الهاتف الداخلي (Built-in Mic)"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "سماعة جكة سلكية مع مايك (Wired Headset TRRS)"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "سماعة جكة سلكية بدون مايك (Wired Headphones TRS)"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "بلوتوث مكالمات (Bluetooth SCO / HFP)"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "بلوتوث وسائط عالي الجودة (Bluetooth A2DP)"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "سماعة USB-C مع مايك (USB Headset)"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "جهاز صوتي عبر USB (USB Audio Device)"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "ملحق صوتي USB (USB Accessory)"
        AudioDeviceInfo.TYPE_TELEPHONY -> "خط الشبكة الخلوية (Telephony TX/RX)"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "سماعة بلوتوث طاقة منخفضة (BLE Audio Headset)"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "مكبر بلوتوث طاقة منخفضة (BLE Speaker)"
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> "بث بلوتوث Auracast (BLE Broadcast)"
        AudioDeviceInfo.TYPE_HEARING_AID -> "معين سمعي طبي (Hearing Aid)"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "مسار افتراضي داخلي للنظام (Remote Submix)"
        else -> "جهاز صوتي آخر (Type $type)"
    }

    fun getEnglishCodeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "TYPE_BUILTIN_EARPIECE"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "TYPE_BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "TYPE_BUILTIN_MIC"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "TYPE_WIRED_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "TYPE_WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "TYPE_BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "TYPE_BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "TYPE_USB_HEADSET"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "TYPE_USB_DEVICE"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TYPE_TELEPHONY"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "TYPE_BLE_HEADSET"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "TYPE_BLE_SPEAKER"
        AudioDeviceInfo.TYPE_HEARING_AID -> "TYPE_HEARING_AID"
        else -> "TYPE_UNKNOWN_$type"
    }

    fun isWiredJackOrUsbMic(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
               type == AudioDeviceInfo.TYPE_USB_HEADSET ||
               type == AudioDeviceInfo.TYPE_USB_DEVICE
    }

    fun isBluetoothOutput(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
               type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
               type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
               type == AudioDeviceInfo.TYPE_BLE_SPEAKER
    }
}

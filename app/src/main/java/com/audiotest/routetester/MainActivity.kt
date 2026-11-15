package com.audiotest.routetester

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.audiotest.routetester.model.AudioDeviceClassifier
import com.audiotest.routetester.model.AudioEndpointDevice
import com.audiotest.routetester.model.DiagnosticLogEntry
import com.audiotest.routetester.model.RouteVerificationReport
import com.audiotest.routetester.model.VerificationVerdict
import com.audiotest.routetester.ui.MainViewModel
import java.util.Locale

private val BgDark = Color(0xFF0D0B14)
private val CardDark = Color(0xFF171324)
private val CardBorder = Color(0xFF2B2342)
private val AccentPurple = Color(0xFF9D65FF)
private val AccentMint = Color(0xFF00E5A3)
private val AccentAmber = Color(0xFFFFB74D)
private val AccentRed = Color(0xFFFF5252)
private val TextPrimary = Color(0xFFF3F0FF)
private val TextSecondary = Color(0xFFB5A8D5)

private val AppDarkColorScheme = darkColorScheme(
    primary = AccentPurple,
    secondary = AccentMint,
    background = BgDark,
    surface = CardDark,
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = AppDarkColorScheme) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        AudioRouteTesterScreen(viewModel = viewModel)
                    }
                }
            }
        }
    }
}

@Composable
fun AudioRouteTesterScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val inputDevices by viewModel.inputDevices.collectAsState()
    val outputDevices by viewModel.outputDevices.collectAsState()
    val selectedInputId by viewModel.selectedInputId.collectAsState()
    val selectedOutputId by viewModel.selectedOutputId.collectAsState()
    val currentAudioMode by viewModel.currentAudioMode.collectAsState()
    val activeCommDevice by viewModel.activeCommDevice.collectAsState()
    val liveMicRmsDb by viewModel.liveMicRmsDb.collectAsState()
    val liveMicPeak by viewModel.liveMicPeak.collectAsState()
    val isLoopbackRunning by viewModel.isLoopbackRunning.collectAsState()
    val latestReport by viewModel.latestReport.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val useCommModeInBridge by viewModel.useCommModeInBridge.collectAsState()
    val injectTone by viewModel.injectTone.collectAsState()
    val isCallGuardEnabled by viewModel.isCallGuardEnabled.collectAsState()

    var hasPermissions by remember { mutableStateOf(checkRequiredPermissions(context)) }
    var selectedTab by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasPermissions = checkRequiredPermissions(context)
        viewModel.refreshHardwareList()
    }

    LaunchedEffect(Unit) {
        if (!hasPermissions) {
            permissionLauncher.launch(buildPermissionArray())
        }
    }

    Scaffold(
        containerColor = BgDark,
        topBar = {
            TopHeaderBar(
                currentMode = currentAudioMode,
                onRefresh = { viewModel.refreshHardwareList() }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!hasPermissions) {
                PermissionWarningBanner(
                    onRequest = { permissionLauncher.launch(buildPermissionArray()) }
                )
            }

            val tabs = listOf(
                "1. مختبر الفحص الفعلي",
                "2. قيود المكالمات والبدائل",
                "3. السجل الهندسي (${logs.size})"
            )

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = CardDark,
                contentColor = AccentPurple,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = AccentMint
                    )
                }
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = title,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 13.sp,
                                color = if (selectedTab == index) AccentMint else TextSecondary
                            )
                        }
                    )
                }
            }

            when (selectedTab) {
                0 -> DiagnosticLabTab(
                    inputDevices = inputDevices,
                    outputDevices = outputDevices,
                    selectedInputId = selectedInputId,
                    selectedOutputId = selectedOutputId,
                    onSelectInput = viewModel::selectInputDevice,
                    onSelectOutput = viewModel::selectOutputDevice,
                    useCommMode = useCommModeInBridge,
                    onToggleCommMode = viewModel::setUseCommModeInBridge,
                    injectTone = injectTone,
                    onToggleInjectTone = viewModel::setInjectTone,
                    isRunning = isLoopbackRunning,
                    liveDbFs = liveMicRmsDb,
                    livePeak = liveMicPeak,
                    onToggleBridge = viewModel::toggleSplitBridgeTest,
                    onRunCallProbe = viewModel::runCallModeCouplingCheck,
                    latestReport = latestReport,
                    activeCommDevice = activeCommDevice
                )
                1 -> CallConstraintsAndWorkaroundsTab(
                    isCallGuardEnabled = isCallGuardEnabled,
                    onToggleCallGuard = viewModel::toggleCallGuardService
                )
                2 -> TelemetryLogsTab(
                    logs = logs,
                    onClear = viewModel::clearLogs
                )
            }
        }
    }
}

@Composable
private fun TopHeaderBar(currentMode: String, onRefresh: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardDark)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(AccentPurple.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = AccentMint
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Audio Route Tester Pro",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "فحص حقيقي لفصل مايك الجكة عن سماعة البلوتوث (Zero-Fake Verification)",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "تحديث الأجهزة",
                    tint = AccentMint
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "وضع النظام الحالي: $currentMode",
            fontSize = 11.sp,
            color = AccentAmber,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun PermissionWarningBanner(onRequest: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = AccentRed.copy(alpha = 0.15f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "صلاحيات الصوت والبلوتوث مطلوبة",
                    fontWeight = FontWeight.Bold,
                    color = AccentRed,
                    fontSize = 14.sp
                )
                Text(
                    text = "يحتاج التطبيق لصلاحية الميكروفون والبلوتوث لقراءة معرفات الأجهزة الحقيقية وقياس الإشارة.",
                    color = TextPrimary,
                    fontSize = 12.sp
                )
            }
            Button(
                onClick = onRequest,
                colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
            ) {
                Text("منح الصلاحيات", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun DiagnosticLabTab(
    inputDevices: List<AudioEndpointDevice>,
    outputDevices: List<AudioEndpointDevice>,
    selectedInputId: Int?,
    selectedOutputId: Int?,
    onSelectInput: (Int) -> Unit,
    onSelectOutput: (Int) -> Unit,
    useCommMode: Boolean,
    onToggleCommMode: (Boolean) -> Unit,
    injectTone: Boolean,
    onToggleInjectTone: (Boolean) -> Unit,
    isRunning: Boolean,
    liveDbFs: Double,
    livePeak: Int,
    onToggleBridge: () -> Unit,
    onRunCallProbe: () -> Unit,
    latestReport: RouteVerificationReport?,
    activeCommDevice: AudioEndpointDevice?
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Hardware Readiness Summary Banner
        item {
            HardwareReadinessCard(inputDevices = inputDevices, outputDevices = outputDevices)
        }

        // 2. Input Selector (Mic)
        item {
            DeviceSelectorCard(
                title = "1. اختر جهاز الدخل المطلوب (الميكروفون - Source)",
                subtitle = "اختر سماعة الجكة السلكية (TYPE_WIRED_HEADSET) أو مايك USB",
                icon = Icons.Default.HeadsetMic,
                devices = inputDevices,
                selectedId = selectedInputId,
                onSelect = onSelectInput
            )
        }

        // 3. Output Selector (Speaker/Bluetooth)
        item {
            DeviceSelectorCard(
                title = "2. اختر جهاز الخرج المطلوب (الاستماع - Sink)",
                subtitle = "اختر سماعة البلوتوث (A2DP للوسائط أو SCO للمكالمات)",
                icon = Icons.Default.BluetoothAudio,
                devices = outputDevices,
                selectedId = selectedOutputId,
                onSelect = onSelectOutput
            )
        }

        // 4. Test Execution & Real-Time Signal Meter
        item {
            TestControlsCard(
                useCommMode = useCommMode,
                onToggleCommMode = onToggleCommMode,
                injectTone = injectTone,
                onToggleInjectTone = onToggleInjectTone,
                isRunning = isRunning,
                liveDbFs = liveDbFs,
                livePeak = livePeak,
                onToggleBridge = onToggleBridge,
                onRunCallProbe = onRunCallProbe
            )
        }

        // 5. Strict Honest Verification Verdict Card
        item {
            if (latestReport != null) {
                VerificationReportCard(report = latestReport)
            }
        }
    }
}

@Composable
private fun HardwareReadinessCard(
    inputDevices: List<AudioEndpointDevice>,
    outputDevices: List<AudioEndpointDevice>
) {
    val hasWiredMic = inputDevices.any { AudioDeviceClassifier.isWiredJackOrUsbMic(it.typeCode) }
    val hasBtOut = outputDevices.any { AudioDeviceClassifier.isBluetoothOutput(it.typeCode) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, CardBorder, RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "حالة العتاد المتصل حالياً (Live Hardware Check)",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = AccentMint
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusChip(
                    modifier = Modifier.weight(1f),
                    label = if (hasWiredMic) "مايك الجكة/USB متصل" else "مايك الجكة غير متصل",
                    isOk = hasWiredMic
                )
                StatusChip(
                    modifier = Modifier.weight(1f),
                    label = if (hasBtOut) "سماعة البلوتوث متصلة" else "البلوتوث غير متصل",
                    isOk = hasBtOut
                )
            }
            if (!hasWiredMic) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "تنبيه: إذا قمت بتوصيل سماعة سلكية ولم تظهر كـ Wired Headset، فتأكد أن كابل الجكة يدعم 4 أقطاب (TRRS) وليس 3 أقطاب (TRS للسماعات فقط).",
                    fontSize = 11.sp,
                    color = AccentAmber
                )
            }
        }
    }
}

@Composable
private fun StatusChip(modifier: Modifier = Modifier, label: String, isOk: Boolean) {
    val color = if (isOk) AccentMint else AccentAmber
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isOk) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label, fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DeviceSelectorCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    devices: List<AudioEndpointDevice>,
    selectedId: Int?,
    onSelect: (Int) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, CardBorder, RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = icon, contentDescription = null, tint = AccentPurple)
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(text = title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextPrimary)
                    Text(text = subtitle, fontSize = 11.sp, color = TextSecondary)
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            devices.forEach { dev ->
                val isSelected = dev.id == selectedId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) AccentPurple.copy(alpha = 0.16f) else Color.Transparent)
                        .clickable { onSelect(dev.id) }
                        .padding(vertical = 6.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = { onSelect(dev.id) },
                        colors = RadioButtonDefaults.colors(selectedColor = AccentMint)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = dev.typeLabelAr,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = TextPrimary
                        )
                        Text(
                            text = "Product: ${dev.productName} | ID: ${dev.id} | ${dev.typeNameEn}",
                            fontSize = 11.sp,
                            color = TextSecondary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TestControlsCard(
    useCommMode: Boolean,
    onToggleCommMode: (Boolean) -> Unit,
    injectTone: Boolean,
    onToggleInjectTone: (Boolean) -> Unit,
    isRunning: Boolean,
    liveDbFs: Double,
    livePeak: Int,
    onToggleBridge: () -> Unit,
    onRunCallProbe: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, CardBorder, RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "3. تشغيل الفحص الحقيقي وقياس الإشارة (Real-Time Verification)",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = AccentMint
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("تفعيل وضع المكالمات (MODE_IN_COMMUNICATION)", fontSize = 13.sp, color = TextPrimary)
                    Text(
                        "عند التفعيل نحاكي بيئة مكالمة واتساب لنرى إن كان النظام سيسمح بالفصل أم سيجبر الدمج.",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
                Switch(
                    checked = useCommMode,
                    onCheckedChange = onToggleCommMode,
                    enabled = !isRunning,
                    colors = SwitchDefaults.colors(checkedThumbColor = AccentMint)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("دمج نغمة اختبار خفيفة (440Hz Tone) مع صوت المايك", fontSize = 13.sp, color = TextPrimary)
                    Text("يساعدك على التأكد سمعياً من وصول البث إلى سماعة البلوتوث فوراً.", fontSize = 11.sp, color = TextSecondary)
                }
                Switch(
                    checked = injectTone,
                    onCheckedChange = onToggleInjectTone,
                    enabled = !isRunning,
                    colors = SwitchDefaults.colors(checkedThumbColor = AccentPurple)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Real-Time VU Meter
            val normalizedLevel = ((liveDbFs + 96.0) / 96.0).coerceIn(0.0, 1.0).toFloat()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(BgDark)
                    .padding(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "مؤشر طاقة الميكروفون الفعلي (PCM RMS):",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    Text(
                        text = "${String.format(Locale.US, "%.1f", liveDbFs)} dBFS (Peak: $livePeak)",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (liveDbFs > -60.0) AccentMint else AccentAmber
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { normalizedLevel },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp)),
                    color = if (liveDbFs > -55.0) AccentMint else AccentPurple,
                    trackColor = CardBorder
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onToggleBridge,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRunning) AccentRed else AccentPurple
                    )
                ) {
                    Icon(
                        imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isRunning) "إيقاف الجسر الصوتي" else "فحص الجسر الصوتي (Stream Split)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = onRunCallProbe,
                    enabled = !isRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "فحص setCommunicationDevice",
                        fontSize = 11.sp,
                        color = AccentMint
                    )
                }
            }
        }
    }
}

@Composable
private fun VerificationReportCard(report: RouteVerificationReport) {
    val context = LocalContext.current
    val verdictColor by animateColorAsState(
        targetValue = when (report.verdict) {
            VerificationVerdict.VERIFIED_TRUE_SPLIT -> AccentMint
            VerificationVerdict.PARTIAL_COUPLING_OVERRIDDEN -> AccentRed
            VerificationVerdict.SILENT_OR_BLOCKED -> AccentAmber
            else -> TextSecondary
        },
        label = "verdictColor"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, verdictColor, RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = if (report.verdict == VerificationVerdict.VERIFIED_TRUE_SPLIT) {
                            Icons.Default.CheckCircle
                        } else {
                            Icons.Default.ErrorOutline
                        },
                        contentDescription = null,
                        tint = verdictColor
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = report.verdict.titleAr,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = verdictColor
                        )
                        Text(
                            text = report.testNameAr,
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }

                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val text = buildReportCopyText(report)
                        clipboard.setPrimaryClip(ClipData.newPlainText("AudioRouteReport", text))
                        Toast.makeText(context, "تم نسخ التقرير الهندسي للحافظة", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "نسخ التقرير", tint = TextSecondary)
                }
            }

            Divider(modifier = Modifier.padding(vertical = 10.dp), color = CardBorder)

            // Comparison Table: Requested vs Actual Hardware Routed by OS
            RouteComparisonRow(
                label = "مسار الدخل (المايك):",
                requested = report.requestedInputDevice?.displayTitle ?: "غير محدد",
                actual = report.actualRoutedInputDevice?.displayTitle ?: "لم يتم التوجيه",
                matched = report.inputActuallyMatchedHardware
            )
            Spacer(modifier = Modifier.height(8.dp))
            RouteComparisonRow(
                label = "مسار الخرج (السماعة):",
                requested = report.requestedOutputDevice?.displayTitle ?: "غير محدد",
                actual = report.actualRoutedOutputDevice?.displayTitle ?: "لم يتم التوجيه",
                matched = report.outputActuallyMatchedHardware
            )

            Divider(modifier = Modifier.padding(vertical = 10.dp), color = CardBorder)

            Text(
                text = "التشخيص الهندسي الدقيق (بدون تزييف):",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = AccentPurple
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = report.technicalDiagnosisAr,
                fontSize = 12.sp,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = report.kernelAndHalNotesAr,
                fontSize = 10.sp,
                color = TextSecondary,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun RouteComparisonRow(
    label: String,
    requested: String,
    actual: String,
    matched: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(BgDark)
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = TextPrimary)
            Text(
                text = if (matched) "تطابق فعلي (MATCH)" else "تم تغييره من النظام (MISMATCH)",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (matched) AccentMint else AccentRed
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = "• المطلوب برمجياً: $requested", fontSize = 11.sp, color = TextSecondary)
        Text(
            text = "• المنفذ فعلياً في HAL: $actual",
            fontSize = 11.sp,
            color = if (matched) AccentMint else AccentAmber,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun CallConstraintsAndWorkaroundsTab(
    isCallGuardEnabled: Boolean,
    onToggleCallGuard: (Boolean) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, AccentPurple, RoundedCornerShape(14.dp)),
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Security, contentDescription = null, tint = AccentMint)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "حارس مسار المكالمات التلقائي (Call Mic Guard Service)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = TextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "يعمل كخدمة أمامية (Foreground Service) + زر في شريط الإشعارات السريع (Quick Settings Tile). عند رصد مكالمة واتساب أو اتصال هاتفي، يقوم بإجبار النظام على استخدام مايك سماعة الجكة السلكية بدلاً من مايك البلوتوث الرديء.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isCallGuardEnabled) "الحارس نشط الآن في الخلفية" else "الحارس متوقف حالياً",
                            fontWeight = FontWeight.Bold,
                            color = if (isCallGuardEnabled) AccentMint else TextSecondary,
                            fontSize = 13.sp
                        )
                        Switch(
                            checked = isCallGuardEnabled,
                            onCheckedChange = onToggleCallGuard,
                            colors = SwitchDefaults.colors(checkedThumbColor = AccentMint)
                        )
                    }
                }
            }
        }

        item {
            TechnicalRealityCard(
                title = "لماذا يفشل فصل المايك عن البلوتوث داخل مكالمات واتساب في Android؟",
                points = listOf(
                    "1. ارتباط المسار في AudioPolicyManager: في نظام Android، عندما يطلب واتساب وضع المكالمات (MODE_IN_COMMUNICATION)، يتعامل النظام مع جهاز الاتصال كوحدة متكاملة (Coupled Endpoint) تشمل المايك والسماعة معاً عبر دالة setCommunicationDevice.",
                    "2. قيد بروتوكول البلوتوث (SCO/HFP مقابل A2DP): أثناء المكالمات، يتحول البلوتوث من وضع الموسيقى (A2DP أحادي الاتجاه) إلى وضع المكالمات (HFP/SCO ثنائي الاتجاه)، والذي يحجز قناة الميكروفون الخاصة بالبلوتوث إجبارياً في الـ Audio HAL.",
                    "3. صلاحية MODIFY_AUDIO_ROUTING: دالة AudioPolicy الداخلي التي تسمح بتغيير مسار تطبيق آخر محمية بصلاحية Signature/System (تعمل فقط لتطبيقات النظام أو بصلاحيات Root / Magisk)."
                )
            )
        }

        item {
            TechnicalRealityCard(
                title = "أفضل 4 حلول عملية واقعية للتغلب على ضعف مايك البلوتوث بدون وضع السلك في الأذن:",
                points = listOf(
                    "الحل الأول (مباشر ومضمون 100% على كل الهواتف بدون روت): استخدام كابل مقسم جكة 3.5mm TRRS Y-Splitter (مخرج مايك + مخرج سماعة)؛ توصل في مدخل المايك ميكروفون ياقة صغير (Lavalier Mic)، وتترك مدخل السماعة فارغاً أو توصل به جهاز بث بلوتوث صغير (Bluetooth 3.5mm Aux Transmitter) يبث لسماعة البلوتوث لديك!",
                    "الحل الثاني (تعديل كابل الجكة السلكية): استخدام سماعة جكة سلكية (أو قص السماعات الأذنية منها والإبقاء على الميكروفون فقط مع مقاومة 16-32 أوم أو تركها معلقة على القميص) مع تفعيل مكبر صوت الهاتف أو جهاز بث خارجي.",
                    "الحل الثالث (على أجهزة Samsung Galaxy - One UI): استخدام ميزة Separate App Sound مع تطبيق SoundAssistant الرسمي من سامسونج لتوجيه صوت الوسائط للبلوتوث مع إبقاء مسار الاتصال على الجكة.",
                    "الحل الرابع (للمستخدمين المتقدمين Root / Audio Policy Patch): تعديل ملف audio_policy_configuration.xml في المسار /vendor/etc لفصل attachedDevices الخاصة بـ handset_mic عن مسار BT SCO."
                )
            )
        }
    }
}

@Composable
private fun TechnicalRealityCard(title: String, points: List<String>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, CardBorder, RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = AccentAmber)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = AccentAmber)
            }
            points.forEach { pt ->
                Text(text = pt, fontSize = 12.sp, color = TextPrimary, lineHeight = 18.sp)
            }
        }
    }
}

@Composable
private fun TelemetryLogsTab(logs: List<DiagnosticLogEntry>, onClear: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "سجل أحداث النظام والـ Audio HAL المباشر",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = AccentMint
            )
            OutlinedButton(onClick = onClear) {
                Text("مسح السجل", fontSize = 12.sp, color = TextSecondary)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(CardDark)
                .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(logs.size) { index ->
                val item = logs[index]
                val color = when {
                    item.isError -> AccentRed
                    item.isSuccess -> AccentMint
                    else -> TextPrimary
                }
                Text(
                    text = "[${item.timeFormatted}] [${item.tag}] ${item.message}",
                    fontSize = 11.sp,
                    color = color,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

private fun buildReportCopyText(report: RouteVerificationReport): String {
    return buildString {
        appendLine("=== Audio Route Tester Pro - Diagnostic Report ===")
        appendLine("Test: ${report.testNameAr}")
        appendLine("Verdict: ${report.verdict.badgeText} - ${report.verdict.titleAr}")
        appendLine("Audio Mode: ${report.audioModeName}")
        appendLine("Requested Input: ${report.requestedInputDevice?.displayTitle}")
        appendLine("Actual Routed Input: ${report.actualRoutedInputDevice?.displayTitle}")
        appendLine("Requested Output: ${report.requestedOutputDevice?.displayTitle}")
        appendLine("Actual Routed Output: ${report.actualRoutedOutputDevice?.displayTitle}")
        appendLine("Measured Signal: ${String.format(Locale.US, "%.1f", report.measuredRmsDbFs)} dBFS (Peak: ${report.measuredPeakAmplitude})")
        appendLine("Diagnosis: ${report.technicalDiagnosisAr}")
        appendLine("HAL Notes: ${report.kernelAndHalNotesAr}")
    }
}

private fun buildPermissionArray(): Array<String> {
    val list = mutableListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_PHONE_STATE
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        list.add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        list.add(Manifest.permission.POST_NOTIFICATIONS)
    }
    return list.toTypedArray()
}

private fun checkRequiredPermissions(context: Context): Boolean {
    val recGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    val btGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
    return recGranted && btGranted
}

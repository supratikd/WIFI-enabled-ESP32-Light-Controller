package com.example.esp32relay

import android.content.Context
import android.os.Bundle
import android.view.animation.OvershootInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.max

private val Saffron = Color(0xFFFB8C00)
private val SaffronDark = Color(0xFFE65100)
private val Gold = Color(0xFFFFB300)
private val Success = Color(0xFF43A047)
private val Danger = Color(0xFFE53935)

private val LightColors = lightColors(
    primary = Saffron, primaryVariant = SaffronDark, secondary = Gold,
    background = Color(0xFFF5F6F8), surface = Color.White, onPrimary = Color.White
)
private val DarkColors = darkColors(
    primary = Saffron, primaryVariant = SaffronDark, secondary = Gold,
    background = Color(0xFF101214), surface = Color(0xFF1C1F23), onPrimary = Color.White
)

// Patterns must match PATTERNS[] in esp-controller/dewali-light/dewali-light.ino.
// Narrow patterns use 4 bits (mirrored in 8ch mode); wide patterns use 8 bits (folded in 4ch mode).
data class Pattern(val name: String, val steps: IntArray, val wide: Boolean = false)

val PATTERNS = listOf(
    Pattern("Dark Chase", intArrayOf(0b1110, 0b1101, 0b1011, 0b0111)),
    Pattern("Chase", intArrayOf(0b0001, 0b0010, 0b0100, 0b1000)),
    Pattern("Pairs", intArrayOf(0b0011, 0b0110, 0b1100, 0b1001)),
    Pattern("Pair Sweep", intArrayOf(0b1100, 0b0110, 0b0011, 0b1001)),
    Pattern("Bounce", intArrayOf(0b0001, 0b0010, 0b0100, 0b1000, 0b0100, 0b0010)),
    Pattern("Fill Up", intArrayOf(0b0001, 0b0011, 0b0111, 0b1111)),
    Pattern("Drain", intArrayOf(0b1111, 0b1110, 0b1100, 0b1000, 0b0000)),
    Pattern("Alternate", intArrayOf(0b1010, 0b0101)),
    Pattern("Blink Burst", intArrayOf(0b0110, 0b1111, 0b1001, 0b0000)),
    Pattern("Disco", intArrayOf(0b1010, 0b0101, 0b1110, 0b0111, 0b1101, 0b1011, 0b1111, 0b0000)),
    Pattern("Chase 8", intArrayOf(0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80), true),
    Pattern("Reverse 8", intArrayOf(0x80, 0x40, 0x20, 0x10, 0x08, 0x04, 0x02, 0x01), true),
    Pattern("Ping-Pong", intArrayOf(0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x40, 0x20, 0x10, 0x08, 0x04, 0x02), true),
    Pattern("Fill 8", intArrayOf(0x01, 0x03, 0x07, 0x0F, 0x1F, 0x3F, 0x7F, 0xFF), true),
    Pattern("Fill & Drain", intArrayOf(0x01, 0x03, 0x07, 0x0F, 0x1F, 0x3F, 0x7F, 0xFF, 0xFE, 0xFC, 0xF8, 0xF0, 0xE0, 0xC0, 0x80, 0x00), true),
    Pattern("Center Out", intArrayOf(0x18, 0x3C, 0x7E, 0xFF, 0x00), true),
    Pattern("Edges In", intArrayOf(0x81, 0x42, 0x24, 0x18), true),
    Pattern("Alt 8", intArrayOf(0xAA, 0x55), true),
    Pattern("Pair Run", intArrayOf(0x03, 0x0C, 0x30, 0xC0), true),
    Pattern("Twinkle", intArrayOf(0x5A, 0xA5, 0x3C, 0xC3, 0x66, 0x99, 0xF0, 0x0F), true)
)

const val AUTO_PATTERN_MS = 10_000L

fun effectiveMask(p: Pattern, step: Int, channels: Int): Int {
    var m = p.steps[step % max(1, p.steps.size)]
    if (p.wide) {
        if (channels == 4) m = (m or (m shr 4)) and 0x0F
    } else {
        m = m and 0x0F
        if (channels == 8) m = m or (m shl 4)
    }
    return m
}

class MainActivity : ComponentActivity() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        super.onDestroy()
        appScope.cancel()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()

        setContent {
            MaterialTheme(colors = if (isSystemInDarkTheme()) DarkColors else LightColors) {
                Surface(color = MaterialTheme.colors.background) {
                    AppNavigation(appScope)
                }
            }
        }
    }
}

@Composable
fun AppNavigation(appScope: CoroutineScope) {
    var showSplashScreen by remember { mutableStateOf(true) }

    if (showSplashScreen) {
        SplashScreenContent(onTimeout = { showSplashScreen = false })
    } else {
        ESP32RelayController(appScope)
    }
}

@Composable
fun SplashScreenContent(onTimeout: () -> Unit) {
    val scale = remember { Animatable(0f) }
    val alpha = remember { Animatable(1f) }

    LaunchedEffect(key1 = true) {
        scale.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 800, easing = { OvershootInterpolator(2f).getInterpolation(it) })
        )
        delay(1500L)
        alpha.animateTo(targetValue = 0f, animationSpec = tween(durationMillis = 300))
        onTimeout()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background)
            .alpha(alpha.value),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.scale(scale.value)
        ) {
            Text("Diwali Light Controller", fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(8.dp))
            Text("- by Supratik", fontSize = 18.sp, color = Color.Gray, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun ESP32RelayController(appScope: CoroutineScope) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("controller", Context.MODE_PRIVATE) }
    val haptics = LocalHapticFeedback.current

    var stepMs by remember { mutableStateOf(prefs.getFloat("stepMs", 300f)) }
    var ip by remember { mutableStateOf(prefs.getString("ip", "192.168.0.26") ?: "192.168.0.26") }
    var portStr by remember { mutableStateOf(prefs.getString("port", "8080") ?: "8080") }
    var channelCount by remember { mutableStateOf(if (prefs.getInt("channels", 4) == 8) 8 else 4) }
    var connected by remember { mutableStateOf(false) }
    var connectionStatus by remember { mutableStateOf("Disconnected") }
    var socketRef by remember { mutableStateOf<Socket?>(null) }
    var writerRef by remember { mutableStateOf<PrintWriter?>(null) }
    var isAuto by remember { mutableStateOf(true) }
    var selectedPatternIdx by remember { mutableStateOf(0) }
    var animStep by remember { mutableStateOf(0) }
    var animPatternIdx by remember { mutableStateOf(0) }
    var sliderDragging by remember { mutableStateOf(false) }

    fun send(cmd: String) = sendCommandOverSocket(appScope, writerRef, cmd)

    // Local preview animation; in AUTO mode it cycles patterns like the firmware does
    LaunchedEffect(isAuto, selectedPatternIdx, stepMs) {
        animPatternIdx = if (isAuto) 0 else selectedPatternIdx
        animStep = 0
        var sincePatternChange = 0L
        while (isActive) {
            val stepDelay = stepMs.toLong().coerceAtLeast(20L)
            delay(stepDelay)
            sincePatternChange += stepDelay
            if (isAuto && sincePatternChange >= AUTO_PATTERN_MS) {
                animPatternIdx = (animPatternIdx + 1) % PATTERNS.size
                animStep = 0
                sincePatternChange = 0L
            } else {
                animStep = (animStep + 1) % max(1, PATTERNS[animPatternIdx].steps.size)
            }
        }
    }

    fun applyStatus(line: String) {
        val fields = line.split(";").mapNotNull {
            val i = it.indexOf(':')
            if (i > 0) it.substring(0, i).trim() to it.substring(i + 1).trim() else null
        }.toMap()
        fields["CHANNELS"]?.toIntOrNull()?.takeIf { it == 4 || it == 8 }?.let { channelCount = it }
        fields["MODE"]?.let { isAuto = it == "AUTO" }
        fields["PATTERN"]?.removePrefix("P")?.toIntOrNull()?.let {
            if (it in 1..PATTERNS.size) selectedPatternIdx = it - 1
        }
        if (!sliderDragging) fields["STEP_MS"]?.toFloatOrNull()?.let { stepMs = it.coerceIn(50f, 1000f) }
    }

    fun disconnect() {
        appScope.launch {
            try { writerRef?.close(); socketRef?.close() } catch (_: Exception) { }
            connected = false
            connectionStatus = "Disconnected"
        }
    }

    fun connect() {
        appScope.launch {
            connectionStatus = "Connecting..."
            try {
                val s = Socket().also { socketRef = it }
                s.connect(InetSocketAddress(ip.trim(), portStr.toIntOrNull() ?: 8080), 3000)
                val writer = PrintWriter(s.getOutputStream(), true)
                writerRef = writer
                connected = true
                connectionStatus = "Connected to ${ip.trim()}"
                prefs.edit().putString("ip", ip.trim()).putString("port", portStr).apply()
                writer.println("CHANNELS $channelCount")
                launch {
                    try {
                        val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                        while (s.isConnected && isActive) {
                            val line = reader.readLine() ?: break
                            if (line.startsWith("STATUS:")) withContext(Dispatchers.Main) { applyStatus(line) }
                        }
                    } catch (e: Exception) {
                        if (isActive) connectionStatus = "Connection lost: ${e.message}"
                    } finally {
                        connected = false
                    }
                }
            } catch (e: Exception) {
                connectionStatus = "Connect failed: ${e.message}"
                connected = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ScreenHeader(connected, connectionStatus)

        ConnectionCard(
            ip = ip, portStr = portStr, connected = connected,
            onIpChange = { ip = it }, onPortChange = { portStr = it },
            onConnectClick = { if (!connected) connect() else disconnect() }
        )

        ControlCard(
            isAuto = isAuto, channelCount = channelCount, stepMs = stepMs,
            onAutoClick = { isAuto = true; send("AUTO") },
            onManualClick = { isAuto = false; send("M P${selectedPatternIdx + 1}") },
            onChannelChange = {
                channelCount = it
                prefs.edit().putInt("channels", it).apply()
                send("CHANNELS $it")
            },
            onAllOnClick = { send("ON") },
            onAllOffClick = { send("OFF") },
            onStepMsChange = { sliderDragging = true; stepMs = it },
            onStepMsFinished = {
                sliderDragging = false
                prefs.edit().putFloat("stepMs", stepMs).apply()
                send("STEP ${stepMs.toInt()}")
            }
        )

        PatternCard(
            isAuto = isAuto, selectedPatternIdx = selectedPatternIdx, channelCount = channelCount,
            onPatternClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                selectedPatternIdx = it
                if (!isAuto) send("M P${it + 1}")
            },
            onRunSelected = {
                isAuto = false
                send("M P${selectedPatternIdx + 1}")
            }
        )

        PreviewCard(
            isAuto = isAuto, patternIdx = animPatternIdx, animStep = animStep, channelCount = channelCount
        )
    }
}

@Composable
fun ScreenHeader(connected: Boolean, status: String) {
    val brush = Brush.horizontalGradient(listOf(SaffronDark, Saffron, Gold))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(brush)
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("✨ Happy Diwali", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("Diwali Light Controller", fontSize = 13.sp, color = Color.White.copy(alpha = 0.9f))
            }
            val dot by animateColorAsState(if (connected) Success else Danger)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.25f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (connected) "Online" else "Offline",
                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
    if (!connected && status.startsWith("Connect")) {
        Text(status, color = Danger, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SectionTitle(text: String, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, fontSize = 12.sp, color = Color.Gray)
    }
}

@Composable
fun AppCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        elevation = 3.dp,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
fun <T> SegmentedSelector(
    options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colors.onSurface.copy(alpha = 0.08f))
            .padding(3.dp)
    ) {
        options.forEach { (value, label) ->
            val isSel = value == selected
            val bg by animateColorAsState(if (isSel) MaterialTheme.colors.primary else Color.Transparent)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(bg)
                    .clickable { onSelect(value) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = if (isSel) Color.White else MaterialTheme.colors.onSurface
                )
            }
        }
    }
}

@Composable
fun ConnectionCard(
    ip: String, portStr: String, connected: Boolean,
    onIpChange: (String) -> Unit, onPortChange: (String) -> Unit, onConnectClick: () -> Unit
) {
    AppCard {
        SectionTitle("Connection")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = ip, onValueChange = onIpChange, label = { Text("ESP32 IP") }, singleLine = true,
                enabled = !connected, modifier = Modifier.weight(1f), textStyle = TextStyle(fontSize = 14.sp)
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = portStr, onValueChange = { onPortChange(it.filter(Char::isDigit)) }, label = { Text("Port") },
                singleLine = true, enabled = !connected, modifier = Modifier.width(90.dp), textStyle = TextStyle(fontSize = 14.sp)
            )
        }
        Button(
            onClick = onConnectClick, modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(backgroundColor = if (connected) Danger else MaterialTheme.colors.primary)
        ) {
            Text(if (!connected) "Connect" else "Disconnect", color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ControlCard(
    isAuto: Boolean, channelCount: Int, stepMs: Float,
    onAutoClick: () -> Unit, onManualClick: () -> Unit, onChannelChange: (Int) -> Unit,
    onAllOnClick: () -> Unit, onAllOffClick: () -> Unit,
    onStepMsChange: (Float) -> Unit, onStepMsFinished: () -> Unit
) {
    AppCard {
        SectionTitle("Controls")
        Text("Mode", fontSize = 12.sp, color = Color.Gray)
        SegmentedSelector(
            options = listOf(true to "AUTO", false to "MANUAL"), selected = isAuto,
            onSelect = { if (it) onAutoClick() else onManualClick() }, modifier = Modifier.fillMaxWidth()
        )
        Text("Relay channels", fontSize = 12.sp, color = Color.Gray)
        SegmentedSelector(
            options = listOf(4 to "4 CH", 8 to "8 CH"), selected = channelCount,
            onSelect = onChannelChange, modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onAllOnClick, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) { Text("ALL ON") }
            OutlinedButton(onClick = onAllOffClick, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) { Text("ALL OFF") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Speed", fontWeight = FontWeight.SemiBold)
            Slider(
                value = stepMs, onValueChange = onStepMsChange, onValueChangeFinished = onStepMsFinished,
                valueRange = 50f..1000f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
            )
            Text("${stepMs.toInt()} ms", fontSize = 13.sp, modifier = Modifier.width(60.dp), textAlign = TextAlign.End)
        }
    }
}

@Composable
fun PatternCard(
    isAuto: Boolean, selectedPatternIdx: Int, channelCount: Int,
    onPatternClick: (Int) -> Unit, onRunSelected: () -> Unit
) {
    AppCard {
        SectionTitle("Patterns", if (isAuto) "Auto cycling all" else "Tap to run")
        for (rowStart in PATTERNS.indices step 4) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                for (i in rowStart until rowStart + 4) {
                    if (i >= PATTERNS.size) {
                        Spacer(Modifier.weight(1f))
                    } else PatternTile(
                        index = i, selected = i == selectedPatternIdx, highlighted = i == selectedPatternIdx && !isAuto,
                        onClick = { onPatternClick(i) }, modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        Button(
            onClick = onRunSelected, modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Run P${selectedPatternIdx + 1} · ${PATTERNS[selectedPatternIdx].name}", color = Color.White, fontWeight = FontWeight.Bold)
        }
        if (PATTERNS[selectedPatternIdx].wide && channelCount == 4) {
            Text("P${selectedPatternIdx + 1} is an 8-channel pattern; it is folded onto 4 channels.", fontSize = 11.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun PatternTile(index: Int, selected: Boolean, highlighted: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val bg by animateColorAsState(
        if (highlighted) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface.copy(alpha = 0.06f)
    )
    val fg = if (highlighted) Color.White else MaterialTheme.colors.onSurface
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(
                width = if (selected) 1.5.dp else 0.dp,
                color = if (selected) MaterialTheme.colors.primary else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 2.dp)
    ) {
        Text("P${index + 1}", color = fg, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(
            PATTERNS[index].name, color = fg.copy(alpha = 0.8f), fontSize = 9.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
        )
    }
}

@Composable
fun PreviewCard(isAuto: Boolean, patternIdx: Int, animStep: Int, channelCount: Int) {
    val pattern = PATTERNS[patternIdx]
    val mask = effectiveMask(pattern, animStep, channelCount)
    AppCard {
        SectionTitle("Preview", "P${patternIdx + 1} · ${pattern.name}" + if (isAuto) " (auto)" else "")
        for (rowStart in 0 until 8 step 4) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (r in rowStart until rowStart + 4) {
                    val active = r < channelCount
                    val on = active && ((mask shr r) and 1) == 1
                    val color by animateColorAsState(
                        when {
                            on -> MaterialTheme.colors.primary
                            else -> MaterialTheme.colors.onSurface.copy(alpha = 0.12f)
                        }
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(60.dp)
                            .alpha(if (active) 1f else 0.35f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(color)
                            .border(BorderStroke(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.2f)), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "R${r + 1}", fontWeight = FontWeight.Bold,
                            color = if (on) Color.White else MaterialTheme.colors.onSurface
                        )
                    }
                }
            }
        }
        Text("Simulated preview. Relays beyond the selected channel count are dimmed.", fontSize = 11.sp, color = Color.Gray)
    }
}

fun sendCommandOverSocket(appScope: CoroutineScope, writer: PrintWriter?, cmd: String) {
    if (writer == null) return
    appScope.launch {
        try {
            writer.println(cmd)
            writer.flush()
        } catch (_: Exception) {
        }
    }
}

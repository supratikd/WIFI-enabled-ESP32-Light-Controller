package com.example.esp32relay

import android.os.Bundle
import android.view.animation.OvershootInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
            MaterialTheme {
                Surface(color = Color(0xFFF3F6F9)) {
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
            .background(Color(0xFFF3F6F9))
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
    val patterns = remember {
        listOf(
            intArrayOf(0b1110, 0b1101, 0b1011, 0b0111), intArrayOf(0b0001, 0b0010, 0b0100, 0b1000),
            intArrayOf(0b0011, 0b0110, 0b1100, 0b1001), intArrayOf(0b1100, 0b0110, 0b0011, 0b1001),
            intArrayOf(0b0001, 0b0010, 0b0100, 0b1000, 0b0100, 0b0010), intArrayOf(0b0001, 0b0011, 0b0111, 0b1111),
            intArrayOf(0b1111, 0b1110, 0b1100, 0b1000, 0b0000), intArrayOf(0b1010, 0b0101),
            intArrayOf(0b0110, 0b1111, 0b1001, 0b0000), intArrayOf(0b1010, 0b0101, 0b1110, 0b0111, 0b1101, 0b1011, 0b1111, 0b0000)
        )
    }

    var stepMs by remember { mutableStateOf(300f) }
    var ip by remember { mutableStateOf("192.168.0.26") }
    var portStr by remember { mutableStateOf("8080") }
    var connected by remember { mutableStateOf(false) }
    var connectionStatus by remember { mutableStateOf("Disconnected") }
    var socketRef by remember { mutableStateOf<Socket?>(null) }
    var writerRef by remember { mutableStateOf<PrintWriter?>(null) }
    var isAuto by remember { mutableStateOf(true) }
    var selectedPatternIdx by remember { mutableStateOf(0) }
    var animStep by remember { mutableStateOf(0) }
    var animPatternIdxInUse by remember { mutableStateOf(0) }
    var lastResponse by remember { mutableStateOf("") }

    val primaryColor = Color(0xFFFB8C00)

    // Animation loop for the local preview
    LaunchedEffect(isAuto, selectedPatternIdx, stepMs) {
        animPatternIdxInUse = if (isAuto) 0 else selectedPatternIdx
        animStep = 0
        while (isActive) {
            val patIdx = if (isAuto) animPatternIdxInUse else selectedPatternIdx
            val len = max(1, patterns[patIdx].size)
            delay(stepMs.toLong())
            animStep = (animStep + 1) % len
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        ScreenHeader(connected)
        Spacer(modifier = Modifier.height(12.dp))

        ConnectionCard(
            ip = ip, portStr = portStr, connected = connected, connectionStatus = connectionStatus, lastResponse = lastResponse,
            onIpChange = { ip = it }, onPortChange = { portStr = it },
            onConnectClick = {
                if (!connected) {
                    appScope.launch {
                        connectionStatus = "Connecting..."
                        try {
                            val s = Socket().also { socketRef = it }
                            s.connect(InetSocketAddress(ip.trim(), portStr.toIntOrNull() ?: 8080), 3000)
                            writerRef = PrintWriter(s.getOutputStream(), true)
                            connected = true
                            lastResponse = ""
                            // Launch a separate coroutine to listen for messages
                            launch {
                                try {
                                    val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                                    while (s.isConnected && isActive) {
                                        val line = reader.readLine() ?: break
                                        withContext(Dispatchers.Main) { lastResponse = line }
                                    }
                                } catch (e: Exception) {
                                    if (isActive) withContext(Dispatchers.Main) { lastResponse = "Read error: ${e.message}" }
                                } finally {
                                    withContext(Dispatchers.Main) { connected = false }
                                }
                            }
                        } catch (e: Exception) {
                            connectionStatus = "Connect failed: ${e.message}"
                            connected = false
                        }
                    }
                } else {
                    appScope.launch {
                        writerRef?.close()
                        socketRef?.close()
                        connected = false
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        ControlCard(
            isAuto = isAuto, selectedPatternIdx = selectedPatternIdx, stepMs = stepMs, primaryColor = primaryColor,
            onAutoClick = { isAuto = true; sendCommandOverSocket(appScope, writerRef, "AUTO") },
            onManualClick = { isAuto = false; sendCommandOverSocket(appScope, writerRef, "M P${selectedPatternIdx + 1}") },
            onAllOnClick = { sendCommandOverSocket(appScope, writerRef, "ON") },
            onAllOffClick = { sendCommandOverSocket(appScope, writerRef, "OFF") },
            onStepMsChange = { stepMs = it; sendCommandOverSocket(appScope, writerRef, "STEP ${it.toInt()}") },
            onPatternClick = {
                selectedPatternIdx = it
                if (!isAuto) { sendCommandOverSocket(appScope, writerRef, "M P${it + 1}") }
            },
            onRunOnceClick = { sendCommandOverSocket(appScope, writerRef, "P${selectedPatternIdx + 1}") }
        )

        Spacer(modifier = Modifier.height(12.dp))

        PreviewCard(
            patterns = patterns, isAuto = isAuto, animPatternIdxInUse = animPatternIdxInUse,
            selectedPatternIdx = selectedPatternIdx, animStep = animStep, primaryColor = primaryColor
        )
    }
}

@Composable
fun ScreenHeader(connected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column {
            Text("✨ Happy Diwali", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Diwali Light Controller", style = MaterialTheme.typography.body2)
        }
        Spacer(modifier = Modifier.weight(1f))
        val connColor by animateColorAsState(targetValue = if (connected) Color(0xFF66BB6A) else Color(0xFFEF5350))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(connColor)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(if (connected) "Connected" else "Disconnected", color = Color.White)
        }
    }
}

@Composable
fun ConnectionCard(
    ip: String, portStr: String, connected: Boolean, connectionStatus: String, lastResponse: String,
    onIpChange: (String) -> Unit, onPortChange: (String) -> Unit, onConnectClick: () -> Unit
) {
    Card(elevation = 6.dp, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = ip, onValueChange = onIpChange, label = { Text("ESP32 IP") }, modifier = Modifier.weight(1f),
                    textStyle = TextStyle(fontSize = 14.sp)
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = portStr, onValueChange = { onPortChange(it.filter(Char::isDigit)) }, label = { Text("Port") }, modifier = Modifier.width(100.dp),
                    textStyle = TextStyle(fontSize = 14.sp)
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = onConnectClick) {
                    Text(if (!connected) "Connect" else "Disconnect")
                }
            }
//            Spacer(modifier = Modifier.height(8.dp))
//            if (lastResponse.isNotBlank()) Text("Server: $lastResponse", style = MaterialTheme.typography.body2)
//            Text(connectionStatus, style = MaterialTheme.typography.caption)
        }
    }
}

@Composable
fun ControlCard(
    isAuto: Boolean, selectedPatternIdx: Int, stepMs: Float, primaryColor: Color,
    onAutoClick: () -> Unit, onManualClick: () -> Unit, onAllOnClick: () -> Unit, onAllOffClick: () -> Unit,
    onStepMsChange: (Float) -> Unit, onPatternClick: (Int) -> Unit, onRunOnceClick: () -> Unit
) {
    Card(elevation = 4.dp, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Mode and Quick Actions Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val autoBg by animateColorAsState(targetValue = if (isAuto) primaryColor else Color.LightGray)
                val manualBg by animateColorAsState(targetValue = if (!isAuto) primaryColor else Color.LightGray)

                Button(onClick = onAutoClick, colors = ButtonDefaults.buttonColors(backgroundColor = autoBg), modifier = Modifier.weight(1f)) { Text("AUTO", fontSize = 10.sp) }
                Button(onClick = onManualClick, colors = ButtonDefaults.buttonColors(backgroundColor = manualBg), modifier = Modifier.weight(1f)) { Text("MANUAL", fontSize = 10.sp) }
                OutlinedButton(onClick = onAllOnClick, modifier = Modifier.weight(1f)) { Text("ALL ON", fontSize = 10.sp) }
                OutlinedButton(onClick = onAllOffClick, modifier = Modifier.weight(1f)) { Text("ALL OFF", fontSize = 10.sp) }
            }
            Spacer(Modifier.height(12.dp))
            // Speed Controller
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Speed:", fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.width(8.dp))
                    Slider(value = stepMs, onValueChange = onStepMsChange, valueRange = 50f..1000f, modifier = Modifier.weight(1f))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("${stepMs.toInt()} ms", modifier = Modifier.width(60.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Divider()
            Spacer(Modifier.height(8.dp))
            // Pattern selection grid
            Text("Patterns:", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Column {
                for (rowStart in 0 until 10 step 5) {
                    Row {
                        for (i in rowStart until rowStart + 5) {
                            val isSelected = (i == selectedPatternIdx)
                            val bg by animateColorAsState(targetValue = if (isAuto) Color.White else if (isSelected) Color(0xFFFFD54F) else Color.White)
                            val contentColor = if (isSelected && !isAuto) Color.Black else Color.DarkGray
                            Box(
                                modifier = Modifier
                                    .padding(6.dp).weight(1f).height(44.dp).clip(RoundedCornerShape(10.dp))
                                    .background(bg)
                                    .border(width = if (isSelected && !isAuto) 2.dp else 1.dp, color = if (isSelected && !isAuto) Color(0xFFFFA726) else Color(0xFFE0E0E0), shape = RoundedCornerShape(10.dp))
                                    .clickable { onPatternClick(i) }.wrapContentSize(Alignment.Center)
                            ) {
                                Text("P${i + 1}", color = contentColor, modifier = Modifier.align(Alignment.Center))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onRunOnceClick) { Text("Run Once") }
            }
        }
    }
}

@Composable
fun PreviewCard(
    patterns: List<IntArray>, isAuto: Boolean, animPatternIdxInUse: Int, selectedPatternIdx: Int, animStep: Int, primaryColor: Color
) {
    Card(elevation = 4.dp, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Preview", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            val usePatternIdx = if (isAuto) animPatternIdxInUse else selectedPatternIdx
            val pat = patterns[usePatternIdx]
            val mask = pat[animStep % max(1, pat.size)]
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                for (r in 3 downTo 0) {
                    val bitOn = ((mask shr r) and 1) == 1
                    val animatedColor by animateColorAsState(targetValue = if (bitOn) primaryColor else Color.LightGray)
                    val textColor = if (bitOn) Color.White else Color.Black
                    Box(
                        modifier = Modifier.size(60.dp).clip(RoundedCornerShape(8.dp)).background(animatedColor).border(2.dp, Color.DarkGray, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("R${4 - r}", color = textColor, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
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

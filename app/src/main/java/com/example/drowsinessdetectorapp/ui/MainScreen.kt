package com.example.drowsinessdetectorapp.ui

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.drowsinessdetectorapp.ml.DrowsinessClassifier
import com.example.drowsinessdetectorapp.ui.camera.CameraPreview
import com.example.drowsinessdetectorapp.ui.camera.DebugInfo
import com.example.drowsinessdetectorapp.ui.camera.DrowsinessAnalyzer
import com.example.drowsinessdetectorapp.ui.camera.RectFNorm
import com.example.drowsinessdetectorapp.ui.services.AlertSender
import com.example.drowsinessdetectorapp.ui.settings.SoundConfig
import com.example.drowsinessdetectorapp.ui.settings.playCustomOrDefaultTone
import kotlinx.coroutines.delay

@Composable
fun MainScreen(viewModel: DrowsinessViewModel = viewModel()) {
    val ctx = LocalContext.current
    val analyzer = remember { DrowsinessAnalyzer(DrowsinessClassifier(ctx)) }

    // Liberar modelo al cerrar pantalla
    DisposableEffect(Unit) {
        onDispose { analyzer.close() }
    }

    val state by analyzer.state.collectAsState()
    val debugInfo by analyzer.debug.collectAsState(initial = null)
    var currentAlert by remember { mutableStateOf<String?>(null) }

    // --- ALERTA AUTOMÁTICA ---
    LaunchedEffect(state.isEyeAlert, state.isYawnAlert, state.isLostFaceAlert) {
        when {
            state.isEyeAlert -> if (currentAlert == null) currentAlert = "eyes"
            state.isYawnAlert -> if (currentAlert == null) currentAlert = "yawn"
            state.isLostFaceAlert -> if (currentAlert == null) currentAlert = "lost"
        }
    }

    LaunchedEffect(currentAlert) {
        if (currentAlert != null) {
            val alertType = currentAlert!!
            delay(2000)
            if (currentAlert == alertType) {
                playAlarmTone(ctx, alertType)
                notifySendExample(ctx, alertType)
                currentAlert = null
            }
        }
    }

    // --- UI PRINCIPAL ---
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
    ) {
        // Cámara
        CameraPreview(
            modifier = Modifier
                .fillMaxSize()
                .align(Alignment.Center),
            analyzer = analyzer
        )

        // Panel de estado
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .background(
                    Color(0x99000000),
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = "Cansancio ojos: ${"%.2f".format(state.eyeScore)}",
                fontSize = 15.sp,
                color = if (state.isEyeAlert) Color.Red else Color.White,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Bostezos: ${state.yawnCount}",
                fontSize = 15.sp,
                color = if (state.isYawnAlert) Color.Red else Color.White,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Rostro perdido: ${state.lostFaceCount}",
                fontSize = 15.sp,
                color = if (state.isLostFaceAlert) Color.Red else Color.White,
                fontWeight = FontWeight.Medium
            )
        }

        // Overlay visual (rectángulos del modelo)
        OverlayDebugCanvas(debugInfo = debugInfo)

        // --- HUD DE ALERTA ---
        currentAlert?.let { type ->
            val (title, color) = when (type) {
                "eyes" -> "Ojos cerrados prolongados" to Color(0xFFFF4444)
                "yawn" -> "¡Bostezos excesivos!" to Color(0xFFFFBB33)
                "lost" -> "Rostro no detectado" to Color(0xFF33B5E5)
                else -> "Alerta desconocida" to Color.Red
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xAA000000))
                    .align(Alignment.Center),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "⚠️ $title",
                        color = color,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "¿Todo está bien? Si no cancelas, se activará la alerta automática.",
                        color = Color.White,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(horizontal = 24.dp),
                        lineHeight = 20.sp
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Row {
                        Button(
                            onClick = { currentAlert = null },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                        ) {
                            Text("Sí, todo bien")
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Button(
                            onClick = { currentAlert = null },
                            colors = ButtonDefaults.buttonColors(containerColor = color)
                        ) {
                            Text("Cancelar alerta")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OverlayDebugCanvas(debugInfo: DebugInfo?) {
    var previewSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize(1, 1)) }
    val density = LocalDensity.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coords -> previewSize = coords.size }
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            debugInfo?.let { di ->
                val w = previewSize.width.toFloat().coerceAtLeast(1f)
                val h = previewSize.height.toFloat().coerceAtLeast(1f)

                fun rectFrom(norm: RectFNorm): androidx.compose.ui.geometry.Rect {
                    val left = (norm.left * w).coerceIn(0f, w)
                    val top = (norm.top * h).coerceIn(0f, h)
                    val right = (norm.right * w).coerceIn(0f, w)
                    val bottom = (norm.bottom * h).coerceIn(0f, h)
                    return androidx.compose.ui.geometry.Rect(
                        Offset(left, top),
                        Size(right - left, bottom - top)
                    )
                }

                // Rectángulos principales
                drawRect(Color(0x9900FF00), rectFrom(di.faceRectNorm).topLeft, rectFrom(di.faceRectNorm).size, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f))
                drawRect(Color(0x9900FFFF), rectFrom(di.eyesRectNorm).topLeft, rectFrom(di.eyesRectNorm).size, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
                drawRect(Color(0x99FFD700), rectFrom(di.mouthRectNorm).topLeft, rectFrom(di.mouthRectNorm).size, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))

                // Texto de depuración
                drawIntoCanvas { canvas ->
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.GREEN
                        isAntiAlias = true
                        textSize = 16f * density.density
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                    val txt = "eye:${"%.2f".format(di.closedProb)}  yawn:${"%.2f".format(di.yawnProb)}"
                    canvas.nativeCanvas.drawText(txt, 20f, 40f * density.density, paint)
                }
            }
        }
    }
}

// 🔔 ALARMA + MENSAJES AUTOMÁTICOS
private fun playAlarmTone(context: Context, alertType: String) {
    try {
        // 🔊 Sonido personalizado o por defecto
        playCustomOrDefaultTone(context, SoundConfig.customSoundUri)

        // 🚨 Enviar alerta automática (SMS + Telegram)
        AlertSender.sendDrowsinessAlert(context, alertType)

    } catch (e: Exception) {
        Log.w("MainScreen", "Error al reproducir tono o enviar alerta: ${e.message}")
        Toast.makeText(context, "No se pudo ejecutar la alerta", Toast.LENGTH_SHORT).show()
    }
}

private fun notifySendExample(context: Context, type: String) {
    Toast.makeText(context, "Alerta Automática: $type", Toast.LENGTH_LONG).show()
}

package com.example.drowsinessdetectorapp.ui

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.drowsinessdetectorapp.ml.DrowsinessClassifier
import com.example.drowsinessdetectorapp.ui.camera.DrowsinessAnalyzer
import com.example.drowsinessdetectorapp.ui.camera.DebugInfo
import com.example.drowsinessdetectorapp.ui.camera.RectFNorm
import com.example.drowsinessdetectorapp.ui.camera.CameraPreview
import kotlinx.coroutines.delay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight

@Composable
fun MainScreen(viewModel: DrowsinessViewModel = viewModel()) {
    val ctx = LocalContext.current

    // recordar el analizador (se crea una vez)
    val analyzer = remember {
        DrowsinessAnalyzer(DrowsinessClassifier(ctx))
    }

    // cerrar el modelo cuando la pantalla se destruya
    DisposableEffect(Unit) {
        onDispose {
            try {
                analyzer.close()
            } catch (error: Exception) {
                Log.e("MainScreen", "Error en MainScreen : ${error.message}")
            }
        }
    }

    // flujos de estado
    val state by analyzer.state.collectAsState()
    val debugInfo by analyzer.debug.collectAsState(initial = null)
    val overlayInfo by analyzer.overlay.collectAsState(initial = null)

    // alert UI state
    var currentAlert by remember { mutableStateOf<String?>(null) } //"eyes","yawn","lost"

    // triggers para mostrar modal cuando aparezca alerta
    LaunchedEffect(state.isEyeAlert, state.isYawnAlert, state.isLostFaceAlert) {
        when {
            state.isEyeAlert -> if (currentAlert == null) currentAlert = "eyes"
            state.isYawnAlert -> if (currentAlert == null) currentAlert = "yawn"
            state.isLostFaceAlert -> if (currentAlert == null) currentAlert = "lost"
        }
    }

    // auto-escalado: si el usuario no cancela en 2s, sonar y "enviar"
    LaunchedEffect(currentAlert) {
        if (currentAlert != null) {
            val alertType = currentAlert!!
            delay(2000)
            if (currentAlert == alertType) {
                playAlarmTone()
                notifySendExample(ctx, alertType)
                currentAlert = null
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        CameraPreview(modifier = Modifier.fillMaxSize(), analyzer = analyzer)

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .background(Color(0x66000000))
                .padding(8.dp)
        ) {
            Text(
                "Cansancio ojos: ${"%.2f".format(state.eyeScore)}",
                fontSize = 14.sp,
                color = if (state.isEyeAlert) Color.Red else Color.White
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Bostezos: ${state.yawnCount}",
                fontSize = 14.sp,
                color = if (state.isYawnAlert) Color.Red else Color.White
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Rostro Perdido: ${state.lostFaceCount}",
                fontSize = 14.sp,
                color = if (state.isLostFaceAlert) Color.Red else Color.White
            )
        }

        // overlay debug drawing (small canvas over preview) using debugInfo
        var previewSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize(1, 1)) }
        val density = LocalDensity.current

        Box(
            modifier = Modifier
                .matchParentSize()
                .onGloballyPositioned { coords ->
                    previewSize = coords.size
                }
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
                        return androidx.compose.ui.geometry.Rect(Offset(left, top), Size((right - left).coerceAtLeast(1f), (bottom - top).coerceAtLeast(1f)))
                    }

                    val faceR = rectFrom(di.faceRectNorm)
                    val eyeR = rectFrom(di.eyesRectNorm)
                    val mouthR = rectFrom(di.mouthRectNorm)

                    drawRect(
                        color = Color(0x9900FF00),
                        topLeft = faceR.topLeft,
                        size = faceR.size,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f)
                    )
                    drawRect(
                        color = Color(0x9900FFFF),
                        topLeft = eyeR.topLeft,
                        size = eyeR.size,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
                    )
                    drawRect(
                        color = Color(0x99FFD700),
                        topLeft = mouthR.topLeft,
                        size = mouthR.size,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
                    )

                    drawIntoCanvas { canvas ->
                        val paint = android.graphics.Paint().apply {
                            color = android.graphics.Color.GREEN
                            isAntiAlias = true
                            textSize = (14f * density.density)
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                        }
                        val txt = "eye:${"%.2f".format(di.closedProb)} ml:${"%.2f".format(di.mlkitClosed)} yawn:${"%.2f".format(di.yawnProb)}"
                        canvas.nativeCanvas.drawText(txt, 10f, 24f * density.density, paint)
                    }
                }
            }
        }

        if (currentAlert != null) {
            val title = when (currentAlert) {
                "eyes" -> "Ojos cerrados prolongados"
                "yawn" -> "Multiples bostezos detectados"
                "lost" -> "Rostro no fue encontrado"
                else -> "Alertaaaaaa"
            }
            AlertDialog(
                onDismissRequest = { currentAlert = null },
                title = { Text(title) },
                text = { Text("¿Todo esta bien? Si no cancelas, se activará la alerta.") },
                confirmButton = {
                    Button(onClick = { currentAlert = null }) { Text("Cancelar") }
                },
                dismissButton = {
                    Button(onClick = { currentAlert = null }) { Text("Aceptar") }
                }
            )
        }
    }
}

private fun playAlarmTone() {
    try {
        val tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        tone.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 1200)
    } catch (e: Exception) {
        Log.w("MainScreen", "No se pudo reproducir el tono: ${e.message}")
    }
}

private fun notifySendExample(context: Context, type: String) {
    Toast.makeText(context, "Alerta Automática: $type (ejemplo)", Toast.LENGTH_LONG).show()
    Log.i("MainScreen", "Alerta Automática enviada (ejemplo) tipo = $type")
}



//@Composable
//fun MainScreen(viewModel: DrowsinessViewModel=viewModel()){
//    //variables reactivas (screo en timepo real)
//    var drowsinessScore by remember { mutableStateOf(0f) }
//    var drowsinessState by remember { mutableStateOf("Analizando.....") }
//
//    //imterpretacion de estado
//    var isDrowsy = drowsinessScore > 0.4f
//    val statusText = if(isDrowsy) "Cansado" else "Despierto"
//    val statusColor = if(isDrowsy) Color.Red else Color(0xFF4CAF50)
//
//    Box(
//        modifier = Modifier
//            .fillMaxSize()
//            .background(Color.Black)
//    ){
//        //Camara + Analisis IA
//        CameraPreview( //mandamos el callback
//            modifier = Modifier.fillMaxSize(),
//            viewModel = viewModel,
//            onScoreChanged = {newScore ->
//                drowsinessScore = newScore
//                drowsinessState = if (newScore > 0.5f) "Cansancio Detectado" else "Todo en Orden"
//                //actualizar el viewmodel
//                viewModel.updateScroe(newScore)
//            }
//        )
//
//        //cuadro flotanto de estado
//        Column (modifier = Modifier
//            .align(Alignment.TopCenter)
//            .padding(top=32.dp),
//            horizontalAlignment = Alignment.CenterHorizontally
//
//        ){
//            Text(
//                text = "Nivel: ${"%.2f".format(drowsinessScore)}",
//                color = Color.White,
//                fontSize = 16.sp
//            )
//            Spacer(modifier = Modifier.height(8.dp))
//            Text(
//                text = statusText,
//                color= statusColor,
//                fontSize = 20.sp
//            )
//        }
//    }
//}
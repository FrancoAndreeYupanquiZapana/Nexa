package com.example.drowsinessdetectorapp.ui.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
//import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.camera.view.PreviewView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberPermissionState
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

private lateinit var cameraExecutor: ExecutorService

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    analyzer: DrowsinessAnalyzer
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val permissionState = rememberPermissionState(permission = android.Manifest.permission.CAMERA)
    androidx.compose.runtime.LaunchedEffect(Unit) { permissionState.launchPermissionRequest() }
    val hasPermission = permissionState.status is com.google.accompanist.permissions.PermissionStatus.Granted

    if (!hasPermission) {
        // permiso faltante
        androidx.compose.material3.Text("Se necesitan permisos de cámara")
        return
    }

    // collect overlay as state
    val overlayInfo by analyzer.overlay.collectAsState(initial = null)

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.matchParentSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                startCamera(ctx, lifecycleOwner, previewView, analyzer)
                previewView
            }
        )

        // overlay drawable: si overlayInfo != null, dibuja rects y textos
        DebugOverlay(overlayInfo = overlayInfo, modifier = Modifier.matchParentSize())
    }
}

@Composable
fun DebugOverlay(overlayInfo: OverlayInfo?, modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.matchParentSize()) {
            if (overlayInfo == null) return@Canvas
            val w = size.width
            val h = size.height

            // face box (green)
            drawRect(
                color = Color(0x9900FF00),
                topLeft = Offset(w * overlayInfo.faceLeft, h * overlayInfo.faceTop),
                size = Size(w * (overlayInfo.faceRight - overlayInfo.faceLeft), h * (overlayInfo.faceBottom - overlayInfo.faceTop)),
                style = Stroke(width = 5f)
            )

            // eyes box (cyan)
            drawRect(
                color = Color(0x9900FFFF),
                topLeft = Offset(w * overlayInfo.eyesLeft, h * overlayInfo.eyesTop),
                size = Size(w * (overlayInfo.eyesRight - overlayInfo.eyesLeft), h * (overlayInfo.eyesBottom - overlayInfo.eyesTop)),
                style = Stroke(width = 4f)
            )

            // mouth box (yellow)
            drawRect(
                color = Color(0x99FFD700),
                topLeft = Offset(w * overlayInfo.mouthLeft, h * overlayInfo.mouthTop),
                size = Size(w * (overlayInfo.mouthRight - overlayInfo.mouthLeft), h * (overlayInfo.mouthBottom - overlayInfo.mouthTop)),
                style = Stroke(width = 4f)
            )

            // text (use native canvas for readable text)
            drawIntoCanvas { canvas ->
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.GREEN
                    textSize = 36f
                    isFakeBoldText = true
                }
                val closedText = "eye:${"%.2f".format(overlayInfo.closedProb)} mlL:${"%.2f".format(overlayInfo.mlkitLeft)} yawn:${"%.2f".format(overlayInfo.yawnProb)}"
                canvas.nativeCanvas.drawText(closedText, 10f, 40f, paint)
            }
        }
    }
}

private fun startCamera(
    context: Context,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView,
    analyzer: DrowsinessAnalyzer
) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

    if (!::cameraExecutor.isInitialized) {
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    cameraProviderFuture.addListener({
        val cameraProvider = cameraProviderFuture.get()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val imageAnalyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor) { imageProxy ->
                    analyzer.process(imageProxy)
                }
            }

        val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalyzer
            )
            Log.i("Camara Preview", "Camara iniciada con exito")
        } catch (e: Exception) {
            Log.e("CameraPreview", "Error al iniciar cámara: ${e.message}")
        }
    }, ContextCompat.getMainExecutor(context))
}
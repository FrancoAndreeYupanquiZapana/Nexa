package com.example.drowsinessdetectorapp.ui.camera

import android.graphics.*
import android.os.Environment
import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.example.drowsinessdetectorapp.ml.DrowsinessClassifier
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// Debug info para UI overlay (normalizado 0..1)
data class DebugInfo(
    val faceRectNorm: RectFNorm,
    val eyesRectNorm: RectFNorm,
    val mouthRectNorm: RectFNorm,
    val closedProb: Float,
    val yawnProb: Float,
    val mlkitClosed: Float
)

data class RectFNorm(val left: Float, val top: Float, val right: Float, val bottom: Float)

// Estado observable
data class DrowsinessState(
    val eyeScore: Float = 0f,
    val yawnCount: Int = 0,
    val lostFaceCount: Int = 0,
    val isEyeAlert: Boolean = false,
    val isYawnAlert: Boolean = false,
    val isLostFaceAlert: Boolean = false
)

// OverlayInfo para dibujar en UI (normalizado 0..1)
data class OverlayInfo(
    val faceLeft: Float, val faceTop: Float, val faceRight: Float, val faceBottom: Float,
    val eyesLeft: Float, val eyesTop: Float, val eyesRight: Float, val eyesBottom: Float,
    val mouthLeft: Float, val mouthTop: Float, val mouthRight: Float, val mouthBottom: Float,
    val closedProb: Float,
    val mlkitLeft: Float,
    val mlkitRight: Float,
    val yawnProb: Float
)

class DrowsinessAnalyzer(
    private val model: DrowsinessClassifier,
    private var EYE_THRESHOLD: Float = 0.23f,
    private var YAWN_THRESHOLD: Float = 0.40f,
    private val calibrateOnStart: Boolean = true,
    private val calibrationFrames: Int = 120
) {
    private val _state = MutableStateFlow(DrowsinessState())
    val state: StateFlow<DrowsinessState> = _state

    private val _overlay = MutableStateFlow<OverlayInfo?>(null)
    val overlay: StateFlow<OverlayInfo?> = _overlay

    private val _debug = MutableStateFlow<DebugInfo?>(null)
    val debug: StateFlow<DebugInfo?> = _debug

    // temporales y contadores
    private var eyeClosedStart = 0L
    private var lastYawnMillis = 0L
    private var yawnCounter = 0
    private var lostFaceStart = 0L

    // PERF
    private var frameCounter = 0
    private val MOUTH_CLASSIFY_EVERY_N_FRAMES = 3

    // Debug
    private val ENABLE_SAVE_DEBUG_IMAGES = false

    // Calibracion
    private var calibSamples = mutableListOf<Float>()
    private var calibPeakYawn = 0f
    private var calibrating = calibrateOnStart

    private val detector by lazy {
        val opts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .enableTracking()
            .build()
        FaceDetection.getClient(opts)
    }

    init {
        if (calibrateOnStart) {
            Log.i("Analyzer", "Calibration ENABLED for $calibrationFrames frames")
            calibrating = true
            calibSamples.clear()
            calibPeakYawn = 0f
        }
    }

    @OptIn(ExperimentalGetImage::class)
    fun process(imageProxy: ImageProxy) {
        val rotation = imageProxy.imageInfo.rotationDegrees

        val input = imageProxy.image?.let { mediaImage ->
            InputImage.fromMediaImage(mediaImage, rotation)
        } ?: run {
            val bmp = imageProxyToBitmap(imageProxy)
            InputImage.fromBitmap(bmp, rotation)
        }

        frameCounter++
        detector.process(input)
            .addOnSuccessListener { faces ->
                if (faces.isEmpty()) {
                    handleNoFace()
                } else {
                    val face = faces.first()
                    val bmp = imageProxyToBitmap(imageProxy)
                    val rotated = rotateBitmap(bmp, rotation)
                    Log.d("Analyzer", "MLKit faces found=1 bbox=${face.boundingBox} leftEye=${face.leftEyeOpenProbability} rightEye=${face.rightEyeOpenProbability}")
                    handleFaceWithLandmarks(face, rotated, frameCounter)
                }
            }
            .addOnFailureListener { e ->
                Log.e("Analyzer", "Face detection failed: ${e?.message}")
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun handleNoFace() {
        val now = System.currentTimeMillis()
        if (lostFaceStart == 0L) lostFaceStart = now
        val elapsed = now - lostFaceStart
        val alert = elapsed >= 5000L
        _state.value = _state.value.copy(
            lostFaceCount = _state.value.lostFaceCount + 1,
            isLostFaceAlert = alert
        )
        _overlay.value = null
        _debug.value = null
        Log.d("Analyzer", "No face for ${elapsed}ms. alert=$alert")
    }

    private fun handleFaceWithLandmarks(face: Face, frameBmp: Bitmap, currentFrame: Int) {
        lostFaceStart = 0L

        val leftEyePoint = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.LEFT_EYE)?.position
        val rightEyePoint = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.RIGHT_EYE)?.position
        val mouthBottomPoint = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.MOUTH_BOTTOM)?.position
        val mouthLeftPoint = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.MOUTH_LEFT)?.position
        val mouthRightPoint = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.MOUTH_RIGHT)?.position

        val eyesRect = if (leftEyePoint != null && rightEyePoint != null) {
            val cx = ((leftEyePoint.x + rightEyePoint.x) / 2f).toInt()
            val cy = ((leftEyePoint.y + rightEyePoint.y) / 2f).toInt()
            val eyeDist = abs(rightEyePoint.x - leftEyePoint.x).toInt().coerceAtLeast(20)
            val padX = (eyeDist * 0.95).toInt().coerceAtLeast(18)
            val padY = (eyeDist * 0.6).toInt().coerceAtLeast(12)
            Rect((cx - padX).coerceAtLeast(0), (cy - padY).coerceAtLeast(0), (cx + padX).coerceAtMost(frameBmp.width), (cy + padY).coerceAtMost(frameBmp.height))
        } else {
            val bbox = face.boundingBox
            val left = bbox.left.coerceAtLeast(0)
            val top = bbox.top.coerceAtLeast(0)
            val right = bbox.right.coerceAtMost(frameBmp.width)
            val bottom = bbox.bottom.coerceAtMost(frameBmp.height)
            val h = (bottom - top).coerceAtLeast(10)
            Rect(left, top, right, top + (h * 0.28).toInt())
        }

        val mouthRect = if (mouthBottomPoint != null && mouthLeftPoint != null && mouthRightPoint != null) {
            val left = (mouthLeftPoint.x - 6).toInt().coerceAtLeast(0)
            val right = (mouthRightPoint.x + 6).toInt().coerceAtMost(frameBmp.width)
            val mouthHeightEstimate = abs(mouthRightPoint.y - mouthLeftPoint.y).toInt().coerceAtLeast(8)
            val bottom = (mouthBottomPoint.y + (mouthHeightEstimate * 0.6f)).toInt().coerceAtMost(frameBmp.height)
            val top = (mouthBottomPoint.y - (mouthHeightEstimate * 1.0f)).toInt().coerceAtLeast(0)
            Rect(left, top, right, bottom)
        } else {
            val bbox = face.boundingBox
            val left = bbox.left.coerceAtLeast(0)
            val top = bbox.top.coerceAtLeast(0)
            val right = bbox.right.coerceAtMost(frameBmp.width)
            val bottom = bbox.bottom.coerceAtMost(frameBmp.height)
            val h = (bottom - top).coerceAtLeast(10)
            Rect(left, top + (h * 0.45).toInt(), right, bottom)
        }

        val safeEyes = Rect(
            eyesRect.left.coerceAtLeast(0),
            eyesRect.top.coerceAtLeast(0),
            eyesRect.right.coerceAtMost(frameBmp.width),
            eyesRect.bottom.coerceAtMost(frameBmp.height)
        )
        val safeMouth = Rect(
            mouthRect.left.coerceAtLeast(0),
            mouthRect.top.coerceAtLeast(0),
            mouthRect.right.coerceAtMost(frameBmp.width),
            mouthRect.bottom.coerceAtMost(frameBmp.height)
        )

        // ----------------------------------------
        var probsEyesCombined = FloatArray(model.numOutputClasses) { 0f }
        try {
            if (leftEyePoint != null && rightEyePoint != null) {
                // distancia entre ojos (en pixels)
                val eyeDistF = kotlin.math.abs(rightEyePoint.x - leftEyePoint.x).coerceAtLeast(20f)
                // tamaño de crop por ojo basado en eyeDist
                val cropW = (eyeDistF * 0.9f).toInt().coerceAtLeast(28)
                val cropH = (cropW * 0.6f).toInt().coerceAtLeast(20)

                val leftEyeRect = Rect(
                    (leftEyePoint.x - cropW / 2f).toInt().coerceAtLeast(0),
                    (leftEyePoint.y - cropH / 2f).toInt().coerceAtLeast(0),
                    (leftEyePoint.x + cropW / 2f).toInt().coerceAtMost(frameBmp.width),
                    (leftEyePoint.y + cropH / 2f).toInt().coerceAtMost(frameBmp.height)
                )
                val rightEyeRect = Rect(
                    (rightEyePoint.x - cropW / 2f).toInt().coerceAtLeast(0),
                    (rightEyePoint.y - cropH / 2f).toInt().coerceAtLeast(0),
                    (rightEyePoint.x + cropW / 2f).toInt().coerceAtMost(frameBmp.width),
                    (rightEyePoint.y + cropH / 2f).toInt().coerceAtMost(frameBmp.height)
                )

                val eyeCrops = mutableListOf<Bitmap>()
                listOf(leftEyeRect, rightEyeRect).forEachIndexed { idx, r ->
                    val w = r.width()
                    val h = r.height()
                    if (w > 12 && h > 12) {
                        val c = cropBitmapSafe(frameBmp, r)
                        val scaled = Bitmap.createScaledBitmap(c, model.inputWidth, model.inputHeight, true)
                        eyeCrops.add(scaled)
                        if (ENABLE_SAVE_DEBUG_IMAGES) saveDebugBitmap(scaled, "eye_crop_${idx}")
                        Log.d("Analyzer", "eyeCrop${idx} size=${w}x${h} -> modelInput=${scaled.width}x${scaled.height}")
                    } else {
                        Log.d("Analyzer", "eyeCrop${idx} too small: ${w}x${h} (skipped)")
                    }
                }

                if (eyeCrops.isNotEmpty()) {
                    // promedio de probabilidades por crop
                    val accum = FloatArray(model.numOutputClasses) { 0f }
                    for (c in eyeCrops) {
                        val p = try { model.classify(c) } catch (e: Exception) { FloatArray(model.numOutputClasses){0f} }
                        for (i in accum.indices) accum[i] += p.getOrNull(i) ?: 0f
                    }
                    for (i in accum.indices) accum[i] = accum[i] / eyeCrops.size.toFloat()
                    probsEyesCombined = accum
                } else {
                    // fallback: usa safeEyes como región combinada
                    val cropEyes = cropBitmapSafe(frameBmp, safeEyes)
                    val inputEyes = Bitmap.createScaledBitmap(cropEyes, model.inputWidth, model.inputHeight, true)
                    probsEyesCombined = try { model.classify(inputEyes) } catch (e: Exception){ FloatArray(model.numOutputClasses){0f} }
                    if (ENABLE_SAVE_DEBUG_IMAGES) saveDebugBitmap(inputEyes, "eye_crop_fallback")
                    Log.d("Analyzer", "eye fallback used (safeEyes) size=${safeEyes.width()}x${safeEyes.height()}")
                }
            } else {
                // no landmarks -> fallback safeEyes
                val cropEyes = cropBitmapSafe(frameBmp, safeEyes)
                val inputEyes = Bitmap.createScaledBitmap(cropEyes, model.inputWidth, model.inputHeight, true)
                probsEyesCombined = try { model.classify(inputEyes) } catch (e: Exception){ FloatArray(model.numOutputClasses){0f} }
                if (ENABLE_SAVE_DEBUG_IMAGES) saveDebugBitmap(inputEyes, "eye_crop_no_landmarks")
                Log.d("Analyzer", "no eye landmarks, used safeEyes crop ${inputEyes.width}x${inputEyes.height}")
            }
        } catch (e: Exception) {
            Log.e("Analyzer", "eyes classify error: ${e.message}")
        }

        // Mouth classification
        var probsMouth = FloatArray(model.numOutputClasses) { 0f }
        val mouthWidth = safeMouth.width()
        val mouthHeight = safeMouth.height()
        val faceHeight = face.boundingBox.height()
        val faceWidth = face.boundingBox.width()

        val enoughResolution = faceHeight >= 120 || faceWidth >= 120
        if (mouthWidth > 20 && mouthHeight > 14 && enoughResolution && currentFrame % MOUTH_CLASSIFY_EVERY_N_FRAMES == 0) {
            try {
                val cropMouth = cropBitmapSafe(frameBmp, safeMouth)
                val inputMouth = Bitmap.createScaledBitmap(cropMouth, model.inputWidth, model.inputHeight, true)
                probsMouth = try { model.classify(inputMouth) } catch (e: Exception) { FloatArray(model.numOutputClasses) { 0f } }
                if (ENABLE_SAVE_DEBUG_IMAGES) saveDebugBitmap(inputMouth, "mouth_landmark")
                Log.d("Analyzer", "eyeCrop=${model.inputWidth}x${model.inputHeight} mouthCrop=${inputMouth.width}x${inputMouth.height}")
            } catch (e: Exception) {
                Log.e("Analyzer", "mouth classify error: ${e.message}")
            }
        } else {
            Log.d("Analyzer", "eyeCrop=${model.inputWidth}x${model.inputHeight} mouthCrop=${mouthWidth}x${mouthHeight} (skipped)")
        }

        // ---
        val closedProb = probsEyesCombined.getOrNull(0) ?: 0f
        val yawnProb = probsMouth.getOrNull(3) ?: 0f

        val leftEyeProb = face.leftEyeOpenProbability ?: -1f
        val rightEyeProb = face.rightEyeOpenProbability ?: -1f
        val mlkitEyeClosedEstimate = if (leftEyeProb >= 0f && rightEyeProb >= 0f) 1f - ((leftEyeProb + rightEyeProb) / 2f) else -1f

        Log.d("Analyzer", "LMK closedProb=$closedProb yawnProb=$yawnProb mlkitClosed=$mlkitEyeClosedEstimate left=$leftEyeProb right=$rightEyeProb bbox=${face.boundingBox}")

        // Normalizar rects al 0..1 usando el tamaño del bitmap
        val bw = frameBmp.width.toFloat().coerceAtLeast(1f)
        val bh = frameBmp.height.toFloat().coerceAtLeast(1f)

        val fb = face.boundingBox
        val faceLeftN = (fb.left.coerceAtLeast(0).toFloat() / bw).coerceIn(0f, 1f)
        val faceTopN = (fb.top.coerceAtLeast(0).toFloat() / bh).coerceIn(0f, 1f)
        val faceRightN = (fb.right.coerceAtMost(frameBmp.width).toFloat() / bw).coerceIn(0f, 1f)
        val faceBottomN = (fb.bottom.coerceAtMost(frameBmp.height).toFloat() / bh).coerceIn(0f, 1f)

        val eyesLeftN = (safeEyes.left.toFloat() / bw).coerceIn(0f, 1f)
        val eyesTopN = (safeEyes.top.toFloat() / bh).coerceIn(0f, 1f)
        val eyesRightN = (safeEyes.right.toFloat() / bw).coerceIn(0f, 1f)
        val eyesBottomN = (safeEyes.bottom.toFloat() / bh).coerceIn(0f, 1f)

        val mouthLeftN = (safeMouth.left.toFloat() / bw).coerceIn(0f, 1f)
        val mouthTopN = (safeMouth.top.toFloat() / bh).coerceIn(0f, 1f)
        val mouthRightN = (safeMouth.right.toFloat() / bw).coerceIn(0f, 1f)
        val mouthBottomN = (safeMouth.bottom.toFloat() / bh).coerceIn(0f, 1f)

        _overlay.value = OverlayInfo(
            faceLeft = faceLeftN, faceTop = faceTopN, faceRight = faceRightN, faceBottom = faceBottomN,
            eyesLeft = eyesLeftN, eyesTop = eyesTopN, eyesRight = eyesRightN, eyesBottom = eyesBottomN,
            mouthLeft = mouthLeftN, mouthTop = mouthTopN, mouthRight = mouthRightN, mouthBottom = mouthBottomN,
            closedProb = closedProb,
            mlkitLeft = leftEyeProb.coerceAtLeast(0f),
            mlkitRight = rightEyeProb.coerceAtLeast(0f),
            yawnProb = yawnProb
        )

        // calibracion
        if (calibrating) {
            calibSamples.add(closedProb)
            if (yawnProb > calibPeakYawn) calibPeakYawn = yawnProb
            if (calibSamples.size >= calibrationFrames) finishCalibrationAndSetThresholds()
        }

        // combine model + mlkit para puntaje de ojos
        val eyeScoreToUse = when {
            closedProb > 0.02f && mlkitEyeClosedEstimate >= 0f -> (closedProb * 0.80f) + (mlkitEyeClosedEstimate * 0.20f)
            closedProb > 0.02f -> closedProb
            mlkitEyeClosedEstimate >= 0f -> mlkitEyeClosedEstimate
            else -> 0f
        }

        // alerta
        val now = System.currentTimeMillis()
        var alertEyes = false
        if (eyeScoreToUse > EYE_THRESHOLD) {
            if (eyeClosedStart == 0L) eyeClosedStart = now
            if (now - eyeClosedStart >= 2000L) alertEyes = true
        } else {
            eyeClosedStart = 0L
        }

        var alertYawn = false
        val mouthWideEnoughForYawn = mouthWidth >= max((faceWidth * 0.28).toInt(), 40)
        if (yawnProb > YAWN_THRESHOLD && mouthWideEnoughForYawn && now - lastYawnMillis > 1500L) {
            yawnCounter++; lastYawnMillis = now
            if (yawnCounter >= 2) { alertYawn = true; yawnCounter = 0 }
        }

        _state.value = _state.value.copy(
            eyeScore = eyeScoreToUse,
            yawnCount = yawnCounter,
            isEyeAlert = alertEyes,
            isYawnAlert = alertYawn,
            isLostFaceAlert = false
        )

        val fw = frameBmp.width.toFloat()
        val fh = frameBmp.height.toFloat()
        val faceNorm = RectFNorm(
            face.boundingBox.left / fw,
            face.boundingBox.top / fh,
            face.boundingBox.right / fw,
            face.boundingBox.bottom / fh
        )
        val eyesNorm = RectFNorm(
            safeEyes.left / fw,
            safeEyes.top / fh,
            safeEyes.right / fw,
            safeEyes.bottom / fh
        )
        val mouthNorm = RectFNorm(
            safeMouth.left / fw,
            safeMouth.top / fh,
            safeMouth.right / fw,
            safeMouth.bottom / fh
        )

        _debug.value = DebugInfo(faceNorm, eyesNorm, mouthNorm, closedProb, yawnProb, mlkitEyeClosedEstimate)
    }

    private fun finishCalibrationAndSetThresholds() {
        if (calibSamples.isEmpty()) {
            calibrating = false
            Log.i("Analyzer", "Calibration ended: no samples")
            return
        }
        val mu = calibSamples.average().toFloat()
        val sigma = sqrt(calibSamples.map { (it - mu) * (it - mu) }.average().toFloat())
        val computedEyeThreshold = max(0.35f, mu + 3f * sigma)
        val computedYawnThreshold = max(0.35f, if (calibPeakYawn > 0f) calibPeakYawn * 0.7f else YAWN_THRESHOLD)

        Log.i("Analyzer", "Calibration done: mu=${"%.3f".format(mu)}, sigma=${"%.3f".format(sigma)} -> EYE_THRESHOLD set to ${"%.3f".format(computedEyeThreshold)}; YAWN_THRESHOLD set to ${"%.3f".format(computedYawnThreshold)}")

        EYE_THRESHOLD = computedEyeThreshold
        YAWN_THRESHOLD = computedYawnThreshold
        calibrating = false
        calibSamples.clear()
        calibPeakYawn = 0f
    }

    fun startCalibrationNow() {
        calibrating = true
        calibSamples.clear()
        calibPeakYawn = 0f
        Log.i("Analyzer", "Manual calibration started for $calibrationFrames frames")
    }

    fun close() {
        try {
            model.close()
        } catch (e: Exception) {
            Log.w("Analyzer", "Error closing model: ${e.message}")
        }
    }

    // ---------------- Helpers ----------------
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 80, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun cropBitmapSafe(src: Bitmap, box: Rect): Bitmap {
        val x = box.left.coerceAtLeast(0)
        val y = box.top.coerceAtLeast(0)
        val width = box.width().coerceAtMost(src.width - x)
        val height = box.height().coerceAtMost(src.height - y)
        if (width <= 0 || height <= 0) return src
        return Bitmap.createBitmap(src, x, y, width, height)
    }

    private fun saveDebugBitmap(bitmap: Bitmap, tag: String) {
        try {
            @Suppress("DEPRECATION")
            val root = Environment.getExternalStorageDirectory()
            val dir = File(root, "NexaDebug")
            if (!dir.exists()) dir.mkdirs()
            val time = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
            val file = File(dir, "${tag}_$time.png")
            val fos = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, fos)
            fos.flush()
            fos.close()
            Log.d("Analyzer", "Saved debug image: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.w("Analyzer", "Failed save debug: ${e.message}")
        }
    }
}

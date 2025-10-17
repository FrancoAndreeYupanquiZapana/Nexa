// <archivo completo con cambios mínimos>
package com.example.drowsinessdetectorapp.ui.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
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
import kotlin.math.sqrt

data class DebugInfo(
    val faceRectNorm: RectFNorm,
    val eyesRectNorm: RectFNorm,
    val mouthRectNorm: RectFNorm,
    val closedProb: Float,
    val yawnProb: Float,
    val mlkitClosed: Float,
    val combinedYawnScore: Float,
    val eyeEma: Float,
    val mouthModelEma: Float,
    val mouthModelAgeMs: Long
)
data class RectFNorm(val left: Float, val top: Float, val right: Float, val bottom: Float)

data class DrowsinessState(
    val eyeScore: Float = 0f,
    val yawnCount: Int = 0,
    val lostFaceCount: Int = 0,
    val isEyeAlert: Boolean = false,
    val isYawnAlert: Boolean = false,
    val isLostFaceAlert: Boolean = false
)

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
    private var EYE_THRESHOLD: Float = 0.28f,
    private var YAWN_THRESHOLD: Float = 0.80f,
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

    private var frameCounter = 0
    private var MOUTH_CLASSIFY_EVERY_N_FRAMES = 2 // cada 2 frames la inferencia de boca

    private var yawnConsecutive = 0
    private val YAWN_CONSECUTIVE_REQUIRED = 2
    private val YAWN_EVENT_GAP_MS = 2000L
    private val YAWN_EVENTS_FOR_ALERT = 5

    // Eye EMA
    private var eyeEma = 0f
    private val EYE_EMA_ALPHA = 0.38f

    // Mouth model EMA (evita que el valor desparezca a 0 cuando no inferimos cada frame)
    private var mouthModelEma = 0f
    private val MOUTH_EMA_ALPHA = 0.3f
    private var mouthModelRawLast = 0f
    private var lastMouthInferMillis = 0L
    private val STALE_MOUTH_MS = 700L
    private val DECAY_FACTOR = 0.82f

    // --- Nuevas constantes para robustecer detección de bostezo ---
    // Requiere que el modelo y la geometría estén ambas por encima para contar un bostezo
    private val MIN_YAWN_MODEL_THRESHOLD = 0.40f
    private val MIN_MOUTH_ASPECT_THRESHOLD = 0.40f
    // Si la salida cruda es muy pequeña, aceleramos el decay
    private val MIN_RAW_FOR_POSITIVE = 0.12f
    // ----------------------------------------------------------------

    // Calibración
    private var calibSamples = mutableListOf<Float>()
    private var calibPeakYawn = 0f
    private var calibrating = calibrateOnStart

    private var EYE_CLOSED_MS_THRESHOLD = 900L
    private val WARMUP_FRAMES = 8

    // Debug / logging
    private val ENABLE_SAVE_DEBUG_IMAGES = false
    private val ENABLE_VERBOSE_LOG = true

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
        resetState()
        if (calibrateOnStart) {
            calibrating = true
            Log.i("Analyzer", "Calibration enabled for $calibrationFrames frames")
        }
    }

    fun resetState() {
        eyeClosedStart = 0L
        lastYawnMillis = 0L
        yawnCounter = 0
        lostFaceStart = 0L
        frameCounter = 0
        calibSamples.clear()
        calibPeakYawn = 0f
        calibrating = calibrateOnStart
        yawnConsecutive = 0
        eyeEma = 0f
        mouthModelEma = 0f
        mouthModelRawLast = 0f
        lastMouthInferMillis = 0L
        _state.value = DrowsinessState()
        _overlay.value = null
        _debug.value = null
        Log.i("Analyzer", "Internal state RESET")
    }

    // setters runtime
    fun setEyeThreshold(v: Float) { EYE_THRESHOLD = v }
    fun setYawnThreshold(v: Float) { YAWN_THRESHOLD = v }
    fun setEyeClosedMsThreshold(ms: Long) { EYE_CLOSED_MS_THRESHOLD = ms }
    fun setMouthClassifyEvery(n: Int) { MOUTH_CLASSIFY_EVERY_N_FRAMES = max(1, n) }

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
                    if (ENABLE_VERBOSE_LOG) Log.d("Analyzer", "MLKit face bbox=${face.boundingBox} leftEye=${face.leftEyeOpenProbability} rightEye=${face.rightEyeOpenProbability}")
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

        // limpiar boca/mouth EMA al perder cara para evitar "cache"
        mouthModelEma = 0f
        mouthModelRawLast = 0f
        lastMouthInferMillis = 0L
        yawnConsecutive = 0

        _state.value = _state.value.copy(
            lostFaceCount = _state.value.lostFaceCount + 1,
            isLostFaceAlert = alert
        )
        _overlay.value = null
        _debug.value = null

        if (elapsed > 1500L) eyeEma = 0f
        Log.d("Analyzer", "No face for ${elapsed}ms. alert=$alert")
    }

    private fun handleFaceWithLandmarks(face: Face, frameBmp: Bitmap, currentFrame: Int) {
        lostFaceStart = 0L

        val fb = face.boundingBox
        val faceArea = fb.width() * fb.height()
        val MIN_FACE_AREA = 90 * 90
        if (faceArea < MIN_FACE_AREA) {
            if (ENABLE_VERBOSE_LOG) Log.d("Analyzer", "Face too small: area=$faceArea")
            _overlay.value = null
            _state.value = _state.value.copy(isLostFaceAlert = false)
            return
        }

        // landmarks -> sólo usamos x,y por compatibilidad (evitamos PointF/PointF3D problemas)
        val leftEyePos = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.LEFT_EYE)?.position
        val rightEyePos = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.RIGHT_EYE)?.position
        val mouthBottomPos = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.MOUTH_BOTTOM)?.position
        val mouthLeftPos = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.MOUTH_LEFT)?.position
        val mouthRightPos = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.MOUTH_RIGHT)?.position

        val leftEyeX: Float? = leftEyePos?.x
        val leftEyeY: Float? = leftEyePos?.y
        val rightEyeX: Float? = rightEyePos?.x
        val rightEyeY: Float? = rightEyePos?.y

        val mouthBottomX: Float? = mouthBottomPos?.x
        val mouthBottomY: Float? = mouthBottomPos?.y
        val mouthLeftX: Float? = mouthLeftPos?.x
        val mouthLeftY: Float? = mouthLeftPos?.y
        val mouthRightX: Float? = mouthRightPos?.x
        val mouthRightY: Float? = mouthRightPos?.y

        val safeEyes = computeEyesRect(frameBmp, fb, leftEyeX, leftEyeY, rightEyeX, rightEyeY)
        val safeMouth = computeMouthRect(frameBmp, fb, mouthBottomX, mouthBottomY, mouthLeftX, mouthLeftY, mouthRightX, mouthRightY)

        // ---- EYES (sin cambios) ----
        var probsEyesCombined = FloatArray(model.numOutputClasses) { 0f }
        try {
            if (leftEyeX != null && leftEyeY != null && rightEyeX != null && rightEyeY != null) {
                val eyeDistF = abs(rightEyeX - leftEyeX).coerceAtLeast(20f)
                val cropW = (eyeDistF * 0.95f).toInt().coerceAtLeast(28)
                val cropH = (cropW * 0.85f).toInt().coerceAtLeast(20)

                val leftEyeRect = Rect(
                    (leftEyeX - cropW / 2f).toInt().coerceAtLeast(0),
                    (leftEyeY - cropH / 2f).toInt().coerceAtLeast(0),
                    (leftEyeX + cropW / 2f).toInt().coerceAtMost(frameBmp.width),
                    (leftEyeY + cropH / 2f).toInt().coerceAtMost(frameBmp.height)
                )
                val rightEyeRect = Rect(
                    (rightEyeX - cropW / 2f).toInt().coerceAtLeast(0),
                    (rightEyeY - cropH / 2f).toInt().coerceAtLeast(0),
                    (rightEyeX + cropW / 2f).toInt().coerceAtMost(frameBmp.width),
                    (rightEyeY + cropH / 2f).toInt().coerceAtMost(frameBmp.height)
                )

                val eyeCrops = mutableListOf<Bitmap>()
                listOf(leftEyeRect, rightEyeRect).forEach { r ->
                    val w = r.width()
                    val h = r.height()
                    if (w > 12 && h > 12) {
                        val c = cropBitmapSafe(frameBmp, r)
                        val scaled = Bitmap.createScaledBitmap(c, model.inputWidth, model.inputHeight, true)
                        eyeCrops.add(scaled)
                    }
                }

                if (eyeCrops.isNotEmpty()) {
                    val accum = FloatArray(model.numOutputClasses) { 0f }
                    for (c in eyeCrops) {
                        val p = try { model.classify(c) } catch (e: Exception) { FloatArray(model.numOutputClasses) { 0f } }
                        for (i in accum.indices) accum[i] += p.getOrNull(i) ?: 0f
                    }
                    for (i in accum.indices) accum[i] = accum[i] / eyeCrops.size.toFloat()
                    probsEyesCombined = accum
                } else {
                    val cropEyes = cropBitmapSafe(frameBmp, safeEyes)
                    val inputEyes = Bitmap.createScaledBitmap(cropEyes, model.inputWidth, model.inputHeight, true)
                    probsEyesCombined = try { model.classify(inputEyes) } catch (e: Exception){ FloatArray(model.numOutputClasses){0f} }
                }
            } else {
                val cropEyes = cropBitmapSafe(frameBmp, safeEyes)
                val inputEyes = Bitmap.createScaledBitmap(cropEyes, model.inputWidth, model.inputHeight, true)
                probsEyesCombined = try { model.classify(inputEyes) } catch (e: Exception){ FloatArray(model.numOutputClasses){0f} }
            }
        } catch (e: Exception) {
            Log.e("Analyzer", "eyes classify error: ${e.message}")
        }

        // ---- MOUTH classification cada N frames + EMA/decay (MODIFICADA ligeramente) ----
        var probsMouth = FloatArray(model.numOutputClasses) { 0f }
        val mouthWidth = safeMouth.width()
        val mouthHeight = safeMouth.height()
        val faceHeight = fb.height()
        val faceWidth = fb.width()

        val enoughResolution = faceHeight >= 110 || faceWidth >= 110
        val now = System.currentTimeMillis()
        var didMouthInfer = false
        if (mouthWidth > 18 && mouthHeight > 12 && enoughResolution && currentFrame % MOUTH_CLASSIFY_EVERY_N_FRAMES == 0) {
            try {
                val cropMouth = cropBitmapSafe(frameBmp, safeMouth)
                val inputMouth = Bitmap.createScaledBitmap(cropMouth, model.inputWidth, model.inputHeight, true)
                probsMouth = try { model.classify(inputMouth) } catch (e: Exception) { FloatArray(model.numOutputClasses) { 0f } }
                if (ENABLE_SAVE_DEBUG_IMAGES) saveDebugBitmap(inputMouth, "mouth_crop")
                if (ENABLE_VERBOSE_LOG) Log.d("Analyzer", "probsMouth=${probsMouth.joinToString(",")}")
                // actualizar EMA y timestamps
                val raw = probsMouth.getOrNull(3) ?: 0f
                mouthModelRawLast = raw
                // Si la salida cruda es muy pequeña, aplicamos un decay más agresivo para evitar que la EMA "se quede" en valores intermedios
                if (raw >= MIN_RAW_FOR_POSITIVE) {
                    mouthModelEma = (MOUTH_EMA_ALPHA * raw) + ((1f - MOUTH_EMA_ALPHA) * mouthModelEma)
                } else {
                    // decay acelerado cuando el modelo no está mostrando señal clara
                    mouthModelEma *= (DECAY_FACTOR * DECAY_FACTOR)
                    if (mouthModelEma < 0.03f) mouthModelEma = 0f
                }
                lastMouthInferMillis = now
                didMouthInfer = true
            } catch (e: Exception) {
                Log.e("Analyzer", "mouth classify error: ${e.message}")
            }
        } else {
            // no inferimos este frame -> aplicar decay si la última inferencia es vieja
            val age = if (lastMouthInferMillis > 0L) now - lastMouthInferMillis else Long.MAX_VALUE
            if (age > STALE_MOUTH_MS) {
                mouthModelEma *= DECAY_FACTOR
                if (mouthModelEma < 0.03f) mouthModelEma = 0f
            }
        }

        // --- scoring / mezclas ---
        val closedProb = probsEyesCombined.getOrNull(0) ?: 0f
        // usamos la EMA (suavizada) como señal del modelo de boca
        val yawnProbModelUsed = mouthModelEma

        val leftEyeProb = face.leftEyeOpenProbability ?: -1f
        val rightEyeProb = face.rightEyeOpenProbability ?: -1f
        val mlkitEyeClosedEstimate = if (leftEyeProb >= 0f && rightEyeProb >= 0f) 1f - ((leftEyeProb + rightEyeProb) / 2f) else -1f

        // update eye EMA
        eyeEma = (EYE_EMA_ALPHA * closedProb) + ((1f - EYE_EMA_ALPHA) * eyeEma)

        // mouth geometry aspect: hacemos más conservador el divisor (0.35) para no llegar a 1 tan fácil
        val mouthHeightEstimate = if (mouthHeight > 0) mouthHeight.toFloat() else 0f
        val mouthAspectScore = if (faceHeight > 0) {
            (mouthHeightEstimate / (faceHeight * 0.35f)).coerceAtMost(1f)
        } else 0f

        val MODEL_WEIGHT = 0.75f
        val combinedYawnScore = (yawnProbModelUsed * MODEL_WEIGHT) + (mouthAspectScore * (1f - MODEL_WEIGHT))

        if (ENABLE_VERBOSE_LOG) {
            val mouthAge = if (lastMouthInferMillis > 0L) now - lastMouthInferMillis else Long.MAX_VALUE
            Log.d("Analyzer", "closedProb=$closedProb eyeEma=$eyeEma mlkitClosed=$mlkitEyeClosedEstimate")
            Log.d("Analyzer", "yawnModelEma=${"%.3f".format(mouthModelEma)} rawLast=${"%.3f".format(mouthModelRawLast)} ageMs=$mouthAge mouthAspect=${"%.3f".format(mouthAspectScore)} combined=${"%.3f".format(combinedYawnScore)}")
        }

        // overlays normalizados
        val bw = frameBmp.width.toFloat().coerceAtLeast(1f)
        val bh = frameBmp.height.toFloat().coerceAtLeast(1f)

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
            yawnProb = yawnProbModelUsed
        )

        // calibración
        if (calibrating) {
            calibSamples.add(closedProb)
            if (combinedYawnScore > calibPeakYawn) calibPeakYawn = combinedYawnScore
            if (calibSamples.size >= calibrationFrames) finishCalibrationAndSetThresholds()
        }

        // --- Lógica alertas ojos (sin cambios) ---
        val nowMs = System.currentTimeMillis()
        val eyeScoreToUse = when {
            eyeEma > 0.02f && mlkitEyeClosedEstimate >= 0f -> (eyeEma * 0.85f) + (mlkitEyeClosedEstimate * 0.15f)
            eyeEma > 0.02f -> eyeEma
            mlkitEyeClosedEstimate >= 0f -> mlkitEyeClosedEstimate
            else -> 0f
        }

        var alertEyes = false
        if (eyeScoreToUse > EYE_THRESHOLD) {
            if (eyeClosedStart == 0L) eyeClosedStart = nowMs
            if (nowMs - eyeClosedStart >= EYE_CLOSED_MS_THRESHOLD) alertEyes = true
        } else {
            eyeClosedStart = 0L
        }

        // --- Lógica bostezo: ahora requerimos modelo + geometría + ancho mínimo ---
        val mouthWideEnoughForYawn = mouthWidth >= max((faceWidth * 0.22).toInt(), 30)

        if (frameCounter > WARMUP_FRAMES) {
            // Reemplazamos la condición original por una más estricta:
            // requiere que la EMA del modelo supere MIN_YAWN_MODEL_THRESHOLD
            // y que mouthAspectScore supere MIN_MOUTH_ASPECT_THRESHOLD
            if (yawnProbModelUsed > MIN_YAWN_MODEL_THRESHOLD && mouthAspectScore > MIN_MOUTH_ASPECT_THRESHOLD && mouthWideEnoughForYawn) {
                yawnConsecutive++
            } else {
                yawnConsecutive = 0
            }

            if (yawnConsecutive >= YAWN_CONSECUTIVE_REQUIRED && now - lastYawnMillis > YAWN_EVENT_GAP_MS) {
                yawnCounter++
                lastYawnMillis = now
                yawnConsecutive = 0
                Log.d("Analyzer", "Yawn confirmed -> counter=$yawnCounter combined=${"%.3f".format(combinedYawnScore)} mouthEma=${"%.3f".format(mouthModelEma)}")
            }
        }

        var alertYawn = false
        if (yawnCounter >= YAWN_EVENTS_FOR_ALERT && frameCounter > WARMUP_FRAMES) {
            alertYawn = true
            yawnCounter = 0
        }

        _state.value = _state.value.copy(
            eyeScore = eyeScoreToUse,
            yawnCount = yawnCounter,
            isEyeAlert = alertEyes,
            isYawnAlert = alertYawn,
            isLostFaceAlert = false
        )

        // debug
        val mouthModelAge = if (lastMouthInferMillis > 0L) now - lastMouthInferMillis else Long.MAX_VALUE
        _debug.value = DebugInfo(
            RectFNorm(fb.left / bw, fb.top / bh, fb.right / bw, fb.bottom / bh),
            RectFNorm(safeEyes.left / bw, safeEyes.top / bh, safeEyes.right / bw, safeEyes.bottom / bh),
            RectFNorm(safeMouth.left / bw, safeMouth.top / bh, safeMouth.right / bw, safeMouth.bottom / bh),
            closedProb, yawnProbModelUsed, mlkitEyeClosedEstimate, combinedYawnScore, eyeEma, mouthModelEma, mouthModelAge
        )
    }

    private fun finishCalibrationAndSetThresholds() {
        if (calibSamples.isEmpty()) {
            calibrating = false
            Log.i("Analyzer", "Calibration ended: no samples")
            return
        }
        val mu = calibSamples.average().toFloat()
        val sigma = sqrt(calibSamples.map { (it - mu) * (it - mu) }.average().toFloat())
        val computedEyeThreshold = max(0.18f, mu + 2.75f * sigma)
        val computedYawnThreshold = max(0.28f, if (calibPeakYawn > 0f) calibPeakYawn * 0.65f else YAWN_THRESHOLD)
        EYE_THRESHOLD = computedEyeThreshold
        YAWN_THRESHOLD = computedYawnThreshold
        calibrating = false
        calibSamples.clear(); calibPeakYawn = 0f
        Log.i("Analyzer", "Calibration finished: EYE=${"%.3f".format(EYE_THRESHOLD)} YAWN=${"%.3f".format(YAWN_THRESHOLD)}")
    }

    fun startCalibrationNow() {
        calibrating = true
        calibSamples.clear(); calibPeakYawn = 0f
        eyeClosedStart = 0L; lastYawnMillis = 0L; yawnCounter = 0; yawnConsecutive = 0; eyeEma = 0f; mouthModelEma = 0f; mouthModelRawLast = 0f; lastMouthInferMillis = 0L
        Log.i("Analyzer", "Manual calibration started for $calibrationFrames frames")
    }

    fun close() {
        try { model.close() } catch (e: Exception) { Log.w("Analyzer", "Error closing model: ${e.message}") }
    }

    // Helpers
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

    // compute rect helpers (aceptan coords simples para evitar PointF/PointF3D)
    private fun computeEyesRect(frameBmp: Bitmap, face: android.graphics.Rect, leftEyeX: Float?, leftEyeY: Float?, rightEyeX: Float?, rightEyeY: Float?): Rect {
        return if (leftEyeX != null && leftEyeY != null && rightEyeX != null && rightEyeY != null) {
            val cx = ((leftEyeX + rightEyeX) / 2f).toInt()
            val cy = ((leftEyeY + rightEyeY) / 2f).toInt()
            val eyeDist = abs(rightEyeX - leftEyeX).toInt().coerceAtLeast(20)
            val padX = (eyeDist * 0.95f).toInt().coerceAtLeast(18)
            val padY = (eyeDist * 0.6f).toInt().coerceAtLeast(12)
            Rect((cx - padX).coerceAtLeast(0), (cy - padY).coerceAtLeast(0), (cx + padX).coerceAtMost(frameBmp.width), (cy + padY).coerceAtMost(frameBmp.height))
        } else {
            val left = face.left.coerceAtLeast(0)
            val top = face.top.coerceAtLeast(0)
            val right = face.right.coerceAtMost(frameBmp.width)
            val bottom = face.bottom.coerceAtMost(frameBmp.height)
            val h = (bottom - top).coerceAtLeast(10)
            Rect(left, top, right, top + (h * 0.28f).toInt())
        }
    }

    private fun computeMouthRect(frameBmp: Bitmap, face: android.graphics.Rect, mouthBottomX: Float?, mouthBottomY: Float?, mouthLeftX: Float?, mouthLeftY: Float?, mouthRightX: Float?, mouthRightY: Float?): Rect {
        return if (mouthBottomX != null && mouthBottomY != null && mouthLeftX != null && mouthLeftY != null && mouthRightX != null && mouthRightY != null) {
            val left = (mouthLeftX - 14f).toInt().coerceAtLeast(0)
            val right = (mouthRightX + 14f).toInt().coerceAtMost(frameBmp.width)
            val mouthHeightEstimate = max(abs(mouthRightY - mouthLeftY).toInt(), abs(mouthBottomY - mouthLeftY).toInt()).coerceAtLeast(8)
            val top = (mouthBottomY - (mouthHeightEstimate * 1.5f)).toInt().coerceAtLeast(0)
            val bottom = (mouthBottomY + (mouthHeightEstimate * 1.3f)).toInt().coerceAtMost(frameBmp.height)
            Rect(left, top, right, bottom)
        } else {
            val left = face.left.coerceAtLeast(0)
            val top = face.top.coerceAtLeast(0)
            val right = face.right.coerceAtMost(frameBmp.width)
            val bottom = face.bottom.coerceAtMost(frameBmp.height)
            val h = (bottom - top).coerceAtLeast(10)
            Rect(left, top + (h * 0.40f).toInt(), right, bottom)
        }
    }
}

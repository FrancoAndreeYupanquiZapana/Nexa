package com.example.drowsinessdetectorapp.ml

//Carga el modelo TensorFlow Lite y ejecuta la inferencia

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

//clase para reconocer somnolencia usando un modelo TFLite
class DrowsinessClassifier(context: Context, modelName: String = "drowsiness_model.tflite") { //tambien esta el drowsiness_model_int8.tflite

    private val interpreter: Interpreter
    val inputShape: IntArray
    val inputDataType: DataType
    val inputHeight: Int
    val inputWidth: Int
    val inputChannels: Int
    val outputShape: IntArray
    val numOutputClasses: Int

    init {
        val model = loadModelFile(context, modelName)
        interpreter = Interpreter(model)
        // inspecciona tensores
        inputShape = interpreter.getInputTensor(0).shape() // ejemplo. [1, H, W, C] o [H,W,C]
        inputDataType = interpreter.getInputTensor(0).dataType()
        // acomodar según forma
        if (inputShape.size == 4) {
            inputHeight = inputShape[1]
            inputWidth = inputShape[2]
            inputChannels = inputShape[3]
        } else if (inputShape.size == 3) {
            inputHeight = inputShape[0]
            inputWidth = inputShape[1]
            inputChannels = inputShape[2]
        } else {
            // fallback
            inputHeight = 150
            inputWidth = 150
            inputChannels = 3
        }

        outputShape = interpreter.getOutputTensor(0).shape() // ejemplo. [1, numClasses]
        numOutputClasses = outputShape.last()
        Log.i("DrowsinessClassifier", "Input shape: ${inputShape.contentToString()} dtype=$inputDataType")
        Log.i("DrowsinessClassifier", "Output shape: ${outputShape.contentToString()} numClasses=$numOutputClasses")
    }

    @Throws(IOException::class)
    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun classify(bitmap: Bitmap): FloatArray {
        // preparar ByteBuffer de entrada
        val bytePerChannel = if (inputDataType == DataType.FLOAT32) 4 else 1
        val imgData = ByteBuffer.allocateDirect(1 * inputWidth * inputHeight * inputChannels * bytePerChannel)
        imgData.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputWidth * inputHeight)
        bitmap.getPixels(intValues, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        var pixel = 0
        for (y in 0 until inputHeight) {
            for (x in 0 until inputWidth) {
                val v = intValues[pixel++]
                val r = ((v shr 16) and 0xFF)
                val g = ((v shr 8) and 0xFF)
                val b = (v and 0xFF)
                if (inputDataType == DataType.FLOAT32) {
                    // Normalizamos a 0..1
                    imgData.putFloat(r / 255.0f)
                    imgData.putFloat(g / 255.0f)
                    imgData.putFloat(b / 255.0f)
                } else {
                    // UINT8
                    imgData.put((r and 0xFF).toByte())
                    imgData.put((g and 0xFF).toByte())
                    imgData.put((b and 0xFF).toByte())
                }
            }
        }
        // preparar el output
        val output = Array(1) { FloatArray(numOutputClasses) }
        imgData.rewind()
        interpreter.run(imgData, output)
        return output[0]
    }

    fun close() {
        interpreter.close()
    }
}
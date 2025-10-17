package com.example.drowsinessdetectorapp.ui.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread
import org.json.JSONObject

object AlertSender {

    /**
     * Envía alerta: primero SMS (offline), luego Telegram mediante Bot API (sin abrir apps).
     * Lee token/chat_id desde SharedPreferences "settings" keys:
     *  - telegram_token
     *  - telegram_chat_id
     *
     * Si telegram_token o chat_id faltan, solo hace SMS.
     */
    fun sendDrowsinessAlert(context: Context, alertType: String) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val number = prefs.getString("emergency_number", null)
        val telegramToken = prefs.getString("telegram_token", null)
        val telegramChatId = prefs.getString("telegram_chat_id", null)

        if (number.isNullOrBlank()) {
            Toast.makeText(context, "❌ No hay número de emergencia guardado", Toast.LENGTH_SHORT).show()
            Log.w("AlertSender", "No hay número de emergencia configurado")
            return
        }

        val message = when (alertType) {
            "eyes" -> "⚠️ Somnolencia detectada: ojos cerrados prolongadamente."
            "yawn" -> "⚠️ Somnolencia detectada: exceso de bostezos."
            "lost" -> "⚠️ Somnolencia detectada: rostro no visible en cámara."
            else -> "⚠️ Alerta de somnolencia detectada. Conduce con precaución."
        }

        // 1) Intentar enviar SMS (funciona offline)
        thread {
            try {
                val smsManager = SmsManager.getDefault()
                smsManager.sendTextMessage(number, null, message, null, null)
                Log.i("AlertSender", "SMS enviado correctamente a $number")
                // Usa Toast en hilo UI
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(context, "📤 SMS enviado a $number", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("AlertSender", "Error SMS: ${e.message}")
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(context, "❌ Error al enviar SMS: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            // 2) Intentar Telegram si token + chat_id presentes y hay internet
            if (!telegramToken.isNullOrBlank() && !telegramChatId.isNullOrBlank() && isNetworkAvailable(context)) {
                val success = sendTelegramMessageHttp(telegramToken, telegramChatId, message)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (success) {
                        Toast.makeText(context, "📨 Alerta enviada a Telegram", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "❌ No se pudo enviar alerta a Telegram", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Log.i("AlertSender", "Telegram no configurado o sin internet; token/chatid=${telegramToken!=null}/${telegramChatId!=null}")
            }
        }
    }

    /**
     * Hace POST simple a Bot API para enviar mensaje.
     * Se ejecuta en hilo background (llamado desde thread {} arriba).
     */
    private fun sendTelegramMessageHttp(botToken: String, chatId: String, message: String): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL("https://api.telegram.org/bot$botToken/sendMessage")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 7000
                readTimeout = 7000
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }

            // parámetros form-urlencoded
            val payload = "chat_id=${URLEncoder.encode(chatId, "UTF-8")}" +
                    "&text=${URLEncoder.encode(message, "UTF-8")}" +
                    "&parse_mode=HTML"

            BufferedOutputStream(connection.outputStream).use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
                out.flush()
            }

            val responseCode = connection.responseCode
            val response = StringBuilder()
            BufferedReader(InputStreamReader(
                if (responseCode in 200..299) connection.inputStream else connection.errorStream
            )).use { br ->
                var line: String?
                while (br.readLine().also { line = it } != null) {
                    response.append(line)
                }
            }

            Log.i("AlertSender", "Telegram responseCode=$responseCode body=${response.toString()}")
            if (responseCode in 200..299) {
                // comprobar "ok":true
                try {
                    val json = JSONObject(response.toString())
                    json.optBoolean("ok", false)
                } catch (e: Exception) {
                    true // si no es JSON, consideramos éxito por status 200
                }
            } else false
        } catch (e: Exception) {
            Log.e("AlertSender", "Error sendTelegramMessageHttp: ${e.message}")
            false
        } finally {
            connection?.disconnect()
        }
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val active = cm.getNetworkCapabilities(network) ?: return false
            return active.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            return false
        }
    }
}

//package com.example.drowsinessdetectorapp.utils
//
//import android.app.PendingIntent
//import android.content.BroadcastReceiver
//import android.content.Context
//import android.content.Intent
//import android.content.IntentFilter
//import android.net.ConnectivityManager
//import android.net.NetworkCapabilities
//import android.net.Uri
//import android.telephony.SmsManager
//import android.util.Log
//import android.widget.Toast
//import java.net.URLEncoder
//
//object AlertSender {
//
//    /**
//     * Envía una alerta de somnolencia por SMS (offline)
//     */
//    fun sendDrowsinessAlert(context: Context, alertType: String) {
//        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
//        val number = prefs.getString("emergency_number", null)
//
//        if (number.isNullOrBlank()) {
//            Toast.makeText(context, "❌ No hay número de emergencia guardado", Toast.LENGTH_SHORT).show()
//            Log.w("AlertSender", "No hay número de emergencia configurado")
//            return
//        }
//
//        val message = when (alertType) {
//            "eyes" -> "⚠️ Somnolencia detectada: ojos cerrados prolongadamente."
//            "yawn" -> "⚠️ Somnolencia detectada: exceso de bostezos."
//            "lost" -> "⚠️ Somnolencia detectada: rostro no visible en cámara."
//            else -> "⚠️ Alerta de somnolencia detectada. Conduce con precaución."
//        }
//
//        sendSMSWithFeedback(context, number, message)
//    }
//
//    /**
//     * Envía un SMS y confirma con un BroadcastReceiver si fue enviado correctamente
//     */
//    private fun sendSMSWithFeedback(context: Context, number: String, message: String) {
//        try {
//            val SENT = "SMS_SENT"
//            val DELIVERED = "SMS_DELIVERED"
//
//            val sentPI = PendingIntent.getBroadcast(
//                context, 0, Intent(SENT),
//                PendingIntent.FLAG_IMMUTABLE
//            )
//
//            val deliveredPI = PendingIntent.getBroadcast(
//                context, 0, Intent(DELIVERED),
//                PendingIntent.FLAG_IMMUTABLE
//            )
//
//            // --- Escucha resultados ---
//            context.registerReceiver(
//                object : BroadcastReceiver() {
//                    override fun onReceive(ctx: Context?, intent: Intent?) {
//                        when (resultCode) {
//                            android.app.Activity.RESULT_OK ->
//                                Toast.makeText(ctx, "✅ SMS enviado correctamente", Toast.LENGTH_SHORT).show()
//                            SmsManager.RESULT_ERROR_GENERIC_FAILURE ->
//                                Toast.makeText(ctx, "❌ Error genérico al enviar SMS", Toast.LENGTH_SHORT).show()
//                            SmsManager.RESULT_ERROR_NO_SERVICE ->
//                                Toast.makeText(ctx, "❌ Sin servicio móvil", Toast.LENGTH_SHORT).show()
//                            SmsManager.RESULT_ERROR_NULL_PDU ->
//                                Toast.makeText(ctx, "❌ Error de PDU", Toast.LENGTH_SHORT).show()
//                            SmsManager.RESULT_ERROR_RADIO_OFF ->
//                                Toast.makeText(ctx, "❌ Modo avión / radio apagada", Toast.LENGTH_SHORT).show()
//                        }
//                    }
//                },
//                IntentFilter(SENT),
//                Context.RECEIVER_NOT_EXPORTED
//            )
//            context.registerReceiver(
//                object : BroadcastReceiver() {
//                    override fun onReceive(ctx: Context?, intent: Intent?) {
//                        Toast.makeText(ctx, "📨 SMS entregado al destinatario", Toast.LENGTH_SHORT).show()
//                    }
//                },
//                IntentFilter(DELIVERED),
//                Context.RECEIVER_NOT_EXPORTED
//            )
//
//            // --- Enviar el SMS ---
//            val smsManager = SmsManager.getDefault()
//            smsManager.sendTextMessage(number, null, message, sentPI, deliveredPI)
//
//            Log.i("AlertSender", "Intentando enviar SMS a $number: $message")
//
//        } catch (e: Exception) {
//            Log.e("AlertSender", "Error al enviar SMS: ${e.message}")
//            Toast.makeText(context, "❌ No se pudo enviar SMS: ${e.message}", Toast.LENGTH_LONG).show()
//        }
//    }
//
//    /**
//     * Verifica si hay conexión (para futuras fases con Telegram)
//     */
//    private fun isNetworkAvailable(context: Context): Boolean {
//        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
//        val network = cm.activeNetwork ?: return false
//        val active = cm.getNetworkCapabilities(network) ?: return false
//        return active.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
//    }
//}

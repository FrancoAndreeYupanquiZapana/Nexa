package com.example.drowsinessdetectorapp.ui.settings

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.example.drowsinessdetectorapp.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// === DataStore ===
private val Context.dataStore by preferencesDataStore("settings_prefs")

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // === Estados ===
    var eyeThreshold by remember { mutableFloatStateOf(0.5f) }
    var yawnThreshold by remember { mutableFloatStateOf(0.7f) }
    var musicUri by remember { mutableStateOf<Uri?>(null) }
    var alertSoundName by remember { mutableStateOf("Predeterminado") }
    var readMessages by remember { mutableStateOf(true) }
    var nexaEnabled by remember { mutableStateOf(false) }

    // === NUEVOS ESTADOS TELEGRAM ===
    var telToken by remember { mutableStateOf("") }
    var telChatId by remember { mutableStateOf("") }

    // === Cargar valores guardados al iniciar ===
    LaunchedEffect(Unit) {
        val prefs = context.dataStore.data.first()
        eyeThreshold = prefs[floatPreferencesKey("eyeThreshold")] ?: 0.5f
        yawnThreshold = prefs[floatPreferencesKey("yawnThreshold")] ?: 0.7f
        readMessages = prefs[booleanPreferencesKey("readMessages")] ?: true
        nexaEnabled = prefs[booleanPreferencesKey("nexaEnabled")] ?: false

        val savedUri = prefs[stringPreferencesKey("musicUri")] ?: ""
        if (savedUri.isNotEmpty()) {
            musicUri = Uri.parse(savedUri)
            alertSoundName = prefs[stringPreferencesKey("alertSoundName")] ?: "Tono personalizado"
            SoundConfig.customSoundUri = musicUri
        }

        // === Cargar Telegram ===
        val prefsLegacy = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        telToken = prefsLegacy.getString("telegram_token", "") ?: ""
        telChatId = prefsLegacy.getString("telegram_chat_id", "") ?: ""
    }

    // === Selector de música ===
    val pickMusicLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            musicUri = uri
            alertSoundName = uri.lastPathSegment ?: "Tono personalizado"
            SoundConfig.customSoundUri = uri
            Toast.makeText(context, "Tono seleccionado correctamente", Toast.LENGTH_SHORT).show()
            playCustomOrDefaultTone(context, uri)
        }
    }

    // === UI ===
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Image(
            painter = painterResource(id = R.drawable.background2),
            contentDescription = "Fondo configuración",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 40.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Configuraciones",
                    tint = Color.Cyan,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Configuraciones",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(40.dp))

            ThresholdItem("Umbral de ojos cerrados", eyeThreshold) { eyeThreshold = it }
            Spacer(modifier = Modifier.height(25.dp))
            ThresholdItem("Umbral de bostezo", yawnThreshold) { yawnThreshold = it }

            Spacer(modifier = Modifier.height(40.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = "Elegir tono",
                    tint = Color.Cyan,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Tono de alerta",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(0.9f)
            ) {
                Text(text = alertSoundName, color = Color.Cyan, fontSize = 15.sp)
                Row {
                    Button(
                        onClick = { pickMusicLauncher.launch("audio/*") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text("Elegir", color = Color.White)
                    }

                    if (musicUri != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                musicUri = null
                                alertSoundName = "Predeterminado"
                                SoundConfig.customSoundUri = null
                                scope.launch {
                                    context.dataStore.edit {
                                        it.remove(stringPreferencesKey("musicUri"))
                                        it.remove(stringPreferencesKey("alertSoundName"))
                                    }
                                }
                                Toast.makeText(context, "Volviste al tono predeterminado", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text("Quitar", color = Color.White)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(35.dp))

            SettingSwitch(
                title = "Lectura de mensajes",
                icon = Icons.Default.Email,
                checked = readMessages,
                onCheckedChange = { readMessages = it }
            )
            SettingSwitch(
                title = "Nexa (asistente IA)",
                icon = Icons.Default.Face,
                checked = nexaEnabled,
                onCheckedChange = { nexaEnabled = it }
            )

            // === CAMPOS DE TELEGRAM ===
            Spacer(modifier = Modifier.height(25.dp))
            Text(
                text = "Configuración de Telegram (para alertas automáticas)",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = telToken,
                onValueChange = { telToken = it },
                label = { Text("Telegram Bot Token") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Cyan,
                    unfocusedIndicatorColor = Color.Gray,
                    focusedLabelColor = Color.Cyan,
                    cursorColor = Color.Cyan
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = telChatId,
                onValueChange = { telChatId = it },
                label = { Text("Telegram Chat ID (ej: -100...)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Cyan,
                    unfocusedIndicatorColor = Color.Gray,
                    focusedLabelColor = Color.Cyan,
                    cursorColor = Color.Cyan
                )

            )

            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putString("telegram_token", telToken.trim())
                        .putString("telegram_chat_id", telChatId.trim())
                        .apply()
                    Toast.makeText(context, "Datos de Telegram guardados ✅", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
            ) {
                Text("Guardar Telegram")
            }

            Spacer(modifier = Modifier.height(40.dp))

            Button(
                onClick = {
                    scope.launch {
                        context.dataStore.edit {
                            it[floatPreferencesKey("eyeThreshold")] = eyeThreshold
                            it[floatPreferencesKey("yawnThreshold")] = yawnThreshold
                            it[booleanPreferencesKey("readMessages")] = readMessages
                            it[booleanPreferencesKey("nexaEnabled")] = nexaEnabled
                            it[stringPreferencesKey("musicUri")] = musicUri?.toString() ?: ""
                            it[stringPreferencesKey("alertSoundName")] = alertSoundName
                        }
                    }
                    Toast.makeText(context, "Configuraciones guardadas ✅", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                modifier = Modifier
                    .width(200.dp)
                    .height(50.dp)
            ) {
                Text("Guardar cambios", fontSize = 16.sp)
            }

            Spacer(modifier = Modifier.height(60.dp))
        }
    }
}

// ==========================================
// 🔊 REPRODUCCIÓN DE TONO
// ==========================================
fun playCustomOrDefaultTone(context: Context, uri: Uri?) {
    try {
        if (uri != null) {
            val player = MediaPlayer.create(context, uri)
            player?.start()
            Handler(Looper.getMainLooper()).postDelayed({
                player?.stop()
                player?.release()
            }, 5000)
        } else {
            val tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            tone.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 1200)
        }
    } catch (e: Exception) {
        Log.e("SettingsScreen", "Error al reproducir tono: ${e.message}")
    }
}

// ==========================================
// 📦 TONO GLOBAL
// ==========================================
object SoundConfig {
    var customSoundUri: Uri? = null
}

// ==========================================
// 🎚 COMPONENTES AUXILIARES
// ==========================================
@Composable
fun ThresholdItem(title: String, value: Float, onValueChange: (Float) -> Unit) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(text = title, color = Color.White, fontSize = 18.sp)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0.1f..1.0f,
            steps = 9,
            colors = SliderDefaults.colors(
                thumbColor = Color.Red,
                activeTrackColor = Color.Cyan,
                inactiveTrackColor = Color.Gray
            )
        )
        Text(text = "Actual: ${"%.2f".format(value)}", color = Color.Cyan, fontSize = 14.sp)
    }
}

@Composable
fun SettingSwitch(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .padding(vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = Color.Cyan, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = title, color = Color.White, fontSize = 16.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Red,
                uncheckedThumbColor = Color.Gray
            )
        )
    }
}

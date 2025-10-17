package com.example.drowsinessdetectorapp.ui.menu

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.example.drowsinessdetectorapp.R
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Composable
fun MenuScreen(navController: NavHostController) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var registeredNumber by remember { mutableStateOf(prefs.getString("emergency_number", "") ?: "") }
    var showModal by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Image(
            painter = painterResource(id = R.drawable.background_menu),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Menú Principal",
                color = Color.Red,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            RedButton(text = "Abrir cámara", onClick = { navController.navigate("camara") })
            Spacer(modifier = Modifier.height(24.dp))

            RedButton(text = "Número de Apoyo", onClick = { showModal = true })
            Spacer(modifier = Modifier.height(24.dp))

            RedButton(text = "Configuraciones", onClick = { navController.navigate("configuracion") })
        }

        // --- Floating Button (opcional: enviar alerta manual) ---
        FloatingActionButton(
            onClick = {
                val savedNumber = prefs.getString("emergency_number", "") ?: ""

                if (savedNumber.isBlank()) {
                    Toast.makeText(context, "⚠️ No hay número registrado para contactar.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "📞 Contactando con soporte...", Toast.LENGTH_SHORT).show()
                    openWhatsApp(context, savedNumber)
                }
            },
            containerColor = Color.Red,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
        ) {
            Text("A", color = Color.White, fontWeight = FontWeight.Bold)
        }


        // --- Modal para número ---
        if (showModal) {
            NumberModal(
                registeredNumber = registeredNumber,
                onClose = { showModal = false },
                onAddNumber = { newNumber ->
                    scope.launch {
                        prefs.edit().putString("emergency_number", newNumber).apply()
                        registeredNumber = newNumber
                        Toast.makeText(context, "✅ Número guardado correctamente", Toast.LENGTH_SHORT).show()
                    }
                    showModal = false
                },
                onOpenWhatsApp = { numberToUse ->
                    openWhatsApp(context, numberToUse)
                }
            )
        }
    }
}

@Composable
fun NumberModal(
    registeredNumber: String,
    onClose: () -> Unit,
    onAddNumber: (String) -> Unit,
    onOpenWhatsApp: (String) -> Unit
) {
    var newNumber by remember { mutableStateOf("") }
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xAA000000)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0B0B0B)),
            border = androidx.compose.foundation.BorderStroke(2.dp, Color.Red)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                IconButton(
                    onClick = { onClose() },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(36.dp)
                ) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Cerrar", tint = Color.White)
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.logo),
                        contentDescription = "Avatar",
                        modifier = Modifier
                            .size(80.dp)
                            .clip(RoundedCornerShape(40.dp)),
                        contentScale = ContentScale.Crop
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Número Registrado:",
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Start)
                    )

                    OutlinedTextField(
                        value = registeredNumber,
                        onValueChange = {},
                        enabled = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            disabledTextColor = Color.White,
                            disabledBorderColor = Color.Red,
                            disabledContainerColor = Color(0xFF0B0B0B)
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Agregar un nuevo número",
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Start)
                    )

                    OutlinedTextField(
                        value = newNumber,
                        onValueChange = { newNumber = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        placeholder = { Text("Ej: +51987654321", color = Color.LightGray) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color.Red,
                            unfocusedBorderColor = Color.Red,
                            focusedContainerColor = Color(0xFF0B0B0B),
                            unfocusedContainerColor = Color(0xFF0B0B0B),
                            cursorColor = Color.Red
                        )
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                if (newNumber.isBlank()) {
                                    Toast.makeText(context, "Ingrese un número válido", Toast.LENGTH_SHORT).show()
                                } else {
                                    onAddNumber(newNumber)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Agregar", color = Color.White)
                        }
                        Button(
                            onClick = {
                                val toUse = when {
                                    newNumber.isNotBlank() -> newNumber
                                    registeredNumber.isNotBlank() -> registeredNumber
                                    else -> ""
                                }
                                if (toUse.isBlank()) {
                                    Toast.makeText(context, "No hay número para abrir WhatsApp", Toast.LENGTH_SHORT).show()
                                } else {
                                    onOpenWhatsApp(toUse)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("WhatsApp", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RedButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Red,
            contentColor = Color.White
        ),
        shape = RoundedCornerShape(50),
        modifier = Modifier
            .fillMaxWidth(0.7f)
            .height(50.dp)
    ) {
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

fun openWhatsApp(context: Context, phoneNumber: String) {
    try {
        val number = phoneNumber.replace(" ", "").replace("-", "")
        if (number.isBlank()) {
            Toast.makeText(context, "Número no válido", Toast.LENGTH_SHORT).show()
            return
        }

        // ✅ Mensaje automático
        val message = "Hola, soy Franco y quería contactarme con usted respecto a la app de somnolencia 🚗💤"
        val encodedMessage = URLEncoder.encode(message, StandardCharsets.UTF_8.toString())

        // ✅ Formatear el número (añadir +51 si no está)
        val fullNumber = if (number.startsWith("+")) number else "+51$number"

        // ✅ Usamos la URL oficial de WhatsApp API (sin setPackage)
        val url = "https://api.whatsapp.com/send?phone=${fullNumber.replace("+", "")}&text=$encodedMessage"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)

    } catch (e: Exception) {
        Toast.makeText(context, "Error al abrir WhatsApp: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}




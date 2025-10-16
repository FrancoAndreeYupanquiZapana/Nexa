/*
* Tiene el app navigator para controlar el flujo principal
* tiene la pantalla de bienvenida que es la principal (bienvenida de la app)
* tiene el menu principal (opciones de momento solo uno)
* */

package com.example.drowsinessdetectorapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.graphics.Color
import com.example.drowsinessdetectorapp.ui.MainScreen
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.drowsinessdetectorapp.ui.theme.Nexatheme


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Nexatheme {
                AppNavigator()
            }
        }
    }
}

@Composable
fun AppNavigator(){
    val navController= rememberNavController()

    NavHost(navController = navController, startDestination = "Bienvenido") {
        composable("Bienvenido"){WelcomeScreen(navController)}
        composable("menu"){MenuScreen(navController)}
        composable ("camara"){ MainScreen()  }
        //composable("contactos"){ContactScreen(prefs)}
        //composable("configuracion"){SettingsScreen(prefs)}
    }
}

//pantalla de bienvenida
@Composable
fun WelcomeScreen( navController: NavHostController){
    Scaffold (
        content = { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment= Alignment.Center
            ){
                Image(
                    painter = painterResource(id = R.drawable.background),
                    contentDescription = "Fondo Nexa",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                Column(modifier = Modifier.padding(padding), horizontalAlignment = Alignment.CenterHorizontally){
                    Image(
                        painter = painterResource(id = R.drawable.logo),
                        contentDescription = "Logo Nexa",
                        modifier = Modifier
                            .size(250.dp)
                            .shadow(
                                elevation = 12.dp,
                                shape = RoundedCornerShape(32.dp),
                                clip = false
                            )
                            .clip(RoundedCornerShape(32.dp))
                    )
                    Spacer(modifier = Modifier.height(40.dp))
                    Button(
                        onClick = { navController.navigate("menu") },
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .height(56.dp)
                            .width(200.dp)
                            .shadow(10.dp, shape = RoundedCornerShape(50))
                    ) {
                        Text(
                            text = "Start ▶",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 2.sp
                            )
                        )
                    }
                }
            }

        }
    )
}

//pantalla de menus
@Composable
fun MenuScreen(navController: NavHostController) {
    // Imagen de fondo
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black) // fallback
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
            // Título
            Text(
                text = "Menu Principal",
                color = Color.Red,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            // Botón 1 - Abrir cámara
            RedButton(
                text = "Abrir cámara",
                onClick = { navController.navigate("camara") }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Botón 2 - WhatsApp
            RedButton(
                text = "WhatsApp",
                onClick = { /* abrir whatsapp */ }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Botón 3 - Configuraciones
            RedButton(
                text = "Configuraciones",
                onClick = { /* ir a configs */ }
            )
        }

        // Botón flotante A (abajo a la derecha)
        FloatingActionButton(
            onClick = { /* Acción de alerta */ },
            containerColor = Color.Red,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
        ) {
            Text(
                "A",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
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
        Text(
            text,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    Nexatheme {
        AppNavigator()
    }
}
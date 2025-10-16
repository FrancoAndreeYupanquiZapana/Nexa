package com.example.drowsinessdetectorapp.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

//tema de la app
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
//import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

//Paleta de colores a usar (ya estan en theme.color)
//val NeonRed = Color(0xFFFF2D55)
//val DarkGray = Color(0xFF0B0B0C)
//val CardGray = Color(0xFF141414)
//val AccentNeon = Color(0xFF00FFD1)
//val WhiteSoft = Color(0xFFEEEEEE)

private val DarkColors = darkColorScheme(
    primary = NexaRed,
    onPrimary = NexaWhite,
    surface=NexaGray,
    onSurface=NexaWhite,
    background=NexaBlack,
    onBackground=NexaWhite,
    secondary=NexaNeon
)

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

@Composable
fun Nexatheme(content: @Composable () -> Unit){
    val colors = DarkColors
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(),
        content = content
    )
}

@Composable
fun DrowsinessDetectorAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
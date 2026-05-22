package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme =
  lightColorScheme(
    primary = SecurePrimary,
    onPrimary = SecureOnPrimary,
    secondary = SecureSecondary,
    secondaryContainer = SecureSecondaryContainer,
    onSecondaryContainer = SecureOnSecondaryContainer,
    background = SecureBackground,
    surface = SecureSurface,
    surfaceVariant = SecureSurfaceVariant,
    onSurface = SecureOnSurface,
    onSurfaceVariant = SecureOnSurfaceVariant,
    outline = SecureOutline
  )

private val DarkColorScheme =
  darkColorScheme(
    primary = SecurePrimary,
    onPrimary = SecureOnPrimary,
    secondary = SecureSecondary,
    secondaryContainer = SecureSecondaryContainer,
    onSecondaryContainer = SecureOnSecondaryContainer,
    background = Color(0xFF111318),
    surface = Color(0xFF111318),
    surfaceVariant = Color(0xFF1F2025),
    onSurface = Color(0xFFE2E2E9),
    onSurfaceVariant = Color(0xFFC4C6CF),
    outline = SecureOutline
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Disable dynamic color by default to preserve the exact brand identity
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}

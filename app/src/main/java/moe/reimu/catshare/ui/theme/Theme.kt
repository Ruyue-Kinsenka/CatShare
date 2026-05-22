package moe.reimu.catshare.ui.theme

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

private val DarkColorScheme = darkColorScheme(
    primary = Blue80,
    onPrimary = Color(0xFF002C7A),
    primaryContainer = Blue30,
    onPrimaryContainer = Blue90,
    secondary = Teal80,
    onSecondary = Color(0xFF003731),
    secondaryContainer = Teal30,
    onSecondaryContainer = Teal90,
    tertiary = Coral80,
    onTertiary = Color(0xFF5A1806),
    tertiaryContainer = Coral30,
    onTertiaryContainer = Coral90,
    background = Neutral8,
    onBackground = Color(0xFFE5E8F0),
    surface = Neutral8,
    onSurface = Color(0xFFE5E8F0),
    surfaceVariant = Color(0xFF454955),
    onSurfaceVariant = Color(0xFFC5C8D3),
    outline = Color(0xFF9094A0),
    outlineVariant = Color(0xFF444852),
    surfaceContainerLowest = Color(0xFF0D0F14),
    surfaceContainerLow = Color(0xFF181B22),
    surfaceContainer = Color(0xFF1F222B),
    surfaceContainerHigh = Color(0xFF292C35),
    surfaceContainerHighest = Color(0xFF343844)
)

private val LightColorScheme = lightColorScheme(
    primary = Blue40,
    onPrimary = Color.White,
    primaryContainer = Blue90,
    onPrimaryContainer = Color(0xFF00194D),
    secondary = Teal40,
    onSecondary = Color.White,
    secondaryContainer = Teal90,
    onSecondaryContainer = Color(0xFF00201B),
    tertiary = Coral40,
    onTertiary = Color.White,
    tertiaryContainer = Coral90,
    onTertiaryContainer = Color(0xFF3B0900),
    background = Neutral96,
    onBackground = Neutral12,
    surface = Neutral96,
    onSurface = Neutral12,
    surfaceVariant = Color(0xFFE2E5EF),
    onSurfaceVariant = Color(0xFF454954),
    outline = Color(0xFF747884),
    outlineVariant = Color(0xFFC4C7D2),
    inverseSurface = Neutral20,
    inverseOnSurface = Color(0xFFF2F4FA),
    inversePrimary = Blue80,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Neutral99,
    surfaceContainer = Neutral94,
    surfaceContainerHigh = Neutral92,
    surfaceContainerHighest = Neutral88
)

@Composable
fun CatShareTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
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

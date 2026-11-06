package ai.zoya.assistant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Void = Color(0xFF05060A)
val VoidLift = Color(0xFF0A0C14)
val Rose = Color(0xFFFF7EA8)
val RoseSoft = Color(0xFFFFB3C9)
val Violet = Color(0xFFA45BFF)
val Cyan = Color(0xFF67E8F9)
val Ink = Color(0xFFEEF1F8)

private val scheme = darkColorScheme(
    primary = Rose,
    onPrimary = Color(0xFF17060E),
    secondary = Violet,
    onSecondary = Color.White,
    tertiary = Cyan,
    background = Void,
    onBackground = Ink,
    surface = VoidLift,
    onSurface = Ink,
    error = Color(0xFFFF8A8A),
)

private val ZoyaType = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 56.sp,
        lineHeight = 58.sp,
        letterSpacing = (-1.6).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        letterSpacing = 0.2.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 19.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 10.5.sp,
        letterSpacing = 2.4.sp,
    ),
)

@Composable
fun ZoyaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = ZoyaType, content = content)
}

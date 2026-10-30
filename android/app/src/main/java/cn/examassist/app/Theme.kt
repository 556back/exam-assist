package cn.examassist.app

import androidx.compose.material3.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object AppColors {
    val Background = Color(0xFFF3F6FA)
    val Ink = Color(0xFF163A5F)
    val Primary = Color(0xFF245BDB)
    val Capture = Color(0xFFD32F2F)
    val Reference = Color(0xFFDDEFEA)
    val Warning = Color(0xFF8B5E1A)
    val Surface = Color.White
}
@Composable fun ExamAssistTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(
        primary = AppColors.Primary, onPrimary = Color.White,
        background = AppColors.Background, onBackground = AppColors.Ink,
        surface = AppColors.Surface, onSurface = AppColors.Ink,
        secondaryContainer = AppColors.Reference, onSecondaryContainer = AppColors.Ink
    ), shapes = Shapes(extraSmall = RoundedCornerShape(12.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(16.dp), extraLarge = RoundedCornerShape(12.dp)), content = content)
}

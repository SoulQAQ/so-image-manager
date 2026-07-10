package cn.soul2.imageai.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DefaultTypography = Typography()

val Typography = Typography(
    displayLarge = DefaultTypography.displayLarge.copy(letterSpacing = 0.sp),
    displayMedium = DefaultTypography.displayMedium.copy(letterSpacing = 0.sp),
    displaySmall = DefaultTypography.displaySmall.copy(letterSpacing = 0.sp),
    headlineLarge = DefaultTypography.headlineLarge.copy(letterSpacing = 0.sp),
    headlineMedium = DefaultTypography.headlineMedium.copy(letterSpacing = 0.sp),
    headlineSmall = DefaultTypography.headlineSmall.copy(letterSpacing = 0.sp),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = DefaultTypography.titleMedium.copy(letterSpacing = 0.sp),
    titleSmall = DefaultTypography.titleSmall.copy(letterSpacing = 0.sp),
    bodyMedium = DefaultTypography.bodyMedium.copy(letterSpacing = 0.sp),
    bodySmall = DefaultTypography.bodySmall.copy(letterSpacing = 0.sp),
    labelLarge = DefaultTypography.labelLarge.copy(letterSpacing = 0.sp),
    labelMedium = DefaultTypography.labelMedium.copy(letterSpacing = 0.sp),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    )
)

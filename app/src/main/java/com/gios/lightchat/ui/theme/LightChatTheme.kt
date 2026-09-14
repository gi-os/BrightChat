@file:OptIn(ExperimentalTextApi::class)

package com.gios.lightchat.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gios.lightchat.R
import com.gios.lightchat.ui.rememberGatedHaptics

object ChatColors {
    val background = Color.Black
    val onSurface = Color.White
    val onSurfaceVariant = Color.White.copy(alpha = 0.7f)
    val onSurfaceDim = Color.White.copy(alpha = 0.5f)
    val onSurfaceDisabled = Color.White.copy(alpha = 0.3f)

    /** An unselected navbar icon. LightFog's own inactive grey, not an alpha of
     *  white: on a matte greyscale panel a solid mid-grey holds its silhouette at
     *  48dp where 30%-alpha white goes muddy. */
    val onSurfaceInactive = Color(0xFF6E6E6E)
}

val PublicSans = FontFamily(
    Font(
        R.font.publicsans_variablefont_wght,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        R.font.publicsans_variablefont_wght,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    // Heavy weight, used by the text tapbacks (haha / !! / ?) so they hold their own
    // next to the solid filled glyphs (heart/thumbs).
    Font(
        R.font.publicsans_variablefont_wght,
        weight = FontWeight.ExtraBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(800)),
    ),
)

object ChatType {
    val title = TextStyle(
        fontFamily = PublicSans,
        fontSize = 32.sp,
        fontWeight = FontWeight.Normal,
    )
    val button = TextStyle(
        fontFamily = PublicSans,
        fontSize = 32.sp,
        fontWeight = FontWeight.Normal,
    )
    val body = TextStyle(
        fontFamily = PublicSans,
        fontSize = 20.sp,
        fontWeight = FontWeight.Normal,
    )
    val meta = TextStyle(
        fontFamily = PublicSans,
        fontSize = 16.sp,
        fontWeight = FontWeight.Normal,
    )
    val hint = TextStyle(
        fontFamily = PublicSans,
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
    )
}

object ChatDimens {
    val screenPadding = 24.dp
}

/**
 * [fillScreen] exists for the heads-up box. Its window is MATCH_PARENT x
 * WRAP_CONTENT, so a `fillMaxSize` Surface would measure to the full screen height,
 * paint it black, and — because Material3's Surface installs a pointerInput to
 * consume touches — swallow every tap on the phone for as long as the box was up.
 */
@Composable
fun LightChatTheme(fillScreen: Boolean = true, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, fontScale = 0.85f),
        // Every buzz in the app goes through LocalHapticFeedback, and all three windows go
        // through this theme, so one switch here turns the lot off. See [com.gios.lightchat.ui.Haptics].
        LocalHapticFeedback provides rememberGatedHaptics(),
    ) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                background = ChatColors.background,
                surface = ChatColors.background,
                onBackground = ChatColors.onSurface,
                onSurface = ChatColors.onSurface,
                primary = ChatColors.onSurface,
            ),
        ) {
            Surface(
                modifier = if (fillScreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
                color = ChatColors.background,
                content = content,
            )
        }
    }
}

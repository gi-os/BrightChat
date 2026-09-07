package com.gios.lightchat.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Hand-parsed Material path data, shared by every glyph in the app — the same house rule
 * [ConversationNavbar] and [TapbackGlyph] already follow: a material-icons-extended
 * dependency would be several megabytes for a handful of shapes we actually draw.
 */
internal fun vectorIcon(pathData: String): ImageVector =
    ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = PathParser().parsePathString(pathData).toNodes(),
        fill = SolidColor(Color.White),
    ).build()

/**
 * Material `phone` — ringing a 1:1's number. Shared by the thread header's Call and the
 * bottom navbar's Dial tab so there is exactly one phone glyph in the app.
 */
val PhoneIcon: ImageVector by lazy {
    vectorIcon(
        "M6.62 10.79c1.44 2.83 3.76 5.14 6.59 6.59l2.2-2.2c.27-.27.67-.36 1.02-.24 " +
            "1.12.37 2.33.57 3.57.57.55 0 1 .45 1 1V20c0 .55-.45 1-1 1-9.39 0-17-7.61-17-17 " +
            "0-.55.45-1 1-1h3.5c.55 0 1 .45 1 1 0 1.25.2 2.45.57 3.57.11.35.03.74-.25 1.02l-2.2 2.2z",
    )
}

/** Material `add` — starting a new conversation from the list header. */
val PlusIcon: ImageVector by lazy {
    vectorIcon("M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6z")
}

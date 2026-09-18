package com.gios.lightchat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.gios.light.common.hw.WheelScroll
import com.gios.lightchat.Attachment
import com.gios.lightchat.ColorMode
import com.gios.lightchat.ui.theme.ChatColors
import com.gios.lightchat.ui.theme.ChatType
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay

/** How far pinch/double-tap zoom may go. The decoded bitmap is capped at 1080px
 *  on its long edge (see [com.gios.lightchat.Attachments]), so past ~4× there's
 *  no detail left to reveal on this screen anyway. */
private const val MAX_SCALE = 4f

/** A GIF is decoded larger for the viewer than for the thread — this is the full-screen look at
 *  it. Still well under the photograph cap: a GIF holds every frame in memory at once. */
private const val GIF_VIEWER_DIM = 900

/** The scale a double-tap jumps to (a second double-tap returns to fit). */
private const val DOUBLE_TAP_SCALE = 2.5f

/** Dismissal: how long the photo takes to fade out to the black background. */
private const val FADE_OUT_MS = 120

/** Dismissal: how long to hold the all-black frame after the grayscale restore
 *  before revealing the thread — the settings write propagates asynchronously
 *  (system_server → SurfaceFlinger), so give it a few frames to land while the
 *  screen is still black and the flip is invisible. */
private const val RESTORE_SETTLE_MS = 70L

/**
 * Full-screen viewer for one image attachment, opened by tapping it in the
 * thread. Same loader (and caches) as the inline rendering, so it appears
 * instantly. Pinch to zoom, drag to pan while zoomed, double-tap to toggle
 * zoom at the tapped point; a single tap — or Back — closes it. Pure black
 * behind the image, no chrome: the LightOS look.
 */
@Composable
fun ImageViewerScreen(
    attachment: Attachment,
    loadImage: suspend (Attachment) -> ImageBitmap?,
    onClose: () -> Unit,
    /**
     * The undecoded file, for an animated GIF — which is played rather than decoded (see
     * [rememberGifPainter]). Null means "no GIF playback here", in which case a GIF opens as its
     * first frame, exactly as it did before: a viewer that shows something is better than one
     * that refuses to open.
     */
    loadFile: (suspend (Attachment) -> java.io.File?)? = null,
    /**
     * Hold the picture to keep it.
     *
     * A long press because there is nowhere to put a button: the viewer is the photograph and
     * nothing else, which is the point of it, and a chrome bar over somebody's picture to hold one
     * action would be the wrong trade. The confirmation is the app's own status line, as with every
     * other thing that takes a moment here.
     */
    onSave: (() -> Unit)? = null,
    /**
     * What saving had to say, drawn over the photograph.
     *
     * The viewer is opaque and covers the app's own status line, so without this the confirmation
     * for a gesture made *here* appears somewhere the user cannot see until they close the picture.
     */
    status: String? = null,
) {
    // True color for exactly as long as the viewer is up (vandamd's zero trick;
    // see ColorMode — a no-op without the one-time WRITE_SECURE_SETTINGS grant).
    // Backgrounding mid-view is handled by MainActivity's onStop/onStart.
    val context = LocalContext.current
    // Exactly one release per acquire. ColorMode counts holders now (the picker holds it
    // too), so the old belt-and-braces "release on close *and* on dispose" would
    // over-release and drop the count to zero underneath the other holder.
    val released = remember { AtomicBoolean(false) }
    fun releaseColor() {
        if (released.compareAndSet(false, true)) ColorMode.release(context)
    }
    DisposableEffect(Unit) {
        ColorMode.acquire(context)
        // Normally already released mid-close (below); this catches the viewer
        // being disposed some other way (e.g. the whole thread closing).
        onDispose { releaseColor() }
    }

    // Closing plays a short exit so the grayscale flip can't be seen: fade the
    // photo out to the black background, restore grayscale while the screen is
    // pure black (black is identical in color and mono — the one moment the flip
    // is invisible), hold a few frames for the flip to land, then dismiss. The
    // thread appears already-B&W; nothing on screen ever visibly desaturates.
    var closing by remember { mutableStateOf(false) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(closing) {
        if (!closing) return@LaunchedEffect
        fade.animateTo(0f, tween(FADE_OUT_MS))
        releaseColor()
        delay(RESTORE_SETTLE_MS)
        onClose()
    }

    BackHandler { closing = true }

    // One or the other, never both: a GIF is played from its file and everything else is decoded.
    val animated = attachment.isGif && loadFile != null
    val bitmap by produceState<ImageBitmap?>(initialValue = null, attachment.guid, animated) {
        value = if (animated) null else loadImage(attachment)
    }
    val gifFile by produceState<java.io.File?>(initialValue = null, attachment.guid, animated) {
        value = if (animated) loadFile?.invoke(attachment) else null
    }
    // Decoded larger here than in the thread: this is the full-screen view of it, and the zoom
    // goes to 4x.
    val gif = rememberGifPainter(gifFile, maxDim = GIF_VIEWER_DIM)

    // The zoom/pan transform: the image is drawn scaled by [scale] about the
    // screen centre, then shifted by [offset] (screen pixels).
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var container by remember { mutableStateOf(IntSize.Zero) }

    // Keep the image from being dragged fully off-screen: the translation is
    // bounded by how much the scaled content overhangs the container. (Bounded
    // on the container, not the fitted image, so a letterboxed photo can be
    // panned edge-to-edge — simple and good enough at these sizes.)
    fun clamp(o: Offset, s: Float): Offset {
        val maxX = container.width * (s - 1f) / 2f
        val maxY = container.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    // The wheel pans a zoomed photo vertically, which is the one thing dragging is worst
    // at here: a drag that starts on the image is also a tap candidate, so nudging a
    // zoomed photo a little tends to close the viewer instead. There is no ScrollableState
    // to hoist — the transform is a translation, not a scroll — so one is made out of the
    // same clamped assignment the drag uses. Reporting what was actually applied matters:
    // at full zoom-out the clamp is zero-width, so the wheel truthfully has nothing to
    // move and its debt is dropped rather than saved up.
    val pan = remember {
        ScrollableState { delta ->
            val before = offset
            offset = clamp(Offset(before.x, before.y - delta), scale)
            before.y - offset.y
        }
    }
    WheelScroll(pan, active = !closing)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ChatColors.background)
            .onSizeChanged { container = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { closing = true },
                    // Only while the photograph is at rest. Zoomed in, the same press-and-hold is
                    // the start of a pan, and a hold that fires mid-pan both saves something nobody
                    // asked for and kills the drag — detectTapGestures consumes the rest of the
                    // gesture once its long press has run.
                    onLongPress = onSave?.let { save -> { if (scale == 1f) save() } },
                    onDoubleTap = { tap ->
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            val center = Offset(size.width / 2f, size.height / 2f)
                            scale = DOUBLE_TAP_SCALE
                            // Pull the tapped point toward the centre of the screen.
                            offset = clamp((center - tap) * (DOUBLE_TAP_SCALE - 1f), DOUBLE_TAP_SCALE)
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, MAX_SCALE)
                    val center = Offset(size.width / 2f, size.height / 2f)
                    // Keep the content point under the fingers fixed while the
                    // scale changes, then apply the drag. (graphicsLayer scales
                    // about the centre then translates, so a content point q —
                    // relative to centre — lands at s·q + offset.)
                    val newOffset = (centroid - center) - (centroid - center - offset) * (newScale / scale) + pan
                    offset = clamp(newOffset, newScale)
                    scale = newScale
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        // The same transform for both, so pinch, pan and the double-tap zoom behave identically
        // whether what is on screen is a photograph or a GIF.
        val transform = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
                alpha = fade.value
            }
        when {
            gif != null -> Image(
                painter = gif,
                contentDescription = attachment.transferName,
                contentScale = ContentScale.Fit,
                modifier = transform,
            )
            image != null -> Image(
                bitmap = image,
                contentDescription = attachment.transferName,
                contentScale = ContentScale.Fit,
                modifier = transform,
            )
            else -> Text(
                text = if (animated) "[GIF]" else "[Image]",
                style = ChatType.hint,
                color = ChatColors.onSurfaceDisabled,
            )
        }
        // Over the picture, at the bottom, and only while there is something to say. It fades with
        // the rest of the viewer on the way out so it cannot be left hanging over the thread.
        status?.let { line ->
            Text(
                text = line,
                style = ChatType.hint,
                color = ChatColors.onSurfaceDisabled,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
                    .graphicsLayer { alpha = fade.value },
            )
        }
    }
}

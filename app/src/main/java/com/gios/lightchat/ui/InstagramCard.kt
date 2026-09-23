package com.gios.lightchat.ui

import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.gios.lightchat.links.Instagram
import com.gios.lightchat.ui.theme.ChatColors
import com.gios.lightchat.ui.theme.ChatType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Opens a video full screen. Provided by the thread, so a card deep in a row can reach it. */
val LocalPlayVideo = staticCompositionLocalOf<(File) -> Unit> { {} }

/**
 * An Instagram post or reel, in place of its link.
 *
 * The picture first (every item of a carousel, side by side), then `@owner` and two lines of the
 * caption. A reel plays **in the thread**: tap the picture and the clip is fetched and runs where
 * the picture was, looping, with sound; tap again to pause. Hold it for full screen. Anything the
 * embed page will not describe falls back to the link itself, which still opens in the browser.
 *
 * See [Instagram] for where the post comes from.
 */
@Composable
fun InstagramCard(link: Instagram.Link, modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    val post by produceState<Instagram.Post?>(null, link.code) {
        value = withContext(Dispatchers.IO) { Instagram.post(link) }
    }
    val p = post ?: return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, ChatColors.onSurfaceDisabled, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .padding(8.dp),
    ) {
        if (p.items.size == 1) {
            InstagramItem(link, p.items[0], 0, Modifier.fillMaxWidth())
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(p.items, key = { i, _ -> i }) { i, item ->
                    InstagramItem(link, item, i, Modifier.width(220.dp))
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { runCatching { uri.openUri(link.url) } },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = (if (link.isReel) "Reel" else "Post") + (p.owner?.let { " · @$it" } ?: ""),
                style = ChatType.hint,
                color = ChatColors.onSurfaceVariant,
                maxLines = 1,
            )
            if (p.items.size > 1) {
                Text(text = "${p.items.size} photos", style = ChatType.hint, color = ChatColors.onSurfaceDisabled)
            }
        }
        p.caption?.let {
            Text(
                text = it,
                style = ChatType.hint,
                color = ChatColors.onSurfaceDim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun InstagramItem(link: Instagram.Link, item: Instagram.Item, index: Int, modifier: Modifier) {
    val context = LocalContext.current
    val playFull = LocalPlayVideo.current
    val scope = rememberCoroutineScope()
    val bitmap by produceState<android.graphics.Bitmap?>(null, link.code, index) {
        value = withContext(Dispatchers.IO) { runCatching { Instagram.image(context, link, index) }.getOrNull() }
    }
    var video by remember(link.code, index) { mutableStateOf<File?>(null) }
    var loading by remember(link.code, index) { mutableStateOf(false) }
    var failed by remember(link.code, index) { mutableStateOf(false) }
    val ratio = if (item.width > 0 && item.height > 0) item.width.toFloat() / item.height else 1f

    val fetch: (then: (File) -> Unit) -> Unit = { then ->
        if (!loading) {
            loading = true
            failed = false
            scope.launch {
                val f = withContext(Dispatchers.IO) { runCatching { Instagram.video(context, link, index) }.getOrNull() }
                loading = false
                if (f != null) then(f) else failed = true
            }
        }
    }

    Box(
        modifier = modifier
            .heightIn(max = 420.dp)
            .aspectRatio(ratio.coerceIn(0.5f, 2f)),
        contentAlignment = Alignment.Center,
    ) {
        val playing = video
        if (playing != null) {
            InlineVideo(playing, onFullScreen = { playFull(playing) })
        } else {
            bitmap?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = item.isVideo,
                        ) { fetch { video = it } },
                )
            }
            if (item.isVideo) {
                Text(
                    text = when {
                        loading -> "Loading…"
                        failed -> "Couldn’t load · tap to retry"
                        else -> "▶"
                    },
                    style = if (loading || failed) ChatType.hint else ChatType.title,
                    color = ChatColors.onSurface,
                )
            }
        }
    }
}

/**
 * A reel playing in place: looping, with sound, tap to pause or resume, hold for full screen.
 * The same platform `VideoView` the full-screen player uses; released when the row scrolls away.
 */
@Composable
private fun InlineVideo(file: File, onFullScreen: () -> Unit) {
    var view by remember { mutableStateOf<VideoView?>(null) }
    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                setOnPreparedListener { mp ->
                    mp.isLooping = true
                    start()
                }
                setOnErrorListener { _, _, _ -> true }
                setOnClickListener { if (isPlaying) pause() else start() }
                setOnLongClickListener {
                    pause()
                    onFullScreen()
                    true
                }
                setVideoPath(file.path)
                view = this
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
    DisposableEffect(file) {
        onDispose { view?.stopPlayback() }
    }
}

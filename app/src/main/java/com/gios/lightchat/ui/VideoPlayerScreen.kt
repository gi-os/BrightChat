package com.gios.lightchat.ui

import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.gios.lightchat.VideoCodecs
import com.gios.lightchat.ui.theme.ChatColors
import com.gios.lightchat.ui.theme.ChatType
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A video, played in the app rather than handed to somebody else.
 *
 * **Handing it off did not work on this phone.** LightOS has no video player installed, so
 * `ACTION_VIEW` ends in "No app can open this file" after a download you have already waited for.
 *
 * **ExoPlayer, not `VideoView`.** `VideoView` was here first and played an iPhone `.mov` as sound
 * with a black screen. Not reproduced on the phone; the likeliest cause: recent iPhones record
 * HEVC, by default in HDR (Dolby Vision profile 8 or HLG), and `MediaPlayer` sees a Dolby Vision
 * track, finds no Dolby Vision decoder, and quietly drops the picture while the audio carries on — no error, so the old "won't play" line never showed
 * either. Profile 8 is ordinary HEVC with extra metadata; ExoPlayer knows that and falls back to the
 * HEVC decoder, which this phone has. It also reports an unplayable picture, where `MediaPlayer`
 * said nothing.
 *
 * **A `TextureView`, not a `SurfaceView`.** A SurfaceView is a hole cut through the window to a
 * layer underneath, and whether the hole shows depends on everything Compose paints around it. A
 * TextureView draws in the view tree like anything else.
 *
 * **When it still can't play,** the line names the codec ([VideoCodecs]), because "HEVC HDR" is
 * something the sender can change and "won't play" is not.
 *
 * Full screen and opaque, like [ImageViewerScreen] — the thread stays composed underneath, so
 * closing lands exactly where you were with its scroll position intact.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(file: File, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    var broken by remember(file) { mutableStateOf(false) }
    var failure by remember(file) { mutableStateOf<String?>(null) }
    var aspect by remember(file) { mutableFloatStateOf(0f) }

    val player = remember(file) {
        // Decoder fallback: if the first decoder for a format refuses to start, try the next one
        // rather than failing. Matters on a phone with few codecs and a vendor one that is picky.
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        ExoPlayer.Builder(context, renderers).build().apply {
            // Loop, and no controls. There is no room on a 3.92" panel for a scrubber, and a clip
            // in a message thread is seconds long — watching it twice is easier than aiming.
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                broken = true
            }

            // The failure VideoView had, caught here: a video track exists but no renderer took
            // it, so only the sound would play. Stop rather than play a black screen with audio.
            override fun onTracksChanged(tracks: Tracks) {
                if (tracks.containsType(C.TRACK_TYPE_VIDEO) && !tracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) {
                    player.pause()
                    broken = true
                }
            }
        }
        player.addListener(listener)
        player.prepare()
        // Released on the way out rather than left to the garbage collector: a player holds a
        // codec, and the phone has few of them.
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(broken) {
        if (broken) {
            player.stop()
            failure = withContext(Dispatchers.IO) { describe(file) }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = ChatColors.background) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val message = failure
            if (broken) {
                Text(
                    text = message ?: "",
                    style = ChatType.body,
                    color = ChatColors.onSurfaceDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            } else {
                // Fitted by hand: the largest box with the video's shape that fits the screen.
                // Until the first frame reports a size, fill the screen — it is black anyway.
                val sized = if (aspect > 0f) {
                    val w = minOf(maxWidth.value, maxHeight.value * aspect)
                    Modifier.size(w.dp, (w / aspect).dp)
                } else {
                    Modifier.fillMaxSize()
                }
                AndroidView(
                    factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } },
                    onRelease = { player.clearVideoTextureView(it) },
                    modifier = sized,
                )
            }

            // The whole surface closes, which is the same gesture the image viewer uses. A
            // dedicated close button would be the only chrome on the screen.
            HapticText(
                text = "Close",
                style = ChatType.hint,
                color = ChatColors.onSurfaceDim,
                onClick = onClose,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            )
        }
    }
}

/**
 * Why this file won't play, in words: the video track's codec and whether it is HDR, read straight
 * off the container. Also says whether the phone has a decoder for it at all, which goes in the
 * log line a shake report picks up.
 */
private fun describe(file: File): String {
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(file.path)
        val format = (0 until extractor.trackCount)
            .map(extractor::getTrackFormat)
            .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        val mime = format?.getString(MediaFormat.KEY_MIME)
        val transfer = format
            ?.takeIf { it.containsKey(MediaFormat.KEY_COLOR_TRANSFER) }
            ?.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
        val decoder = format?.let {
            runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(it) }.getOrNull()
        }
        android.util.Log.w("VideoPlayer", "can't play ${file.name}: $format decoder=$decoder")
        VideoCodecs.cantPlay(mime, transfer)
    } catch (t: Throwable) {
        android.util.Log.w("VideoPlayer", "can't read ${file.name}", t)
        VideoCodecs.cantPlay(null, null)
    } finally {
        extractor.release()
    }
}

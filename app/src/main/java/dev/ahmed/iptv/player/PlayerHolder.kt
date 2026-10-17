package dev.ahmed.iptv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.ahmed.iptv.data.ChannelRow
import dev.ahmed.iptv.data.Repo
import java.util.Locale

/**
 * One ExoPlayer for the whole app, so playback continues in the mini player while browsing.
 * Video is decoded by the phone's hardware decoders; FFmpeg is only used for audio formats
 * the phone can't decode (AC3 / E-AC3 / DTS…).
 */
class PlayerHolder(private val context: Context, private val repo: Repo) {

    data class TrackOption(val type: Int, val groupIndex: Int, val trackIndex: Int, val label: String, val selected: Boolean)

    private var _player: ExoPlayer? = null
    val player: ExoPlayer get() = _player ?: build().also { _player = it }

    var channel by mutableStateOf<ChannelRow?>(null)
        private set
    /** Title shown when playing a catch-up program instead of the live channel. */
    var catchupTitle by mutableStateOf<String?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** Bumped when the available tracks change. */
    var tracksVersion by mutableIntStateOf(0)
        private set

    private var list: List<ChannelRow> = emptyList()
    private var playlistId = 0L
    private var retries = 0
    private val handler = Handler(Looper.getMainLooper())

    private fun build(): ExoPlayer {
        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val dataSource = OkHttpDataSource.Factory(repo.http).setUserAgent(Repo.USER_AGENT)
        val loadControl = DefaultLoadControl.Builder()
            // Start fast (1 s buffered) but keep enough in reserve to ride out network hiccups.
            .setBufferDurationsMs(15_000, 50_000, 1_000, 2_500)
            .build()
        return ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply { addListener(listener) }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
        }

        override fun onPlaybackStateChanged(state: Int) {
            buffering = state == Player.STATE_BUFFERING
            if (state == Player.STATE_READY) {
                retries = 0
                error = null
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            tracksVersion++
        }

        override fun onPlayerError(e: PlaybackException) {
            val p = _player ?: return
            if (retries < MAX_RETRIES) {
                retries++
                error = "Reconnecting… ($retries/$MAX_RETRIES)"
                handler.postDelayed({
                    if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) p.seekToDefaultPosition()
                    p.prepare()
                    p.play()
                }, 1_000L * retries)
            } else {
                error = describe(e)
            }
        }
    }

    fun play(channels: List<ChannelRow>, index: Int, playlistId: Long) {
        if (index !in channels.indices) return
        list = channels
        this.playlistId = playlistId
        val ch = channels[index]
        channel = ch
        catchupTitle = null
        start(ch.url)
        val key = ch.key
        val pid = playlistId
        Thread { runCatching { repo.db.addRecent(pid, key, System.currentTimeMillis()) } }.start()
    }

    fun playCatchup(ch: ChannelRow, url: String, title: String) {
        channel = ch
        catchupTitle = title
        start(url)
    }

    private fun start(url: String) {
        handler.removeCallbacksAndMessages(null)
        retries = 0
        error = null
        val item = MediaItem.Builder().setUri(url).apply {
            if (url.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        player.setMediaItem(item)
        player.prepare()
        player.play()
    }

    /** Zaps to the next (+1) or previous (-1) channel in the list it was started from. */
    fun zap(step: Int) {
        val cur = channel ?: return
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.id == cur.id }.coerceAtLeast(0)
        val next = ((i + step) % list.size + list.size) % list.size
        play(list, next, playlistId)
    }

    fun currentList(): List<ChannelRow> = list

    fun togglePause() {
        val p = _player ?: return
        if (p.isPlaying) p.pause() else {
            if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) p.prepare()
            p.play()
        }
    }

    fun pause() {
        _player?.pause()
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        _player?.stop()
        _player?.clearMediaItems()
        channel = null
        catchupTitle = null
        error = null
    }

    // ---------- tracks ----------

    fun tracks(type: Int): List<TrackOption> {
        val p = _player ?: return emptyList()
        val out = ArrayList<TrackOption>()
        p.currentTracks.groups.forEachIndexed { gi, g ->
            if (g.type != type) return@forEachIndexed
            for (ti in 0 until g.length) {
                if (!g.isTrackSupported(ti)) continue
                out += TrackOption(type, gi, ti, label(g.getTrackFormat(ti), out.size + 1), g.isTrackSelected(ti))
            }
        }
        return out
    }

    fun select(option: TrackOption) {
        val p = _player ?: return
        val group = p.currentTracks.groups.getOrNull(option.groupIndex) ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(option.type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
            .build()
    }

    fun subtitlesOff() {
        val p = _player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    fun subtitlesEnabled(): Boolean = _player?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) != true

    private fun label(f: Format, n: Int): String {
        val parts = ArrayList<String>()
        val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }?.let {
            Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()).ifBlank { it }
        }
        parts += f.label?.takeIf { it.isNotBlank() } ?: lang ?: "Track $n"
        codecName(f.sampleMimeType)?.let { parts += it }
        if (f.channelCount == 6) parts += "5.1" else if (f.channelCount == 8) parts += "7.1"
        if (f.height > 0) parts += "${f.height}p"
        return parts.joinToString(" · ")
    }

    private fun codecName(mime: String?): String? = when (mime) {
        MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital+"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L2 -> "MP2/MP3"
        MimeTypes.AUDIO_DTS -> "DTS"
        else -> null
    }

    private fun describe(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The provider refused this stream (HTTP error). Your connection limit may be reached."
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Can't reach the stream. Check your connection."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "This stream's format isn't supported."
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "This stream's video format isn't supported on this phone."
        else -> "Playback failed: ${e.errorCodeName}"
    }

    companion object {
        const val MAX_RETRIES = 5
    }
}

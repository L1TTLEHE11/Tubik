@file:androidx.annotation.OptIn(UnstableApi::class)

package dev.tubik.app.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import dev.tubik.app.App
import dev.tubik.app.data.GoogleVideoInterceptor
import dev.tubik.app.data.InnerTube
import dev.tubik.app.data.Repository
import dev.tubik.app.data.Repository.Companion.toVideos
import dev.tubik.app.data.VideoItem
import dev.tubik.app.data.WatchTracker
import dev.tubik.app.ui.Nav
import dev.tubik.app.data.SbCategories
import dev.tubik.app.data.Segment
import dev.tubik.app.ui.TubikTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.ByteArrayInputStream

class PlayerActivity : ComponentActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var trackSelector: DefaultTrackSelector
    private val app get() = App.instance

    val ui = PlayerUiState()
    private var segments: List<Segment> = emptyList()
    private val skipped = mutableSetOf<Segment>()
    private var tickJob: Job? = null
    private var loadJob: Job? = null
    private val watched = mutableSetOf<String>()
    /** Отметки просмотра в историю YouTube (только когда вошёл в аккаунт). */
    private var tracker: WatchTracker? = null
    private var tickCount = 0

    private fun track(block: WatchTracker.() -> Unit) {
        val t = tracker ?: return
        app.bgScope.launch { t.block() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val prefs = app.prefs
        trackSelector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                .setPreferredVideoMimeTypes(prefs.codec.mime, "video/avc")
                .apply { if (prefs.maxQuality > 0) setMaxVideoSize(Int.MAX_VALUE, prefs.maxQuality) }
                .build()
        }
        player = ExoPlayer.Builder(this).setTrackSelector(trackSelector).build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                ui.isPlaying.value = isPlaying
                if (!isPlaying) { val pos = player.currentPosition / 1000.0; track { flush(pos) } }
            }
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    val from = old.positionMs / 1000.0
                    val to = new.positionMs / 1000.0
                    track { onSeek(from, to) }
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                ui.buffering.value = state == Player.STATE_BUFFERING
                ui.ended.value = state == Player.STATE_ENDED
                if (state == Player.STATE_READY) ui.durationMs.value = player.duration.coerceAtLeast(0)
            }
            override fun onPlayerError(error: PlaybackException) {
                ui.error.value = "Плеер упал: ${error.errorCodeName}\n${error.cause?.message ?: error.message}"
            }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val h = player.videoFormat?.height ?: 0
                ui.currentHeight.value = h
            }
        })

        setContent {
            TubikTheme { dev.tubik.app.ui.Scaled {
                PlayerScreen(
                    player = player,
                    state = ui,
                    isTv = App.isTv(this),
                    autoplay = app.prefs.autoplay,
                    onQuality = ::setQuality,
                    onSpeed = { player.setPlaybackSpeed(it); ui.speed.value = it },
                    onPlay = ::playVideo,
                    onChannel = {
                        ui.channelUrl.value.takeIf { it.isNotBlank() }?.let {
                            Nav.openChannelFrom(this, it)
                            finish()
                        }
                    },
                    onBack = { finish() },
                )
            } }
        }

        val id = intent.getStringExtra(EXTRA_ID) ?: intent.data?.toString()?.let { Repository.idFromUrl(it) }
        if (id == null) { finish(); return }
        load(id)
    }

    /** Переключиться на другое видео, не выходя из плеера. */
    private fun playVideo(id: String) {
        val pos = player.currentPosition / 1000.0
        track { flush(pos) }
        tracker = null
        loadJob?.cancel()
        tickJob?.cancel()
        player.stop()
        player.clearMediaItems()
        segments = emptyList()
        skipped.clear()
        ui.reset()
        load(id)
    }

    private fun load(id: String) {
        watched += id
        loadJob = lifecycleScope.launch {
            try {
                val sb = async(Dispatchers.IO) {
                    if (app.prefs.sponsorBlockEnabled) runCatching {
                        app.sponsorBlock.segments(id, app.prefs.sponsorBlockCategories)
                    }.getOrDefault(emptyList()) else emptyList()
                }
                val info = app.repo.stream(id)
                ui.title.value = info.name
                if (app.repo.isLoggedIn) {
                    val t = WatchTracker(app.innerTube, id)
                    tracker = t
                    app.bgScope.launch { t.prepare() }
                }
                ui.channel.value = info.uploaderName.orEmpty()
                ui.channelUrl.value = info.uploaderUrl.orEmpty()
                val related = info.relatedItems.toVideos()
                ui.related.value = related
                ui.next.value = related.firstOrNull { !it.isLive && it.id !in watched }
                ui.isLive.value = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM
                ui.qualities.value = DashBuilder.videoStreams(info).map { it.height }.filter { it > 0 }.distinct().sortedDescending()
                player.setMediaSource(mediaSource(info))
                player.prepare()
                player.playWhenReady = true
                segments = sb.await()
                ui.segments.value = segments
                startTicker()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ui.error.value = "Не получилось открыть видео:\n${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun mediaSource(info: StreamInfo): MediaSource {
        // HLS (эфиры) — обычные GET-запросы; DASH к googlevideo — в стиле клиента YouTube
        val ds = OkHttpDataSource.Factory(app.http).setUserAgent(InnerTube.USER_AGENT)
        val gvDs = OkHttpDataSource.Factory(app.http.newBuilder().addInterceptor(GoogleVideoInterceptor()).build())
            .setUserAgent(InnerTube.USER_AGENT)
        val item = MediaItem.Builder().setUri(info.url).setMediaId(info.id).build()

        if (info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM) {
            if (info.hlsUrl.isNotEmpty()) {
                return HlsMediaSource.Factory(ds).createMediaSource(MediaItem.fromUri(info.hlsUrl))
            }
        }
        DashBuilder.build(info)?.let { mpd ->
            val manifest = DashManifestParser().parse(Uri.parse(info.url), ByteArrayInputStream(mpd.toByteArray()))
            return DashMediaSource.Factory(gvDs).createMediaSource(manifest, item)
        }
        if (info.hlsUrl.isNotEmpty()) {
            return HlsMediaSource.Factory(ds).createMediaSource(MediaItem.fromUri(info.hlsUrl))
        }
        val muxed = info.videoStreams.filter { it.isUrl }.maxByOrNull { it.height }
            ?: throw IllegalStateException("YouTube не отдал ни одного потока")
        return ProgressiveMediaSource.Factory(gvDs).createMediaSource(MediaItem.fromUri(muxed.content))
    }

    /** Раз в 250 мс: обновить позицию и пропустить сегмент SponsorBlock, если в него попали. */
    private fun startTicker() {
        tickJob?.cancel()
        tickJob = lifecycleScope.launch {
            while (isActive) {
                val pos = player.currentPosition
                ui.positionMs.value = pos
                ui.bufferedMs.value = player.bufferedPosition
                // раз в секунду — отметки просмотра
                if (++tickCount % 4 == 0 && player.isPlaying) {
                    val sec = pos / 1000.0
                    track { onPlaying(sec); tick(sec, true) }
                }
                if (player.duration > 0) ui.durationMs.value = player.duration
                val seg = segments.firstOrNull { pos >= it.startMs && pos < it.endMs - 300 }
                if (seg != null && seg !in skipped && player.isPlaying) {
                    skipped += seg
                    player.seekTo(seg.endMs)
                    val name = SbCategories.all[seg.category] ?: seg.category
                    Toast.makeText(this@PlayerActivity, "Пропущено: $name", Toast.LENGTH_SHORT).show()
                }
                delay(250)
            }
        }
    }

    /** height = 0 — авто (с учётом лимита из настроек). */
    private fun setQuality(height: Int) {
        val cap = app.prefs.maxQuality
        val b = trackSelector.buildUponParameters()
        if (height == 0) {
            b.clearVideoSizeConstraints().setForceHighestSupportedBitrate(false)
            if (cap > 0) b.setMaxVideoSize(Int.MAX_VALUE, cap)
        } else {
            b.setMaxVideoSize(Int.MAX_VALUE, height)
                .setMinVideoSize(0, height)
                .setForceHighestSupportedBitrate(true)
        }
        trackSelector.parameters = b.build()
        ui.selectedQuality.value = height
    }

    override fun onStop() {
        super.onStop()
        player.pause()
        val pos = player.currentPosition / 1000.0
        track { flush(pos) }
    }

    override fun onDestroy() {
        tickJob?.cancel()
        player.release()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ID = "id"
        fun open(context: Context, videoId: String) {
            context.startActivity(Intent(context, PlayerActivity::class.java).putExtra(EXTRA_ID, videoId))
        }
    }
}

class PlayerUiState {
    val title = mutableStateOf("")
    val channel = mutableStateOf("")
    val isPlaying = mutableStateOf(false)
    val buffering = mutableStateOf(true)
    val ended = mutableStateOf(false)
    val isLive = mutableStateOf(false)
    val positionMs = mutableStateOf(0L)
    val bufferedMs = mutableStateOf(0L)
    val durationMs = mutableStateOf(0L)
    val error = mutableStateOf<String?>(null)
    val qualities = mutableStateOf<List<Int>>(emptyList())
    val selectedQuality = mutableStateOf(0)
    val currentHeight = mutableStateOf(0)
    val speed = mutableStateOf(1f)
    val segments = mutableStateOf<List<Segment>>(emptyList())
    val channelUrl = mutableStateOf("")
    val related = mutableStateOf<List<VideoItem>>(emptyList())
    val next = mutableStateOf<VideoItem?>(null)

    fun reset() {
        title.value = ""; channel.value = ""; channelUrl.value = ""
        isPlaying.value = false; buffering.value = true; ended.value = false; isLive.value = false
        positionMs.value = 0; bufferedMs.value = 0; durationMs.value = 0
        error.value = null; qualities.value = emptyList(); currentHeight.value = 0
        segments.value = emptyList(); related.value = emptyList(); next.value = null
    }
}

@file:androidx.annotation.OptIn(UnstableApi::class)

package dev.tubik.app.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import dev.tubik.app.ui.VideoCard
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPresentationState
import kotlinx.coroutines.delay

private val Accent = Color(0xFFFF3D57)

@Composable
fun PlayerScreen(
    player: Player,
    state: PlayerUiState,
    isTv: Boolean,
    autoplay: Boolean,
    onQuality: (Int) -> Unit,
    onSpeed: (Float) -> Unit,
    onPlay: (String) -> Unit,
    onChannel: () -> Unit,
    onBack: () -> Unit,
) {
    var controlsVisible by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }

    fun poke() { lastInteraction = System.currentTimeMillis(); controlsVisible = true }
    fun seekBy(ms: Long) {
        if (state.isLive.value) return
        val dur = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + ms).coerceIn(0, dur))
        state.positionMs.value = player.currentPosition
    }
    fun togglePlay() { if (player.isPlaying) player.pause() else { if (state.ended.value) player.seekTo(0); player.play() } }

    // Автоскрытие контролов, пока видео играет
    LaunchedEffect(controlsVisible, lastInteraction, state.isPlaying.value, dialog) {
        if (controlsVisible && state.isPlaying.value && dialog == null) {
            delay(4000)
            if (System.currentTimeMillis() - lastInteraction >= 3900) controlsVisible = false
        }
    }
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) runCatching { playFocus.requestFocus() } else runCatching { rootFocus.requestFocus() }
    }

    LaunchedEffect(state.error.value) {
        if (state.error.value != null) { delay(100); runCatching { playFocus.requestFocus() } }
    }

    var autoCancelled by remember(state.title.value) { mutableStateOf(false) }

    BackHandler {
        if (dialog != null) dialog = null
        else if (state.ended.value && autoplay && !autoCancelled && state.next.value != null) autoCancelled = true
        else if (controlsVisible && state.isPlaying.value) controlsVisible = false
        else onBack()
    }

    val presentation = rememberPresentationState(player)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.MediaPlayPause -> { togglePlay(); poke(); true }
                    Key.MediaPlay -> { player.play(); poke(); true }
                    Key.MediaPause -> { player.pause(); poke(); true }
                    Key.MediaFastForward -> { seekBy(30_000); poke(); true }
                    Key.MediaRewind -> { seekBy(-10_000); poke(); true }
                    else -> {
                        if (!controlsVisible && dialog == null && !state.ended.value) {
                            when (ev.key) {
                                Key.DirectionLeft -> { seekBy(-10_000); poke(); true }
                                Key.DirectionRight -> { seekBy(10_000); poke(); true }
                                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { togglePlay(); poke(); true }
                                Key.DirectionUp, Key.DirectionDown -> { poke(); true }
                                else -> false
                            }
                        } else { lastInteraction = System.currentTimeMillis(); false }
                    }
                }
            }
            .focusable()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (controlsVisible) controlsVisible = false else poke() },
                    onDoubleTap = { off -> if (off.x < size.width / 2) seekBy(-10_000) else seekBy(10_000); poke() },
                )
            },
    ) {
        PlayerSurface(
            player = player,
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
            modifier = Modifier
                .align(Alignment.Center)
                .resizeWithContentScale(ContentScale.Fit, presentation.videoSizeDp),
        )

        if (state.buffering.value && state.error.value == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(56.dp), color = Accent)
        }

        state.error.value?.let { err ->
            Column(
                Modifier.align(Alignment.Center).padding(32.dp).background(Color(0xCC000000), RoundedCornerShape(16.dp)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Ой, хуюмбала какая-то", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(err, color = Color(0xFFDDDDDD), fontSize = 14.sp)
                Spacer(Modifier.height(16.dp))
                PlayerButton("Назад", isTv, onClick = onBack, modifier = Modifier.focusRequester(playFocus))
            }
        }

        AnimatedVisibility(controlsVisible && state.error.value == null && !state.ended.value, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                // Верх: название
                Row(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!isTv) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Color.White) }
                        Spacer(Modifier.width(8.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(state.title.value, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(state.channel.value, color = Color(0xFFBBBBBB), fontSize = 14.sp, maxLines = 1)
                    }
                    if (state.isLive.value) {
                        Text("● В ЭФИРЕ", color = Color.White, fontSize = 12.sp, modifier = Modifier.background(Accent, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
                // Низ: прогресс и кнопки
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                ) {
                    if (!state.isLive.value) {
                        SeekBar(state, isTv, onSeek = { player.seekTo(it); state.positionMs.value = it; poke() }, onStep = { seekBy(it); poke() })
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            Text(fmt(state.positionMs.value), color = Color.White, fontSize = 13.sp)
                            Spacer(Modifier.weight(1f))
                            Text(fmt(state.durationMs.value), color = Color(0xFFBBBBBB), fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (!state.isLive.value) PlayerButton("−10", isTv, onClick = { seekBy(-10_000); poke() })
                        PlayerButton(
                            if (state.isPlaying.value) "❚❚" else "▶", isTv, primary = true,
                            onClick = { togglePlay(); poke() },
                            modifier = Modifier.focusRequester(playFocus),
                        )
                        if (!state.isLive.value) PlayerButton("+10", isTv, onClick = { seekBy(10_000); poke() })
                        state.next.value?.let { n -> PlayerButton("Далее ⏭", isTv, onClick = { onPlay(n.id) }) }
                        Spacer(Modifier.weight(1f))
                        if (state.channelUrl.value.isNotBlank()) PlayerButton("Канал", isTv, onClick = onChannel)
                        val q = state.currentHeight.value
                        PlayerButton(if (q > 0) "${q}p" else "Качество", isTv, onClick = { dialog = "quality"; poke() })
                        PlayerButton("${fmtSpeed(state.speed.value)}×", isTv, onClick = { dialog = "speed"; poke() })
                    }
                }
            }
        }

        if (state.ended.value && state.error.value == null) {
            EndScreen(state, isTv, autoplay && !autoCancelled, onPlay = onPlay, onCancel = { autoCancelled = true },
                onReplay = { player.seekTo(0); player.play() })
        }

        when (dialog) {
            "quality" -> ChoiceDialog(
                title = "Качество",
                options = listOf(0 to "Авто") + state.qualities.value.map { it to "${it}p" },
                selected = state.selectedQuality.value,
                onPick = { onQuality(it); dialog = null },
                onDismiss = { dialog = null },
            )
            "speed" -> ChoiceDialog(
                title = "Скорость",
                options = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).map { it to "${fmtSpeed(it)}×" },
                selected = state.speed.value,
                onPick = { onSpeed(it); dialog = null },
                onDismiss = { dialog = null },
            )
        }
    }
}

@Composable
private fun SeekBar(state: PlayerUiState, isTv: Boolean, onSeek: (Long) -> Unit, onStep: (Long) -> Unit) {
    val dur = state.durationMs.value.coerceAtLeast(1)
    val pos = state.positionMs.value
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()

    Box(
        Modifier
            .fillMaxWidth()
            .height(if (focused) 28.dp else 22.dp)
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (ev.key) {
                    Key.DirectionLeft -> { onStep(-10_000); true }
                    Key.DirectionRight -> { onStep(10_000); true }
                    else -> false
                }
            }
            .focusable(interactionSource = src)
            .pointerInput(dur) {
                detectTapGestures { off -> onSeek((off.x / size.width * dur).toLong()) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
            val barH = if (focused) 8.dp else 4.dp
        val knob = if (focused) 9.dp else 7.dp
        val buffered = state.bufferedMs.value
        val segs = state.segments.value
        Canvas(Modifier.fillMaxWidth().height(knob * 2)) {
            val w = size.width
            val cy = size.height / 2
            val bh = barH.toPx()
            val r = CornerRadius(bh / 2, bh / 2)
            fun x(ms: Long) = (ms.toFloat() / dur).coerceIn(0f, 1f) * w
            drawRoundRect(Color(0x55FFFFFF), Offset(0f, cy - bh / 2), Size(w, bh), r)
            drawRoundRect(Color(0x88FFFFFF), Offset(0f, cy - bh / 2), Size(x(buffered), bh), r)
            segs.forEach { sg -> drawRect(Color(0xFF00D400), Offset(x(sg.startMs), cy - bh / 2), Size(x(sg.endMs) - x(sg.startMs), bh)) }
            drawRoundRect(Accent, Offset(0f, cy - bh / 2), Size(x(pos), bh), r)
            drawCircle(if (focused) Color.White else Accent, knob.toPx(), Offset(x(pos), cy))
        }
    }
    if (isTv && focused) {
        Text("← → перемотка на 10 секунд", color = Color(0xFF999999), fontSize = 11.sp)
    }
}

@Composable
fun PlayerButton(text: String, isTv: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    Box(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(
                when {
                    focused -> Color.White
                    primary -> Accent
                    else -> Color(0x33FFFFFF)
                }
            )
            .border(if (focused) 2.dp else 0.dp, if (focused) Accent else Color.Transparent, RoundedCornerShape(24.dp))
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = if (primary) 26.dp else 18.dp, vertical = if (isTv) 10.dp else 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (focused) Color.Black else Color.White, fontSize = if (primary) 20.sp else 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = { Text(title) },
        text = {
            LazyColumn {
                items(options.size) { i ->
                    val (value, label) = options[i]
                    val src = remember { MutableInteractionSource() }
                    val focused by src.collectIsFocusedAsState()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.Transparent)
                            .then(if (value == selected) Modifier.focusRequester(first) else Modifier)
                            .clickable(interactionSource = src, indication = null) { onPick(value) }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(label, fontSize = 16.sp)
                    }
                }
            }
        },
    )
    LaunchedEffect(Unit) { delay(100); runCatching { first.requestFocus() } }
}

private fun fmt(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

private fun fmtSpeed(f: Float) = if (f % 1f == 0f) f.toInt().toString() else f.toString().trimEnd('0')


@Composable
private fun EndScreen(
    state: PlayerUiState,
    isTv: Boolean,
    countdownActive: Boolean,
    onPlay: (String) -> Unit,
    onCancel: () -> Unit,
    onReplay: () -> Unit,
) {
    val next = state.next.value
    val mainFocus = remember { FocusRequester() }
    var left by remember(next?.id, countdownActive) { mutableIntStateOf(8) }

    LaunchedEffect(next?.id, countdownActive) {
        if (next == null || !countdownActive) return@LaunchedEffect
        while (left > 0) { delay(1000); left-- }
        onPlay(next.id)
    }
    LaunchedEffect(Unit) { delay(150); runCatching { mainFocus.requestFocus() } }

    Column(
        Modifier.fillMaxSize().background(Color(0xE6000000)).padding(horizontal = 40.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        if (next != null) {
            Text(if (countdownActive) "Далее через $left…" else "Далее", color = Color(0xFFBBBBBB), fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            Text(next.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(next.channel, color = Color(0xFFBBBBBB), fontSize = 15.sp)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PlayerButton("▶ Смотреть", isTv, primary = true, onClick = { onPlay(next.id) }, modifier = Modifier.focusRequester(mainFocus))
                if (countdownActive) PlayerButton("Отмена", isTv, onClick = onCancel)
                PlayerButton("↻ Ещё раз", isTv, onClick = onReplay)
            }
        } else {
            PlayerButton("↻ Ещё раз", isTv, primary = true, onClick = onReplay, modifier = Modifier.focusRequester(mainFocus))
        }
        val more = state.related.value.filter { it.id != next?.id }.take(8)
        if (more.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("Похожие", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(more, key = { it.id }) { v ->
                    Box(Modifier.width(if (isTv) 240.dp else 200.dp)) {
                        VideoCard(v, isTv) { onPlay(v.id) }
                    }
                }
            }
        }
    }
}

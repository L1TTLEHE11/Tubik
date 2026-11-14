package dev.tubik.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.tubik.app.data.NotLoggedInException
import dev.tubik.app.data.Paged
import dev.tubik.app.data.VideoItem
import dev.tubik.app.player.PlayerActivity
import kotlinx.coroutines.launch

/** Состояние бесконечной ленты: держит загруженные элементы и умеет догружать следующую страницу. */
class FeedState(private val first: suspend () -> Paged) {
    val items = mutableStateListOf<VideoItem>()
    var loading by mutableStateOf(false)
    var error by mutableStateOf<Throwable?>(null)
    private var next: (suspend () -> Paged)? = first
    private val seen = HashSet<String>()

    val canLoadMore get() = next != null

    suspend fun loadMore() {
        val n = next ?: return
        if (loading) return
        loading = true
        error = null
        try {
            val page = n()
            page.items.filter { seen.add(it.id) }.let { items.addAll(it) }
            next = page.next
            // Пустая страница с продолжением — сразу просим следующую (бывает в ленте подписок)
            if (page.items.isEmpty() && next != null && items.isEmpty()) {
                loading = false
                loadMore()
                return
            }
        } catch (e: Throwable) {
            error = e
        } finally {
            loading = false
        }
    }

    suspend fun refresh() {
        items.clear(); seen.clear(); next = first; error = null
        loadMore()
    }
}

@Composable
fun rememberFeed(key: Any?, source: () -> (suspend () -> Paged)): FeedState {
    val state = remember(key) { FeedState(source()) }
    LaunchedEffect(state) { if (state.items.isEmpty()) state.loadMore() }
    return state
}

@Composable
fun VideoGrid(
    feed: FeedState,
    isTv: Boolean,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    onNeedLogin: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Своё состояние прокрутки на каждую ленту: после обновления — снова сверху
    val grid = remember(feed) { androidx.compose.foundation.lazy.grid.LazyGridState() }

    // Догрузка, когда до конца осталось меньше 8 карточек
    val nearEnd by remember {
        derivedStateOf {
            val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= grid.layoutInfo.totalItemsCount - 8
        }
    }
    LaunchedEffect(nearEnd, feed.items.size) {
        if (nearEnd && feed.canLoadMore && !feed.loading && feed.error == null && feed.items.isNotEmpty()) feed.loadMore()
    }

    LazyVerticalGrid(
        state = grid,
        columns = if (isTv) GridCells.Fixed(3) else GridCells.Adaptive(300.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(if (isTv) 28.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(if (isTv) 20.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (isTv) 24.dp else 16.dp),
    ) {
        if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { header() }
        itemsIndexed(feed.items, key = { _, v -> v.id }) { _, v ->
            VideoCard(v, isTv, onLongClick = { Nav.openChannel(v.channelUrl) }) { PlayerActivity.open(ctx, v.id) }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                val err = feed.error
                when {
                    feed.loading -> CircularProgressIndicator(color = Accent)
                    err is NotLoggedInException -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Тут нужен вход в аккаунт (или куки протухли).", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (onNeedLogin != null) {
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = onNeedLogin) { Text("В настройки") }
                        }
                    }
                    err != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Не загрузилось: ${err.message ?: err.javaClass.simpleName}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { scope.launch { feed.loadMore() } }) { Text("Ещё раз") }
                    }
                    feed.items.isEmpty() -> Text("Пусто. Подозрительно пусто.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun VideoCard(v: VideoItem, isTv: Boolean, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    var longFired by remember { mutableStateOf(false) }
    val focused by src.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused && isTv) 1.06f else 1f, label = "scale")
    val shape = RoundedCornerShape(12.dp)

    Column(
        Modifier
            .scale(scale)
            .clip(shape)
            .background(if (focused) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
            .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape)
            // На пульте: удержание ОК или кнопка «Меню» — открыть канал
            .onPreviewKeyEvent { ev ->
                val lc = onLongClick ?: return@onPreviewKeyEvent false
                val k = ev.key
                val isOk = k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter
                when {
                    k == Key.Menu && ev.type == KeyEventType.KeyUp -> { lc(); true }
                    isOk && ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.repeatCount > 0 -> {
                        if (!longFired) { longFired = true; lc() }
                        true
                    }
                    isOk && ev.type == KeyEventType.KeyUp && longFired -> { longFired = false; true }
                    else -> false
                }
            }
            .combinedClickable(
                interactionSource = src,
                indication = if (isTv) null else androidx.compose.foundation.LocalIndication.current,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .padding(bottom = 8.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape).background(Color(0xFF222228))) {
            AsyncImage(
                model = v.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            val badge = if (v.isLive) "● LIVE" else v.duration
            if (badge.isNotBlank()) {
                Text(
                    badge,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(if (v.isLive) Accent else Color(0xCC000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(v.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = if (isTv) 15.sp else 15.sp, fontWeight = FontWeight.Medium, lineHeight = 19.sp)
            if (v.channel.isNotBlank()) {
                Text(v.channel, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (v.meta.isNotBlank()) {
                Text(v.meta, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

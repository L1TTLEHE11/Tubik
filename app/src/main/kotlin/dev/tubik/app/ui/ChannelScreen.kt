package dev.tubik.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.tubik.app.App
import dev.tubik.app.data.ChannelPage

@Composable
fun ChannelScreen(url: String, isTv: Boolean) {
    BackHandler { Nav.back() }
    var page by remember(url) { mutableStateOf<ChannelPage?>(null) }
    var error by remember(url) { mutableStateOf<String?>(null) }
    var attempt by remember(url) { mutableIntStateOf(0) }

    LaunchedEffect(url, attempt) {
        error = null
        try {
            page = App.instance.repo.channel(url)
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }

    val p = page
    when {
        p != null -> ChannelContent(p, isTv)
        error != null -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Канал не открылся: $error", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { attempt++ }) { Text("Ещё раз") }
                OutlinedButton(onClick = { Nav.back() }) { Text("Назад") }
            }
        }
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
    }
}

@Composable
private fun ChannelContent(p: ChannelPage, isTv: Boolean) {
    if (p.tabs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("У канала нет видео. Ни одного. Загадочно.") }
        return
    }
    var tab by rememberSaveable(p.url) { mutableIntStateOf(0) }
    val (_, handler) = p.tabs[tab.coerceIn(p.tabs.indices)]
    val feed = rememberFeed(p.url to tab) { App.instance.repo.channelTab(handler) }

    VideoGrid(feed, isTv, header = {
        Column {
            if (p.banner.isNotBlank()) {
                AsyncImage(
                    model = p.banner,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(if (isTv) 140.dp else 90.dp).clip(RoundedCornerShape(14.dp)),
                )
                Spacer(Modifier.height(14.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = p.avatar,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(if (isTv) 72.dp else 56.dp).clip(CircleShape).background(Color(0xFF2A2A33)),
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontSize = if (isTv) 28.sp else 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (p.subscribers.isNotBlank()) Text(p.subscribers, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                }
            }
            if (p.description.isNotBlank()) {
                Text(
                    p.description, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                p.tabs.forEachIndexed { i, (title, _) -> Chip(title, i == tab) { tab = i } }
            }
        }
    })
}

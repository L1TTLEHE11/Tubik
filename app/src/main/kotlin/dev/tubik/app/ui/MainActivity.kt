package dev.tubik.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tubik.app.App
import dev.tubik.app.data.Repository
import dev.tubik.app.player.PlayerActivity

enum class Tab(val title: String, val icon: ImageVector) {
    HOME("Главная", Icons.Filled.Home),
    SUBS("Подписки", Icons.AutoMirrored.Filled.List),
    HISTORY("История", HistoryIcon),
    SEARCH("Поиск", Icons.Filled.Search),
    SETTINGS("Настройки", Icons.Filled.Settings),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShare(intent)
        Updates.autoCheck(this)
        val isTv = App.isTv(this)
        setContent {
            TubikTheme {
                Scaled {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        MainScreen(isTv)
                        UpdateDialogHost()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** «Поделиться» ссылкой на YouTube из любого приложения — сразу открываем плеер. */
    private fun handleShare(intent: Intent?) {
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: intent?.dataString ?: return
        Repository.idFromUrl(text)?.let { PlayerActivity.open(this, it) }
    }
}

@Composable
fun MainScreen(isTv: Boolean) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    // Повторный выбор текущей вкладки — обновить ленту (как в SmartTube)
    var refresh by remember { mutableIntStateOf(0) }
    val selectTab: (Tab) -> Unit = { t ->
        when {
            Nav.channels.isNotEmpty() -> { Nav.clear(); tab = t }
            t == tab -> refresh++
            else -> tab = t
        }
    }
    val content: @Composable (Modifier) -> Unit = { m ->
        Box(m) {
            val channel = Nav.channels.lastOrNull()
            if (channel != null) {
                key(channel) { ChannelScreen(channel, isTv) }
            } else when (tab) {
                Tab.HOME -> HomeScreen(isTv, refresh, onNeedLogin = { selectTab(Tab.SETTINGS) })
                Tab.SUBS -> SubscriptionsScreen(isTv, refresh, onNeedLogin = { selectTab(Tab.SETTINGS) })
                Tab.HISTORY -> HistoryScreen(isTv, refresh, onNeedLogin = { selectTab(Tab.SETTINGS) })
                Tab.SEARCH -> SearchScreen(isTv)
                Tab.SETTINGS -> SettingsScreen(isTv)
            }
        }
    }

    if (isTv) {
        Box(Modifier.fillMaxSize()) {
            content(Modifier.padding(start = RAIL_COLLAPSED).fillMaxSize())
            TvRail(tab, selectTab)
        }
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { selectTab(t) },
                            icon = {
                                if (t == Tab.SETTINGS && Updates.hasUpdate) BadgedBox(badge = { Badge(containerColor = Accent) }) { Icon(t.icon, null) }
                                else Icon(t.icon, null)
                            },
                            label = { Text(t.title) },
                        )
                    }
                }
            },
        ) { pad ->
            content(Modifier.padding(pad).fillMaxSize())
        }
    }
}

private val RAIL_COLLAPSED = 76.dp
private val RAIL_EXPANDED = 236.dp

/** Меню слева на ТВ: узкая полоска с иконками, раскрывается поверх контента, когда на него уходит фокус. */
@Composable
private fun TvRail(selected: Tab, onSelect: (Tab) -> Unit) {
    val firstFocus = remember { FocusRequester() }
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (expanded) RAIL_EXPANDED else RAIL_COLLAPSED, label = "rail")
    val scrim by animateFloatAsState(if (expanded) 0.6f else 0f, label = "scrim")

    Box(Modifier.fillMaxSize()) {
        // Затемнение контента под раскрытым меню (не ловит фокус и клики)
        if (scrim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim)))

        Column(
            Modifier
                .fillMaxHeight()
                .width(width)
                .shadow(if (expanded) 16.dp else 0.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .onFocusChanged { expanded = it.hasFocus }
                .focusRestorer(firstFocus)
                .focusGroup()
                .padding(vertical = 28.dp, horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                if (expanded) "Тюбик" else "Т",
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                color = Accent,
                maxLines = 1,
                modifier = Modifier.padding(start = 14.dp, bottom = 24.dp),
            )
            Tab.entries.forEach { t ->
                val src = remember { MutableInteractionSource() }
                val focused by src.collectIsFocusedAsState()
                // Вкладка переключается, как только на неё встал фокус; ОК на текущей — обновить ленту
                LaunchedEffect(focused) { if (focused && selected != t) onSelect(t) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            when {
                                focused -> Color.White
                                selected == t -> Accent.copy(alpha = 0.22f)
                                else -> Color.Transparent
                            }
                        )
                        .then(if (t == selected) Modifier.focusRequester(firstFocus) else Modifier)
                        .clickable(interactionSource = src, indication = null) { onSelect(t) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val c = if (focused) Color.Black else MaterialTheme.colorScheme.onSurface
                    Box {
                        Icon(t.icon, null, tint = c)
                        if (t == Tab.SETTINGS && Updates.hasUpdate && !expanded) {
                            Box(Modifier.align(Alignment.TopEnd).size(8.dp).clip(RoundedCornerShape(4.dp)).background(Accent))
                        }
                    }
                    if (expanded) {
                        Spacer(Modifier.width(14.dp))
                        Text(
                            t.title, color = c, fontSize = 17.sp, maxLines = 1,
                            fontWeight = if (selected == t) FontWeight.Bold else FontWeight.Normal,
                        )
                        if (t == Tab.SETTINGS && Updates.hasUpdate) {
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Accent))
                        }
                    }
                }
            }
            if (expanded && (selected == Tab.HOME || selected == Tab.SUBS || selected == Tab.HISTORY)) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "ОК на вкладке — обновить ленту", fontSize = 12.sp, maxLines = 2,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 14.dp),
                )
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
}

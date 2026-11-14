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
                Surface(color = MaterialTheme.colorScheme.background) {
                    MainScreen(isTv)
                    UpdateDialogHost()
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
    val selectTab: (Tab) -> Unit = { t -> Nav.clear(); tab = t }
    val content: @Composable (Modifier) -> Unit = { m ->
        Box(m) {
            val channel = Nav.channels.lastOrNull()
            if (channel != null) {
                key(channel) { ChannelScreen(channel, isTv) }
            } else when (tab) {
                Tab.HOME -> HomeScreen(isTv, onNeedLogin = { selectTab(Tab.SETTINGS) })
                Tab.SUBS -> SubscriptionsScreen(isTv, onNeedLogin = { selectTab(Tab.SETTINGS) })
                Tab.SEARCH -> SearchScreen(isTv)
                Tab.SETTINGS -> SettingsScreen(isTv)
            }
        }
    }

    if (isTv) {
        Row(Modifier.fillMaxSize()) {
            TvRail(tab, selectTab)
            content(Modifier.weight(1f).fillMaxHeight())
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

@Composable
private fun TvRail(selected: Tab, onSelect: (Tab) -> Unit) {
    val firstFocus = remember { FocusRequester() }
    Column(
        Modifier
            .fillMaxHeight()
            .width(200.dp)
            .focusRestorer(firstFocus)
            .focusGroup()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(vertical = 28.dp, horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "Тюбик",
            fontSize = 26.sp,
            fontWeight = FontWeight.Black,
            color = Accent,
            modifier = Modifier.padding(start = 12.dp, bottom = 24.dp),
        )
        Tab.entries.forEach { t ->
            val src = remember { MutableInteractionSource() }
            val focused by src.collectIsFocusedAsState()
            // На ТВ вкладка переключается, как только на неё встал фокус — без лишнего «ОК»
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
                Icon(t.icon, null, tint = c)
                Spacer(Modifier.width(14.dp))
                Text(t.title, color = c, fontSize = 17.sp, fontWeight = if (selected == t) FontWeight.Bold else FontWeight.Normal)
                if (t == Tab.SETTINGS && Updates.hasUpdate) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Accent))
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
}

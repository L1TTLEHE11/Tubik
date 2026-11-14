package dev.tubik.app.ui

import android.content.Intent
import android.webkit.CookieManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tubik.app.App
import dev.tubik.app.data.Codec
import dev.tubik.app.data.PairClient
import dev.tubik.app.data.PairCrypto
import dev.tubik.app.data.PairServer
import dev.tubik.app.data.SbCategories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val repo get() = App.instance.repo
private val prefs get() = App.instance.prefs

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    Text(
        text,
        color = if (focused) Color.Black else if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        fontSize = 15.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                when {
                    focused -> Color.White
                    selected -> Accent
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            )
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
fun HomeScreen(isTv: Boolean, refresh: Int, onNeedLogin: () -> Unit) {
    val cookies by prefs.cookiesFlow.collectAsState()
    val loggedIn = !cookies.isNullOrBlank()
    val sections = remember(loggedIn) {
        (if (loggedIn) listOf("__home" to "Для тебя") else emptyList()) + repo.kiosks.toList()
    }
    var section by rememberSaveable(loggedIn) { mutableStateOf(sections.first().first) }
    val feed = rememberFeed(Triple(section, loggedIn, refresh)) {
        if (section == "__home") repo.home() else repo.kiosk(section)
    }
    VideoGrid(
        feed, isTv,
        onNeedLogin = onNeedLogin,
        header = {
            Column {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(sections.size) { i ->
                        val (id, title) = sections[i]
                        Chip(title, id == section) { section = id }
                    }
                }
                if (!loggedIn) {
                    Text(
                        "Войди в аккаунт в настройках — появятся рекомендации и подписки.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        },
    )
}

@Composable
private fun NeedLogin(title: String, onNeedLogin: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Войди в аккаунт в настройках, и тут всё появится.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onNeedLogin) { Text("В настройки") }
    }
}

@Composable
fun SubscriptionsScreen(isTv: Boolean, refresh: Int, onNeedLogin: () -> Unit) {
    val cookies by prefs.cookiesFlow.collectAsState()
    if (cookies.isNullOrBlank()) { NeedLogin("Подписки живут в аккаунте", onNeedLogin); return }
    val feed = rememberFeed("subs" to refresh) { repo.subscriptions() }
    VideoGrid(feed, isTv, onNeedLogin = onNeedLogin)
}

@Composable
fun HistoryScreen(isTv: Boolean, refresh: Int, onNeedLogin: () -> Unit) {
    val cookies by prefs.cookiesFlow.collectAsState()
    if (cookies.isNullOrBlank()) { NeedLogin("История живёт в аккаунте", onNeedLogin); return }
    val feed = rememberFeed("history" to refresh) { repo.history() }
    VideoGrid(feed, isTv, onNeedLogin = onNeedLogin, header = {
        Text(
            "Что ты смотрел — из истории YouTube. Видео из Тюбика попадают сюда тоже (если не появилось сразу — обнови).",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    })
}

@Composable
fun SearchScreen(isTv: Boolean) {
    var text by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    // true — показываем историю и подсказки, false — результаты
    var editing by rememberSaveable { mutableStateOf(true) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    val kb = LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val fieldFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val pad = if (isTv) 28.dp else 12.dp

    fun submit(q: String) {
        val t = q.trim()
        if (t.isEmpty()) return
        text = t
        query = t
        prefs.addSearch(t)
        editing = false
        kb?.hide()
    }

    // Подсказки YouTube с небольшой задержкой, чтобы не дёргать сеть на каждую букву
    LaunchedEffect(text) {
        if (text.isBlank()) { suggestions = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(250)
        suggestions = repo.suggestions(text)
    }
    // После поиска уводим фокус из поля в результаты (на ТВ)
    LaunchedEffect(query, editing) {
        if (!editing && query.isNotBlank() && isTv) {
            kotlinx.coroutines.delay(600)
            focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = pad, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; editing = true },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(fieldFocus)
                    .onFocusChanged { if (it.isFocused) editing = true },
                singleLine = true,
                placeholder = { Text("Что ищем?") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (text.isNotEmpty()) IconButton(onClick = { text = ""; editing = true }) { Icon(Icons.Filled.Clear, "Стереть") }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit(text) }),
            )
            Spacer(Modifier.width(12.dp))
            Button(onClick = { submit(text) }) { Text("Найти") }
        }

        if (editing || query.isBlank()) {
            SearchSuggestions(
                text = text,
                suggestions = suggestions,
                isTv = isTv,
                pad = pad,
                onPick = { submit(it) },
                onFill = { text = it; runCatching { fieldFocus.requestFocus() } },
            )
        } else {
            val feed = rememberFeed(query) { repo.search(query) }
            VideoGrid(feed, isTv)
        }
    }
}

@Composable
private fun SearchSuggestions(
    text: String,
    suggestions: List<String>,
    isTv: Boolean,
    pad: androidx.compose.ui.unit.Dp,
    onPick: (String) -> Unit,
    onFill: (String) -> Unit,
) {
    val history = prefs.searchHistory
    val t = text.trim()
    val fromHistory = if (t.isEmpty()) history.take(20)
        else history.filter { it.contains(t, ignoreCase = true) && !it.equals(t, ignoreCase = true) }.take(5)
    val fromYt = suggestions.filter { s -> fromHistory.none { it.equals(s, ignoreCase = true) } }.take(10)

    if (fromHistory.isEmpty() && fromYt.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                if (t.isEmpty()) "Тут будет история поиска. Пока пусто — ищи что-нибудь." else "Подсказок нет. Жми «Найти».",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    androidx.compose.foundation.lazy.LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = pad, vertical = 4.dp),
    ) {
        if (t.isEmpty() && fromHistory.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("Ты искал", fontWeight = FontWeight.Bold, color = Accent, modifier = Modifier.weight(1f))
                    TextButton(onClick = { prefs.clearSearchHistory() }) { Text("Очистить историю") }
                }
            }
        }
        items(fromHistory.size, key = { "h" + fromHistory[it] }) { i ->
            val q = fromHistory[i]
            SuggestionRow(q, isHistory = true, isTv = isTv, onClick = { onPick(q) }, onFill = { onFill(q) }, onDelete = { prefs.removeSearch(q) })
        }
        items(fromYt.size, key = { "s" + fromYt[it] }) { i ->
            val q = fromYt[i]
            SuggestionRow(q, isHistory = false, isTv = isTv, onClick = { onPick(q) }, onFill = { onFill(q) }, onDelete = null)
        }
    }
}

@Composable
private fun SuggestionRow(q: String, isHistory: Boolean, isTv: Boolean, onClick: () -> Unit, onFill: () -> Unit, onDelete: (() -> Unit)?) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Color.White else Color.Transparent)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = if (isTv) 10.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val c = if (focused) Color.Black else MaterialTheme.colorScheme.onSurface
        Text(if (isHistory) "🕘" else "🔍", fontSize = 15.sp)
        Spacer(Modifier.width(14.dp))
        Text(q, color = c, fontSize = 16.sp, maxLines = 1, modifier = Modifier.weight(1f))
        // Подставить в поле, чтобы дописать (как стрелочка ↖ в YouTube)
        SmallAction("↖", focusedParent = focused, onClick = onFill)
        if (onDelete != null) SmallAction("✕", focusedParent = focused, onClick = onDelete)
    }
}

@Composable
private fun SmallAction(label: String, focusedParent: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    Text(
        label,
        fontSize = 15.sp,
        color = when { focused -> Color.White; focusedParent -> Color.Black; else -> MaterialTheme.colorScheme.onSurfaceVariant },
        modifier = Modifier
            .padding(start = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Accent else Color.Transparent)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun SettingsScreen(isTv: Boolean) {
    val ctx = LocalContext.current
    val cookies by prefs.cookiesFlow.collectAsState()
    var showPaste by remember { mutableStateOf(false) }
    var showReceive by remember { mutableStateOf(false) }
    var showSend by remember { mutableStateOf(false) }
    var autoplay by remember { mutableStateOf(prefs.autoplay) }
    var quality by remember { mutableIntStateOf(prefs.maxQuality) }
    var codec by remember { mutableStateOf(prefs.codec) }
    var sbOn by remember { mutableStateOf(prefs.sponsorBlockEnabled) }
    var sbCats by remember { mutableStateOf(prefs.sponsorBlockCategories) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(if (isTv) 32.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Section("Аккаунт")
        if (cookies.isNullOrBlank()) {
            Text("Не вошёл. Без аккаунта работают поиск и подборки.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (isTv) {
                    Button(onClick = { showReceive = true }) { Text("Войти с телефона") }
                    OutlinedButton(onClick = { ctx.startActivity(Intent(ctx, LoginActivity::class.java)) }) { Text("Войти через Google") }
                } else {
                    Button(onClick = { ctx.startActivity(Intent(ctx, LoginActivity::class.java)) }) { Text("Войти через Google") }
                    OutlinedButton(onClick = { showReceive = true }) { Text("Принять с другого устройства") }
                }
                OutlinedButton(onClick = { showPaste = true }) { Text("Вставить cookies") }
            }
            Text(
                "Если Google ругается на «небезопасный браузер» — экспортируй cookies youtube.com из браузера на компе (например, расширением «Get cookies.txt») и вставь сюда.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text("Вошёл ✓  Рекомендации, подписки и история подтягиваются из аккаунта.")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { showSend = true }) { Text("Передать вход на ТВ") }
                OutlinedButton(onClick = {
                    prefs.cookies = null
                    CookieManager.getInstance().removeAllCookies(null)
                }) { Text("Выйти") }
            }
        }

        Section("Воспроизведение")
        OptionRow("Автоматически включать следующее видео", autoplay) { autoplay = !autoplay; prefs.autoplay = autoplay }
        if (isTv) {
            Text("Подсказка: удержи ОК на карточке (или нажми «Меню» на пульте) — откроется канал.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("Подсказка: долгое нажатие на карточку открывает канал.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Section("Масштаб")
        Text("Размер интерфейса (карточки, кнопки, отступы)", fontSize = 14.sp)
        ScaleChips(prefs.uiScale) { prefs.uiScale = it }
        Text("Размер текста", fontSize = 14.sp)
        ScaleChips(prefs.textScale) { prefs.textScale = it }

        Section("Качество по умолчанию")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            listOf(0 to "Авто", 2160 to "4K", 1440 to "1440p", 1080 to "1080p", 720 to "720p", 480 to "480p").forEach { (v, t) ->
                Chip(t, quality == v) { quality = v; prefs.maxQuality = v }
            }
        }

        Section("Кодек")
        Codec.entries.forEach { c ->
            OptionRow(c.title, codec == c, radio = true) { codec = c; prefs.codec = c }
        }

        Section("SponsorBlock")
        OptionRow("Пропускать сегменты автоматически", sbOn) { sbOn = !sbOn; prefs.sponsorBlockEnabled = sbOn }
        if (sbOn) {
            SbCategories.all.forEach { (key, title) ->
                OptionRow(title, key in sbCats) {
                    sbCats = if (key in sbCats) sbCats - key else sbCats + key
                    prefs.sponsorBlockCategories = sbCats
                }
            }
        }

        Section("Обновления")
        UpdatesSection()

        Spacer(Modifier.height(16.dp))
        Text(
            "Тюбик • github.com/L1TTLEHE11/Tubik • данные: NewPipeExtractor, SponsorBlock",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showPaste) PasteCookiesDialog(onDismiss = { showPaste = false })
    if (showReceive) ReceiveLoginDialog(isTv, onDismiss = { showReceive = false })
    if (showSend) SendLoginDialog(onDismiss = { showSend = false })
}

@Composable
private fun ScaleChips(value: Float, onPick: (Float) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        listOf(0.75f, 0.85f, 1f, 1.15f, 1.3f, 1.5f).forEach { v ->
            Chip("${(v * 100).toInt()}%", kotlin.math.abs(value - v) < 0.01f) { onPick(v) }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Accent, modifier = Modifier.padding(top = 14.dp))
}

@Composable
private fun OptionRow(title: String, checked: Boolean, radio: Boolean = false, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (radio) RadioButton(selected = checked, onClick = null) else Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 15.sp)
    }
}

@Composable
private fun PasteCookiesDialog(onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cookies youtube.com") },
        text = {
            Column {
                Text("Подходит строка вида «SAPISID=…; SID=…» или файл cookies.txt целиком.", fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 240.dp))
                err?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val parsed = CookieUtils.parse(text)
                if (parsed == null) err = "Не вижу там SAPISID — это точно cookies от youtube.com?"
                else { prefs.cookies = parsed; onDismiss() }
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

object CookieUtils {
    /** Принимает заголовок Cookie или Netscape cookies.txt; возвращает строку для заголовка Cookie. */
    fun parse(raw: String): String? {
        val text = raw.trim()
        val pairs = LinkedHashMap<String, String>()
        val lines = text.lines()
        if (lines.any { it.split('\t').size >= 7 }) {
            lines.filter { !it.startsWith("#") || it.startsWith("#HttpOnly_") }.forEach { line ->
                val f = line.split('\t')
                if (f.size >= 7 && f[0].contains("youtube.com")) pairs[f[5]] = f[6].trim()
            }
        } else {
            text.removePrefix("Cookie:").split(';').forEach { part ->
                val i = part.indexOf('=')
                if (i > 0) pairs[part.substring(0, i).trim()] = part.substring(i + 1).trim()
            }
        }
        if (pairs.keys.none { it == "SAPISID" || it == "__Secure-3PAPISID" }) return null
        return pairs.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun hasAuth(cookie: String?): Boolean =
        cookie != null && (cookie.contains("SAPISID=") || cookie.contains("__Secure-3PAPISID="))
}


/** ТВ: показываем код и ждём, пока телефон пришлёт вход. */
@Composable
private fun ReceiveLoginDialog(isTv: Boolean, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var done by remember { mutableStateOf(false) }
    val server = remember {
        PairServer(ctx.applicationContext) { cookies ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                prefs.cookies = cookies
                done = true
            }
        }
    }
    var port by remember { mutableIntStateOf(0) }
    var startError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { server.start() }.onFailure { startError = it.message ?: it.javaClass.simpleName }
        }
        port = server.port
    }
    DisposableEffect(Unit) { onDispose { Thread { server.stop() }.start() } }
    LaunchedEffect(done) {
        if (done) {
            android.widget.Toast.makeText(ctx, "Вошёл! Можно смотреть подписки", android.widget.Toast.LENGTH_LONG).show()
            onDismiss()
        }
    }
    val addresses = remember { PairServer.localAddresses() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Вход с телефона") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text("На телефоне: Тюбик → Настройки → «Передать вход на ТВ» и введи код:", fontSize = 15.sp)
                Spacer(Modifier.height(16.dp))
                Text(
                    PairCrypto.pretty(server.code),
                    fontSize = if (isTv) 44.sp else 34.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 4.sp,
                    color = Accent,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Accent)
                    Spacer(Modifier.width(10.dp))
                    Text(startError?.let { "Не смог открыть порт: $it" } ?: "Жду телефон…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (addresses.isNotEmpty() && port > 0) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Если телефон не находит ТВ, укажи адрес: " + addresses.joinToString(" или ") { "$it:$port" },
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Телефон: вводим код с экрана ТВ и отправляем туда cookies. */
@Composable
private fun SendLoginDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var showAddress by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Передать вход на ТВ") },
        text = {
            Column {
                Text("На ТВ открой Тюбик → Настройки → «Войти с телефона» и введи код с экрана. Телефон и ТВ должны быть в одной Wi-Fi.", fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase().take(9) },
                    label = { Text("Код, например K7QM-9XRT") },
                    singleLine = true,
                    enabled = !busy && !success,
                    keyboardOptions = KeyboardOptions(
                        capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showAddress) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it.trim() },
                        label = { Text("Адрес ТВ (с экрана ТВ)") },
                        placeholder = { Text("192.168.1.42:47110") },
                        singleLine = true,
                        enabled = !busy && !success,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    TextButton(onClick = { showAddress = true }) { Text("Не находит ТВ? Ввести адрес вручную") }
                }
                status?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 13.sp, color = if (success) Color(0xFF6BE38A) else MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            if (success) {
                Button(onClick = onDismiss) { Text("Готово") }
            } else {
                Button(enabled = !busy && PairCrypto.normalize(code).length == 8, onClick = {
                    val cookies = prefs.cookies ?: return@Button
                    busy = true; status = null
                    scope.launch {
                        when (val r = PairClient.send(ctx.applicationContext, code, cookies, address.ifBlank { null })) {
                            is PairClient.Result.Ok -> { success = true; status = "Готово, вход передан на ${r.device} 👌" }
                            is PairClient.Result.Fail -> { status = r.message; if (!showAddress && r.message.startsWith("ТВ не нашёлся")) showAddress = true }
                        }
                        busy = false
                    }
                }) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    else Text("Передать")
                }
            }
        },
        dismissButton = { if (!success) TextButton(enabled = !busy, onClick = onDismiss) { Text("Отмена") } },
    )
}

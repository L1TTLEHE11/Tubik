package dev.tubik.app.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import dev.tubik.app.App
import dev.tubik.app.data.UpdateInfo
import dev.tubik.app.data.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed class UpdState {
    object Idle : UpdState()
    object Checking : UpdState()
    object UpToDate : UpdState()
    data class Available(val info: UpdateInfo) : UpdState()
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdState()
    data class Ready(val info: UpdateInfo, val apk: File) : UpdState()
    data class Failed(val message: String, val info: UpdateInfo? = null) : UpdState()
}

/** Состояние обновлений на всё приложение — живёт, пока жив процесс. */
object Updates {
    private val scope: CoroutineScope = MainScope()
    var state by mutableStateOf<UpdState>(UpdState.Idle)
        private set
    /** Показать окно с предложением обновиться. */
    var showDialog by mutableStateOf(false)
    private var autoChecked = false
    private var job: Job? = null

    val info: UpdateInfo?
        get() = when (val s = state) {
            is UpdState.Available -> s.info
            is UpdState.Downloading -> s.info
            is UpdState.Ready -> s.info
            is UpdState.Failed -> s.info
            else -> null
        }

    val hasUpdate get() = info != null

    /** Тихая проверка при запуске: раз за процесс, окно — только если версию не пропускали. */
    fun autoCheck(ctx: Context) {
        val prefs = App.instance.prefs
        if (autoChecked || !prefs.updateCheck || !Updater.isEnabled(ctx)) return
        autoChecked = true
        check(ctx, silent = true)
    }

    fun check(ctx: Context, silent: Boolean = false) {
        if (state is UpdState.Checking || state is UpdState.Downloading) return
        state = UpdState.Checking
        job = scope.launch {
            state = try {
                val info = withContext(Dispatchers.IO) { Updater.fetch() }
                if (info.versionCode > Updater.currentVersionCode(ctx)) {
                    if (!silent || App.instance.prefs.skippedVersion != info.versionCode) showDialog = true
                    UpdState.Available(info)
                } else UpdState.UpToDate
            } catch (e: Exception) {
                if (silent) UpdState.Idle else UpdState.Failed(e.message ?: "Ошибка")
            }
        }
    }

    fun download(ctx: Context) {
        val info = info ?: return
        if (state is UpdState.Downloading) return
        state = UpdState.Downloading(info, 0f)
        job = scope.launch {
            state = try {
                val dir = File(ctx.cacheDir, "updates")
                val apk = withContext(Dispatchers.IO) {
                    Updater.download(info, dir, onProgress = { p ->
                        scope.launch { if (state is UpdState.Downloading) state = UpdState.Downloading(info, p) }
                    }, running = { state is UpdState.Downloading })
                }
                UpdState.Ready(info, apk).also { install(ctx, it.apk) }
            } catch (e: Exception) {
                if (e.message == Updater.CANCELLED) UpdState.Available(info) else UpdState.Failed(e.message ?: "Ошибка", info)
            }
        }
    }

    fun cancel() {
        val i = info ?: return
        if (state is UpdState.Downloading) state = UpdState.Available(i)
    }

    fun install(ctx: Context, apk: File) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { Updater.install(ctx.applicationContext, apk) }
            } catch (e: Exception) {
                state = UpdState.Failed("Не удалось запустить установку: ${e.message}", info)
            }
        }
    }

    fun skip() {
        info?.let { App.instance.prefs.skippedVersion = it.versionCode }
        showDialog = false
    }
}

/** Окно «Вышла новая версия». Сам решает, показываться ли. */
@Composable
fun UpdateDialogHost() {
    if (!Updates.showDialog) return
    val info = Updates.info ?: return
    UpdateDialog(info, onDismiss = { Updates.showDialog = false })
}

@Composable
fun UpdateDialog(info: UpdateInfo, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val state = Updates.state
    var canInstall by remember { mutableStateOf(Updater.canInstall(ctx)) }
    var noSettings by remember { mutableStateOf(false) }
    val mainFocus = remember { FocusRequester() }

    // Вернулись из настроек — перепроверяем разрешение
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) canInstall = Updater.canInstall(ctx) }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(canInstall, state is UpdState.Downloading) { delay(150); runCatching { mainFocus.requestFocus() } }

    AlertDialog(
        onDismissRequest = { if (state !is UpdState.Downloading) onDismiss() },
        title = { Text("Вышел Тюбик ${info.versionName}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "У тебя ${Updater.currentVersionName(ctx)}" + (if (info.date.isNotBlank()) " • новая от ${info.date}" else "") +
                        (if (info.size > 0) " • %.1f МБ".format(info.size / 1048576.0) else ""),
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (info.notes.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Что нового:", fontWeight = FontWeight.SemiBold)
                    info.notes.forEach { Text("• $it", fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp)) }
                }
                Spacer(Modifier.height(14.dp))
                when (state) {
                    is UpdState.Downloading -> {
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth(), color = Accent)
                        Text("Качаю… ${(state.progress * 100).toInt()}%", fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                    is UpdState.Ready -> Text("Скачано и проверено. Подтверди установку в системном окне.", fontSize = 13.sp)
                    is UpdState.Failed -> Text(state.message, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    else -> if (!canInstall) {
                        Text(
                            "Нужно разрешить Тюбику устанавливать приложения: в настройках найди «Установка неизвестных приложений» → Тюбик → Разрешить, потом вернись сюда.",
                            fontSize = 13.sp,
                        )
                        if (noSettings) {
                            Text(
                                "Экран разрешения на этом устройстве не открылся. Поищи пункт «Неизвестные источники» в настройках безопасности вручную.",
                                fontSize = 13.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (state) {
                is UpdState.Downloading -> TextButton(onClick = { Updates.cancel() }, modifier = Modifier.focusRequester(mainFocus)) { Text("Отменить") }
                is UpdState.Ready -> Button(onClick = { Updates.install(ctx, state.apk) }, modifier = Modifier.focusRequester(mainFocus)) { Text("Установить") }
                else -> if (!canInstall) {
                    Button(onClick = { noSettings = !Updater.openInstallPermission(ctx) }, modifier = Modifier.focusRequester(mainFocus)) { Text("Открыть настройки") }
                } else {
                    Button(onClick = { Updates.download(ctx) }, modifier = Modifier.focusRequester(mainFocus)) {
                        Text(if (state is UpdState.Failed) "Ещё раз" else "Обновить")
                    }
                }
            }
        },
        dismissButton = {
            if (state !is UpdState.Downloading) {
                Row {
                    TextButton(onClick = { Updates.skip() }) { Text("Пропустить версию") }
                    TextButton(onClick = onDismiss) { Text("Позже") }
                }
            }
        },
    )
}

/** Блок «Обновления» для экрана настроек. */
@Composable
fun UpdatesSection() {
    val ctx = LocalContext.current
    val prefs = App.instance.prefs
    var auto by remember { mutableStateOf(prefs.updateCheck) }
    val state = Updates.state

    Text("Установлена версия ${Updater.currentVersionName(ctx)}", fontSize = 14.sp)
    if (!Updater.isEnabled(ctx)) {
        Text("Это отладочная сборка — обновления для неё выключены.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(checked = auto, onCheckedChange = { auto = it; prefs.updateCheck = it })
        Text("Проверять при запуске", fontSize = 15.sp)
    }
    val status = when (state) {
        UpdState.Idle -> null
        UpdState.Checking -> "Проверяю…"
        UpdState.UpToDate -> "Последняя версия, обновляться некуда 👌"
        is UpdState.Available -> "Доступна версия ${state.info.versionName}"
        is UpdState.Downloading -> "Качаю ${state.info.versionName}: ${(state.progress * 100).toInt()}%"
        is UpdState.Ready -> "Версия ${state.info.versionName} скачана"
        is UpdState.Failed -> state.message
    }
    status?.let {
        Text(it, fontSize = 13.sp, color = if (state is UpdState.Failed) MaterialTheme.colorScheme.error else Accent)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(enabled = state !is UpdState.Checking && state !is UpdState.Downloading, onClick = { Updates.check(ctx) }) {
            Text("Проверить обновления")
        }
        if (Updates.hasUpdate) {
            Button(onClick = { Updates.showDialog = true }) { Text("Обновить до ${Updates.info?.versionName}") }
        }
    }
}

package dev.tubik.app.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Описание версии из update.json в репозитории:
 * {"versionCode":3,"versionName":"0.3.0","apk":"apk/Tubik-0.3.0.apk","sha256":"…","size":13400000,
 *  "date":"14.11.2026","notes":["…","…"]}
 */
data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkPath: String,
    val sha256: String,
    val size: Long,
    val date: String,
    val notes: List<String>,
) {
    companion object {
        fun parse(json: String): UpdateInfo {
            val o: JsonObject = JsonParser.`object`().from(json)
            val notes = (o.getArray("notes_ru") ?: o.getArray("notes"))?.mapNotNull { it as? String }.orEmpty()
            return UpdateInfo(
                versionCode = o.getLong("versionCode"),
                versionName = o.getString("versionName"),
                apkPath = o.getString("apk"),
                sha256 = o.getString("sha256").lowercase(),
                size = o.getLong("size", 0),
                date = o.getString("date", ""),
                notes = notes,
            )
        }
    }
}

class UpdateException(msg: String) : Exception(msg)

object Updater {
    /**
     * Откуда берём обновления. Несколько зеркал: если GitHub недоступен или тормозит,
     * пробуем jsDelivr (CDN, кэширует репозиторий GitHub).
     */
    val mirrors = listOf(
        "https://raw.githubusercontent.com/L1TTLEHE11/Tubik/main/",
        "https://cdn.jsdelivr.net/gh/L1TTLEHE11/Tubik@main/",
        "https://github.com/L1TTLEHE11/Tubik/raw/main/",
    )

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private fun get(url: String) = Request.Builder().url(url)
        .header("User-Agent", "Tubik-Updater")
        .header("Cache-Control", "no-cache")
        .build()

    /** Отладочная сборка — отдельное приложение (.debug), её обновлять релизом нельзя. */
    fun isEnabled(ctx: Context) = !ctx.packageName.endsWith(".debug")

    /**
     * Прочитать update.json со всех зеркал параллельно и взять самую новую версию:
     * кэши CDN обновляются не одновременно, и одно зеркало может отдавать устаревший файл.
     */
    fun fetch(bases: List<String> = mirrors): UpdateInfo {
        val results = Collections.synchronizedList(mutableListOf<UpdateInfo>())
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val threads = bases.map { b ->
            thread(isDaemon = true) {
                try {
                    http.newCall(get(b + "update.json?t=" + System.currentTimeMillis() / 60_000)).execute().use { r ->
                        if (r.code != 200) throw UpdateException("HTTP ${r.code}")
                        results += UpdateInfo.parse(r.body?.string().orEmpty())
                    }
                } catch (e: Exception) {
                    errors += (e.message ?: e.toString())
                }
            }
        }
        threads.forEach { it.join(10_000) }
        return results.maxByOrNull { it.versionCode }
            ?: throw UpdateException("Сервер обновлений недоступен" + (errors.firstOrNull()?.let { ": $it" } ?: ""))
    }

    fun currentVersionCode(ctx: Context): Long {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }

    fun currentVersionName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val b = ByteArray(65536)
            while (true) {
                val n = s.read(b)
                if (n < 0) break
                md.update(b, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Скачать APK (пробуя зеркала по очереди) и проверить SHA-256 — битый или подменённый
     * файл до установки не дойдёт. onProgress(доля 0..1).
     */
    fun download(
        info: UpdateInfo,
        dir: File,
        onProgress: (Float) -> Unit,
        running: () -> Boolean = { true },
        bases: List<String> = mirrors,
    ): File {
        dir.mkdirs()
        dir.listFiles()?.forEach { if (it.name.endsWith(".apk") || it.name.endsWith(".part")) it.delete() }
        val out = File(dir, "Tubik-${info.versionName}.apk")
        val part = File(dir, out.name + ".part")
        var last: Exception? = null
        for (b in bases) {
            try {
                http.newCall(get(b + info.apkPath)).execute().use { r ->
                    if (r.code != 200) throw UpdateException("HTTP ${r.code}")
                    val body = r.body ?: throw UpdateException("пустой ответ")
                    val total = body.contentLength().takeIf { it > 0 } ?: info.size
                    var got = 0L
                    body.byteStream().use { inp ->
                        part.outputStream().use { o ->
                            val buf = ByteArray(65536)
                            while (true) {
                                if (!running()) throw UpdateException(CANCELLED)
                                val n = inp.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                                got += n
                                if (total > 0) onProgress((got.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                if (sha256(part) != info.sha256) {
                    part.delete()
                    throw UpdateException("Контрольная сумма не сошлась — файл битый")
                }
                part.renameTo(out)
                onProgress(1f)
                return out
            } catch (e: UpdateException) {
                if (e.message == CANCELLED) throw e
                last = e
            } catch (e: Exception) {
                last = e
            }
        }
        throw UpdateException("Не удалось скачать обновление" + (last?.message?.let { ": $it" } ?: ""))
    }

    /** Может ли приложение предложить установку (Android 8+: «Установка неизвестных приложений»). */
    fun canInstall(ctx: Context) = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /**
     * Открыть экран разрешения «Установка неизвестных приложений».
     * На части прошивок Android TV этого экрана нет — тогда пробуем общие настройки безопасности.
     * Возвращает false, если не открылось ничего.
     */
    fun openInstallPermission(ctx: Context): Boolean {
        val intents = buildList {
            if (Build.VERSION.SDK_INT >= 26) {
                add(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName)))
                add(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
            }
            add(Intent(Settings.ACTION_SECURITY_SETTINGS))
            add(Intent(Settings.ACTION_SETTINGS))
        }
        for (i in intents) {
            try {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    /**
     * Установить APK через PackageInstaller (надёжнее, чем ACTION_VIEW, особенно на ТВ).
     * Система покажет своё окно подтверждения; приложение перезапустится, данные и вход сохранятся.
     */
    fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { session ->
            apk.inputStream().use { inp ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    inp.copyTo(out, 65536)
                    session.fsync(out)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(
                ctx, id, Intent(ctx, InstallResultReceiver::class.java).setPackage(ctx.packageName), flags,
            )
            session.commit(callback.intentSender)
        }
    }

    const val CANCELLED = "Загрузка отменена"
}

/** Ответ установщика: просьба подтвердить установку или ошибка. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                val human = when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "Установка отменена"
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "Конфликт: подпись APK не совпадает с установленной версией. Удали старую и поставь заново."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Не хватает места"
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "APK несовместим с устройством"
                    else -> "Установка не удалась"
                }
                Toast.makeText(context, if (msg.isNotBlank()) "$human ($msg)" else human, Toast.LENGTH_LONG).show()
            }
        }
    }
}

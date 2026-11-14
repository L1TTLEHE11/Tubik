package dev.tubik.app.data

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.security.MessageDigest

data class VideoItem(
    val id: String,
    val title: String,
    val channel: String = "",
    val thumbnail: String = "",
    val duration: String = "",
    val meta: String = "",
    val isLive: Boolean = false,
    val channelUrl: String = "",
) {
    val url get() = "https://www.youtube.com/watch?v=$id"
}

data class FeedPage(val items: List<VideoItem>, val continuation: String?)

class NotLoggedInException : Exception("Нужно войти в аккаунт")

/**
 * Минимальный клиент к внутреннему API YouTube (InnerTube, клиент WEB).
 * Нужен для того, чего нет в NewPipeExtractor: лент залогиненного пользователя.
 * cookieProvider возвращает строку Cookie для youtube.com (или null, если не вошли).
 */
class InnerTube(
    private val http: OkHttpClient,
    private val cookieProvider: () -> String?,
    private val onCookiesUpdated: (String) -> Unit = {},
    private val hl: String = "ru",
    private val gl: String = "RU",
) {
    private val origin = "https://www.youtube.com"

    private fun clientVersion(): String = try {
        YoutubeParsingHelper.getClientVersion()
    } catch (e: Exception) {
        "2.20251101.00.00"
    }

    private fun context(): JsonObject = JsonObject().apply {
        put("client", JsonObject().apply {
            put("clientName", "WEB")
            put("clientVersion", clientVersion())
            put("hl", hl)
            put("gl", gl)
            put("platform", "DESKTOP")
        })
        put("user", JsonObject().apply { put("lockedSafetyMode", false) })
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun cookieValue(cookies: String, name: String): String? =
        cookies.split(';').map { it.trim() }.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')

    private fun authHeader(cookies: String): String? {
        val ts = System.currentTimeMillis() / 1000
        val parts = mutableListOf<String>()
        cookieValue(cookies, "SAPISID")?.let { parts += "SAPISIDHASH ${ts}_${sha1("$ts $it $origin")}" }
        cookieValue(cookies, "__Secure-1PAPISID")?.let { parts += "SAPISID1PHASH ${ts}_${sha1("$ts $it $origin")}" }
        cookieValue(cookies, "__Secure-3PAPISID")?.let { parts += "SAPISID3PHASH ${ts}_${sha1("$ts $it $origin")}" }
        if (parts.isEmpty()) {
            // Бывает, что SAPISID нет, а есть только __Secure-3PAPISID — тогда он идёт как основной.
            return null
        }
        return parts.joinToString(" ")
    }

    fun post(endpoint: String, body: JsonObject, requireAuth: Boolean): JsonObject {
        body["context"] = context()
        val cookies = cookieProvider()
        if (requireAuth && cookies.isNullOrBlank()) throw NotLoggedInException()
        val rb = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
            .post(JsonWriter.string(body).toRequestBody("application/json".toMediaType()))
            .header("User-Agent", USER_AGENT)
            .header("Origin", origin)
            .header("X-Origin", origin)
            .header("Referer", "$origin/")
            .header("X-Youtube-Client-Name", "1")
            .header("X-Youtube-Client-Version", clientVersion())
        if (!cookies.isNullOrBlank()) {
            rb.header("Cookie", cookies)
            authHeader(cookies)?.let {
                rb.header("Authorization", it)
                rb.header("X-Goog-AuthUser", "0")
            }
        }
        http.newCall(rb.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!cookies.isNullOrBlank()) mergeSetCookies(cookies, r.headers("Set-Cookie"))
            if (r.code == 401 || r.code == 403) throw NotLoggedInException()
            if (!r.isSuccessful) throw Exception("InnerTube $endpoint: HTTP ${r.code}")
            return JsonParser.`object`().from(text)
        }
    }

    /** Google периодически ротирует часть cookies — подхватываем новые значения, чтобы вход не протухал. */
    private fun mergeSetCookies(current: String, setCookies: List<String>) {
        if (setCookies.isEmpty()) return
        val map = LinkedHashMap<String, String>()
        current.split(';').forEach { p ->
            val i = p.indexOf('=')
            if (i > 0) map[p.substring(0, i).trim()] = p.substring(i + 1).trim()
        }
        var changed = false
        for (sc in setCookies) {
            val pair = sc.substringBefore(';')
            val i = pair.indexOf('=')
            if (i <= 0) continue
            val name = pair.substring(0, i).trim()
            val value = pair.substring(i + 1).trim()
            if (value.isEmpty() || sc.contains("Max-Age=0", ignoreCase = true)) continue
            if (map[name] != value) { map[name] = value; changed = true }
        }
        if (changed) onCookiesUpdated(map.entries.joinToString("; ") { "${it.key}=${it.value}" })
    }

    /** true/false — что сказал YouTube, null — если он вообще не сообщил. */
    fun loggedInFlag(json: JsonObject): Boolean? {
        val params = json.getObject("responseContext").getArray("serviceTrackingParams")
        for (p in params) {
            val ps = (p as? JsonObject)?.getArray("params") ?: continue
            for (kv in ps) {
                val o = kv as? JsonObject ?: continue
                if (o.getString("key") == "logged_in") return o.getString("value") == "1"
            }
        }
        return null
    }

    fun isLoggedIn(json: JsonObject): Boolean {
        val params = json.getObject("responseContext").getArray("serviceTrackingParams")
        for (p in params) {
            val ps = (p as? JsonObject)?.getArray("params") ?: continue
            for (kv in ps) {
                val o = kv as? JsonObject ?: continue
                if (o.getString("key") == "logged_in") return o.getString("value") == "1"
            }
        }
        return false
    }

    fun browse(browseId: String, continuation: String? = null, requireAuth: Boolean = true): FeedPage {
        val body = JsonObject()
        if (continuation != null) body["continuation"] = continuation else body["browseId"] = browseId
        val json = post("browse", body, requireAuth)
        if (requireAuth && loggedInFlag(json) == false) throw NotLoggedInException()
        return parseFeed(json)
    }

    /** Поиск через InnerTube: с куками выдача персональная, без них обычная. */
    fun search(query: String, continuation: String? = null): FeedPage {
        val body = JsonObject()
        if (continuation != null) body["continuation"] = continuation else {
            body["query"] = query
            body["params"] = "EgIQAQ%3D%3D".replace("%3D", "=") // фильтр: только видео
        }
        return parseFeed(post("search", body, requireAuth = false))
    }

    /**
     * Подписан ли пользователь на канал. Смотрим по порядку:
     * subscriptionStateEntity (новый формат), subscribeButtonRenderer (старый),
     * а если YouTube не сказал — ищем канал в списке подписок (FEchannels).
     * null — не удалось понять.
     */
    fun isSubscribed(channelId: String): Boolean? {
        val json = post("browse", JsonObject().apply { put("browseId", channelId) }, requireAuth = true)
        findSubscribed(json, channelId)?.let { return it }
        return runCatching { subscribedChannelIds().contains(channelId) }.getOrNull()
    }

    /** Все каналы из списка подписок (до ~10 страниц). */
    fun subscribedChannelIds(): Set<String> {
        val ids = HashSet<String>()
        var body = JsonObject().apply { put("browseId", "FEchannels") }
        repeat(10) {
            val json = post("browse", body, requireAuth = true)
            var cont: String? = null
            fun walk(n: Any?) {
                when (n) {
                    is JsonObject -> {
                        for (key in listOf("channelRenderer", "gridChannelRenderer")) {
                            n.getObject(key)?.getString("channelId")?.let { ids += it }
                        }
                        n.getObject("continuationCommand")?.getString("token")?.let { if (cont == null) cont = it }
                        n.values.forEach { walk(it) }
                    }
                    is JsonArray -> n.forEach { walk(it) }
                }
            }
            walk(json)
            val c = cont ?: return ids
            body = JsonObject().apply { put("continuation", c) }
        }
        return ids
    }

    fun setSubscribed(channelId: String, subscribe: Boolean) {
        val body = JsonObject().apply { put("channelIds", JsonArray().apply { add(channelId) }) }
        post(if (subscribe) "subscription/subscribe" else "subscription/unsubscribe", body, requireAuth = true)
    }

    fun home(continuation: String? = null) = browse("FEwhat_to_watch", continuation)
    fun subscriptions(continuation: String? = null) = browse("FEsubscriptions", continuation)
    fun history(continuation: String? = null) = browse("FEhistory", continuation)

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

        fun parseFeed(json: JsonObject): FeedPage {
            val items = LinkedHashMap<String, VideoItem>()
            var cont: String? = null
            fun walk(node: Any?) {
                when (node) {
                    is JsonObject -> {
                        for ((k, v) in node) {
                            when (k) {
                                "videoRenderer", "gridVideoRenderer", "compactVideoRenderer" ->
                                    (v as? JsonObject)?.let { parseVideoRenderer(it) }?.let { items.putIfAbsent(it.id, it) }
                                "lockupViewModel" ->
                                    (v as? JsonObject)?.let { parseLockup(it) }?.let { items.putIfAbsent(it.id, it) }
                                "continuationCommand" ->
                                    (v as? JsonObject)?.getString("token")?.let { if (cont == null) cont = it }
                                "nextContinuationData" ->
                                    (v as? JsonObject)?.getString("continuation")?.let { if (cont == null) cont = it }
                                "reelShelfRenderer", "richShelfRenderer" -> {
                                    // Полки Shorts и прочие карусели пропускаем
                                    if ((v as? JsonObject)?.toString()?.contains("reelWatchEndpoint") == true) Unit else walk(v)
                                }
                                else -> walk(v)
                            }
                        }
                    }
                    is JsonArray -> node.forEach { walk(it) }
                }
            }
            walk(json)
            return FeedPage(items.values.toList(), cont)
        }

        private fun text(o: JsonObject?): String {
            if (o == null) return ""
            o.getString("simpleText")?.let { return it }
            o.getString("content")?.let { return it }
            val runs = o.getArray("runs") ?: return ""
            return runs.joinToString("") { (it as? JsonObject)?.getString("text").orEmpty() }
        }

        private fun bestThumb(arr: JsonArray?): String {
            val list = arr?.mapNotNull { it as? JsonObject } ?: return ""
            val best = list.maxByOrNull { it.getInt("width", 0) } ?: return ""
            return best.getString("url").orEmpty().let { if (it.startsWith("//")) "https:$it" else it }
        }

        fun parseVideoRenderer(o: JsonObject): VideoItem? {
            val id = o.getString("videoId") ?: return null
            val badges = o.toString()
            val live = badges.contains("BADGE_STYLE_TYPE_LIVE_NOW") || badges.contains("\"LIVE\"")
            val meta = listOf(
                text(o.getObject("shortViewCountText")).ifEmpty { text(o.getObject("viewCountText")) },
                text(o.getObject("publishedTimeText")),
            ).filter { it.isNotBlank() }.joinToString(" • ")
            return VideoItem(
                id = id,
                title = text(o.getObject("title")),
                channel = text(o.getObject("ownerText")).ifEmpty { text(o.getObject("shortBylineText")) }
                    .ifEmpty { text(o.getObject("longBylineText")) },
                thumbnail = bestThumb(o.getObject("thumbnail").getArray("thumbnails")),
                duration = text(o.getObject("lengthText")),
                meta = meta,
                isLive = live,
                channelUrl = channelUrlOf(o.getObject("ownerText")).ifEmpty { channelUrlOf(o.getObject("shortBylineText")) }
                    .ifEmpty { channelUrlOf(o.getObject("longBylineText")) },
            )
        }

        /** Ищет первый browseEndpoint канала (UC…) внутри узла. */
        private fun channelUrlOf(node: Any?): String {
            fun walk(n: Any?): String? {
                when (n) {
                    is JsonObject -> {
                        n.getObject("browseEndpoint")?.getString("browseId")
                            ?.takeIf { it.startsWith("UC") }?.let { return "https://www.youtube.com/channel/$it" }
                        for (v in n.values) walk(v)?.let { return it }
                    }
                    is JsonArray -> for (v in n) walk(v)?.let { return it }
                }
                return null
            }
            return walk(node).orEmpty()
        }

        fun findSubscribed(json: JsonObject, channelId: String): Boolean? {
            var entity: Boolean? = null
            var renderer: Boolean? = null
            var viewModel: Boolean? = null
            fun walk(n: Any?, inVm: Boolean) {
                when (n) {
                    is JsonObject -> {
                        n.getObject("subscriptionStateEntity")?.let { e ->
                            if (entity == null && e.has("subscribed")) entity = e.getBoolean("subscribed")
                        }
                        n.getObject("subscribeButtonRenderer")?.let { r ->
                            val forThis = r.getArray("channelIds")?.contains(channelId) ?: true
                            if (renderer == null && forThis && r.has("subscribed")) renderer = r.getBoolean("subscribed")
                        }
                        if (inVm && viewModel == null && n.get("subscribed") is Boolean) viewModel = n.getBoolean("subscribed")
                        for ((k, v) in n) walk(v, inVm || k == "subscribeButtonViewModel")
                    }
                    is JsonArray -> n.forEach { walk(it, inVm) }
                }
            }
            walk(json, false)
            return entity ?: renderer ?: viewModel
        }

        private fun findFirst(node: Any?, key: String): Any? {
            when (node) {
                is JsonObject -> {
                    if (node.containsKey(key)) return node[key]
                    for (v in node.values) findFirst(v, key)?.let { return it }
                }
                is JsonArray -> for (v in node) findFirst(v, key)?.let { return it }
            }
            return null
        }

        fun parseLockup(o: JsonObject): VideoItem? {
            if (o.getString("contentType") != "LOCKUP_CONTENT_TYPE_VIDEO") return null
            val id = o.getString("contentId") ?: return null
            val md = o.getObject("metadata").getObject("lockupMetadataViewModel")
            val rows = md.getObject("metadata").getObject("contentMetadataViewModel").getArray("metadataRows")
            val rowTexts = rows.mapNotNull { r ->
                (r as? JsonObject)?.getArray("metadataParts")
                    ?.mapNotNull { (it as? JsonObject)?.getObject("text")?.getString("content") }
                    ?.filter { it.isNotBlank() }
                    ?.joinToString(" • ")
            }.filter { it.isNotBlank() }
            val img = findFirst(o.getObject("contentImage"), "sources") as? JsonArray
            val badge = findFirst(o.getObject("contentImage"), "thumbnailBadgeViewModel") as? JsonObject
            val badgeText = badge?.getString("text").orEmpty()
            val live = badge?.getString("badgeStyle")?.contains("LIVE") == true
            return VideoItem(
                id = id,
                title = md.getObject("title").getString("content").orEmpty(),
                channel = if (rowTexts.size >= 2) rowTexts[0] else "",
                thumbnail = bestThumb(img),
                duration = if (live) "" else badgeText,
                channelUrl = channelUrlOf(o.getObject("metadata")),
                meta = if (rowTexts.size >= 2) rowTexts.drop(1).joinToString(" • ") else rowTexts.firstOrNull().orEmpty(),
                isLive = live,
            )
        }
    }
}

package dev.tubik.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.kiosk.KioskExtractor
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler

class ChannelPage(
    val url: String,
    val name: String,
    val avatar: String,
    val banner: String,
    val subscribers: String,
    val description: String,
    val tabs: List<Pair<String, ListLinkHandler>>,
)

/** Страница ленты + функция, которая грузит следующую (null — дальше пусто). */
class Paged(val items: List<VideoItem>, val next: (suspend () -> Paged)?)

class Repository(
    private val innerTube: InnerTube,
    private val prefs: Prefs,
) {
    private val yt get() = ServiceList.YouTube

    val isLoggedIn get() = !prefs.cookies.isNullOrBlank()

    private fun innerPaged(load: (String?) -> FeedPage, cont: String? = null): suspend () -> Paged = {
        val page = withContext(Dispatchers.IO) { load(cont) }
        Paged(page.items, page.continuation?.let { innerPaged(load, it) })
    }

    fun home(): suspend () -> Paged = innerPaged({ innerTube.home(it) })
    fun subscriptions(): suspend () -> Paged = innerPaged({ innerTube.subscriptions(it) })
    fun history(): suspend () -> Paged = innerPaged({ innerTube.history(it) })
    fun search(query: String): suspend () -> Paged = innerPaged({ innerTube.search(query, it) })

    /** Подборки YouTube, которые работают без аккаунта. */
    val kiosks = linkedMapOf(
        "trending_music" to "Музыка",
        "trending_gaming" to "Игры",
        "trending_movies_and_shows" to "Фильмы и сериалы",
        "trending_podcasts_episodes" to "Подкасты",
        "live" to "Эфиры",
    )

    fun kiosk(id: String): suspend () -> Paged = {
        withContext(Dispatchers.IO) {
            val ex = yt.kioskList.getExtractorById(id, null)
            ex.fetchPage()
            val p = ex.initialPage
            Paged(p.items.toVideos(), p.nextPage?.let { kioskNext(ex, it) })
        }
    }

    private fun kioskNext(ex: KioskExtractor<out InfoItem>, page: Page): suspend () -> Paged = {
        withContext(Dispatchers.IO) {
            val p = ex.getPage(page)
            Paged(p.items.toVideos(), p.nextPage?.takeIf { Page.isValid(it) }?.let { kioskNext(ex, it) })
        }
    }

    suspend fun channel(url: String): ChannelPage = withContext(Dispatchers.IO) {
        val info = ChannelInfo.getInfo(yt, url)
        val titles = linkedMapOf(
            ChannelTabs.VIDEOS to "Видео",
            ChannelTabs.LIVESTREAMS to "Эфиры",
            ChannelTabs.SHORTS to "Shorts",
        )
        val tabs = titles.mapNotNull { (filter, title) ->
            info.tabs.firstOrNull { it.contentFilters.contains(filter) }?.let { title to it }
        }
        ChannelPage(
            url = url,
            name = info.name,
            avatar = info.avatars.maxByOrNull { it.height }?.url.orEmpty(),
            banner = info.banners.maxByOrNull { it.width }?.url.orEmpty(),
            subscribers = formatCount(info.subscriberCount).let { if (it.isNotEmpty()) "$it подписчиков" else "" },
            description = info.description.orEmpty(),
            tabs = tabs,
        )
    }

    fun channelTab(tab: ListLinkHandler): suspend () -> Paged = {
        withContext(Dispatchers.IO) {
            val info = ChannelTabInfo.getInfo(yt, tab)
            Paged(info.relatedItems.toVideos(), info.nextPage?.takeIf { Page.isValid(it) }?.let { channelTabNext(tab, it) })
        }
    }

    private fun channelTabNext(tab: ListLinkHandler, page: Page): suspend () -> Paged = {
        withContext(Dispatchers.IO) {
            val p = ChannelTabInfo.getMoreItems(yt, tab, page)
            Paged(p.items.toVideos(), p.nextPage?.takeIf { Page.isValid(it) }?.let { channelTabNext(tab, it) })
        }
    }

    suspend fun stream(videoId: String): StreamInfo = withContext(Dispatchers.IO) {
        StreamInfo.getInfo(yt, "https://www.youtube.com/watch?v=$videoId")
    }

    companion object {
        fun formatDuration(sec: Long): String {
            if (sec <= 0) return ""
            val h = sec / 3600
            val m = (sec % 3600) / 60
            val s = sec % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }

        fun formatCount(n: Long): String = when {
            n < 0 -> ""
            n >= 1_000_000_000 -> "%.1f млрд".format(n / 1e9)
            n >= 1_000_000 -> "%.1f млн".format(n / 1e6)
            n >= 1_000 -> "%.0f тыс.".format(n / 1e3)
            else -> n.toString()
        }.replace(".0 ", " ")

        fun idFromUrl(url: String): String? =
            Regex("(?:v=|youtu\\.be/|/shorts/|/live/)([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1)

        fun List<InfoItem>.toVideos(): List<VideoItem> = filterIsInstance<StreamInfoItem>().mapNotNull { it.toVideo() }

        fun StreamInfoItem.toVideo(): VideoItem? {
            val id = idFromUrl(url) ?: return null
            val views = formatCount(viewCount).let { if (it.isNotEmpty()) "$it просмотров" else "" }
            return VideoItem(
                id = id,
                title = name,
                channel = uploaderName.orEmpty(),
                thumbnail = thumbnails.maxByOrNull { it.height }?.url.orEmpty(),
                duration = formatDuration(duration),
                meta = listOf(views, textualUploadDate.orEmpty()).filter { it.isNotBlank() }.joinToString(" • "),
                isLive = streamType.name.contains("LIVE"),
                channelUrl = uploaderUrl.orEmpty(),
            )
        }
    }
}

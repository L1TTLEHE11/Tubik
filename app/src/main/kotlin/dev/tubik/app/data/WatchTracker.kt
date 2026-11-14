package dev.tubik.app.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.YoutubeStreamHelper
import java.util.Locale

/**
 * Отметки просмотра для истории YouTube — то же, что шлёт официальный клиент:
 *  • playback — «начал смотреть» (видео появляется в истории);
 *  • watchtime — какие куски просмотрены (отсюда красная полоска «досмотрел до…»).
 * Адреса берём из ответа плеера (клиент visionOS, как в NewPipe), а сами отметки уходят
 * с куками пользователя — так YouTube понимает, в чью историю писать.
 * Все методы блокирующие: вызывать не из главного потока.
 */
class WatchTracker(private val innerTube: InnerTube, private val videoId: String) {
    private var playbackUrl: String? = null
    private var watchtimeUrl: String? = null
    private var cpn: String = ""
    private var started = false
    private var segStart = 0.0
    private var lastSent = 0L
    private var sends = 0

    /** Достать адреса отметок. false — не вышло (тогда трекер просто молчит). */
    fun prepare(): Boolean = try {
        cpn = YoutubeParsingHelper.generateContentPlaybackNonce()
        val loc = NewPipe.getPreferredLocalization() ?: Localization("ru", "RU")
        val country = NewPipe.getPreferredContentCountry() ?: ContentCountry("RU")
        val pr = YoutubeStreamHelper.getVisionOsPlayerResponse(country, loc, videoId, cpn)
        val pt = pr.getObject("playbackTracking")
        playbackUrl = pt.getObject("videostatsPlaybackUrl").getString("baseUrl")
        watchtimeUrl = pt.getObject("videostatsWatchtimeUrl").getString("baseUrl")
        playbackUrl != null
    } catch (e: Exception) {
        false
    }

    private fun fmt(sec: Double) = String.format(Locale.US, "%.3f", sec.coerceAtLeast(0.0))

    private fun ping(base: String?, params: Map<String, String>) {
        base ?: return
        val b = base.toHttpUrl().newBuilder()
            .setQueryParameter("ver", "2")
            .setQueryParameter("cpn", cpn)
            .setQueryParameter("el", "detailpage")
        params.forEach { (k, v) -> b.setQueryParameter(k, v) }
        runCatching { innerTube.authedGet(b.build().toString()) }
    }

    /** Видео пошло: регистрируем просмотр (один раз). */
    @Synchronized
    fun onPlaying(posSec: Double) {
        if (playbackUrl == null || started) return
        started = true
        segStart = posSec
        lastSent = System.currentTimeMillis()
        ping(playbackUrl, mapOf("cmt" to fmt(posSec)))
    }

    /** Раз в ~250 мс из плеера. Сам решает, пора ли слать watchtime (через 10 с, потом каждые 30 с). */
    @Synchronized
    fun tick(posSec: Double, playing: Boolean) {
        if (!started || !playing) return
        val interval = if (sends == 0) 10_000 else 30_000
        if (System.currentTimeMillis() - lastSent >= interval) flush(posSec, "playing")
    }

    /** Отправить просмотренный кусок [segStart, posSec] — на паузе, перемотке и выходе. */
    @Synchronized
    fun flush(posSec: Double, state: String = "paused") {
        if (!started) return
        if (posSec > segStart + 0.5) {
            ping(
                watchtimeUrl,
                mapOf("st" to fmt(segStart), "et" to fmt(posSec), "cmt" to fmt(posSec), "state" to state),
            )
            sends++
        }
        segStart = posSec
        lastSent = System.currentTimeMillis()
    }

    /** Перемотка: закрываем кусок до старой позиции и начинаем новый с новой. */
    @Synchronized
    fun onSeek(fromSec: Double, toSec: Double) {
        if (!started) return
        flush(fromSec, "playing")
        segStart = toSec
    }
}

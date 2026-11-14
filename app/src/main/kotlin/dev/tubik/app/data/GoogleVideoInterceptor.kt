package dev.tubik.app.data

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.util.concurrent.atomic.AtomicInteger

/**
 * Запросы к googlevideo.com/videoplayback оформляем так же, как это делает NewPipe:
 * POST с телом «x\0», номер запроса в параметре rn и User-Agent того клиента YouTube,
 * для которого выдана ссылка (сейчас это visionOS). Range остаётся в заголовке.
 */
class GoogleVideoInterceptor : Interceptor {
    private val rn = AtomicInteger(0)
    private val body = byteArrayOf(0x78, 0)

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (!req.url.host.endsWith("googlevideo.com") || !req.url.encodedPath.startsWith("/videoplayback")) {
            return chain.proceed(req)
        }
        val url = if (req.url.queryParameter("rn") == null) {
            req.url.newBuilder().addQueryParameter("rn", rn.getAndIncrement().toString()).build()
        } else req.url
        val ua = if (YoutubeParsingHelper.isVisionOsStreamingUrl(req.url.toString())) {
            YoutubeParsingHelper.getVisionOsUserAgent(null)
        } else InnerTube.USER_AGENT
        val newReq = req.newBuilder()
            .url(url)
            .header("User-Agent", ua)
            .post(body.toRequestBody("application/x-protobuf".toMediaType()))
            .build()
        return chain.proceed(newReq)
    }
}

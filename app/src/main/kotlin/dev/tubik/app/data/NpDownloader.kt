package dev.tubik.app.data

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/** Мост между NewPipeExtractor и OkHttp. */
class NpDownloader(private val http: OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        val b = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
            .header("User-Agent", InnerTube.USER_AGENT)
        request.headers().forEach { (name, values) ->
            b.removeHeader(name)
            values.forEach { b.addHeader(name, it) }
        }
        http.newCall(b.build()).execute().use { r ->
            if (r.code == 429) {
                throw ReCaptchaException("YouTube просит капчу (слишком много запросов)", request.url())
            }
            return Response(
                r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString()
            )
        }
    }
}

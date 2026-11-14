package dev.tubik.app.data

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest

data class Segment(val startMs: Long, val endMs: Long, val category: String)

/**
 * SponsorBlock (sponsor.ajay.app). Запрос идёт по первым 4 символам SHA-256 от id видео,
 * так что сервер не знает, какое именно видео ты смотришь.
 */
class SponsorBlock(private val http: OkHttpClient) {
    fun segments(videoId: String, categories: Set<String>): List<Segment> {
        if (categories.isEmpty()) return emptyList()
        val hash = MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(4)
        val url = "https://sponsor.ajay.app/api/skipSegments/$hash".toHttpUrl().newBuilder()
            .addQueryParameter("categories", categories.joinToString(",", "[", "]") { "\"$it\"" })
            .addQueryParameter("actionTypes", "[\"skip\"]")
            .build()
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.code == 404) return emptyList()
            if (!r.isSuccessful) return emptyList()
            val arr: JsonArray = JsonParser.array().from(r.body?.string().orEmpty())
            val video = arr.mapNotNull { it as? JsonObject }.firstOrNull { it.getString("videoID") == videoId }
                ?: return emptyList()
            return video.getArray("segments").mapNotNull { it as? JsonObject }.mapNotNull { s ->
                val seg = s.getArray("segment")
                val start = (seg.getDouble(0) * 1000).toLong()
                val end = (seg.getDouble(1) * 1000).toLong()
                if (end - start < 500) null else Segment(start, end, s.getString("category"))
            }.sortedBy { it.startMs }
        }
    }
}

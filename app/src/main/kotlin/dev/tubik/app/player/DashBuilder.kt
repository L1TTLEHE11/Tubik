package dev.tubik.app.player

import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * Собирает один DASH-манифест из всех отдельных видео- и аудиопотоков YouTube.
 * Тогда ExoPlayer сам качает видео и звук, сводит их, переключает качество
 * и ходит за кусками через Range-запросы.
 */
object DashBuilder {

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun usable(initEnd: Int, indexEnd: Int, isUrl: Boolean, dm: DeliveryMethod) =
        isUrl && dm == DeliveryMethod.PROGRESSIVE_HTTP && initEnd > 0 && indexEnd > 0

    private fun family(codec: String?): String = when {
        codec == null -> "unknown"
        codec.startsWith("avc") -> "avc"
        codec.startsWith("vp9") || codec.startsWith("vp09") -> "vp9"
        codec.startsWith("av01") -> "av1"
        else -> codec.substringBefore('.')
    }

    private fun mime(f: MediaFormat?): String = f?.mimeType ?: "video/mp4"

    fun videoStreams(info: StreamInfo): List<VideoStream> =
        info.videoOnlyStreams.filter {
            it.itagItem != null && usable(it.initEnd, it.indexEnd, it.isUrl, it.deliveryMethod)
        }

    fun audioStreams(info: StreamInfo): List<AudioStream> {
        val all = info.audioStreams.filter {
            it.itagItem != null && usable(it.initEnd, it.indexEnd, it.isUrl, it.deliveryMethod)
        }
        // Если есть дубляж на разных языках — берём оригинальную дорожку.
        val original = all.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
        return original.ifEmpty { all }
    }

    fun build(info: StreamInfo): String? {
        val video = videoStreams(info)
        val audio = audioStreams(info)
        if (video.isEmpty() || audio.isEmpty()) return null

        val durMs = (video + audio).mapNotNull { it.itagItem?.approxDurationMs?.takeIf { d -> d > 0 } }.maxOrNull()
            ?: (info.duration * 1000)
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.append("""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" type="static" minBufferTime="PT1.500S" mediaPresentationDuration="PT${durMs / 1000.0}S">""")
        sb.append("<Period>")

        var setId = 0
        video.groupBy { mime(it.format) to family(it.codec) }.forEach { (key, streams) ->
            sb.append("""<AdaptationSet id="${setId++}" contentType="video" mimeType="${key.first}" subsegmentAlignment="true">""")
            streams.sortedByDescending { it.height }.forEach { v ->
                val ii = v.itagItem!!
                sb.append("""<Representation id="${v.itag}" codecs="${esc(v.codec ?: "")}" bandwidth="${maxOf(ii.bitrate, 1)}" width="${v.width}" height="${v.height}"""")
                if (v.fps > 0) sb.append(""" frameRate="${v.fps}"""")
                sb.append(">")
                sb.append("<BaseURL>${esc(v.content)}</BaseURL>")
                sb.append("""<SegmentBase indexRange="${v.indexStart}-${v.indexEnd}"><Initialization range="${v.initStart}-${v.initEnd}"/></SegmentBase>""")
                sb.append("</Representation>")
            }
            sb.append("</AdaptationSet>")
        }

        audio.groupBy { mime(it.format) to family(it.codec) }.forEach { (key, streams) ->
            sb.append("""<AdaptationSet id="${setId++}" contentType="audio" mimeType="${key.first}" subsegmentAlignment="true">""")
            streams.sortedByDescending { it.bitrate }.forEach { a ->
                val ii = a.itagItem!!
                val rate = ii.sampleRate.takeIf { it > 0 } ?: 48000
                val ch = ii.audioChannels.takeIf { it > 0 } ?: 2
                sb.append("""<Representation id="${a.itag}" codecs="${esc(a.codec ?: "")}" bandwidth="${maxOf(ii.bitrate, 1)}" audioSamplingRate="$rate">""")
                sb.append("""<AudioChannelConfiguration schemeIdUri="urn:mpeg:dash:23003:3:audio_channel_configuration:2011" value="$ch"/>""")
                sb.append("<BaseURL>${esc(a.content)}</BaseURL>")
                sb.append("""<SegmentBase indexRange="${a.indexStart}-${a.indexEnd}"><Initialization range="${a.initStart}-${a.initEnd}"/></SegmentBase>""")
                sb.append("</Representation>")
            }
            sb.append("</AdaptationSet>")
        }
        sb.append("</Period></MPD>")
        return sb.toString()
    }
}

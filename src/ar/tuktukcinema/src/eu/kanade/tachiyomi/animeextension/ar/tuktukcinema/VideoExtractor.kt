package eu.kanade.tachiyomi.animeextension.ar.tuktukcinema

import aniyomi.lib.unpacker.Unpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class VideoExtractor(private val client: OkHttpClient, private val headers: Headers) {
    fun videosFromUrl(url: String, host: String = "", resolution: String = ""): List<Video>{
        val prefix = host.ifBlank {
            url.toHttpUrl().host.substringBefore('.')
        }.replaceFirstChar(Char::titlecase)
        val quality = resolution.ifBlank { "Mirror" }
        val response = client.newCall(GET(url, headers)).execute()
        val document = response.asJsoup()
        val sourceElements = document.select("source[src]")
        if (sourceElements.isNotEmpty()) {
            val videoHeaders = headers.newBuilder().add("Referer", url).build()
            return sourceElements.map {
                val src = it.attr("src")
                Video(src, "$prefix: $quality", src, headers = videoHeaders)
            }
        }

        // ==================== Script Search ===================
        val scriptElement = document.selectFirst("script:containsData(eval)")
            ?: document.select("script").firstOrNull { PLAYER_SCRIPT_REGEX.containsMatchIn(it.data()) }
            ?: document.selectFirst("script")
            ?: return emptyList()
        
        val playerData = scriptElement.data()
        val unpacked = Unpacker.unpack(playerData).takeIf { it.isNotBlank() } ?: playerData

        val videoUrl = VIDEO_URL_REGEX.find(unpacked)
            ?.value
            ?.replace("\\/", "/")
            ?: return emptyList()
        
        return Video(url, "$prefix: $videoUrl", url).let(::listOf)
    }

    companion object {
        private val PLAYER_SCRIPT_REGEX = Regex("(?i)(eval\\(|player|file\\s*[:=]|source\\s*[:=])")
        private val VIDEO_URL_REGEX = Regex("https?://[^\\s\"'<>\\]+?\\.(?:m3u8|mpd|mp4)(?:\\?[^\"'<>\\]*)?")
    }
}
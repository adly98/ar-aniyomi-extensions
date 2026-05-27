package eu.kanade.tachiyomi.animeextension.ar.tuktukcinema

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class VideoExtractor(private val client: OkHttpClient, private val headers: Headers) {
    fun videosFromUrl(url: String, host: String = ""): List<Video>{
        val prefix = host.ifBlank {
            url.toHttpUrl().host.substringBefore('.')
        }.replaceFirstChar(Char::titlecase)
        val response = client.newCall(GET(url, headers)).execute()
        val document = response.asJsoup()
        val sourceElements = document.select("source[src]")
        if (sourceElements.isNotEmpty()) {
            val videoHeaders = headers.newBuilder().add("Referer", url).build()
            return sourceElements.map {
                val src = it.attr("src")
                Video(src, prefix, src, headers = videoHeaders)
            }
        }
        return Video(url, "$prefix: $url", url).let(::listOf)
    }
}
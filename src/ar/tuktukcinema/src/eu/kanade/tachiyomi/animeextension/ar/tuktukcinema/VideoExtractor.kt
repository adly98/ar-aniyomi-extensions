package eu.kanade.tachiyomi.animeextension.ar.tuktukcinema

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class VideoExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, host: String = ""): List<Video>{
        val prefix = host.ifBlank {
            url.toHttpUrl().host.substringBefore('.')
        }.replaceFirstChar(Char::titlecase)
        return Video(url, "$prefix: $url", url).let(::listOf)
    }
}
package aniyomi.lib.multivideoextractor

import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.unpacker.Unpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.Locale

/**
 * Generic extractor that handles:
 *  - direct <source> tags with media URLs
 *  - packed player scripts that contain a video URL
 *  - MP4 URLs directly without PlaylistUtils
 *  - HLS/DASH playback URLs via PlaylistUtils
 *
 * This can replace generic extractor modules like `lib/upstreamextractor`
 * and `lib/vidlandextractor` for sites where the video is exposed through a
 * source tag or a script containing an embedded player URL.
 */
class MultiVideoExtractor(private val client: OkHttpClient) {

    fun videosFromUrl(url: String, refererUrl: String = "", prefix: String = ""): List<Video> {
        val headers = Headers.Builder().apply {
            set("Accept", "*/*")
            if (refererUrl.isNotBlank()) {
                set("Referer", refererUrl)
                set("Origin", "https://${refererUrl.toHttpUrl().host}")
            }
        }.build()

        val document = client.newCall(GET(url, headers)).execute().asJsoup()

        val actualPrefix = prefix.ifBlank {
            url.toHttpUrl().host.substringBefore('.').proper()
        }

        val sourceElements = document.select("source[src]")
        if (sourceElements.isNotEmpty()) {
            val videoHeaders = headers.newBuilder().add("Referer", url).build()
            return sourceElements.map { source ->
                val sourceUrl = source.attr("src").replace("\\/", "/")
                val quality = source.attr("data-quality").ifBlank {
                    source.attr("label").ifBlank {
                        source.attr("title").ifBlank {
                            source.attr("res").ifBlank {
                                source.attr("size") + "p"
                            }
                        }
                    }
                }
                val qualityLabel = if (quality.isNotBlank()) "$actualPrefix: $quality" else actualPrefix
                Video(sourceUrl, qualityLabel, sourceUrl, headers = videoHeaders)
            }
        }

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

        val playlistUtils = PlaylistUtils(client, headers)

        return when {
            videoUrl.contains(".mp4") -> {
                val videoHeaders = headers.newBuilder().add("Referer", url).build()
                listOf(Video(videoUrl, "$actualPrefix: Mirror", videoUrl, headers = videoHeaders))
            }
            videoUrl.contains(".mpd") -> playlistUtils.extractFromDash(videoUrl, { "$actualPrefix: $it" }, headers, headers, url)
            else -> playlistUtils.extractFromHls(videoUrl, url, videoNameGen = { "$actualPrefix: $it" })
        }
    }

    private fun String.proper(): String = replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
    }

    companion object {
        private val PLAYER_SCRIPT_REGEX = Regex("(?i)(eval\\(|player|file\\s*[:=]|source\\s*[:=])")
        private val VIDEO_URL_REGEX = Regex("https?://[^\\s\"'<>\\]+?\\.(?:m3u8|mpd|mp4)(?:\\?[^\"'<>\\]*)?")
    }
}

package aniyomi.lib.veevextractor

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import java.net.URLEncoder

/**
 * Veev Extractor for Aniyomi
 * Supports: veev.to, poophq.com, doods.to
 * Ported from: https://github.com/Gujal00/ResolveURL/blob/master/script.module.resolveurl/lib/resolveurl/plugins/veev.py
 */
class VeevExtractor(private val client: OkHttpClient) {

    private val json by lazy { Json { ignoreUnknownKeys = true } }

    fun videosFromUrl(url: String, quality: String = "Default"): List<Video> {
        return try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer" to url
            )

            val doc = client.newCall(GET(url, headers.mapKeys { it.key })).execute().asJsoup()

            // Extract encoded parameters from script
            val encodedParams = extractEncodedParams(doc.html())
            if (encodedParams.isEmpty()) {
                Log.w(TAG, "No encoded parameters found")
                return emptyList()
            }

            // Try each encoded parameter (reversed)
            val videos = mutableListOf<Video>()
            for (encodedParam in encodedParams.reversed()) {
                val decodedCh = veevDecode(encodedParam)
                if (decodedCh != encodedParam) {
                    val mediaId = extractMediaId(url)
                    val videoUrl = fetchVideoUrl(url, mediaId, decodedCh, headers)
                    if (videoUrl != null) {
                        videos.add(
                            Video(
                                url = videoUrl,
                                quality = "Veev - $quality",
                                videoUrl = videoUrl
                            )
                        )
                        break
                    }
                }
            }

            videos
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting videos", e)
            emptyList()
        }
    }

    private fun fetchVideoUrl(
        webUrl: String,
        mediaId: String,
        ch: String,
        headers: Map<String, String>
    ): String? {
        return try {
            val params = mapOf(
                "op" to "player_api",
                "cmd" to "gi",
                "file_code" to mediaId,
                "ch" to ch,
                "ie" to "1"
            )

            val baseUrl = webUrl.substringBefore("/e/").substringBefore("/d/")
            val dlUrl = baseUrl + "/dl?" +
                params.entries.joinToString("&") { (k, v) ->
                    "$k=${URLEncoder.encode(v, "UTF-8")}"
                }

            val response = client.newCall(GET(dlUrl, headers.mapKeys { it.key }))
                .execute()
                .body?.string() ?: return null

            val jsonObj = json.parseToJsonElement(response).jsonObject
            val fileObj = jsonObj["file"]?.jsonObject ?: return null

            if (fileObj["file_status"]?.jsonPrimitive?.content == "OK") {
                val dvArray = fileObj["dv"]?.jsonObject?.values?.firstOrNull()?.jsonObject
                val encryptedUrl = dvArray?.get("s")?.jsonPrimitive?.content ?: return null

                val decodedUrl = veevDecode(encryptedUrl)
                val keyArray = buildArray(ch)
                decodeUrl(decodedUrl, keyArray.firstOrNull() ?: emptyList())
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching video URL", e)
            null
        }
    }

    private fun extractEncodedParams(html: String): List<String> {
        val regex = Regex("""[\.\s'](?:fc|_vvto\[[^\]]*)(?:['\]]*)?\s*[:=]\s*['"]([^'"]+)['"]""")
        return regex.findAll(html).map { it.groupValues[1] }.toList()
    }

    private fun extractMediaId(url: String): String {
        return url.substringAfterLast("/")
    }

    companion object {
        private const val TAG = "VeevExtractor"
    }
}

/**
 * LZW Decompression algorithm used by Veev
 * Ported from Python veev_decode function
 */
fun veevDecode(etext: String): String {
    val result = mutableListOf<String>()
    val lut = mutableMapOf<Int, String>()
    var n = 256
    var c = etext[0].toString()
    result.add(c)

    for (i in 1 until etext.length) {
        val char = etext[i]
        val code = char.code
        val nc = if (code < 256) char.toString() else lut[code] ?: (c + c[0])
        result.add(nc)
        lut[n] = c + nc[0]
        n++
        c = nc
    }

    return result.joinToString("")
}

/**
 * Helper function to check if string contains only digits
 */
private fun String.isDigitsOnly(): Boolean {
    return this.all { it.isDigit() }
}

/**
 * Builds array from encoded string
 * Ported from Python build_array function
 */
fun buildArray(encodedString: String): List<List<Int>> {
    val d = mutableListOf<List<Int>>()
    val c = encodedString.toMutableList()

    val countStr = if (c.isNotEmpty()) c.removeAt(0).toString() else "0"
    var count = if (countStr.isDigitsOnly()) countStr.toInt() else 0

    while (count > 0) {
        val currentArray = mutableListOf<Int>()
        repeat(count) {
            if (c.isNotEmpty()) {
                val charStr = c.removeAt(0).toString()
                val value = if (charStr.isDigitsOnly()) charStr.toInt() else 0
                currentArray.add(0, value)
            }
        }
        d.add(currentArray)

        val nextCountStr = if (c.isNotEmpty()) c.removeAt(0).toString() else "0"
        count = if (nextCountStr.isDigitsOnly()) nextCountStr.toInt() else 0
    }

    return d
}

/**
 * Decodes URL using transformation array
 * Ported from Python decode_url function
 */
fun decodeUrl(etext: String, tarray: List<Int>): String {
    var ds = etext
    for (t in tarray) {
        if (t == 1) {
            ds = ds.reversed()
        }
        ds = try {
            ds.chunked(2)
                .map { it.toInt(16).toChar() }
                .joinToString("")
        } catch (e: Exception) {
            ds
        }
        ds = ds.replace("dXRmOA==", "")
    }
    return ds
}

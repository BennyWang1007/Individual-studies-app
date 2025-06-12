package ai.mlc.mlcchat

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

fun Context.getNewsSummaryCacheFile(): File {
    val externalDir = getExternalFilesDir(null)
    return File(externalDir, "news_summary_cache.json")
}

fun Context.loadNewsCacheFile(): File {
    val externalDir = getExternalFilesDir(null)
    return File(externalDir, "news_cache.json")
}

suspend fun downloadFile(url: String): String? {
    return withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient()
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            response.body?.string()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

suspend fun Context._loadSummaryCache(): MutableMap<String, String> {
    println("Loading summary cache from ${getNewsSummaryCacheFile().absolutePath}")
    val file = getNewsSummaryCacheFile()
    if (!file.exists()) {
        println("Local summary cache not found, downloading from remote...")

        val url = "https://github.com/BennyWang1007/Individual-studies-app/releases/download/v1.1.0/news_summary_cache.json"
        val json = downloadFile(url)

        if (json != null) {
            file.writeText(json)
            println("Downloaded and saved summary cache.")
        } else {
            println("Failed to download summary cache.")
            return mutableMapOf()
        }
    }
    return try {
        val json = file.readText()
        val type = object : TypeToken<Map<String, String>>() {}.type
        Gson().fromJson<Map<String, String>>(json, type).toMutableMap()
    } catch (e: Exception) {
        mutableMapOf()
    }
}

suspend fun Context.loadSummaryCache(): MutableMap<String, String> {
    val summaryCache = _loadSummaryCache()
    val newsCache = loadNewsCache()

    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    // Convert entries into a list with timestamp (if found)
    val entriesWithTime = summaryCache.entries.map { entry ->
        val url = entry.key
        val summary = entry.value

        val newsTime = newsCache[url]?.time
        val timestamp = try {
            if (newsTime != null) {
                dateFormat.parse(newsTime)?.time
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }

        Triple(url, summary, timestamp)
    }

    // Sort: first by timestamp descending, then nulls last
    val sorted = entriesWithTime.sortedWith(
        compareByDescending<Triple<String, String, Long?>> { it.third }
            .thenBy { it.first }  // optional: stable sorting for same timestamp
    )

    // Build sorted map
    val sortedMap = sorted.associate { it.first to it.second }.toMutableMap()

    return sortedMap
}

suspend fun Context.loadNewsCache(): MutableMap<String, News> {
    println("Loading news cache from ${loadNewsCacheFile().absolutePath}")
    val file = loadNewsCacheFile()
    if (!file.exists()) {
        println("Local news cache not found, downloading from remote...")

        val url = "https://github.com/BennyWang1007/Individual-studies-app/releases/download/v1.1.0/news_cache.json"
        val json = downloadFile(url)

        if (json != null) {
            file.writeText(json)
            println("Downloaded and saved news cache.")
        } else {
            println("Failed to download news cache.")
            return mutableMapOf()
        }
    }
    return try {
        val json = file.readText()
        val type = object : TypeToken<Map<String, News>>() {}.type
        val map = Gson().fromJson<Map<String, News>>(json, type).toMutableMap()

        // Sort the map by time descending
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val sorted = map.entries.sortedByDescending { entry ->
            try {
                dateFormat.parse(entry.value.time)?.time ?: 0L
            } catch (e: Exception) {
                0L  // fallback if parsing fails
            }
        }.associate { it.toPair() }.toMutableMap()

        sorted
    } catch (e: Exception) {
        mutableMapOf()
    }
}

fun Context.saveSummaryCache(cache: Map<String, String>) {
    println("Saving summary cache to ${getNewsSummaryCacheFile().absolutePath}")
    val json = Gson().toJson(cache)
    getNewsSummaryCacheFile().writeText(json)
}

fun Context.saveNewsCache(cache: Map<String, News>) {
    println("Saving news cache to ${loadNewsCacheFile().absolutePath}")
    val json = Gson().toJson(cache)
    loadNewsCacheFile().writeText(json)
}

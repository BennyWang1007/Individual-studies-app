package ai.mlc.mlcchat

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
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

suspend fun Context.loadSummaryCache(): MutableMap<String, String> {
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
        Gson().fromJson<Map<String, News>>(json, type).toMutableMap()
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

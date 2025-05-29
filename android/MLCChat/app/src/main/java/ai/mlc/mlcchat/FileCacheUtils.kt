package ai.mlc.mlcchat

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

fun Context.getNewsSummaryCacheFile(): File {
    val externalDir = getExternalFilesDir(null)
    return File(externalDir, "news_summary_cache.json")
}

fun Context.loadNewsCacheFile(): File {
    val externalDir = getExternalFilesDir(null)
    return File(externalDir, "news_cache.json")
}

fun Context.loadSummaryCache(): MutableMap<String, String> {
    println("Loading summary cache from ${getNewsSummaryCacheFile().absolutePath}")
    val file = getNewsSummaryCacheFile()
    if (!file.exists()) return mutableMapOf()
    return try {
        val json = file.readText()
        val type = object : TypeToken<Map<String, String>>() {}.type
        Gson().fromJson<Map<String, String>>(json, type).toMutableMap()
    } catch (e: Exception) {
        mutableMapOf()
    }
}

fun Context.loadNewsCache(): MutableMap<String, News> {
    println("Loading news cache from ${loadNewsCacheFile().absolutePath}")
    val file = loadNewsCacheFile()
    if (!file.exists()) return mutableMapOf()
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

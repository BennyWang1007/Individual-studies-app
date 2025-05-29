package ai.mlc.mlcchat

import ai.mlc.mlcllm.MLCEngine
import ai.mlc.mlcllm.OpenAIProtocol
import ai.mlc.mlcllm.OpenAIProtocol.ChatCompletionMessage
import ai.mlc.mlcllm.OpenAIProtocol.ChatCompletionMessageContent
import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.toMutableStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.nio.channels.Channels
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import com.github.houbb.opencc4j.util.ZhConverterUtil

class AppViewModel(application: Application) : AndroidViewModel(application) {
    val modelList = emptyList<ModelState>().toMutableStateList()

    val modelSampleList = emptyList<ModelRecord>().toMutableStateList()
    private var showAlert = mutableStateOf(false)
    private var alertMessage = mutableStateOf("")
    private var appConfig = AppConfig(
        emptyList<String>().toMutableList(),
        emptyList<ModelRecord>().toMutableList()
    )
    private val application = getApplication<Application>()
    private val appDirFile = application.getExternalFilesDir("")
    private val gson = Gson()
    private val modelIdSet = emptySet<String>().toMutableSet()

    val chatState = ChatState()

    val newsService = NewsService(application)
    var newsItems = mutableStateOf<List<News>>(emptyList())
        private set

    fun fetchNews() {
        viewModelScope.launch {
            try {
                newsItems.value = newsService.fetchNews(6)
            } catch (e: Exception) {
                Log.e("AppViewModel", "Error fetching news: ${e.message}")
            }
        }
    }

    fun searchNews(query: String) {
        viewModelScope.launch {
            try {
                println("Searching news with query: $query")
                newsItems.value = newsService.searchNews(query, 6)
            } catch (e: Exception) {
                Log.e("AppViewModel", "Error searching news: ${e.message}")
            }
        }
    }

    fun summarizeNews(news: News): String {
        var summary = ""
        chatState.summarizeNews(news)
        chatState.onResult = { result ->
            summary = result
        }
        return summary
    }

    companion object {
        const val AppConfigFilename = "mlc-app-config.json"
        const val ModelConfigFilename = "mlc-chat-config.json"
        const val ParamsConfigFilename = "ndarray-cache.json"
        const val ModelUrlSuffix = "resolve/main/"
    }

    init {
        loadAppConfig()
    }

    fun isShowingAlert(): Boolean {
        return showAlert.value
    }

    fun errorMessage(): String {
        return alertMessage.value
    }

    fun dismissAlert() {
        require(showAlert.value)
        showAlert.value = false
    }

    fun copyError() {
        require(showAlert.value)
        val clipboard =
            application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("MLCChat", errorMessage()))
    }

    private fun issueAlert(error: String) {
        showAlert.value = true
        alertMessage.value = error
    }

    fun requestDeleteModel(modelId: String) {
        deleteModel(modelId)
        issueAlert("Model: $modelId has been deleted")
    }


    private fun loadAppConfig() {
        val appConfigFile = File(appDirFile, AppConfigFilename)
        val jsonString: String = if (!appConfigFile.exists()) {
            application.assets.open(AppConfigFilename).bufferedReader().use { it.readText() }
        } else {
            appConfigFile.readText()
        }
        appConfig = gson.fromJson(jsonString, AppConfig::class.java)
        appConfig.modelLibs = emptyList<String>().toMutableList()
        modelList.clear()
        modelIdSet.clear()
        modelSampleList.clear()
        for (modelRecord in appConfig.modelList) {
            appConfig.modelLibs.add(modelRecord.modelLib)
            val modelDirFile = File(appDirFile, modelRecord.modelId)
            val modelConfigFile = File(modelDirFile, ModelConfigFilename)
            if (modelConfigFile.exists()) {
                val modelConfigString = modelConfigFile.readText()
                val modelConfig = gson.fromJson(modelConfigString, ModelConfig::class.java)
                modelConfig.modelId = modelRecord.modelId
                modelConfig.modelLib = modelRecord.modelLib
                modelConfig.estimatedVramBytes = modelRecord.estimatedVramBytes
                addModelConfig(modelConfig, modelRecord.modelUrl, true)
            } else {
                downloadModelConfig(
                    if (modelRecord.modelUrl.endsWith("/")) modelRecord.modelUrl else "${modelRecord.modelUrl}/",
                    modelRecord,
                    true
                )
            }
        }
    }

    private fun updateAppConfig(action: () -> Unit) {
        action()
        val jsonString = gson.toJson(appConfig)
        val appConfigFile = File(appDirFile, AppConfigFilename)
        appConfigFile.writeText(jsonString)
    }

    private fun addModelConfig(modelConfig: ModelConfig, modelUrl: String, isBuiltin: Boolean) {
        require(!modelIdSet.contains(modelConfig.modelId))
        modelIdSet.add(modelConfig.modelId)
        modelList.add(
            ModelState(
                modelConfig,
                modelUrl + if (modelUrl.endsWith("/")) "" else "/",
                File(appDirFile, modelConfig.modelId)
            )
        )
        if (!isBuiltin) {
            updateAppConfig {
                appConfig.modelList.add(
                    ModelRecord(
                        modelUrl,
                        modelConfig.modelId,
                        modelConfig.estimatedVramBytes,
                        modelConfig.modelLib
                    )
                )
            }
        }
    }

    private fun deleteModel(modelId: String) {
        val modelDirFile = File(appDirFile, modelId)
        modelDirFile.deleteRecursively()
        require(!modelDirFile.exists())
        modelIdSet.remove(modelId)
        modelList.removeIf { modelState -> modelState.modelConfig.modelId == modelId }
        updateAppConfig {
            appConfig.modelList.removeIf { modelRecord -> modelRecord.modelId == modelId }
        }
    }

    private fun isModelConfigAllowed(modelConfig: ModelConfig): Boolean {
        if (appConfig.modelLibs.contains(modelConfig.modelLib)) return true
        viewModelScope.launch {
            issueAlert("Model lib ${modelConfig.modelLib} is not supported.")
        }
        return false
    }


    private fun downloadModelConfig(
        modelUrl: String,
        modelRecord: ModelRecord,
        isBuiltin: Boolean
    ) {
        thread(start = true) {
            try {
                val url = URL("${modelUrl}${ModelUrlSuffix}${ModelConfigFilename}")
                val tempId = UUID.randomUUID().toString()
                val tempFile = File(
                    application.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                    tempId
                )
                url.openStream().use {
                    Channels.newChannel(it).use { src ->
                        FileOutputStream(tempFile).use { fileOutputStream ->
                            fileOutputStream.channel.transferFrom(src, 0, Long.MAX_VALUE)
                        }
                    }
                }
                require(tempFile.exists())
                viewModelScope.launch {
                    try {
                        val modelConfigString = tempFile.readText()
                        val modelConfig = gson.fromJson(modelConfigString, ModelConfig::class.java)
                        modelConfig.modelId = modelRecord.modelId
                        modelConfig.modelLib = modelRecord.modelLib
                        modelConfig.estimatedVramBytes = modelRecord.estimatedVramBytes
                        if (modelIdSet.contains(modelConfig.modelId)) {
                            tempFile.delete()
                            issueAlert("${modelConfig.modelId} has been used, please consider another local ID")
                            return@launch
                        }
                        if (!isModelConfigAllowed(modelConfig)) {
                            tempFile.delete()
                            return@launch
                        }
                        val modelDirFile = File(appDirFile, modelConfig.modelId)
                        val modelConfigFile = File(modelDirFile, ModelConfigFilename)
                        tempFile.copyTo(modelConfigFile, overwrite = true)
                        tempFile.delete()
                        require(modelConfigFile.exists())
                        addModelConfig(modelConfig, modelUrl, isBuiltin)
                    } catch (e: Exception) {
                        viewModelScope.launch {
                            issueAlert("Add model failed: ${e.localizedMessage}")
                        }
                    }
                }
            } catch (e: Exception) {
                viewModelScope.launch {
                    issueAlert("Download model config failed: ${e.localizedMessage}")
                }
            }

        }
    }

    inner class ModelState(
        val modelConfig: ModelConfig,
        private val modelUrl: String,
        private val modelDirFile: File
    ) {
        var modelInitState = mutableStateOf(ModelInitState.Initializing)
        private var paramsConfig = ParamsConfig(emptyList())
        val progress = mutableStateOf(0)
        val total = mutableStateOf(1)
        val id: UUID = UUID.randomUUID()
        private val remainingTasks = emptySet<DownloadTask>().toMutableSet()
        private val downloadingTasks = emptySet<DownloadTask>().toMutableSet()
        private val maxDownloadTasks = 3
        private val gson = Gson()


        init {
            switchToInitializing()
        }

        private fun switchToInitializing() {
            val paramsConfigFile = File(modelDirFile, ParamsConfigFilename)
            if (paramsConfigFile.exists()) {
                loadParamsConfig()
                switchToIndexing()
            } else {
                downloadParamsConfig()
            }
        }

        private fun loadParamsConfig() {
            val paramsConfigFile = File(modelDirFile, ParamsConfigFilename)
            require(paramsConfigFile.exists())
            val jsonString = paramsConfigFile.readText()
            paramsConfig = gson.fromJson(jsonString, ParamsConfig::class.java)
        }

        private fun downloadParamsConfig() {
            thread(start = true) {
                val url = URL("${modelUrl}${ModelUrlSuffix}${ParamsConfigFilename}")
                val tempId = UUID.randomUUID().toString()
                val tempFile = File(modelDirFile, tempId)
                url.openStream().use {
                    Channels.newChannel(it).use { src ->
                        FileOutputStream(tempFile).use { fileOutputStream ->
                            fileOutputStream.channel.transferFrom(src, 0, Long.MAX_VALUE)
                        }
                    }
                }
                require(tempFile.exists())
                val paramsConfigFile = File(modelDirFile, ParamsConfigFilename)
                tempFile.renameTo(paramsConfigFile)
                require(paramsConfigFile.exists())
                viewModelScope.launch {
                    loadParamsConfig()
                    switchToIndexing()
                }
            }
        }

        fun handleStart() {
            switchToDownloading()
        }

        fun handlePause() {
            switchToPausing()
        }

        fun handleClear() {
            require(
                modelInitState.value == ModelInitState.Downloading ||
                        modelInitState.value == ModelInitState.Paused ||
                        modelInitState.value == ModelInitState.Finished
            )
            switchToClearing()
        }

        private fun switchToClearing() {
            if (modelInitState.value == ModelInitState.Paused) {
                modelInitState.value = ModelInitState.Clearing
                clear()
            } else if (modelInitState.value == ModelInitState.Finished) {
                modelInitState.value = ModelInitState.Clearing
                if (chatState.modelName.value == modelConfig.modelId) {
                    chatState.requestTerminateChat { clear() }
                } else {
                    clear()
                }
            } else {
                modelInitState.value = ModelInitState.Clearing
            }
        }

        fun handleDelete() {
            require(
                modelInitState.value == ModelInitState.Downloading ||
                        modelInitState.value == ModelInitState.Paused ||
                        modelInitState.value == ModelInitState.Finished
            )
            switchToDeleting()
        }

        private fun switchToDeleting() {
            if (modelInitState.value == ModelInitState.Paused) {
                modelInitState.value = ModelInitState.Deleting
                delete()
            } else if (modelInitState.value == ModelInitState.Finished) {
                modelInitState.value = ModelInitState.Deleting
                if (chatState.modelName.value == modelConfig.modelId) {
                    chatState.requestTerminateChat { delete() }
                } else {
                    delete()
                }
            } else {
                modelInitState.value = ModelInitState.Deleting
            }
        }

        private fun switchToIndexing() {
            modelInitState.value = ModelInitState.Indexing
            progress.value = 0
            total.value = modelConfig.tokenizerFiles.size + paramsConfig.paramsRecords.size
            for (tokenizerFilename in modelConfig.tokenizerFiles) {
                val file = File(modelDirFile, tokenizerFilename)
                if (file.exists()) {
                    ++progress.value
                } else {
                    remainingTasks.add(
                        DownloadTask(
                            URL("${modelUrl}${ModelUrlSuffix}${tokenizerFilename}"),
                            file
                        )
                    )
                }
            }
            for (paramsRecord in paramsConfig.paramsRecords) {
                val file = File(modelDirFile, paramsRecord.dataPath)
                if (file.exists()) {
                    ++progress.value
                } else {
                    remainingTasks.add(
                        DownloadTask(
                            URL("${modelUrl}${ModelUrlSuffix}${paramsRecord.dataPath}"),
                            file
                        )
                    )
                }
            }
            if (progress.value < total.value) {
                switchToPaused()
            } else {
                switchToFinished()
            }
        }

        private fun switchToDownloading() {
            modelInitState.value = ModelInitState.Downloading
            for (downloadTask in remainingTasks) {
                if (downloadingTasks.size < maxDownloadTasks) {
                    handleNewDownload(downloadTask)
                } else {
                    return
                }
            }
        }

        private fun handleNewDownload(downloadTask: DownloadTask) {
            require(modelInitState.value == ModelInitState.Downloading)
            require(!downloadingTasks.contains(downloadTask))
            downloadingTasks.add(downloadTask)
            thread(start = true) {
                val tempId = UUID.randomUUID().toString()
                val tempFile = File(modelDirFile, tempId)
                downloadTask.url.openStream().use {
                    Channels.newChannel(it).use { src ->
                        FileOutputStream(tempFile).use { fileOutputStream ->
                            fileOutputStream.channel.transferFrom(src, 0, Long.MAX_VALUE)
                        }
                    }
                }
                require(tempFile.exists())
                tempFile.renameTo(downloadTask.file)
                require(downloadTask.file.exists())
                viewModelScope.launch {
                    handleFinishDownload(downloadTask)
                }
            }
        }

        private fun handleNextDownload() {
            require(modelInitState.value == ModelInitState.Downloading)
            for (downloadTask in remainingTasks) {
                if (!downloadingTasks.contains(downloadTask)) {
                    handleNewDownload(downloadTask)
                    break
                }
            }
        }

        private fun handleFinishDownload(downloadTask: DownloadTask) {
            remainingTasks.remove(downloadTask)
            downloadingTasks.remove(downloadTask)
            ++progress.value
            require(
                modelInitState.value == ModelInitState.Downloading ||
                        modelInitState.value == ModelInitState.Pausing ||
                        modelInitState.value == ModelInitState.Clearing ||
                        modelInitState.value == ModelInitState.Deleting
            )
            if (modelInitState.value == ModelInitState.Downloading) {
                if (remainingTasks.isEmpty()) {
                    if (downloadingTasks.isEmpty()) {
                        switchToFinished()
                    }
                } else {
                    handleNextDownload()
                }
            } else if (modelInitState.value == ModelInitState.Pausing) {
                if (downloadingTasks.isEmpty()) {
                    switchToPaused()
                }
            } else if (modelInitState.value == ModelInitState.Clearing) {
                if (downloadingTasks.isEmpty()) {
                    clear()
                }
            } else if (modelInitState.value == ModelInitState.Deleting) {
                if (downloadingTasks.isEmpty()) {
                    delete()
                }
            }
        }

        private fun clear() {
            val files = modelDirFile.listFiles { dir, name ->
                !(dir == modelDirFile && name == ModelConfigFilename)
            }
            require(files != null)
            for (file in files) {
                file.deleteRecursively()
                require(!file.exists())
            }
            val modelConfigFile = File(modelDirFile, ModelConfigFilename)
            require(modelConfigFile.exists())
            switchToIndexing()
        }

        private fun delete() {
            modelDirFile.deleteRecursively()
            require(!modelDirFile.exists())
            requestDeleteModel(modelConfig.modelId)
        }

        private fun switchToPausing() {
            modelInitState.value = ModelInitState.Pausing
        }

        private fun switchToPaused() {
            modelInitState.value = ModelInitState.Paused
        }


        private fun switchToFinished() {
            modelInitState.value = ModelInitState.Finished
        }

        fun startChat() {
            chatState.requestReloadChat(
                modelConfig,
                modelDirFile.absolutePath,
            )
        }

    }

    inner class ChatState {
        val messages = emptyList<MessageData>().toMutableStateList()
        val report = mutableStateOf("")
        val modelName = mutableStateOf("")
        private var modelChatState = mutableStateOf(ModelChatState.Ready)
            @Synchronized get
            @Synchronized set
        private val engine = MLCEngine()
        private var historyMessages = mutableListOf<ChatCompletionMessage>()
        private var modelLib = ""
        private var modelPath = ""
        private val executorService = Executors.newSingleThreadExecutor()
        private val viewModelScope = CoroutineScope(Dispatchers.Main + Job())
        private var imageUri: Uri? = null
        var onResult: (String) -> Unit = {}
        var context: Context = application
        var summaryCache = context.loadSummaryCache()

        fun mainResetChat() {
            imageUri = null
            executorService.submit {
                callBackend { engine.reset() }
                historyMessages = mutableListOf<ChatCompletionMessage>()
                viewModelScope.launch {
                    clearHistory()
                    switchToReady()
                }
            }
        }

        fun mainResetChatSync() {
            imageUri = null
            callBackend { engine.reset() }
            historyMessages = mutableListOf<ChatCompletionMessage>()
            clearHistory()
            switchToReady()
        }

        private fun clearHistory() {
            messages.clear()
            report.value = ""
            historyMessages.clear()
        }


        private fun switchToResetting() {
            modelChatState.value = ModelChatState.Resetting
        }

        private fun switchToGenerating() {
            modelChatState.value = ModelChatState.Generating
        }

        private fun switchToReloading() {
            modelChatState.value = ModelChatState.Reloading
        }

        private fun switchToReady() {
            modelChatState.value = ModelChatState.Ready
        }

        private fun switchToFailed() {
            modelChatState.value = ModelChatState.Falied
        }

        private fun callBackend(callback: () -> Unit): Boolean {
            try {
                callback()
            } catch (e: Exception) {
                viewModelScope.launch {
                    val stackTrace = e.stackTraceToString()
                    val errorMessage = e.localizedMessage
                    appendMessage(
                        MessageRole.Assistant,
                        "MLCChat failed\n\nStack trace:\n$stackTrace\n\nError message:\n$errorMessage"
                    )
                    switchToFailed()
                }
                return false
            }
            return true
        }

        fun requestResetChat() {
            require(interruptable())
            interruptChat(
                prologue = {
                    switchToResetting()
                },
                epilogue = {
                    mainResetChat()
                }
            )
        }

        private fun interruptChat(prologue: () -> Unit, epilogue: () -> Unit) {
            // prologue runs before interruption
            // epilogue runs after interruption
            require(interruptable())
            if (modelChatState.value == ModelChatState.Ready) {
                prologue()
                epilogue()
            } else if (modelChatState.value == ModelChatState.Generating) {
                prologue()
                executorService.submit {
                    viewModelScope.launch { epilogue() }
                }
            } else {
                require(false)
            }
        }

        fun requestTerminateChat(callback: () -> Unit) {
            require(interruptable())
            interruptChat(
                prologue = {
                    switchToTerminating()
                },
                epilogue = {
                    mainTerminateChat(callback)
                }
            )
        }

        private fun mainTerminateChat(callback: () -> Unit) {
            executorService.submit {
                callBackend { engine.unload() }
                viewModelScope.launch {
                    clearHistory()
                    switchToReady()
                    callback()
                }
            }
        }

        private fun switchToTerminating() {
            modelChatState.value = ModelChatState.Terminating
        }


        fun requestReloadChat(modelConfig: ModelConfig, modelPath: String) {

            if (this.modelName.value == modelConfig.modelId && this.modelLib == modelConfig.modelLib && this.modelPath == modelPath) {
                return
            }
            require(interruptable())
            interruptChat(
                prologue = {
                    switchToReloading()
                },
                epilogue = {
                    mainReloadChat(modelConfig, modelPath)
                }
            )
        }

        private fun mainReloadChat(modelConfig: ModelConfig, modelPath: String) {
            clearHistory()
            this.modelName.value = modelConfig.modelId
            this.modelLib = modelConfig.modelLib
            this.modelPath = modelPath
            executorService.submit {
                viewModelScope.launch {
                    Toast.makeText(application, "Initialize...", Toast.LENGTH_SHORT).show()
                }
                if (!callBackend {
                        engine.unload()
                        engine.reload(modelPath, modelConfig.modelLib)
                    }) return@submit
                viewModelScope.launch {
                    Toast.makeText(application, "Ready to chat", Toast.LENGTH_SHORT).show()
                    switchToReady()
                }
            }
        }

        fun requestImageBitmap(uri: Uri?) {
            require(chatable())
            switchToGenerating()
            executorService.submit {
                imageUri = uri
                viewModelScope.launch {
                    report.value = "Image process is done, ask any question."
                    if (modelChatState.value == ModelChatState.Generating) switchToReady()
                }
            }
        }

        fun bitmapToURL(bm: Bitmap): String {
            val targetSize = 336
            val scaledBitmap = Bitmap.createScaledBitmap(bm, targetSize, targetSize, true)

            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
            scaledBitmap.recycle()

            val imageBytes = outputStream.toByteArray()
            val imageBase64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            return "data:image/jpg;base64,$imageBase64"
        }

        // Generating summary for news
        fun summarizeNews(news: News) {
            require(chatable())
            // mainResetChat()
            mainResetChatSync()
            switchToGenerating()

            // Update UI
            chatState.appendMessage(MessageRole.System, "請為新聞生成摘要：")
            chatState.appendMessage(MessageRole.User, news.content)

            // Check if the summary is cached
            if (summaryCache.containsKey(news.url)) {
                val cachedSummary = summaryCache[news.url] ?: "（讀取快取失敗）"
                println("Loaded summary from cache: $cachedSummary")
                appendMessage(MessageRole.Assistant, cachedSummary)
                onResult(cachedSummary)
                return
            }


            // Prepare prompt
            historyMessages.add(
                ChatCompletionMessage(
                    role = OpenAIProtocol.ChatCompletionRole.system,
                    content = ChatCompletionMessageContent("請為新聞生成摘要：")
                )
            )
            historyMessages.add(
                ChatCompletionMessage(
                    role = OpenAIProtocol.ChatCompletionRole.user,
                    content = ChatCompletionMessageContent(news.content)
                )
            )

            chatState.appendMessage(MessageRole.Assistant, "生成中，請稍候...")

            executorService.submit {
                viewModelScope.launch(Dispatchers.IO) {
                    val responses = engine.chat.completions.create(
                        messages = historyMessages,
                        stream_options = OpenAIProtocol.StreamOptions(include_usage = true)
                    )

                    var summary = ""
                    var finishReasonLength = false
                    var shouldStop = false

                    fun detectRepeats(text: String): Boolean {
                        val sentences = text.split(Regex("(?<=[。！？.?!])"))
                            .map { it.trim() }
                            .filter { it.length > 4 }

                        if (sentences.size < 2) return false

                        val lastSentence = sentences.last()
                        val priorSentences = sentences.dropLast(1)

                        return priorSentences.any { it == lastSentence }
                    }

                    for (res in responses) {
                        if (!callBackend {
                                for (choice in res.choices) {
                                    choice.delta.content?.let { content ->
                                        summary += content.asText()
                                    }
                                    choice.finish_reason?.let { reason ->
                                        if (reason == "length") finishReasonLength = true
                                    }
                                }

                                if (detectRepeats(summary)) {
                                    shouldStop = true
                                }

                                res.usage?.let { usage ->
                                    report.value = usage.extra?.asTextLabel() ?: ""
                                }
                            }) continue

                        if (shouldStop) break
                    }

                    if (summary.isNotEmpty()) {
                        if (finishReasonLength) {
                            summary += " [輸出因長度限制被截斷...]"
                        }

                        // Remove repeated tail if any
                        if (shouldStop) {
                            val sentences = summary.split(Regex("(?<=[。！？.?!])")).map { it.trim() }
                            val deduped = mutableListOf<String>()
                            for (sentence in sentences) {
                                if (deduped.lastOrNull() == sentence) break
                                deduped.add(sentence)
                            }
                            summary = deduped.joinToString("")
                        }

                        summary = ZhConverterUtil.toTraditional(summary)

                        updateMessage(MessageRole.Assistant, summary)

                        historyMessages.add(
                            ChatCompletionMessage(
                                role = OpenAIProtocol.ChatCompletionRole.assistant,
                                content = summary
                            )
                        )

                        // Save to cache
                        summaryCache[news.url] = summary
                        context.saveSummaryCache(summaryCache)

                        switchToReady()
                        onResult(summary)
                    } else {
                        // Remove empty assistant placeholder
                        if (historyMessages.isNotEmpty()) {
                            historyMessages.removeAt(historyMessages.size - 1)
                        }
                        switchToReady()
                        onResult("生成失敗，請稍後再試")
                    }

                    if (modelChatState.value == ModelChatState.Generating) switchToReady()
                }
            }
        }

        fun requestGenerate(prompt: String, activity: Activity) {
            require(chatable())
            switchToGenerating()
            appendMessage(MessageRole.User, prompt)
            appendMessage(MessageRole.Assistant, "")
            var content = ChatCompletionMessageContent(text=prompt)
            if (imageUri != null) {
                val uri = imageUri
                val bitmap = uri?.let {
                    activity.contentResolver.openInputStream(it)?.use { input ->
                        BitmapFactory.decodeStream(input)
                    }
                }
                val imageBase64URL = bitmapToURL(bitmap!!)
                Log.v("requestGenerate", "image base64 url: $imageBase64URL")
                val parts = listOf(
                    mapOf("type" to "text", "text" to prompt),
                    mapOf("type" to "image_url", "image_url" to imageBase64URL)
                )
                content = ChatCompletionMessageContent(parts=parts)
                imageUri = null
            }

            executorService.submit {
                historyMessages.add(ChatCompletionMessage(
                    role = OpenAIProtocol.ChatCompletionRole.user,
                    content = content
                ))

                viewModelScope.launch {
                    val responses = engine.chat.completions.create(
                        messages = historyMessages,
                        stream_options = OpenAIProtocol.StreamOptions(include_usage = true)
                    )

                    var finishReasonLength = false
                    var streamingText = ""

                    for (res in responses) {
                        if (!callBackend {
                            for (choice in res.choices) {
                                choice.delta.content?.let { content ->
                                    streamingText += content.asText()
                                }
                                choice.finish_reason?.let { finishReason ->
                                    if (finishReason == "length") {
                                        finishReasonLength = true
                                    }
                                }
                            }
                            updateMessage(MessageRole.Assistant, streamingText)
                            res.usage?.let { finalUsage ->
                                report.value = finalUsage.extra?.asTextLabel() ?: ""
                            }
                            if (finishReasonLength) {
                                streamingText += " [output truncated due to context length limit...]"
                                updateMessage(MessageRole.Assistant, streamingText)
                            }
                        });
                    }
                    if (streamingText.isNotEmpty()) {
                        historyMessages.add(ChatCompletionMessage(
                            role = OpenAIProtocol.ChatCompletionRole.assistant,
                            content = streamingText
                        ))
                        streamingText = ""
                    } else {
                        if (historyMessages.isNotEmpty()) {
                            historyMessages.removeAt(historyMessages.size - 1)
                        }
                    }

                    if (modelChatState.value == ModelChatState.Generating) switchToReady()
                }
            }
        }

        private fun appendMessage(role: MessageRole, text: String) {
            messages.add(MessageData(role, text))
        }


        private fun updateMessage(role: MessageRole, text: String) {
            messages[messages.size - 1] = MessageData(role, text)
        }

        fun chatable(): Boolean {
            return modelChatState.value == ModelChatState.Ready
        }

        fun interruptable(): Boolean {
            return modelChatState.value == ModelChatState.Ready
                    || modelChatState.value == ModelChatState.Generating
                    || modelChatState.value == ModelChatState.Falied
        }
    }
}

enum class ModelInitState {
    Initializing,
    Indexing,
    Paused,
    Downloading,
    Pausing,
    Clearing,
    Deleting,
    Finished
}

enum class ModelChatState {
    Generating,
    Resetting,
    Reloading,
    Terminating,
    Ready,
    Falied
}

enum class MessageRole {
    System,
    Assistant,
    User
}

data class DownloadTask(val url: URL, val file: File)

data class MessageData(val role: MessageRole, val text: String, val id: UUID = UUID.randomUUID(), var imageUri: Uri? = null)

data class AppConfig(
    @SerializedName("model_libs") var modelLibs: MutableList<String>,
    @SerializedName("model_list") val modelList: MutableList<ModelRecord>,
)

data class ModelRecord(
    @SerializedName("model_url") val modelUrl: String,
    @SerializedName("model_id") val modelId: String,
    @SerializedName("estimated_vram_bytes") val estimatedVramBytes: Long?,
    @SerializedName("model_lib") val modelLib: String
)

data class ModelConfig(
    @SerializedName("model_lib") var modelLib: String,
    @SerializedName("model_id") var modelId: String,
    @SerializedName("estimated_vram_bytes") var estimatedVramBytes: Long?,
    @SerializedName("tokenizer_files") val tokenizerFiles: List<String>,
    @SerializedName("context_window_size") val contextWindowSize: Int,
    @SerializedName("prefill_chunk_size") val prefillChunkSize: Int,
)

data class ParamsRecord(
    @SerializedName("dataPath") val dataPath: String
)

data class ParamsConfig(
    @SerializedName("records") val paramsRecords: List<ParamsRecord>
)

data class NewsResponse(
    val state: Boolean,
    val page: String,
    val end: Boolean,
    val lists: List<NewsItem>
)

data class NewsItem(
    val url: String?,
    val titleLink: String?,
    val title: String?,
    val paragraph: String?,
    val time: NewsTime?,
    val view: Int?,
    val content_level: String?,
    val story_list: String?
)

@Serializable
data class Headline(
    val title: String,
    val url: String,
    val imageUrl: String,
    val paragraph: String,
    val time: String,
    val viewCount: Int,
    val contentLevel: String,
    val storyList: String
)

@Serializable
data class News(
    val url: String,
    val title: String,
    val time: String,
    val content: String
)

@Serializable
data class NewsTime(
    val date: String
)

class NewsService(val context: Context) {
    private val newsWebsiteUrl = "https://udn.com/api/more"
    private val timeout = 10L
    private val client = OkHttpClient.Builder()
        .connectTimeout(timeout, TimeUnit.SECONDS)
        .readTimeout(timeout, TimeUnit.SECONDS)
        .writeTimeout(timeout, TimeUnit.SECONDS)
        .build()
    
    private val json = Json { 
        ignoreUnknownKeys = true 
        isLenient = true
    }
    
    var newsCache = context.loadNewsCache()

    suspend fun fetchNews(n: Int, page: Int = 1): List<News> = withContext(Dispatchers.IO) {
        val headlines = mutableListOf<Headline>()
        var _page = page
        
        // Fetch headlines until we have at least n items
        while (headlines.size < n) {
            try {
                val pageHeadlines = fetchBreakNewsHeadlines(_page)
                if (pageHeadlines.isEmpty()) break
                headlines.addAll(pageHeadlines)
                _page++
            } catch (e: Exception) {
                println("Error fetching headlines for page $_page: ${e.message}")
                break
            }
        }
        
        // Take only the first n headlines and parse them
        val selectedHeadlines = headlines.take(n)
        val newsList = mutableListOf<News>()
        
        for (headline in selectedHeadlines) {
            try {
                val news = parseNews(headline.url)
                news?.let { newsList.add(it) }
            } catch (e: Exception) {
                println("Error parsing news from ${headline.url}: ${e.message}")
            }
        }
        
        newsList
    }

    suspend fun searchNews(searchTerm: String, n: Int, page: Int = 1): List<News> = withContext(Dispatchers.IO) {
        val headlines = mutableListOf<Headline>()
        var _page = page
        
        // Fetch headlines until we have at least n items
        while (headlines.size < n) {
            try {
                println("Fetching search results for term: $searchTerm, page: $_page")
                val pageHeadlines = fetchSearchHeadlines(searchTerm, _page)
                if (pageHeadlines.isEmpty()) {
                    println("No more search results found for term: $searchTerm on page $_page")
                    break
                }
                headlines.addAll(pageHeadlines)
                _page++
            } catch (e: Exception) {
                println("Error fetching search results for page $_page: ${e.message}")
                break
            }
        }
        
        // Take only the first n headlines and parse them
        val selectedHeadlines = headlines.take(n)
        val newsList = mutableListOf<News>()

        println("Selected ${selectedHeadlines.size} headlines for parsing")
        
        for (headline in selectedHeadlines) {
            try {
                val news = parseNews(headline.url)
                news?.let { newsList.add(it) }
            } catch (e: Exception) {
                println("Error parsing news from ${headline.url}: ${e.message}")
            }
        }
        
        newsList
    }

    private suspend fun performRequest(params: Map<String, String>? = null): Response = withContext(Dispatchers.IO) {
        val urlBuilder = newsWebsiteUrl.toHttpUrlOrNull()?.newBuilder()
            ?: throw IOException("Invalid URL: $newsWebsiteUrl")
        
        params?.forEach { (key, value) ->
            urlBuilder.addQueryParameter(key, value)
        }
        
        val url = urlBuilder.build()
        println("Performing request to URL: $url with params: $params")
        
        val request = Request.Builder()
            .url(url)
            .build()
        
        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val code = response.code
                throw IOException("Request failed with code: $code")
            }
            response
        } catch (e: IOException) {
            println("Request failed: ${e.message}")
            delay(3000) // Wait 3 seconds before retry
            performRequest(params) // Retry
        }
    }

    private suspend fun fetchSearchHeadlines(searchTerm: String, page: Int = 1): List<Headline> {
        println("Fetching search headlines for term: $searchTerm, page: $page")
        val params = createSearchParams(page, searchTerm)
        println("Search params: $params")
        val response = performRequest(params)
        return parseHeadlines(response)
    }

    private suspend fun fetchBreakNewsHeadlines(page: Int): List<Headline> {
        val params = createNextpageParam(page)
        val response = performRequest(params)
        return parseHeadlines(response)
    }

    private fun createNextpageParam(page: Int): Map<String, String> {
        return mapOf(
            "page" to page.toString(),
            "id" to "nextpage",
            "channelId" to "1",
            "type" to "breaknews"
        )
    }

    private fun createSearchParams(page: Int, searchTerm: String): Map<String, String> {
        return mapOf(
            "page" to page.toString(),
            "id" to "search:$searchTerm",
            "channelId" to "2",
            "type" to "searchword"
        )
    }

    private fun parseHeadlines(response: Response): List<Headline> {
        return try {
            val jsonString = response.body?.string() ?: return emptyList()
            val gson = Gson()
            val newsResponse = gson.fromJson(jsonString, NewsResponse::class.java)
            
            newsResponse.lists.map { item ->
                Headline(
                    title = item.title ?: "",
                    url = if (item.titleLink?.startsWith("http") == true) item.titleLink else "https://udn.com${item.titleLink ?: ""}",
                    imageUrl = item.url ?: "",
                    paragraph = item.paragraph ?: "",
                    time = item.time?.date ?: "",
                    viewCount = item.view ?: 0,
                    contentLevel = item.content_level ?: "",
                    storyList = item.story_list ?: ""
                )
            }
        } catch (e: Exception) {
            println("Failed to parse JSON response: ${e.message}")
            emptyList()
        }
    }

    private suspend fun request(url: String, params: Map<String, String>? = null): Response = withContext(Dispatchers.IO) {
        val urlBuilder = url.toHttpUrlOrNull()?.newBuilder()
            ?: throw IOException("Invalid URL: $url")
        
        params?.forEach { (key, value) ->
            urlBuilder.addQueryParameter(key, value)
        }
        
        val finalUrl = urlBuilder.build()
        val request = Request.Builder()
            .url(finalUrl)
            .build()
        
        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val code = response.code
                throw IOException("Request failed with code: $code")
            }
            response
        } catch (e: IOException) {
            println("Request failed: ${e.message}")
            throw IOException("Request failed", e)
        }
    }

    suspend fun parseNews(url: String): News? = withContext(Dispatchers.IO) {
        // if cached news exists, return it
        if (newsCache.containsKey(url)) {
            println("Loaded news from cache: $url")
            return@withContext newsCache[url]
        }
        try {
            val response = makeRequest(url)
            val body = response.body ?: return@withContext null
            val html = body.string()
            val soup: Document = Jsoup.parse(html, url)
            val news = extractNews(soup, url)
            if (news != null) {
                println("Parsed news article from URL: $url")
                newsCache[url] = news // save to cache
                context.saveNewsCache(newsCache) // persist cache
            }
            news
        } catch (e: Exception) {
            println("Error parsing news from $url: ${e.message}")
            null
        }
    }

    private fun makeRequest(url: String): okhttp3.Response {
        val request = Request.Builder().url(url).build()
        return client.newCall(request).execute()
    }

    private fun extractNews(soup: Document, url: String): News? {
        return try {
            val articleTitle = soup.selectFirst("h1.article-content__title")?.text()
                ?: return null
            
            val time = soup.selectFirst("time.article-content__time")?.text()
                ?: return null
            
            val contentSection = soup.selectFirst("section.article-content__editor")
                ?: return null
            
            val paragraphs = contentSection.select("p")
            val content = paragraphs
                .map { it.text() }
                .filter { it.trim().isNotEmpty() && !it.contains("▪") }
                .joinToString(" ")
            
            News(
                url = url,
                title = articleTitle,
                time = time,
                content = content
            )
        } catch (e: Exception) {
            println("Failed to extract news from: $url\n${e.message}")
            null
        }
    }
}

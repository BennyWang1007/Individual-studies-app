package ai.mlc.mlcchat

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsScreen(
    navController: NavController, viewModel: AppViewModel = viewModel()
) {
    val context = LocalContext.current
    var isSearching by remember { mutableStateOf(false) }
    var isSearched by remember { mutableStateOf(false) }
    var keyword by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        Toast.makeText(context,"Fetching latest news...", Toast.LENGTH_SHORT).show()
        viewModel.fetchNews()
    }

    val newsList by viewModel.newsItems

    fun clickOnNews(news: News) {
        // Toast.makeText(context, "Clicked on: ${news.title}", Toast.LENGTH_SHORT).show()
        // println("Goto chat with model: ${viewModel.chatState.modelName}")
        val _summarization: String = viewModel.summarizeNews(news)
        navController.navigate("chat")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearching) {
                        OutlinedTextField(
                            value = keyword,
                            onValueChange = { keyword = it },
                            singleLine = true,
                            placeholder = { Text("搜尋新聞...") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text("最新新聞")
                    }
                },
                actions = {
                    if (!isSearching) {
                        IconButton(onClick = { isSearching = true }) {
                            Icon(Icons.Outlined.Search, contentDescription = "Search")
                        }
                    } else {
                        IconButton(onClick = {
                            if (keyword.isBlank()) {
                                viewModel.fetchNews()
                            } else {
                                viewModel.searchNews(keyword)
                                isSearched = true
                            }
                            isSearching = false
                            keyword = ""
                        }) {
                            Icon(Icons.Outlined.ArrowForward, contentDescription = "Submit search")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).padding(16.dp)) {
            if (newsList.isEmpty()) {
                if (isSearched) {
                    Text("沒有找到相關新聞", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("載入中...", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                newsList.forEach { news ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { clickOnNews(news) },
                        elevation = CardDefaults.cardElevation(4.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(news.title, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(news.content.take(50) + "...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

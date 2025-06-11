package ai.mlc.mlcchat

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsScreen(
    navController: NavController, viewModel: AppViewModel = viewModel()
) {
    val context = LocalContext.current
    val cachedNews by produceState<List<News>>(initialValue = emptyList()) {
        value = context.loadNewsCache().values.toList()
    }
    var isSearching by remember { mutableStateOf(false) }
    var isSearched by remember { mutableStateOf(false) }
    var keyword by remember { mutableStateOf("") }

    val newsList by viewModel.newsItems
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Only fetch news after cached news loaded
    LaunchedEffect(cachedNews) {
        if (cachedNews.isNotEmpty() && newsList.isEmpty()) {
            // Show cached news first
            viewModel.loadCachedNews(cachedNews)
        }
        Toast.makeText(context, "Fetching latest news...", Toast.LENGTH_SHORT).show()
        viewModel.fetchNews()  // fetch latest news after cache shown
    }

    // Detect when near bottom to load more
    LaunchedEffect(newsList.size, listState.firstVisibleItemIndex, listState.layoutInfo.totalItemsCount) {
        val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        val totalItems = listState.layoutInfo.totalItemsCount
        if (lastVisibleIndex >= totalItems - 2 && !viewModel.isLoading.value) {
            // Near bottom
            viewModel.fetchNews()
        }
    }

    fun clickOnNews(news: News) {
        viewModel.summarizeNews(news)
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
                    // Chat button
                    IconButton(onClick = {
                        navController.navigate("chat")
                    }) {
                        Icon(Icons.Outlined.Chat, contentDescription = "Go to Chat")
                    }

                    // Search logic
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
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Outlined.List, contentDescription = "News") },
                    label = { Text("新聞") },
                    selected = true,
                    onClick = { navController.navigate("news") }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Outlined.History, contentDescription = "History") },
                    label = { Text("歷史") },
                    selected = false,
                    onClick = { navController.navigate("history") }
                )
            }
        }
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            if (newsList.isEmpty()) {
                item {
                    Text(
                        text = if (isSearched) "沒有找到相關新聞" else "載入中...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                items(newsList) { news ->
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
                if (viewModel.isLoading.value) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}
package ai.mlc.mlcchat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.History
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(navController: NavController) {
    val context = LocalContext.current

    // Load news cache asynchronously
    val newsCache by produceState<Map<String, News>>(initialValue = emptyMap()) {
        value = context.loadNewsCache()
    }

    // Load summary cache asynchronously
    val summaryCache by produceState<Map<String, String>>(initialValue = emptyMap()) {
        value = context.loadSummaryCache()
    }
    val expandedStates = remember { mutableStateMapOf<String, Boolean>() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("歷史摘要") }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Outlined.List, contentDescription = "News") },
                    label = { Text("新聞") },
                    selected = false,
                    onClick = { navController.navigate("news") }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Outlined.History, contentDescription = "History") },
                    label = { Text("歷史") },
                    selected = true,
                    onClick = { navController.navigate("history") }
                )
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            if (summaryCache.isEmpty()) {
                item {
                    Text("尚無歷史摘要", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                summaryCache.forEach { (newsId, summary) ->
                    // remove 新聞摘要：\n in the front if it exists
                    val cleanedSummary = summary.removePrefix("新聞摘要：\n").trim()
                    val news = newsCache[newsId]
                    if (news != null) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        val currentlyExpanded = expandedStates[newsId] ?: false
                                        if (!currentlyExpanded) {
                                            expandedStates[newsId] = true
                                        } else {
                                            navController.currentBackStackEntry?.savedStateHandle?.apply {
                                                set("title", news.title)
                                                set("time", news.time)
                                                set("content", news.content)
                                                set("summary", cleanedSummary)
                                            }
                                            navController.navigate("history_detail")
                                        }
                                    },
                                elevation = CardDefaults.cardElevation(4.dp)
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(news.title, style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.height(4.dp))
                                    val expanded = expandedStates[newsId] == true
                                    val previewText = if (expanded) "摘要：" + cleanedSummary else "摘要：" + cleanedSummary.take(50) + "..."
                                    Text(previewText, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

package ai.mlc.mlcchat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryDetailScreen(navController: NavController) {
    val title = navController.previousBackStackEntry?.savedStateHandle?.get<String>("title") ?: "未知標題"
    val time = navController.previousBackStackEntry?.savedStateHandle?.get<String>("time") ?: "未知時間"
    val content = navController.previousBackStackEntry?.savedStateHandle?.get<String>("content") ?: "無內容"
    val summary = navController.previousBackStackEntry?.savedStateHandle?.get<String>("summary") ?: "無摘要"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("詳細內容") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = time, style = MaterialTheme.typography.labelSmall)
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = content, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "摘要：", style = MaterialTheme.typography.titleMedium)
            Text(text = summary, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

package dev.forgeai.ui.navigation

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.forgeai.core.ForgeViewModel
import dev.forgeai.intelligence.ChatViewModel
import dev.forgeai.ui.screens.AccessScreen
import dev.forgeai.ui.screens.ChatScreen
import dev.forgeai.ui.screens.DashboardScreen
import dev.forgeai.ui.screens.ModelsScreen
import dev.forgeai.ui.screens.WorkspaceScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ForgeApp(viewModel: ForgeViewModel, chat: ChatViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var route by rememberSaveable { mutableStateOf(Destination.DASHBOARD) }
    var pickerError by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.register(uri)
    }
    BackHandler(route != Destination.DASHBOARD) { route = Destination.DASHBOARD }
    if (pickerError) AlertDialog(onDismissRequest = { pickerError = false },
        title = { Text("File picker unavailable") }, text = { Text("There is no Android document picker on this device.") },
        confirmButton = { TextButton(onClick = { pickerError = false }) { Text("OK") } })
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 10.dp)) {
            Text("FORGE AI", color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text("LOCAL WORKSTATION  /  0.2.0", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
        }
    }, bottomBar = {
        PrimaryTabRow(selectedTabIndex = route.ordinal) {
            Destination.entries.forEach { tab ->
                Tab(selected = route == tab, onClick = { route = tab }, text = { Text(tab.title, maxLines = 1) })
            }
        }
    }) { padding ->
        val content = Modifier.padding(padding).fillMaxSize()
        when (route) {
            Destination.DASHBOARD -> DashboardScreen(state, { route = Destination.MODELS }, { route = Destination.CHAT }, content)
            Destination.MODELS -> ModelsScreen(state, onImport = {
                try { picker.launch(arrayOf("*/*")) } catch (_: ActivityNotFoundException) { pickerError = true }
            }, onRefresh = viewModel::refresh, onPrefer = viewModel::prefer, onRemove = viewModel::remove,
                clearMessage = viewModel::clearMessage, modifier = content)
            Destination.ACCESS -> AccessScreen(state, { route = Destination.MODELS }, content)
            Destination.CHAT -> ChatScreen(state.models.firstOrNull { it.selected }, chat, content)
            Destination.WORKSPACE -> WorkspaceScreen(content) { excerpt ->
                chat.setWorkspaceDraft(excerpt)
                route = Destination.CHAT
            }
        }
    }
}

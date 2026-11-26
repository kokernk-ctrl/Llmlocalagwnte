package dev.forgeai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import android.text.format.Formatter
import dev.forgeai.core.RegistryState
import dev.forgeai.models.ModelAvailability
import dev.forgeai.models.ModelEntry

@Composable internal fun ModelsScreen(
    state: RegistryState, onImport: () -> Unit, onRefresh: () -> Unit, onPrefer: (Long) -> Unit,
    onRemove: (Long) -> Unit, clearMessage: () -> Unit, modifier: Modifier,
) {
    var pendingRemoval by rememberSaveable { mutableStateOf<Long?>(null) }
    val removing = state.models.firstOrNull { it.id == pendingRemoval }
    if (removing != null) AlertDialog(onDismissRequest = { pendingRemoval = null },
        title = { Text("Remove registration?") },
        text = { Text("FORGE AI will release its access to ${removing.name}. The original file will not be deleted.") },
        dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = { pendingRemoval = null; onRemove(removing.id) }, enabled = state.busy == null) { Text("Remove") } })
    LazyColumn(modifier, contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            PageHeading("Model Manager", "Register local GGUF files. Import stores a read-only reference, not a copy.")
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onImport, enabled = state.busy == null) { Text("Import GGUF") }
                OutlinedButton(onClick = onRefresh, enabled = state.busy == null) { Text("Refresh") }
            }
        }
        state.busy?.let { busy -> item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(busy, style = MaterialTheme.typography.labelLarge)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        } }
        (state.error ?: state.notice)?.let { message -> item {
            val isError = state.error != null
            Card(colors = CardDefaults.cardColors(containerColor = if (isError) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(if (isError) "Operation failed" else "Registry updated", style = MaterialTheme.typography.titleSmall)
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = clearMessage) { Text("Dismiss") }
                }
            }
        } }
        if (state.models.isEmpty() && state.busy == null) item {
            Panel("No models registered", "Choose a local .gguf document to inspect its metadata.")
        }
        items(state.models, key = { it.id }) { entry ->
            ModelCard(entry, state.busy == null, { onPrefer(entry.id) }, { pendingRemoval = entry.id })
        }
        item { Panel("4 GB device profile", "The registry does not load weights. For future inference, start with a small quantized model and a conservative context; file size alone does not establish memory fit.") }
    }
}

@Composable private fun ModelCard(entry: ModelEntry, enabled: Boolean, prefer: () -> Unit, remove: () -> Unit) {
    val sizeLabel = Formatter.formatShortFileSize(LocalContext.current, entry.size)
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(entry.name, style = MaterialTheme.typography.titleMedium)
            Text("${entry.metadata.architecture} · ${entry.metadata.quantization} · $sizeLabel",
                style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            Text("GGUF v${entry.metadata.version} · ${entry.metadata.tensorCount} tensors",
                style = MaterialTheme.typography.labelMedium)
            Text(entry.availability.description + if (entry.selected) " · Preferred" else "",
                color = if (entry.availability == ModelAvailability.AVAILABLE) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!entry.selected && entry.availability == ModelAvailability.AVAILABLE) {
                    TextButton(onClick = prefer, enabled = enabled) { Text("Set preferred") }
                }
                TextButton(onClick = remove, enabled = enabled) { Text("Remove") }
            }
        }
    }
}

package dev.forgeai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.forgeai.intelligence.ChatViewModel
import dev.forgeai.intelligence.ChatProtocol
import dev.forgeai.intelligence.Phase
import dev.forgeai.intelligence.MAX_MODEL_BYTES
import dev.forgeai.models.ModelAvailability
import dev.forgeai.models.ModelEntry

@Composable
internal fun ChatScreen(model: ModelEntry?, viewModel: ChatViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val focus = LocalFocusManager.current
    DisposableEffect(viewModel, owner, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.unload()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); viewModel.unload() }
    }

    val blocker = when {
        model?.selected != true -> "Import a GGUF in Models and mark it preferred to start a chat."
        model.availability != ModelAvailability.AVAILABLE -> "Preferred model: ${model.availability.description}. Check it in Models."
        model.metadata.architecture != "qwen2" -> "Chat supports Qwen2 and Qwen2.5 instruct GGUFs, not ${model.metadata.architecture}."
        model.size > MAX_MODEL_BYTES -> "This model exceeds the 1.5 GiB limit. Select a smaller quantized GGUF."
        else -> null
    }
    val list = rememberLazyListState()
    LaunchedEffect(state.turns.size, state.turns.lastOrNull()?.answer?.length) {
        if (state.turns.isNotEmpty()) list.scrollToItem(state.turns.size)
    }
    Column(modifier.imePadding().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Local chat", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            Text(if (state.phase == Phase.COPYING) "${state.phase.title} ${state.percent}%" else state.phase.title,
                style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary)
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(model?.name ?: "No preferred model", style = MaterialTheme.typography.titleSmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (state.phase == Phase.EMPTY) {
                            Text(blocker ?: "Qwen ChatML · Text only. Loading makes a temporary private copy. Unloading clears this chat.",
                                style = MaterialTheme.typography.bodySmall)
                            if (blocker == null) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Context", style = MaterialTheme.typography.labelMedium)
                                    ChatProtocol.contexts.forEach { n ->
                                        FilterChip(selected = state.context == n, onClick = { viewModel.setContext(n) },
                                            label = { Text(n.toString()) })
                                    }
                                }
                                Button(onClick = { model?.let(viewModel::load) }) { Text("Load model") }
                            }
                        } else {
                            Text("${state.context} context · 128 reply tokens · Offline", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            itemsIndexed(state.turns) { _, turn ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MessageBubble("You", turn.user)
                    MessageBubble("Model", turn.answer.ifBlank {
                        if (state.phase == Phase.RUNNING && state.turns.last() === turn) "Generating…" else "No text returned."
                    })
                }
            }
        }
        state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        if (state.phase == Phase.COPYING || state.phase == Phase.LOADING || state.phase == Phase.UNLOADING)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        OutlinedTextField(value = draft, onValueChange = viewModel::editDraft, modifier = Modifier.fillMaxWidth(),
            maxLines = 3, label = { Text("Message") }, placeholder = { Text("Message the local model") })
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(enabled = state.phase == Phase.READY && draft.isNotBlank(), onClick = {
                if (viewModel.send(draft)) focus.clearFocus()
            }) { Text("Send") }
            when (state.phase) {
                Phase.RUNNING, Phase.STOPPING -> OutlinedButton(onClick = viewModel::stop,
                    enabled = state.phase == Phase.RUNNING) { Text("Stop") }
                else -> Unit
            }
            if (state.phase != Phase.EMPTY) TextButton(enabled = state.phase != Phase.UNLOADING,
                onClick = { viewModel.unload() }) {
                Text(if (state.phase in listOf(Phase.COPYING, Phase.LOADING)) "Cancel load" else "Unload")
            }
        }
    }
}

@Composable
private fun MessageBubble(label: String, text: String) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small,
        color = if (label == "You") MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

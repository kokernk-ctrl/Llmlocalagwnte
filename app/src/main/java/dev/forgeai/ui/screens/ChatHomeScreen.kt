package dev.forgeai.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.forgeai.agent.AgentMode
import dev.forgeai.agent.CheckOutcome
import dev.forgeai.agent.ChatAgentViewModel
import dev.forgeai.agent.Tl
import dev.forgeai.agent.WriteOutcome
import dev.forgeai.intelligence.ChatViewModel
import dev.forgeai.intelligence.Phase
import dev.forgeai.models.ModelAvailability
import dev.forgeai.models.ModelEntry

/** Folder picker that asks for persistable read (+write when authorized) grants. */
private class ChatGrantTree(private val write: Boolean) : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?) = super.createIntent(context, input).apply {
        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        putExtra(Intent.EXTRA_LOCAL_ONLY, true)
    }
}

/**
 * THE app screen: one conversation with the agent. The model, the folder and the edit
 * mode live in the header; every real action (reads, writes, diffs, checks, approvals,
 * errors) appears in the timeline. Shell/Python/Files are secondary tools, not tabs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatHomeScreen(
    model: ModelEntry?,
    chat: ChatViewModel,
    agent: ChatAgentViewModel,
    onOpenModels: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenShell: () -> Unit,
    onOpenPython: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val engineState by agent.state.collectAsStateWithLifecycle()
    val chatState by chat.state.collectAsStateWithLifecycle()
    val grants by agent.folderGrants.collectAsStateWithLifecycle()
    val mode by agent.mode.collectAsStateWithLifecycle()
    val selectedFolder by agent.selectedFolder.collectAsStateWithLifecycle()
    val draft by agent.draft.collectAsStateWithLifecycle()
    var consentWrite by rememberSaveable { mutableStateOf(false) }
    var pickWrite by rememberSaveable { mutableStateOf(false) }
    var showTools by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }

    val grant = grants.firstOrNull { it.root.toString() == selectedFolder } ?: grants.firstOrNull()
    val busy = engineState.phase.let { !it.terminal && it != dev.forgeai.agent.RunPhase.IDLE }

    // Grants change in the Files tab; refresh whenever the chat becomes visible again.
    LaunchedEffect(Unit) { agent.refresh() }

    val readPicker = rememberLauncherForActivityResult(remember { ChatGrantTree(false) }) { uri ->
        if (uri != null) agent.grantPicked(uri, false)
    }
    val writePicker = rememberLauncherForActivityResult(remember { ChatGrantTree(true) }) { uri ->
        val asked = pickWrite; pickWrite = false; consentWrite = false
        if (uri != null && asked) agent.grantPicked(uri, true)
    }

    Column(modifier.fillMaxSize()) {
        Header(model, chatState.phase, grant, mode, busy,
            onLoad = { model?.let(chat::load) }, onUnload = { chat.unload() },
            onPickFolder = { read -> if (read) { try { readPicker.launch(null) } catch (_: Exception) {} }
                else { pickWrite = true; try { writePicker.launch(null) } catch (_: Exception) { pickWrite = false } } },
            onSwitchMode = { if (!busy) agent.setMode(if (mode == AgentMode.REVIEW) AgentMode.AUTO else AgentMode.REVIEW) },
            onTools = { showTools = true }, onHistory = { showHistory = true },
            consentWrite, { consentWrite = it }, onOpenModels)

        val list = rememberLazyListState()
        LaunchedEffect(engineState.items.size) {
            if (engineState.items.isNotEmpty()) list.animateScrollToItem(engineState.items.size - 1)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            state = list, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            if (engineState.items.isEmpty()) {
                item { EmptyConversation(model != null, grant != null) }
            }
            itemsIndexed(engineState.items) { _, item ->
                TimelineCard(item, busy, onApprove = agent::approve, onRefuse = agent::refuse)
            }
        }

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp))

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = draft, onValueChange = agent::editDraft,
                modifier = Modifier.weight(1f), maxLines = 4,
                label = { Text("Message the agent") },
                placeholder = { Text("e.g. read the README, create notes and verify both") })
            if (busy) {
                OutlinedButton(onClick = agent::cancel) { Text("Cancel") }
            } else {
                Button(enabled = draft.isNotBlank(), onClick = { agent.send(draft) }) { Text("Send") }
            }
        }
    }

    if (showTools) {
        ModalBottomSheet(onDismissRequest = { showTools = false }) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Secondary tools", style = MaterialTheme.typography.titleMedium)
                Text("The agent already uses these inside the conversation. The screens below are manual controls.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { showTools = false; onOpenShell() }, modifier = Modifier.fillMaxWidth()) { Text("Open Shell") }
                Button(onClick = { showTools = false; onOpenPython() }, modifier = Modifier.fillMaxWidth()) { Text("Open Python") }
                TextButton(onClick = { showTools = false; onOpenFiles() }, modifier = Modifier.fillMaxWidth()) { Text("Files & editor") }
                TextButton(onClick = { showTools = false; onOpenModels() }, modifier = Modifier.fillMaxWidth()) { Text("Models") }
                Spacer(Modifier.height(18.dp))
            }
        }
    }

    if (showHistory) {
        val saved = remember { agent.savedTasks() }
        ModalBottomSheet(onDismissRequest = { showHistory = false }) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Previous conversations", style = MaterialTheme.typography.titleMedium)
                if (saved.isEmpty()) Text("Nothing saved yet.", style = MaterialTheme.typography.bodySmall)
                saved.take(8).forEach { task ->
                    val st = task.state
                    val label = when {
                        st.interruptedLike -> "INTERRUPTED — not complete"
                        st.phase == dev.forgeai.agent.RunPhase.DONE -> "finished"
                        st.phase == dev.forgeai.agent.RunPhase.FAILED -> "failed"
                        st.phase == dev.forgeai.agent.RunPhase.CANCELLED -> "cancelled"
                        else -> st.phase.label
                    }
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("#${task.id % 100000} · $label", style = MaterialTheme.typography.labelMedium,
                                color = if (st.interruptedLike) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.primary)
                            Text((st.task?.objective ?: "").take(90), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(onClick = { agent.showCheckpoint(task.id); showHistory = false }) { Text("View") }
                                TextButton(onClick = { agent.deleteTask(task.id); showHistory = false }) { Text("Delete") }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

@Composable
private fun Header(
    model: ModelEntry?, phase: Phase, grant: dev.forgeai.workspace.WorkspaceAccess.Grant?,
    mode: AgentMode, busy: Boolean,
    onLoad: () -> Unit, onUnload: () -> Unit, onPickFolder: (Boolean) -> Unit,
    onSwitchMode: () -> Unit, onTools: () -> Unit, onHistory: () -> Unit,
    consentWrite: Boolean, onConsent: (Boolean) -> Unit, onOpenModels: () -> Unit,
) {
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("FORGE AI", color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onHistory) { Text("History") }
                TextButton(onClick = onTools) { Text("Tools") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = onOpenModels, label = {
                    Text((model?.name?.take(18) ?: "no model") + when (phase) {
                        Phase.READY -> " ·on"
                        Phase.EMPTY -> ""
                        else -> " ·" + phase.title.lowercase()
                    }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                })
                AssistChip(onClick = { onPickFolder(false) }, label = {
                    Text((grant?.name?.take(16) ?: "no folder") +
                        (if (grant?.writable == true) " ·rw" else if (grant != null) " ·ro" else ""),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                })
                AssistChip(onClick = onSwitchMode, enabled = !busy, label = {
                    Text(if (mode == AgentMode.REVIEW) "mode: review" else "mode: auto")
                })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when (phase) {
                    Phase.EMPTY -> {
                        if (model?.selected == true && model.availability == ModelAvailability.AVAILABLE) {
                            Button(onClick = onLoad, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Load model") }
                        } else {
                            Text("Import a Qwen2/Qwen2.5 GGUF in Models to drive the agent.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Phase.READY -> TextButton(onClick = onUnload) { Text("Unload model") }
                    else -> Text(phase.title, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.weight(1f))
                if (grant == null || !grant.writable) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (grant == null) {
                            TextButton(onClick = { onPickFolder(false) }) { Text("Add folder (read)") }
                        } else {
                            Checkbox(checked = consentWrite, onCheckedChange = onConsent)
                            TextButton(enabled = consentWrite, onClick = { onPickFolder(true) }) {
                                Text("Grant write")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyConversation(hasModel: Boolean, hasFolder: Boolean) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("One conversation, real work", style = MaterialTheme.typography.titleMedium)
            Text("Write a task in plain language. The agent plans, calls tools and verifies inside this chat: " +
                "it lists, reads, creates and edits files in the folder you authorize, runs local checks and " +
                "can use the bundled Python. Proposed changes appear as diffs; in review mode you approve " +
                "each one here before anything is written.",
                style = MaterialTheme.typography.bodySmall)
            Text(when {
                !hasModel && !hasFolder -> "Status: no model loaded (deterministic plans will be used) and no folder selected (file tools disabled)."
                !hasModel -> "Status: no model loaded — the agent will use a labeled deterministic plan."
                !hasFolder -> "Status: no folder selected — file tools are disabled for now."
                else -> "Status: model ${if (hasModel) "ready" else "off"} · folder ready. Send a task to start."
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TimelineCard(item: Tl, busy: Boolean, onApprove: (Int) -> Unit, onRefuse: (Int) -> Unit) {
    when (item) {
        is Tl.Objective -> MessageBubble("You", item.text)
        is Tl.AgentText -> MessageBubble("Agent", item.text)
        is Tl.PlanNote -> OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Plan · ${item.source}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Text(item.text, style = MaterialTheme.typography.bodySmall)
            }
        }
        is Tl.Info -> Text(item.text, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
        is Tl.Problem -> Text(item.text, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 4.dp))
        is Tl.Action -> ActionCard(item)
        is Tl.Write -> WriteCard(item)
        is Tl.Approval -> ApprovalCard(item, busy, onApprove, onRefuse)
        is Tl.Check -> CheckCard(item)
        is Tl.FinalReport -> OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Task report", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
                Text(item.text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun MessageBubble(label: String, text: String) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small,
        color = if (label == "You") MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ActionCard(item: Tl.Action) {
    var open by rememberSaveable(item.id) { mutableStateOf(false) }
    val color = when (item.outcome) {
        dev.forgeai.agent.ActionOutcome.OK -> MaterialTheme.colorScheme.primary
        dev.forgeai.agent.ActionOutcome.BLOCKED -> MaterialTheme.colorScheme.tertiary
        dev.forgeai.agent.ActionOutcome.NOT_RUN -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.error
    }
    OutlinedCard(Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${item.index}. ${item.tool}", style = MaterialTheme.typography.titleSmall, color = color)
                Spacer(Modifier.weight(1f))
                Text(item.outcome.label, style = MaterialTheme.typography.labelMedium, color = color)
            }
            if (item.argsSummary.isNotBlank())
                Text(item.argsSummary, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(item.summary, style = MaterialTheme.typography.bodySmall)
            if (item.detail.isNotBlank()) {
                TextButton(onClick = { open = !open }) { Text(if (open) "Hide details" else "Details") }
                if (open) Text(item.detail, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()))
            }
        }
    }
}

@Composable
private fun WriteCard(item: Tl.Write) {
    var open by rememberSaveable(item.id) { mutableStateOf(false) }
    val color = when (item.outcome) {
        WriteOutcome.VERIFIED, WriteOutcome.SAVED, WriteOutcome.RUN_OK -> MaterialTheme.colorScheme.primary
        WriteOutcome.PROPOSED -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    OutlinedCard(Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val title = if (item.path == dev.forgeai.agent.AgentEngine.SCRIPT_MARKER) "Python script"
                else "${if (item.kind == dev.forgeai.agent.TrackedWrite.Kind.CREATE) "Create" else "Modify"} ${item.path}" +
                    (if (item.providerPath != null && item.providerPath != item.path) " (saved as ${item.providerPath})" else "")
            Text(title, style = MaterialTheme.typography.titleSmall, color = color)
            Text(item.outcome.label, style = MaterialTheme.typography.labelMedium, color = color)
            item.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (item.diff.isNotBlank()) {
                TextButton(onClick = { open = !open }) { Text(if (open) "Hide diff" else "Show diff") }
                if (open) Text(item.diff, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()))
            }
        }
    }
}

@Composable
private fun ApprovalCard(item: Tl.Approval, busy: Boolean, onApprove: (Int) -> Unit, onRefuse: (Int) -> Unit) {
    var open by rememberSaveable(item.id) { mutableStateOf(true) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Approval needed", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.tertiary)
            Text(if (item.script != null) "A Python script wants to run on this device (isolated, no network, 30 s limit)."
                else "${if (item.kind == dev.forgeai.agent.TrackedWrite.Kind.CREATE) "Create" else "Modify"} '${item.path}' — nothing is written until you approve.",
                style = MaterialTheme.typography.bodySmall)
            if (item.script != null) {
                Text(item.script.take(1500), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()))
            } else if (item.diff.isNotBlank()) {
                TextButton(onClick = { open = !open }) { Text(if (open) "Hide diff" else "Show diff") }
                if (open) Text(item.diff, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onApprove(item.writeId) }) { Text("Approve") }
                OutlinedButton(onClick = { onRefuse(item.writeId) }) { Text("Refuse") }
            }
        }
    }
}

@Composable
private fun CheckCard(item: Tl.Check) {
    var open by rememberSaveable(item.id) { mutableStateOf(false) }
    val color = when (item.outcome) {
        CheckOutcome.PASSED -> MaterialTheme.colorScheme.primary
        CheckOutcome.FAILED -> MaterialTheme.colorScheme.error
        CheckOutcome.AWAITING_WRITE, CheckOutcome.SKIPPED, CheckOutcome.NOT_EXECUTED, CheckOutcome.CANCELLED ->
            MaterialTheme.colorScheme.onSurfaceVariant
    }
    OutlinedCard(Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$ ${item.command} ${item.args.joinToString(" ")}".trim(),
                    style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace, color = color)
                Spacer(Modifier.weight(1f))
                Text(item.outcome.label + (item.exitCode?.let { " ($it)" } ?: ""),
                    style = MaterialTheme.typography.labelMedium, color = color)
            }
            if (item.reason.isNotBlank())
                Text(item.reason, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (item.stdout.isNotBlank() || item.stderr.isNotBlank()) {
                TextButton(onClick = { open = !open }) { Text(if (open) "Hide output" else "Output") }
                if (open) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (item.stdout.isNotBlank()) Text(item.stdout, style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace)
                    if (item.stderr.isNotBlank()) Text("[stderr] ${item.stderr}", style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

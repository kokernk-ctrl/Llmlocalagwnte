package dev.forgeai.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.forgeai.agent.AgentMode
import dev.forgeai.agent.AgentPhase
import dev.forgeai.agent.AgentViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private class WriteTree : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?) = super.createIntent(context, input).apply {
        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        putExtra(Intent.EXTRA_LOCAL_ONLY, true)
    }
}

@Composable
internal fun AgentScreen(viewModel: AgentViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val grants by viewModel.folderGrants.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var objective by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(AgentMode.REVIEW.name) }
    var planFromModel by rememberSaveable { mutableStateOf(false) }
    var consent by rememberSaveable { mutableStateOf(false) }
    var chosen by rememberSaveable { mutableStateOf(0) }
    var pendingGrant by rememberSaveable { mutableStateOf(false) }
    var checkpoints by remember { mutableStateOf(viewModel.interruptedList()) }
    var showDiffFor by remember { mutableStateOf<dev.forgeai.agent.PendingWrite?>(null) }

    val writePicker = rememberLauncherForActivityResult(remember { WriteTree() }) { uri ->
        val allowed = pendingGrant
        pendingGrant = false; consent = false
        if (uri != null && allowed) {
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    dev.forgeai.workspace.WorkspaceAccess(context.applicationContext).grantReadWrite(uri)
                }
                viewModel.refresh()
            }
        }
    }

    val grant = grants.getOrNull(chosen.coerceIn(0, (grants.size - 1).coerceAtLeast(0)))
    val busy = !state.phase.terminal && state.phase != AgentPhase.IDLE
    LazyColumn(modifier, contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeading("Agent", "Plans with the local model, then reads, writes and checks real files inside the folder you authorize. Plan and evidence are shown; the model's private reasoning is not.") }

        // Task setup
        if (!busy) {
            item {
                OutlinedTextField(value = objective, onValueChange = { objective = it.take(1200) },
                    modifier = Modifier.fillMaxWidth(), label = { Text("Task objective") },
                    placeholder = { Text("e.g. create task-notes.md and append a changelog line to README.md") },
                    minLines = 2)
            }
            item {
                Column {
                    AgentMode.entries.forEach { m ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            androidx.compose.material3.RadioButton(selected = mode == m.name,
                                onClick = { mode = m.name })
                            Column {
                                Text(m.label, style = MaterialTheme.typography.titleSmall)
                                Text(m.detail, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            item {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.Switch(checked = planFromModel, onCheckedChange = { planFromModel = it })
                    Column(Modifier.padding(start = 8.dp)) {
                        Text("Use the local model for planning", style = MaterialTheme.typography.titleSmall)
                        Text("Requires a loaded model in Chat. If the model output is unusable, a labeled deterministic plan runs instead.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                if (grants.isEmpty()) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = consent, onCheckedChange = { consent = it })
                        Text("I authorize writes in the folder I am about to pick.")
                    }
                    Button(enabled = consent, onClick = { pendingGrant = true
                        try { writePicker.launch(null) } catch (_: Exception) { pendingGrant = false }
                    }) { Text("Grant read+write folder") }
                    Panel("No folder yet", "Agent tasks need a folder with read+write access. Read-only folders can only be inspected; write tools will be rejected.")
                } else {
                    Column {
                        Text("Task folder", style = MaterialTheme.typography.titleSmall)
                        grants.forEachIndexed { index, g ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                androidx.compose.material3.RadioButton(selected = chosen == index,
                                    onClick = { chosen = index })
                                Text("${g.name} · ${if (g.writable) "read+write" else "read-only"}",
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                    Button(enabled = objective.isNotBlank() && grant != null, onClick = {
                        viewModel.start(objective, grant!!.root,
                            AgentMode.valueOf(mode), planFromModel)
                    }) { Text(if (mode == AgentMode.AUTO.name) "Run agent (auto-apply)" else "Run agent (review first)") }
                    Text("Budget: 12 tool steps, 6 writes, 24 reads, 4 checks, 3 minutes. " +
                        "Writes go through scope, budget and conflict checks; replaced files keep a backup copy.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                val saved = checkpoints
                if (saved.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Previous tasks (checkpoints)", style = MaterialTheme.typography.titleSmall)
                    saved.take(5).forEach { task ->
                        val label = if (task.state.interruptedLike) "interrupted" else task.state.phase.label
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("#${task.id % 100000} · ${label} · ${task.state.objective.take(48)}",
                                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { viewModel.deleteTask(task.id)
                                checkpoints = viewModel.interruptedList() }) { Text("Clear") }
                        }
                        if (task.state.interruptedLike) {
                            Text("This task was interrupted (app closed or crash). It is not complete; resume it by re-running the objective.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        // Progress and results
        if (state.phase != AgentPhase.IDLE) {
            item {
                Card {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(state.phase.label, style = MaterialTheme.typography.titleMedium,
                                color = if (state.phase == AgentPhase.FAILED) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.weight(1f))
                            if (busy) OutlinedButton(onClick = { viewModel.cancel() }) { Text("Cancel") }
                        }
                        Text("Objective: ${state.objective.take(160)}", style = MaterialTheme.typography.bodySmall)
                        if (state.planSource.isNotBlank()) Text("Plan source: ${state.planSource}",
                            style = MaterialTheme.typography.bodySmall)
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        state.notice?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (state.appliedFiles.isNotEmpty()) {
                            Text("Applied files: ${state.appliedFiles.joinToString()}",
                                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
            items(state.steps) { step ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${step.index + 1}. ${step.tool} ${if (step.ok) "· ok" else "· failed"}",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (step.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        Text(step.summary, style = MaterialTheme.typography.bodyMedium)
                        if (step.detail.isNotBlank()) Text(step.detail.take(1200),
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            // Diffs: proposed (review) or applied evidence
            items(state.pending) { pw ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${if (pw.kind == dev.forgeai.agent.PendingWrite.Kind.CREATE) "Create" else "Modify"} ${pw.path}" +
                            (if (pw.applied) " · applied" else if (pw.error != null) " · error" else " · pending review"),
                            style = MaterialTheme.typography.titleSmall)
                        pw.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (!busy && state.phase == AgentPhase.AWAITING_REVIEW) {
                            TextButton(onClick = { showDiffFor = pw }) { Text("Show diff") }
                        } else if (pw.applied) {
                            Text("Written and verified by re-read. A backup of the previous version was kept in app storage when applicable.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                if (state.phase == AgentPhase.AWAITING_REVIEW) {
                    Text("Review the proposed changes above. Nothing has been written yet.",
                        style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = state.pending.any { !it.applied }, onClick = { viewModel.approve() }) {
                            Text("Approve and apply")
                        }
                        OutlinedButton(onClick = { viewModel.cancel() }) { Text("Discard") }
                    }
                }
                if (state.phase.terminal && state.phase != AgentPhase.IDLE) {
                    val checkSteps = state.steps.filter { it.tool == "run_check" }
                    if (state.pending.none { it.error == null && !it.applied } && checkSteps.isEmpty() &&
                        state.appliedFiles.isEmpty() && state.phase == AgentPhase.DONE) {
                        Text("No compatible runner was part of this plan; local checks were not executed.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item {
            HorizontalDivider()
            Text("Security notes", style = MaterialTheme.typography.titleSmall)
            Text("The agent cannot reach the network (the app has no INTERNET permission), cannot run arbitrary shell, " +
                "cannot touch folders outside the grant, and never executes file contents. Cancel stops before the next operation " +
                "and reports any write already started.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    showDiffFor?.let { pw ->
        AlertDialog(onDismissRequest = { showDiffFor = null },
            title = { Text("Diff · ${pw.path}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val diff = pw.displayDiff().ifBlank { "(diff unavailable after restart; re-run the task)" }
                    Text(diff.ifEmpty { "(no textual differences)" }, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { showDiffFor = null }) { Text("Close") } })
    }
}

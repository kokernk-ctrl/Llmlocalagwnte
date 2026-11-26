package dev.forgeai.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.forgeai.agent.ShellLogEntry
import dev.forgeai.agent.ShellViewModel

@Composable
internal fun ShellScreen(viewModel: ShellViewModel, modifier: Modifier = Modifier) {
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val grants by viewModel.folderGrants.collectAsStateWithLifecycle()
    var command by rememberSaveable { mutableStateOf("wc") }
    var args by rememberSaveable { mutableStateOf("-l") }
    var timeoutSec by rememberSaveable { mutableStateOf("10") }
    var chosen by rememberSaveable { mutableStateOf(0) }
    val grant = grants.getOrNull(chosen.coerceIn(0, (grants.size - 1).coerceAtLeast(0)))
    val allowed = viewModel.allowedCommands()

    LaunchedEffect(Unit) { viewModel.refreshGrants() }

    LazyColumn(modifier, contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            PageHeading("Shell", "Local read-only commands on a working copy of an authorized folder. No shell interpreter, no network, no writes, absolute timeout and output caps.")
        }
        item {
            if (grants.isEmpty()) {
                Panel("No folder authorized", "Grant a folder in Files first. Shell commands run against a private mirror copy of that folder.")
            } else {
                Column {
                    Text("Working copy source", style = MaterialTheme.typography.titleSmall)
                    grants.forEachIndexed { index, g ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            androidx.compose.material3.RadioButton(selected = chosen == index, onClick = { chosen = index })
                            Text("${g.name} · ${if (g.writable) "read+write" else "read-only"}", fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                OutlinedTextField(value = command, onValueChange = { command = it.trim().take(32) },
                    singleLine = true, label = { Text("Command (whitelisted)") },
                    supportingText = { Text("Allowed: ${allowed.joinToString(" ")}") },
                    modifier = Modifier.fillMaxWidth(), textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace))
                OutlinedTextField(value = args, onValueChange = { args = it.take(512) },
                    singleLine = true, label = { Text("Arguments (space-separated, relative paths only)") },
                    modifier = Modifier.fillMaxWidth(), textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace))
                OutlinedTextField(value = timeoutSec, onValueChange = { timeoutSec = it.filter { c -> c.isDigit() }.take(3) },
                    singleLine = true, label = { Text("Timeout (seconds, 1–120)") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !running, onClick = {
                        viewModel.runUserCommand(command, args.split(' ').filter { it.isNotBlank() },
                            grant!!.root, timeoutSec.toIntOrNull() ?: 10)
                    }) { Text("Run") }
                    if (running) OutlinedButton(onClick = { viewModel.cancel() }) { Text("Cancel") }
                }
                Text("Commands are validated: whitelist, flag whitelist, relative paths inside the working copy, no shell metacharacters (argv vectors), no absolute paths.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(logs.asReversed()) { log ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("$ ${log.command} ${log.args.joinToString(" ")}".take(200),
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleSmall)
                    val r = log.result
                    if (r.failure != null) {
                        Text("Not started: ${r.failure}", color = MaterialTheme.colorScheme.error)
                    } else {
                        Text(if (r.timedOut) "Timed out after ${r.durationMs} ms"
                            else if (r.cancelled) "Cancelled after ${r.durationMs} ms"
                            else "Exit code ${r.exitCode} · ${r.durationMs} ms",
                            style = MaterialTheme.typography.bodySmall)
                        if (r.stdout.isNotBlank()) {
                            Text("stdout:", style = MaterialTheme.typography.labelMedium)
                            Text(r.stdout.take(4000), fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (r.stderr.isNotBlank()) {
                            Text("stderr:", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error)
                            Text(r.stderr.take(2000), fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (r.stdoutTruncated || r.stderrTruncated) {
                            Text("Output was truncated at the cap.", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

package dev.forgeai.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.forgeai.python.PythonHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Manual Python controls; the agent can also invoke these tools inside the chat. */
@Composable
internal fun PythonScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<PythonHost.Result?>(null) }
    var busy by remember { mutableStateOf(false) }
    var sourceName by rememberSaveable { mutableStateOf("") }
    var inputText by rememberSaveable { mutableStateOf("") }
    var freeScript by rememberSaveable { mutableStateOf("") }
    var approval by rememberSaveable { mutableStateOf(false) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                try {
                    val text = withContext(Dispatchers.IO) { readDocument(context, uri) }
                    inputText = text
                    sourceName = uri.lastPathSegment?.substringAfterLast('/') ?: "document"
                } catch (e: Exception) {
                    sourceName = "read failed: ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    busy = false
                }
            }
        }
    }

    LazyColumn(modifier, contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            PageHeading("Python", "Bundled CPython runtime (no Termux). Tools process JSON, CSV and text locally; free scripts require explicit approval and run with time and output limits. Network is impossible: the app has no INTERNET permission.")
        }
        item {
            Text("Runtime: ${if (PythonHost.available) "bundled CPython (Chaquopy)" else "not bundled in this build"}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (PythonHost.available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            if (!PythonHost.available) {
                Text("Analysis tools require the bundled runtime and stay disabled in this build; free-script execution remains pending.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Column {
                Text("Input", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedButton(enabled = !busy, onClick = {
                        try { filePicker.launch(arrayOf("*/*")) } catch (_: Exception) { }
                    }) { Text("Load document") }
                    if (sourceName.isNotBlank()) Text(sourceName, style = MaterialTheme.typography.labelMedium)
                }
                OutlinedTextField(value = inputText.take(4096), onValueChange = { inputText = it },
                    enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    label = { Text("Data to analyze (JSON, CSV, text, JSONL…)") },
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace))
                Text("Loaded input is capped at 512 KiB for analysis; the field shows the first 4 KiB.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(PythonHost.Predefined.entries.toList()) { tool ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tool.title, style = MaterialTheme.typography.titleSmall)
                    Text(tool.description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(enabled = PythonHost.available && !busy && inputText.isNotBlank(), onClick = {
                        scope.launch {
                            busy = true
                            result = withContext(Dispatchers.IO) {
                                PythonHost.runPredefined(context, tool, inputText)
                            }
                            busy = false
                        }
                    }) { Text("Run ${tool.title.lowercase()}") }
                }
            }
        }
        item {
            HorizontalDivider()
            Text("Free script (experimental)", style = MaterialTheme.typography.titleSmall)
            Text("Runs with: 30 s cooperative deadline (C-level loops cannot be interrupted), 64 KiB output cap, captured exceptions, no network access at OS level. Scripts see your input as the variable INPUT. Nothing outside this process is touched.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(checked = approval, onCheckedChange = { approval = it })
                Text("I approve running this exact script on this device.")
            }
            OutlinedTextField(value = freeScript.take(4096), onValueChange = { freeScript = it },
                enabled = !busy && approval, modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                label = { Text("Python code") }, textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
                placeholder = { Text("import json\nprint(json.loads(INPUT)['key'])", fontFamily = FontFamily.Monospace) })
            Button(enabled = PythonHost.available && !busy && approval && freeScript.isNotBlank(), onClick = {
                scope.launch {
                    busy = true
                    result = withContext(Dispatchers.IO) {
                        PythonHost.run(context, freeScript, inputText, deadlineSeconds = 30)
                    }
                    busy = false
                }
            }) { Text("Run approved script") }
        }
        result?.let { r ->
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (r.ok) "OK · ${r.durationMs} ms" else "Failed · ${r.durationMs} ms",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (r.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        if (r.stdout.isNotBlank()) Text(r.stdout.take(8000), fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall)
                        if (r.stderr.isNotBlank()) {
                            Text("stderr:", style = MaterialTheme.typography.labelMedium)
                            Text(r.stderr.take(2000), fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (r.error.isNotBlank()) Text(r.error.take(2000), fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        if (r.truncated) Text("Output truncated at the cap.", style = MaterialTheme.typography.labelSmall)
                        r.note?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        TextButton(onClick = { result = null }) { Text("Clear result") }
                    }
                }
            }
        }
    }
}

/** One-shot SAF read through the picker grant; size-capped and UTF-8 checked. */
private fun readDocument(context: android.content.Context, uri: Uri): String {
    val cap = 512 * 1024
    context.contentResolver.openInputStream(uri)?.use { stream ->
        val bytes = ByteArray(cap + 1)
        var len = 0
        while (len < bytes.size) {
            val n = stream.read(bytes, len, bytes.size - len)
            if (n < 0) break
            len += n
        }
        if (len > cap) throw IllegalArgumentException("Document exceeds the 512 KiB input limit.")
        val text = String(bytes, 0, len, Charsets.UTF_8)
        check(text.none { it.isISOControl() && it !in "\t\n\r" }) { "Not plain UTF-8 text." }
        return text
    } ?: throw IllegalStateException("Document could not be opened.")
}

package dev.forgeai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.forgeai.workspace.MAX_ROWS
import dev.forgeai.workspace.Preview
import dev.forgeai.workspace.WorkspaceAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import dev.forgeai.workspace.DiffTool

private class ReadTree : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?) = super.createIntent(context, input).apply {
        putExtra(Intent.EXTRA_LOCAL_ONLY, true)
    }
}

/** Grants the picked tree with read or read+write persistable permissions, per user choice. */
private class GrantTree(private val write: Boolean) : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?) = super.createIntent(context, input).apply {
        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0) or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        putExtra(Intent.EXTRA_LOCAL_ONLY, true)
    }
}

@Composable
fun WorkspaceScreen(modifier: Modifier = Modifier, onAnalyzeExcerpt: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val access = remember(context) { WorkspaceAccess(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var grants by remember { mutableStateOf(emptyList<WorkspaceAccess.Grant>()) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var consentRead by rememberSaveable { mutableStateOf(false) }
    var consentWrite by rememberSaveable { mutableStateOf(false) }
    var awaitingPicker by rememberSaveable { mutableStateOf<String?>(null) } // "read" | "write"
    var chosen by rememberSaveable { mutableStateOf<Int>(0) }               // index into grants
    var listing by remember { mutableStateOf<WorkspaceAccess.Listing?>(null) }
    var previewTarget by remember { mutableStateOf<WorkspaceAccess.Entry?>(null) }
    var previewText by remember { mutableStateOf<Preview?>(null) }
    var editorText by rememberSaveable { mutableStateOf("") }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var showDiff by rememberSaveable { mutableStateOf(false) }
    var newFileName by rememberSaveable { mutableStateOf("") }
    var creatingNew by rememberSaveable { mutableStateOf(false) }
    var history by remember { mutableStateOf(emptyList<dev.forgeai.workspace.LogEntry>()) }

    fun runAction(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action(); withContext(Dispatchers.IO) { history = access.history() } }
            catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (e: Exception) { notice = e.message ?: "The action could not complete." }
            finally { busy = false }
        }
    }

    val grantPicker = rememberLauncherForActivityResult(remember { GrantTree(false) }) { uri ->
        val kind = awaitingPicker; awaitingPicker = null; consentRead = false; consentWrite = false
        if (uri != null && kind == "read") runAction { withContext(Dispatchers.IO) { access.grantRead(uri) } }
    }
    val writePicker = rememberLauncherForActivityResult(remember { GrantTree(true) }) { uri ->
        val kind = awaitingPicker; awaitingPicker = null; consentRead = false; consentWrite = false
        if (uri != null && kind == "write") runAction { withContext(Dispatchers.IO) { access.grantReadWrite(uri) } }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { grants = access.grants(); history = access.history() }
    }

    val grant = grants.getOrNull(chosen.coerceIn(0, (grants.size - 1).coerceAtLeast(0)))
    Column(modifier.verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PageHeading("Files", "Authorized folders for reading, editing and agent tasks. Each folder is granted and revoked individually.")

        if (grants.isEmpty()) {
            dev.forgeai.ui.screens.Panel("Before you choose a folder",
                "Android will keep the permission for the selected folder until you revoke it. " +
                "Read-only folders never receive writes. Read+write folders let FORGE AI create and edit " +
                "real files (manually or via Agent tasks), with a diff shown and a backup of replaced versions kept in app storage. " +
                "No other apps' private folders and no system locations are accessible, and nothing leaves the device.")
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = consentRead, onCheckedChange = { consentRead = it })
                Text("I understand the read-only folder grant.")
            }
            OutlinedButton(enabled = consentRead && !busy, onClick = {
                awaitingPicker = "read"
                try { grantPicker.launch(null) } catch (_: Exception) { awaitingPicker = null; notice = "Folder picker unavailable." }
            }) { Text("Grant read-only folder") }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = consentWrite, onCheckedChange = { consentWrite = it })
                Text("I authorize file creation and edits in the folder I am about to pick.")
            }
            Button(enabled = consentWrite && !busy, onClick = {
                awaitingPicker = "write"
                try { writePicker.launch(null) } catch (_: Exception) { awaitingPicker = null; notice = "Folder picker unavailable." }
            }) { Text("Grant read+write folder") }
        } else {
            val format = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }
            grants.forEachIndexed { index, g ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(selected = chosen == index, onClick = { chosen = index; listing = null; previewTarget = null; editorOpen = false })
                    Text("${g.name} · ${if (g.writable) "read+write" else "read-only"}",
                        Modifier.weight(1f), fontFamily = FontFamily.Monospace)
                    TextButton(enabled = !busy, onClick = { runAction {
                        withContext(Dispatchers.IO) { access.revoke(g.root) }
                        grants = withContext(Dispatchers.IO) { access.grants() }
                        chosen = 0; listing = null; previewTarget = null; editorOpen = false
                        notice = "Folder revoked. Files were not deleted."
                    } }) { Text("Revoke") }
                }
            }
            if (grants.size > 1) Text("Selected folder: ${grant?.name}", style = MaterialTheme.typography.labelLarge)
        }

        if (grant != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { runAction {
                    listing = withContext(Dispatchers.IO) { access.listDir(grant.root, "", MAX_ROWS) }
                    previewTarget = null; editorOpen = false
                } }) { Text("List folder") }
                if (grant.writable) {
                    OutlinedButton(enabled = !busy, onClick = { creatingNew = true; newFileName = ""; editorText = ""; editorOpen = true; showDiff = false; previewTarget = null }) {
                        Text("New file")
                    }
                }
            }
            listing?.let { page ->
                Text(if (page.truncated) "First $MAX_ROWS entries (limit reached)." else "${page.entries.size} top-level entries.")
                page.entries.forEach { entry ->
                    if (entry.folder) {
                        Text("· ${entry.name}/")
                    } else OutlinedButton(enabled = !busy, onClick = { runAction {
                        val target = entry
                        val text = withContext(Dispatchers.IO) {
                            try { val t = access.readText(target); dev.forgeai.workspace.Preview(t.take(8192), t.length > 8192) }
                            catch (e: Exception) { null }
                        }
                        if (text == null) { notice = "Not readable as UTF-8 text or larger than the preview limit." } else {
                            previewTarget = target; previewText = text
                            editorText = text.text; editorOpen = false; showDiff = false
                        }
                    } }) { Text(entry.name) }
                }
            }
            val shown = previewTarget
            if (shown != null && previewText != null && !editorOpen) {
                HorizontalDivider()
                Text(shown.name, style = MaterialTheme.typography.titleMedium)
                Text(if (previewText!!.clipped) "Preview clipped at 8 KiB." else "UTF-8 preview",
                    style = MaterialTheme.typography.labelMedium)
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Text(previewText!!.text.ifEmpty { "(Empty file)" },
                        Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                        fontFamily = FontFamily.Monospace)
                }
                if (onAnalyzeExcerpt != null) {
                    val shareable = shown.name.endsWith(".txt") || shown.name.endsWith(".md") ||
                        shown.name.endsWith(".json") || shown.name.endsWith(".csv")
                    OutlinedButton(enabled = !busy && shareable,
                        onClick = { onAnalyzeExcerpt(dev.forgeai.workspace.excerpt(previewText!!.text, 1200)) }) {
                        Text("Use in Chat")
                    }
                }
                if (grant.writable) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy, onClick = { editorOpen = true; editorText = previewText!!.text; showDiff = false }) {
                            Text("Edit")
                        }
                        OutlinedButton(enabled = !busy, onClick = { runAction {
                            withContext(Dispatchers.IO) { access.exportCopy(shown, grant.root, "", "copy-of-${shown.name}") }
                            notice = "Exported a copy as 'copy-of-${shown.name}' inside the same folder."
                        } }) { Text("Export copy") }
                    }
                }
            }

            if (editorOpen && grant != null) {
                HorizontalDivider()
                Text(if (creatingNew) "New file in ${grant.name}" else "Editing ${shown?.name ?: ""}",
                    style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(value = editorText, onValueChange = { editorText = it.take(1024 * 1024) },
                    enabled = !busy && (creatingNew || grant.writable),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
                    label = { Text("File content (UTF-8)") }, textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !busy && shown != null && !creatingNew, onClick = { showDiff = !showDiff }) {
                        Text(if (showDiff) "Hide diff" else "Show diff")
                    }
                    if (creatingNew) {
                        Button(enabled = !busy && newFileName.isNotBlank() && editorText.isNotEmpty(), onClick = { runAction {
                            val name = newFileName.trim()
                            withContext(Dispatchers.IO) {
                                access.createFile(grant.root, "", name, editorText.toByteArray(Charsets.UTF_8),
                                    dev.forgeai.agent.FileKinds.mimeFor(name))
                            }
                            creatingNew = false; editorOpen = false
                            notice = "Created '$name' and verified the saved content."
                            listing = withContext(Dispatchers.IO) { access.listDir(grant.root, "", MAX_ROWS) }
                        } }) { Text("Create file") }
                    } else {
                        Button(enabled = !busy && shown != null, onClick = { runAction {
                            val target = shown!!
                            val original = previewText!!.text
                            val bytes = editorText.toByteArray(Charsets.UTF_8)
                            withContext(Dispatchers.IO) {
                                access.replaceFile(target, bytes, original.toByteArray(Charsets.UTF_8))
                            }
                            editorOpen = false
                            notice = "Saved '${target.name}' and verified by re-read. Previous version kept as backup."
                        } }) { Text("Save to file") }
                    }
                    TextButton(enabled = !busy, onClick = { editorOpen = false; creatingNew = false }) { Text("Close") }
                }
                if (creatingNew) {
                    OutlinedTextField(value = newFileName, onValueChange = { newFileName = it.take(120) },
                        enabled = !busy, singleLine = true, label = { Text("File name (e.g. notes.md)") },
                        modifier = Modifier.fillMaxWidth())
                }
                if (showDiff && shown != null) {
                    val diff = remember(editorText, previewText) {
                        DiffTool.unifiedText(previewText!!.text, editorText)
                    }
                    Text(if (diff.isEmpty()) "No differences." else "Unified diff (original → edited):",
                        style = MaterialTheme.typography.labelLarge)
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Text(diff, Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                            fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        if (notice.isNotBlank()) {
            Text(notice, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { notice = "" }) { Text("Dismiss") }
        }
        HorizontalDivider()
        TextButton(onClick = { runAction {} }) { Text("Refresh grants") }
        Text("Recent activity · last ${dev.forgeai.workspace.MAX_LOG} events",
            style = MaterialTheme.typography.labelLarge)
        if (history.isEmpty()) Text("No activity recorded.")
        val format = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }
        history.forEach { entry ->
            Text("${format.format(java.util.Date(entry.time))} · ${entry.event.label}")
        }
    }
}

package dev.forgeai.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri

import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.forgeai.workspace.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private class ReadTree : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?) = super.createIntent(context, input).apply {
        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        putExtra(Intent.EXTRA_LOCAL_ONLY, true)
    }
}

@Composable
fun WorkspaceScreen(modifier: Modifier = Modifier, onAnalyzeExcerpt: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val store = remember(context) { WorkspaceStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var ready by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var roots by remember { mutableStateOf(emptyList<Uri>()) }
    val root = roots.firstOrNull()
    var listing by remember { mutableStateOf<WorkspaceStore.Listing?>(null) }
    var preview by remember { mutableStateOf<Preview?>(null) }
    var filename by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<Int?>(null) }
    var notice by remember { mutableStateOf("") }
    var consent by rememberSaveable { mutableStateOf(false) }
    var awaitingPicker by rememberSaveable { mutableStateOf(false) }
    var history by remember { mutableStateOf(emptyList<LogEntry>()) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    fun closePreview() { preview = null; filename = ""; query = ""; matches = null }
    suspend fun sync() {
        val (updated, log) = withContext(Dispatchers.IO) { store.roots() to store.history() }
        if (roots != updated) { closePreview(); listing = null }
        roots = updated
        history = log
        ready = true
    }
    fun runAction(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                notice = ""
                action()
                sync()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                closePreview(); listing = null
                notice = "Could not complete the action. Check folder access and use a plain UTF-8 text file."
                withContext(Dispatchers.IO) { store.record(WorkspaceEvent.FAILED) }
                try { sync() } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { ready = false; roots = emptyList() }
            } finally { busy = false }
        }
    }
    val picker = rememberLauncherForActivityResult(remember { ReadTree() }) { uri ->
        val allowed = awaitingPicker
        awaitingPicker = false
        consent = false
        if (uri != null && allowed) runAction { withContext(Dispatchers.IO) { store.grant(uri) } }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val refresh by rememberUpdatedState(newValue = {
        // The result callback, not a lifecycle callback, owns authorization.
        if (!busy && !awaitingPicker) runAction {}
    })
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val enabled = ready && !busy && !awaitingPicker
    Column(modifier.verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PageHeading("Workspace", "Inspect selected documents locally. Chat cannot run these tools.")
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodyMedium)
        if (root == null) {
            Panel("Before you choose a folder",
                "Android will grant ongoing read access to the selected folder and its descendants until revoked. " +
                "Choose a dedicated, non-sensitive folder. FORGE only lists its first $MAX_ROWS top-level entries and previews up to 8 KiB of text on request. " +
                "No document edits, background scanning, uploads or automatic sharing with chat. The local log keeps only times and action types.")
            Row(Modifier.fillMaxWidth().toggleable(consent, enabled, Role.Checkbox) { consent = it }
                .heightIn(min = 48.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = consent, onCheckedChange = null, enabled = enabled)
                Text("I agree to persistent read-only access.")
            }
            Button(enabled = enabled && consent, onClick = {
                awaitingPicker = true
                try { picker.launch(null) } catch (_: Exception) {
                    awaitingPicker = false; consent = false; notice = "Folder picker unavailable."
                }
            }) { Text("Choose folder") }
            if (!ready && !busy) OutlinedButton(onClick = { runAction {} }) { Text("Check access") }
        } else {
            Text("Authorized folder: ${store.folderName(root)}", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = enabled, onClick = { runAction {
                    closePreview(); listing = withContext(Dispatchers.IO) { store.list(root) }
                } }) { Text("List files") }
                OutlinedButton(enabled = enabled, onClick = { runAction {
                    closePreview(); listing = null
                    withContext(Dispatchers.IO) { store.revoke(root) }
                    consent = false; notice = "Read access revoked. Documents unchanged."
                } }) { Text("Revoke access") }
            }
            val shown = preview
            if (shown != null) {
                Text(filename, style = MaterialTheme.typography.titleMedium)
                Text(if (shown.clipped) "Preview clipped at 8 KiB." else "UTF-8 text preview")
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Text(shown.text.ifEmpty { "(Empty file)" },
                        Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                        fontFamily = FontFamily.Monospace)
                }
                OutlinedTextField(value = query, onValueChange = { query = it.take(MAX_QUERY); matches = null },
                    enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Exact text (case-sensitive)") })
                OutlinedButton(enabled = enabled && query.isNotEmpty(), onClick = { runAction {
                    matches = literalMatches(shown.text, query)
                    withContext(Dispatchers.IO) { store.record(WorkspaceEvent.SEARCH) }
                } }) { Text("Search preview") }
                matches?.let { Text("$it non-overlapping matches in this preview.") }
                Text("Copying sends up to 2,000 characters to the system clipboard.")
                Button(enabled = enabled && shown.text.isNotEmpty(), onClick = { runAction {
                    val clip = ClipData.newPlainText("Workspace excerpt", excerpt(shown.text, 2000))
                    clip.description.extras = PersistableBundle().apply {
                        putBoolean("android.content.extra.IS_SENSITIVE", true)
                    }
                    checkNotNull(context.getSystemService(ClipboardManager::class.java)).setPrimaryClip(clip)
                    withContext(Dispatchers.IO) { store.record(WorkspaceEvent.COPY) }
                    notice = "Excerpt copied."
                } }) { Text("Copy excerpt") }
                if (onAnalyzeExcerpt != null) {
                    Text("Place an excerpt of up to 1,200 characters in a chat draft. Chat may shorten it further. Review before sending.")
                    OutlinedButton(enabled = enabled && shown.text.isNotEmpty(), onClick = { runAction {
                        withContext(Dispatchers.IO) { store.record(WorkspaceEvent.DRAFT) }
                        onAnalyzeExcerpt(excerpt(shown.text, 1200))
                    } }) { Text("Use in Chat") }
                }
                TextButton(enabled = enabled, onClick = { closePreview(); notice = "" }) { Text("Back to file list") }
            } else listing?.let { page ->
                Text("${page.entries.size} entries shown${if (page.more) " · limit reached" else ""}. Subfolders are not opened.")
                page.entries.forEach { entry ->
                    if (entry.readable) {
                        OutlinedButton(enabled = enabled, onClick = { runAction {
                            preview = withContext(Dispatchers.IO) { store.preview(entry) }
                            filename = entry.name
                        } }) { Text(entry.name) }
                    } else Text("${entry.name} · ${if (entry.folder) "folder" else "not text"}")
                }
            }
        }
        HorizontalDivider()
        TextButton(onClick = { showHistory = !showHistory }) {
            Text(if (showHistory) "Hide activity" else "Show activity")
        }
        if (showHistory) {
            Text("Recent workspace activity · last $MAX_LOG events")
            val format = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
            if (history.isEmpty()) Text("No activity recorded.")
            history.forEach { Text("${format.format(Date(it.time))} · ${it.event.label}") }
            OutlinedButton(enabled = enabled && history.isNotEmpty(), onClick = { runAction {
                withContext(Dispatchers.IO) { store.clearHistory() }
            } }) { Text("Clear activity") }
        }
    }
}

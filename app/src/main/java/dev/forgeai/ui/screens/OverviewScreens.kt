package dev.forgeai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.forgeai.core.RegistryState
import dev.forgeai.models.ModelAvailability

@Composable internal fun AccessScreen(state: RegistryState, openModels: () -> Unit, modifier: Modifier) {
    OverviewPage(modifier) {
        PageHeading("Permissions", "All access is scoped to documents you pick in the Android file picker.")
        Panel("Model files", "Import grants persistent read access to one file. The chat engine stages a temporary private copy while a model is loaded. FORGE AI does not write to the original model document.")
        Panel("Revoking access", "Remove its registration in Model Manager to release the read grant without deleting the file. If access is revoked elsewhere, use Refresh to update its status.")
        Panel("Workspace folders", "Files grants each folder individually: read-only or read+write, revoked separately and re-checked on every sensitive operation. Write operations keep a backup of replaced files in app storage; SAF replacement is not atomic and the app reports that limitation instead of claiming otherwise.")
        Panel("Agent conversation", "The agent works inside the chat on the folder you select in the header, with explicit budgets. Proposed changes appear as diffs in the conversation; in review mode nothing is written until you approve. The storage provider may store a document under a slightly different name — the agent tracks and shows the real name. After revocation or cancellation no new operations start; started writes are reported.")
        Text("${state.models.count { it.availability == ModelAvailability.AVAILABLE }} readable models",
            style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
        OutlinedButton(onClick = openModels) { Text("Manage model access") }
    }
}

@Composable private fun OverviewPage(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable internal fun PageHeading(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable internal fun Panel(title: String, detail: String) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

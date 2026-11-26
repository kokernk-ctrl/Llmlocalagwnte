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

@Composable internal fun DashboardScreen(state: RegistryState, openModels: () -> Unit, openChat: () -> Unit, modifier: Modifier) {
    OverviewPage(modifier) {
        PageHeading("Dashboard", "Your offline model inventory.")
        Panel("Model registry", "${state.models.size} registered · ${state.models.count { it.availability == ModelAvailability.AVAILABLE }} readable")
        Panel("Preferred model", state.models.firstOrNull { it.selected }?.name ?: "None selected")
        Panel("Local Chat", "Load a small Qwen2-family instruct GGUF to generate text offline. Chat does not execute tools.")
        Button(onClick = openChat) { Text("Open Chat") }
        Button(onClick = openModels) { Text("Open Model Manager") }
    }
}

@Composable internal fun AccessScreen(state: RegistryState, openModels: () -> Unit, modifier: Modifier) {
    OverviewPage(modifier) {
        PageHeading("Permissions", "Document access is scoped to the model files you select.")
        Panel("Model files", "Import grants persistent read access to one file. Chat stages a temporary private copy while a model is loaded. FORGE AI does not write to the original document.")
        Panel("Revoking access", "Remove its registration in Model Manager to release the read grant without deleting the file. If access is revoked elsewhere, use Refresh to update its status.")
        Panel("Workspace access", "Use Files to grant read-only access to one folder, review file activity, or revoke access. Chat does not read your workspace automatically.")
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

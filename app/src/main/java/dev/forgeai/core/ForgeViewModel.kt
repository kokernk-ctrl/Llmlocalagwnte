package dev.forgeai.core

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.forgeai.models.ModelEntry
import dev.forgeai.models.ModelImportException
import dev.forgeai.models.ModelRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RegistryState(
    val models: List<ModelEntry> = emptyList(),
    val busy: String? = null,
    val notice: String? = null,
    val error: String? = null,
)

class ForgeViewModel(app: Application) : AndroidViewModel(app) {
    private val mutable = MutableStateFlow(RegistryState())
    val state = mutable.asStateFlow()

    init { refresh() }
    fun refresh() = perform("Checking model access…") { null }
    fun register(uri: Uri) = perform("Validating GGUF metadata…") { register(uri) }
    fun prefer(id: Long) = perform("Updating selection…") { prefer(id); "Preferred model updated. No model is loaded." }
    fun remove(id: Long) = perform("Removing registration…") { remove(id) }
    fun clearMessage() = mutable.update { it.copy(notice = null, error = null) }

    // A single in-flight action serializes registry mutations. Provider calls stay off the UI thread.
    private fun perform(label: String, action: ModelRegistry.() -> String?) {
        if (mutable.value.busy != null) return
        mutable.update { it.copy(busy = label, notice = null, error = null) }
        viewModelScope.launch {
            try {
                val (notice, models) = withContext(Dispatchers.IO) {
                    ModelRegistry(getApplication()).use { store -> store.action() to store.list() }
                }
                mutable.value = RegistryState(models, notice = notice)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = when (error) {
                    is ModelImportException -> error.message ?: "Could not read the model."
                    is SecurityException -> "Android denied access. Select the file again in the file picker."
                    else -> "The registry operation could not finish. Refresh and try again."
                }
                mutable.update { it.copy(busy = null, error = message) }
            }
        }
    }
}

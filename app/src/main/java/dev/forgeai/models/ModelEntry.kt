package dev.forgeai.models

data class ModelEntry(
    val id: Long,
    val name: String,
    val size: Long,
    val metadata: GgufMetadata,
    val selected: Boolean,
    val availability: ModelAvailability,
)

enum class ModelAvailability(val description: String) {
    AVAILABLE("Readable"), NO_ACCESS("Access revoked"), UNAVAILABLE("File unavailable"),
    CHANGED("File size changed; re-import required")
}

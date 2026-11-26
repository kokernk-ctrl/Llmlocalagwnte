package dev.forgeai.intelligence

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.flow.Flow

/** Future inference boundary. No implementation or simulated generation in 0.1. */
interface LocalInference {
    /** Borrow the read-only descriptor; duplicate it if it is needed after this call. */
    suspend fun load(model: ParcelFileDescriptor, contextTokens: Int)
    fun generate(prompt: String): Flow<String>
    suspend fun cancelGeneration()
    suspend fun unload()
}

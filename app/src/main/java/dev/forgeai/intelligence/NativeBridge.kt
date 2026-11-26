package dev.forgeai.intelligence

/** JNI contract. These names must also be kept if release minification is enabled. */
fun interface TokenSink {
    fun onBytes(bytes: ByteArray)
}

object NativeBridge {
    init { System.loadLibrary("forge_inference") }
    external fun load(path: String, contextSize: Int): Long
    external fun generate(handle: Long, prompt: ByteArray, maxTokens: Int, callback: TokenSink)
    external fun cancel(handle: Long)
    external fun free(handle: Long)
}

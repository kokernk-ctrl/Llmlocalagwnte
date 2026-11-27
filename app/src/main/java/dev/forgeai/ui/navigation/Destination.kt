package dev.forgeai.ui.navigation

/**
 * v0.4.0: the app opens directly on the agent conversation. Chat, Models, Files and
 * Access are tabs; Shell and Python are secondary routes reachable from the chat's
 * Tools menu (the agent already uses them as tools inside the conversation).
 */
internal enum class Destination(val title: String, val isTab: Boolean) {
    CHAT("Chat", true),
    MODELS("Models", true),
    FILES("Files", true),
    ACCESS("Access", true),
    SHELL("Shell", false),
    PYTHON("Python", false),
}

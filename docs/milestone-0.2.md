# Milestone 0.2 · Local chat and read-only workspace

- Android package `dev.forgeai`, minimum API 26 and target 33. ABIs: `arm64-v8a` and `x86_64`.
- Android NDK `28.2.13676358`, CMake `3.22.1`, pinned llama.cpp `4762ad7316dcdec20016ab5985fb46a27902204d` (b6000). `app/src/main/cpp/CMakeLists.txt` retrieves and verifies the source archive; no model is embedded or downloaded by the build or app.
- In `Models`, import a Qwen2-family GGUF, then set it preferred. In `Chat`, load it and send a message. The JNI CPU engine streams token pieces; Stop and Unload are explicit. The app stages a private temporary copy while loaded; make room for a second copy of the GGUF on device storage.
- `Files` asks for consent before requesting a folder. Browse/search and preview text manually, inspect activity, and revoke the grant. A selected excerpt can be sent to the chat input for review; it is not sent to the model automatically.
- No writes, command execution, Git, Python, autonomous tool calls, or access to private directories of other apps. Local chat is not an autonomous agent. Qwen3 and Llama tags can still be inventoried, but this chat template only supports Qwen2-family instruct models.
- Test weights (outside repo; never published in APK): official `Qwen/Qwen2.5-0.5B-Instruct-GGUF`, `qwen2.5-0.5b-instruct-q4_k_m.gguf`, 491400032 bytes, SHA-256 `74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db`.

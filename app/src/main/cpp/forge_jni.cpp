#include <jni.h>
#include <llama.h>

#include <algorithm>
#include <atomic>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

namespace {
constexpr int kMaxContext = 4096;
constexpr int kMaxPromptBytes = 128 * 1024;
constexpr int kBatch = 128;
constexpr int kMaxNewTokens = 1024;

enum class Run { idle, active, cancelled };
struct Session {
    std::atomic<Run> run{Run::idle};
    int context_limit = 0;
    std::unique_ptr<llama_model, decltype(&llama_model_free)> model{nullptr, llama_model_free};
    // Must be freed before model and before abort callback data (run).
    std::unique_ptr<llama_context, decltype(&llama_free)> ctx{nullptr, llama_free};
};

std::once_flag backend_once;
std::mutex handles_mutex;
std::unordered_map<jlong, std::shared_ptr<Session>> handles;
jlong next_handle = 1;

bool aborted(void *ptr) {
    return static_cast<Session *>(ptr)->run.load(std::memory_order_relaxed) == Run::cancelled;
}

void java_error(JNIEnv *env, const char *type, const char *message) noexcept {
    if (env->ExceptionCheck()) return; // Preserve callback exceptions and JNI OOMs.
    jclass cls = env->FindClass(type);
    if (cls) env->ThrowNew(cls, message);
}

void translate_exception(JNIEnv *env) noexcept {
    try {
        throw;
    } catch (const std::bad_alloc &) {
        java_error(env, "java/lang/OutOfMemoryError", "Not enough native memory for this model/context.");
    } catch (const std::invalid_argument &e) {
        java_error(env, "java/lang/IllegalArgumentException", e.what());
    } catch (const std::exception &e) {
        java_error(env, "java/lang/IllegalStateException", e.what());
    } catch (...) {
        java_error(env, "java/lang/IllegalStateException", "Unexpected native inference error.");
    }
}

// Kotlin stages imports in an app-private file. Use standard UTF-8, not JNI's
// modified UTF-8 (which cannot represent supplementary file-name characters).
std::string file_path(JNIEnv *env, jstring path) {
    if (!path) throw std::invalid_argument("No model path supplied.");
    const int length = env->GetStringLength(path);
    if (length < 1 || length > 4096) throw std::invalid_argument("Invalid model path length.");
    std::vector<jchar> chars(length);
    env->GetStringRegion(path, 0, length, chars.data());
    if (env->ExceptionCheck()) return {};
    std::string bytes;
    for (int i = 0; i < length; ++i) {
        uint32_t ch = chars[i];
        if (ch == 0) throw std::invalid_argument("Invalid NUL in model path.");
        if (ch >= 0xd800 && ch <= 0xdbff && i + 1 < length &&
            chars[i+1] >= 0xdc00 && chars[i+1] <= 0xdfff) {
            ch = 0x10000 + ((ch - 0xd800) << 10) + chars[++i] - 0xdc00;
        } else if (ch >= 0xd800 && ch <= 0xdfff) {
            throw std::invalid_argument("Invalid Unicode in model path.");
        }
        if (ch <= 0x7f) {
            bytes.push_back(static_cast<char>(ch));
        } else {
            if (ch > 0xffff) {
                bytes.push_back(static_cast<char>(0xf0 | (ch >> 18)));
                bytes.push_back(static_cast<char>(0x80 | ((ch >> 12) & 63)));
            } else if (ch > 0x7ff) {
                bytes.push_back(static_cast<char>(0xe0 | (ch >> 12)));
            } else {
                bytes.push_back(static_cast<char>(0xc0 | (ch >> 6)));
            }
            if (ch > 0x7ff) bytes.push_back(static_cast<char>(0x80 | ((ch >> 6) & 63)));
            bytes.push_back(static_cast<char>(0x80 | (ch & 63)));
        }
    }
    return bytes;
}

// Mark active under the same lock used to cancel/retire a handle. Never hold
// this lock over decoding, Java callbacks, or destruction of model weights.
std::shared_ptr<Session> begin(jlong handle) {
    std::lock_guard<std::mutex> guard(handles_mutex);
    auto it = handles.find(handle);
    if (it == handles.end()) throw std::invalid_argument("Model is not loaded.");
    Run expected = Run::idle;
    if (!it->second->run.compare_exchange_strong(expected, Run::active)) {
        throw std::runtime_error("Generation is already in progress.");
    }
    return it->second;
}

bool decode(Session &s, llama_token *tokens, int count) {
    if (aborted(&s)) return false;
    const int rc = llama_decode(s.ctx.get(), llama_batch_get_one(tokens, count));
    if (aborted(&s) || rc == 2) return false;
    if (rc != 0) throw std::runtime_error("llama.cpp decode failed (code " + std::to_string(rc) + ").");
    return true;
}

void generate(JNIEnv *env, Session &s, jbyteArray input, jint max_tokens, jobject sink) {
    if (!input || !sink) throw std::invalid_argument("Missing prompt or token callback.");
    const int length = env->GetArrayLength(input);
    if (length < 1 || length > kMaxPromptBytes) throw std::invalid_argument("Prompt is empty or exceeds 128 KiB.");
    if (max_tokens < 1 || max_tokens > kMaxNewTokens) throw std::invalid_argument("Output limit must be 1–1024 tokens.");
    jclass cls = env->GetObjectClass(sink);
    if (!cls) return;
    jmethodID on_bytes = env->GetMethodID(cls, "onBytes", "([B)V");
    if (!on_bytes) return;

    std::string prompt(length, '\0');
    env->GetByteArrayRegion(input, 0, length, reinterpret_cast<jbyte *>(prompt.data()));
    if (env->ExceptionCheck() || aborted(&s)) return;
    const llama_vocab *vocab = llama_model_get_vocab(s.model.get());
    std::vector<llama_token> tokens(s.context_limit);
    // Kotlin supplies the complete ChatML template, including control tokens.
    const int n = llama_tokenize(vocab, prompt.data(), length, tokens.data(),
                                 s.context_limit, false, true);
    if (n < 0 || n >= s.context_limit) throw std::invalid_argument("Prompt exceeds the model context. Shorten the conversation.");
    if (n == 0) throw std::invalid_argument("Prompt contains no tokens.");
    llama_memory_clear(llama_get_memory(s.ctx.get()), false);
    for (int offset = 0; offset < n; offset += kBatch) {
        if (!decode(s, tokens.data() + offset, std::min(kBatch, n - offset))) return;
    }

    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_init_greedy(), llama_sampler_free);
    if (!sampler) throw std::runtime_error("Could not initialize llama.cpp sampler.");
    std::vector<char> piece(128);
    const int budget = std::min<int>(max_tokens, s.context_limit - n);
    for (int i = 0; i < budget && !aborted(&s); ++i) {
        llama_token next = llama_sampler_sample(sampler.get(), s.ctx.get(), -1);
        if (llama_vocab_is_eog(vocab, next)) break;
        int size = llama_token_to_piece(vocab, next, piece.data(), static_cast<int>(piece.size()), 0, false);
        if (size < 0 && size >= -kMaxPromptBytes) {
            piece.resize(-size);
            size = llama_token_to_piece(vocab, next, piece.data(), static_cast<int>(piece.size()), 0, false);
        }
        if (size < 0) throw std::runtime_error("Could not convert generated token to bytes.");
        if (size > 0 && !aborted(&s)) {
            jbyteArray bytes = env->NewByteArray(size);
            if (!bytes) return;
            env->SetByteArrayRegion(bytes, 0, size, reinterpret_cast<const jbyte *>(piece.data()));
            if (!env->ExceptionCheck()) env->CallVoidMethod(sink, on_bytes, bytes);
            env->DeleteLocalRef(bytes);
            if (env->ExceptionCheck()) return;
        }
        if (i + 1 < budget && !decode(s, &next, 1)) return;
    }
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_forgeai_intelligence_NativeBridge_load(JNIEnv *env, jobject, jstring path, jint context_size) {
    try {
        if (context_size < 64 || context_size > kMaxContext) throw std::invalid_argument("Context size must be 64–4096 tokens.");
        auto name = file_path(env, path);
        if (env->ExceptionCheck()) return 0;
        std::call_once(backend_once, llama_backend_init);
        auto s = std::make_shared<Session>();
        auto mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        mp.use_mmap = true;
        s->model.reset(llama_model_load_from_file(name.c_str(), mp));
        if (!s->model) throw std::runtime_error("Cannot load GGUF. Check that the model is complete, supported and fits in memory.");
        if (llama_model_has_encoder(s->model.get()) || !llama_model_has_decoder(s->model.get())) {
            throw std::invalid_argument("A decoder-only chat model is required.");
        }
        s->context_limit = std::min<int>(context_size, llama_model_n_ctx_train(s->model.get()));
        if (s->context_limit < 2) throw std::runtime_error("Invalid model context length.");
        auto cp = llama_context_default_params();
        cp.n_ctx = s->context_limit;
        cp.n_batch = cp.n_ubatch = std::min(kBatch, s->context_limit);
        cp.n_threads = cp.n_threads_batch = std::clamp(std::thread::hardware_concurrency(), 1u, 4u);
        cp.offload_kqv = false;
        cp.op_offload = false;
        cp.abort_callback = aborted;
        cp.abort_callback_data = s.get();
        s->ctx.reset(llama_init_from_model(s->model.get(), cp));
        if (!s->ctx || !llama_get_memory(s->ctx.get())) throw std::runtime_error("Could not allocate inference context. Try a smaller model.");
        std::lock_guard<std::mutex> guard(handles_mutex);
        if (next_handle == std::numeric_limits<jlong>::max()) throw std::runtime_error("Native handles exhausted.");
        const jlong handle = next_handle++;
        handles.emplace(handle, std::move(s));
        return handle;
    } catch (...) {
        translate_exception(env);
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_forgeai_intelligence_NativeBridge_generate(JNIEnv *env, jobject, jlong handle, jbyteArray prompt, jint max_tokens, jobject sink) {
    try {
        auto s = begin(handle);
        struct Reset { Session &s; ~Reset() { s.run.store(Run::idle); } } reset{*s};
        generate(env, *s, prompt, max_tokens, sink);
    } catch (...) { translate_exception(env); }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_forgeai_intelligence_NativeBridge_cancel(JNIEnv *env, jobject, jlong handle) {
    try {
        std::lock_guard<std::mutex> guard(handles_mutex);
        auto it = handles.find(handle);
        if (it != handles.end()) {
            Run active = Run::active;
            it->second->run.compare_exchange_strong(active, Run::cancelled);
        }
    } catch (...) { translate_exception(env); }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_forgeai_intelligence_NativeBridge_free(JNIEnv *env, jobject, jlong handle) {
    try {
        std::shared_ptr<Session> retired;
        {
            std::lock_guard<std::mutex> guard(handles_mutex);
            auto it = handles.find(handle);
            if (it == handles.end()) return;
            retired = std::move(it->second);
            handles.erase(it);
            retired->run.store(Run::cancelled);
        }
    } catch (...) { translate_exception(env); }
}

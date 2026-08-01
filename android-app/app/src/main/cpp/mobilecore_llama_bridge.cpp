#include <jni.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <sstream>
#include <string>
#include <vector>

#ifdef MOBILECORE_USE_LLAMA_CPP
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#endif

namespace {
bool g_model_loaded = false;
std::string g_model_path;
std::string g_model_id = "local-model";
long long g_last_prompt_eval_ms = 0;
long long g_last_first_token_ms = 0;
long long g_last_decode_ms = 0;
long long g_last_total_ms = 0;
double g_last_decode_tokens_per_second = 0.0;
int32_t g_last_prompt_tokens = 0;
int32_t g_last_completion_tokens = 0;
long long g_last_memory_mb = 0;
std::atomic<bool> g_cancel_requested{false};

#ifdef MOBILECORE_USE_LLAMA_CPP
bool g_backend_initialized = false;
llama_model* g_model = nullptr;
llama_context* g_context = nullptr;
mtmd_context* g_mtmd_context = nullptr;
std::string g_mmproj_path;
bool g_mtmd_supports_vision = false;
bool g_mtmd_supports_audio = false;
std::string g_cached_media_id;
std::vector<float> g_cached_media_embeddings;
std::string g_cached_kv_media_id;
std::vector<llama_token> g_cached_kv_prefix_tokens;
llama_pos g_cached_kv_prefix_n_past = 0;

void ensure_backend_initialized() {
    if (!g_backend_initialized) {
        llama_backend_init();
        g_backend_initialized = true;
    }
}

void release_llama_state() {
    g_cached_media_id.clear();
    g_cached_media_embeddings.clear();
    g_cached_kv_media_id.clear();
    g_cached_kv_prefix_tokens.clear();
    g_cached_kv_prefix_n_past = 0;
    if (g_mtmd_context != nullptr) {
        mtmd_free(g_mtmd_context);
        g_mtmd_context = nullptr;
    }
    g_mmproj_path.clear();
    g_mtmd_supports_vision = false;
    g_mtmd_supports_audio = false;
    if (g_context != nullptr) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_model_loaded = false;
}

int32_t eval_mtmd_chunks_with_media_cache(
    mtmd_input_chunks* chunks,
    int32_t n_batch,
    llama_pos* new_n_past
) {
    llama_pos n_past = 0;
    const size_t chunk_count = mtmd_input_chunks_size(chunks);
    size_t media_index = chunk_count;
    std::string media_id;
    std::vector<llama_token> prefix_tokens;
    for (size_t index = 0; index < chunk_count; ++index) {
        const mtmd_input_chunk* chunk = mtmd_input_chunks_get(chunks, index);
        const mtmd_input_chunk_type type = mtmd_input_chunk_get_type(chunk);
        if (type == MTMD_INPUT_CHUNK_TYPE_IMAGE || type == MTMD_INPUT_CHUNK_TYPE_AUDIO) {
            media_index = index;
            const char* raw_id = mtmd_input_chunk_get_id(chunk);
            media_id = raw_id == nullptr ? "" : raw_id;
            break;
        }
        if (type == MTMD_INPUT_CHUNK_TYPE_TEXT) {
            size_t token_count = 0;
            const llama_token* tokens = mtmd_input_chunk_get_tokens_text(chunk, &token_count);
            if (tokens != nullptr) {
                prefix_tokens.insert(prefix_tokens.end(), tokens, tokens + token_count);
            }
        }
    }

    bool kv_prefix_hit = media_index < chunk_count && !media_id.empty() &&
        media_id == g_cached_kv_media_id &&
        prefix_tokens == g_cached_kv_prefix_tokens &&
        g_cached_kv_prefix_n_past > 0;
    llama_memory_t memory = llama_get_memory(g_context);
    if (kv_prefix_hit) {
        kv_prefix_hit = llama_memory_seq_rm(memory, 0, g_cached_kv_prefix_n_past, -1);
    }
    if (kv_prefix_hit) {
        n_past = g_cached_kv_prefix_n_past;
    } else {
        llama_memory_clear(memory, true);
        g_cached_kv_media_id.clear();
        g_cached_kv_prefix_tokens.clear();
        g_cached_kv_prefix_n_past = 0;
    }

    for (size_t index = 0; index < chunk_count; ++index) {
        if (kv_prefix_hit && index <= media_index) continue;
        const mtmd_input_chunk* chunk = mtmd_input_chunks_get(chunks, index);
        const bool logits_last = index + 1 == chunk_count;
        const mtmd_input_chunk_type type = mtmd_input_chunk_get_type(chunk);
        int32_t result = 0;
        if (type == MTMD_INPUT_CHUNK_TYPE_IMAGE || type == MTMD_INPUT_CHUNK_TYPE_AUDIO) {
            const char* raw_id = mtmd_input_chunk_get_id(chunk);
            const std::string media_id = raw_id == nullptr ? "" : raw_id;
            const size_t embedding_count = mtmd_input_chunk_get_n_tokens(chunk) *
                static_cast<size_t>(llama_model_n_embd_inp(g_model));
            const bool cache_hit = !media_id.empty() && media_id == g_cached_media_id &&
                g_cached_media_embeddings.size() == embedding_count;
            if (!cache_hit) {
                result = mtmd_encode_chunk(g_mtmd_context, chunk);
                if (result != 0) return result;
                const float* encoded = mtmd_get_output_embd(g_mtmd_context);
                g_cached_media_embeddings.assign(encoded, encoded + embedding_count);
                g_cached_media_id = media_id;
            }
            result = mtmd_helper_decode_image_chunk(
                g_mtmd_context,
                g_context,
                chunk,
                g_cached_media_embeddings.data(),
                n_past,
                0,
                n_batch,
                &n_past,
                nullptr,
                nullptr
            );
            if (result == 0 && index == media_index) {
                g_cached_kv_media_id = media_id;
                g_cached_kv_prefix_tokens = prefix_tokens;
                g_cached_kv_prefix_n_past = n_past;
            }
        } else {
            result = mtmd_helper_eval_chunk_single(
                g_mtmd_context,
                g_context,
                chunk,
                n_past,
                0,
                n_batch,
                logits_last,
                &n_past
            );
        }
        if (result != 0) return result;
        *new_n_past = n_past;
    }
    return 0;
}

bool file_exists(const std::string& path) {
    FILE* f = std::fopen(path.c_str(), "rb");
    if (f != nullptr) {
        std::fclose(f);
        return true;
    }
    return false;
}
#endif

std::string escape_json(const std::string& value) {
    std::ostringstream out;
    for (const char ch : value) {
        switch (ch) {
            case '\\': out << "\\\\"; break;
            case '"': out << "\\\""; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default: out << ch; break;
        }
    }
    return out.str();
}

std::string model_id_from_path(const std::string& model_path) {
    const auto slash = model_path.find_last_of("/\\");
    std::string name = slash == std::string::npos ? model_path : model_path.substr(slash + 1);
    const std::string suffix = ".gguf";
    if (name.size() > suffix.size() && name.compare(name.size() - suffix.size(), suffix.size(), suffix) == 0) {
        name.erase(name.size() - suffix.size());
    }
    return name.empty() ? "local-model" : name;
}

long long elapsed_ms(const std::chrono::steady_clock::time_point& start,
                     const std::chrono::steady_clock::time_point& end) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();
}

std::string chat_json(
    bool ok,
    const std::string& model_id,
    const std::string& message,
    int32_t prompt_tokens,
    int32_t completion_tokens,
    long long prompt_eval_ms,
    long long first_token_ms,
    long long decode_ms,
    long long total_ms,
    double decode_tokens_per_second,
    long long memory_peak_mb,
    const std::string& code = ""
) {
    std::ostringstream json;
    json << "{"
         << "\"ok\":" << (ok ? "true" : "false") << ","
         << "\"model\":\"" << escape_json(model_id) << "\","
         << "\"message\":\"" << escape_json(message) << "\","
         << "\"promptTokens\":" << prompt_tokens << ","
         << "\"completionTokens\":" << completion_tokens << ","
         << "\"totalTokens\":" << (prompt_tokens + completion_tokens) << ","
         << "\"promptEvalMs\":" << prompt_eval_ms << ","
         << "\"firstTokenMs\":" << first_token_ms << ","
         << "\"decodeMs\":" << decode_ms << ","
         << "\"totalMs\":" << total_ms << ","
         << "\"decodeTokensPerSecond\":" << decode_tokens_per_second << ","
         << "\"memoryPeakMb\":" << memory_peak_mb;
    if (!code.empty()) {
        json << ",\"code\":\"" << escape_json(code) << "\"";
    }
    json
         << "}";
    return json.str();
}

#ifdef MOBILECORE_USE_LLAMA_CPP
std::string format_mtmd_user_prompt(const std::string& user_prompt) {
    const char* marker_value = g_mtmd_context == nullptr ? nullptr : mtmd_get_marker(g_mtmd_context);
    const std::string marker = marker_value == nullptr || marker_value[0] == '\0'
        ? mtmd_default_marker()
        : marker_value;
    const std::string content = marker + "\n" + user_prompt;
    const char* chat_template = g_model == nullptr ? nullptr : llama_model_chat_template(g_model, nullptr);
    if (chat_template != nullptr) {
        const llama_chat_message message = {"user", content.c_str()};
        const int32_t required = llama_chat_apply_template(
            chat_template,
            &message,
            1,
            true,
            nullptr,
            0
        );
        if (required > 0) {
            std::vector<char> formatted(static_cast<size_t>(required) + 1, '\0');
            const int32_t written = llama_chat_apply_template(
                chat_template,
                &message,
                1,
                true,
                formatted.data(),
                static_cast<int32_t>(formatted.size())
            );
            if (written > 0 && static_cast<size_t>(written) < formatted.size()) {
                return std::string(formatted.data(), static_cast<size_t>(written));
            }
        }
    }
    return std::string("<|im_start|>user\n") + content +
        "<|im_end|>\n<|im_start|>assistant\n";
}
#endif
} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_ai_mobilecore_runtime_RuntimeBridge_nativeBackendInfo(JNIEnv* env, jobject /* this */) {
    std::ostringstream json;
    json << "{"
         << "\"id\":\""
#ifdef MOBILECORE_USE_LLAMA_CPP
         << "android-llama-cpp"
#else
         << "android-llama-cpp-native-stub"
#endif
         << "\","
         << "\"backend\":\"llama.cpp\","
         << "\"status\":\"" << (
#ifdef MOBILECORE_USE_LLAMA_CPP
             "linked"
#else
             "stub"
#endif
         ) << "\","
         << "\"llamaLinked\":"
#ifdef MOBILECORE_USE_LLAMA_CPP
         << "true,"
#else
         << "false,"
#endif
         << "\"modelLoaded\":" << (g_model_loaded ? "true" : "false") << ","
         << "\"mtmdLoaded\":" << (g_mtmd_context != nullptr ? "true" : "false") << ","
         << "\"visionInput\":" << (g_mtmd_context != nullptr && g_mtmd_supports_vision ? "true" : "false") << ","
         << "\"audioInput\":" << (g_mtmd_context != nullptr && g_mtmd_supports_audio ? "true" : "false") << ","
         << "\"audioSampleRate\":" << (g_mtmd_context != nullptr && g_mtmd_supports_audio ? mtmd_get_audio_sample_rate(g_mtmd_context) : 0) << ","
         << "\"mtmdProjectorLoaded\":" << (g_mtmd_context != nullptr ? "true" : "false") << ","
         << "\"mtmdProjector\":\"" << escape_json(g_mmproj_path.empty() ? "" : model_id_from_path(g_mmproj_path)) << "\","
         << "\"mtmdSupportsVision\":" << (g_mtmd_supports_vision ? "true" : "false") << ","
         << "\"mtmdSupportsAudio\":" << (g_mtmd_supports_audio ? "true" : "false") << ","
         << "\"visionProjectorLoaded\":" << (g_mtmd_context != nullptr && g_mtmd_supports_vision ? "true" : "false") << ","
         << "\"visionProjector\":\"" << escape_json(g_mmproj_path.empty() ? "" : model_id_from_path(g_mmproj_path)) << "\","
         << "\"activeModel\":\"" << escape_json(g_model_id) << "\","
         << "\"lastPromptEvalMs\":" << g_last_prompt_eval_ms << ","
         << "\"lastFirstTokenMs\":" << g_last_first_token_ms << ","
         << "\"lastDecodeMs\":" << g_last_decode_ms << ","
         << "\"lastTotalMs\":" << g_last_total_ms << ","
         << "\"lastDecodeTokensPerSecond\":" << g_last_decode_tokens_per_second << ","
         << "\"lastPromptTokens\":" << g_last_prompt_tokens << ","
         << "\"lastCompletionTokens\":" << g_last_completion_tokens << ","
         << "\"lastMemoryPeakMb\":" << g_last_memory_mb << ","
         << "\"message\":\"JNI bridge is loaded"
#ifdef MOBILECORE_USE_LLAMA_CPP
         << " and linked to llama.cpp"
#else
         << "; real llama.cpp linkage is not enabled yet"
#endif
         << "\""
         << "}";
    return env->NewStringUTF(json.str().c_str());
}

JNIEXPORT jstring JNICALL
Java_ai_mobilecore_runtime_RuntimeBridge_nativeLoadMtmdProjector(
    JNIEnv* env,
    jobject /* this */,
    jstring projectorPath,
    jint threads,
    jint imageMinTokens,
    jint imageMaxTokens
) {
#ifdef MOBILECORE_USE_LLAMA_CPP
    const char* projector_path_utf = env->GetStringUTFChars(projectorPath, nullptr);
    const std::string projector_path = projector_path_utf == nullptr ? "" : projector_path_utf;
    if (projector_path_utf != nullptr) {
        env->ReleaseStringUTFChars(projectorPath, projector_path_utf);
    }
    if (g_model == nullptr || g_context == nullptr) {
        return env->NewStringUTF("{\"ok\":false,\"code\":\"artifact_missing\",\"message\":\"load the text model before the mtmd projector\"}");
    }
    if (projector_path.empty() || !file_exists(projector_path)) {
        return env->NewStringUTF("{\"ok\":false,\"code\":\"artifact_missing\",\"message\":\"mtmd projector not found\"}");
    }
    if (g_mtmd_context != nullptr) {
        mtmd_free(g_mtmd_context);
        g_mtmd_context = nullptr;
    }
    g_mmproj_path.clear();
    g_mtmd_supports_vision = false;
    g_mtmd_supports_audio = false;
    g_cached_media_id.clear();
    g_cached_media_embeddings.clear();
    g_cached_kv_media_id.clear();
    g_cached_kv_prefix_tokens.clear();
    g_cached_kv_prefix_n_past = 0;
    mtmd_context_params params = mtmd_context_params_default();
    params.use_gpu = false;
    params.print_timings = true;
    params.n_threads = std::max(1, static_cast<int32_t>(threads));
    params.warmup = false;
    params.image_min_tokens = std::max(0, static_cast<int32_t>(imageMinTokens));
    params.image_max_tokens = std::max(0, static_cast<int32_t>(imageMaxTokens));
    g_mtmd_context = mtmd_init_from_file(projector_path.c_str(), g_model, params);
    if (g_mtmd_context == nullptr) {
        return env->NewStringUTF("{\"ok\":false,\"code\":\"model_load_failed\",\"message\":\"libmtmd failed to load the projector\"}");
    }
    g_mtmd_supports_vision = mtmd_support_vision(g_mtmd_context);
    g_mtmd_supports_audio = mtmd_support_audio(g_mtmd_context);
    if (!g_mtmd_supports_vision && !g_mtmd_supports_audio) {
        if (g_mtmd_context != nullptr) {
            mtmd_free(g_mtmd_context);
            g_mtmd_context = nullptr;
        }
        g_mtmd_supports_vision = false;
        g_mtmd_supports_audio = false;
        return env->NewStringUTF("{\"ok\":false,\"code\":\"unsupported_modality\",\"message\":\"mtmd projector exposes neither image nor audio input\"}");
    }
    g_mmproj_path = projector_path;
    std::ostringstream json;
    json << "{\"ok\":true,\"backend\":\"llama.cpp/libmtmd\",\"projector\":\""
         << escape_json(model_id_from_path(g_mmproj_path)) << "\",\"visionInput\":"
         << (g_mtmd_supports_vision ? "true" : "false") << ",\"imageInput\":"
         << (g_mtmd_supports_vision ? "true" : "false") << ",\"audioInput\":"
         << (g_mtmd_supports_audio ? "true" : "false")
         << ",\"audioSampleRate\":" << (g_mtmd_supports_audio ? mtmd_get_audio_sample_rate(g_mtmd_context) : 0)
         << ",\"message\":\"mtmd projector loaded\"}";
    return env->NewStringUTF(json.str().c_str());
#else
    return env->NewStringUTF("{\"ok\":false,\"code\":\"model_load_failed\",\"message\":\"llama.cpp is unavailable\"}");
#endif
}

JNIEXPORT jstring JNICALL
Java_ai_mobilecore_runtime_RuntimeBridge_nativeMediaChat(
    JNIEnv* env,
    jobject /* this */,
    jstring modelId,
    jstring mediaPath,
    jstring modality,
    jstring prompt,
    jint maxTokens
) {
#ifdef MOBILECORE_USE_LLAMA_CPP
    const char* model_id_utf = env->GetStringUTFChars(modelId, nullptr);
    const char* media_path_utf = env->GetStringUTFChars(mediaPath, nullptr);
    const char* modality_utf = env->GetStringUTFChars(modality, nullptr);
    const char* prompt_utf = env->GetStringUTFChars(prompt, nullptr);
    const std::string requested_model = model_id_utf == nullptr ? g_model_id : model_id_utf;
    const std::string media_path = media_path_utf == nullptr ? "" : media_path_utf;
    const std::string requested_modality = modality_utf == nullptr ? "" : modality_utf;
    const std::string user_prompt = prompt_utf == nullptr ? "" : prompt_utf;
    if (model_id_utf != nullptr) env->ReleaseStringUTFChars(modelId, model_id_utf);
    if (media_path_utf != nullptr) env->ReleaseStringUTFChars(mediaPath, media_path_utf);
    if (modality_utf != nullptr) env->ReleaseStringUTFChars(modality, modality_utf);
    if (prompt_utf != nullptr) env->ReleaseStringUTFChars(prompt, prompt_utf);

    if (requested_modality != "image" && requested_modality != "audio") {
        const std::string json = chat_json(false, requested_model, "only image or audio input is supported", 0, 0, 0, 0, 0, 0, 0.0, g_last_memory_mb, "unsupported_modality");
        return env->NewStringUTF(json.c_str());
    }
    if (g_model == nullptr || g_context == nullptr || g_mtmd_context == nullptr) {
        const std::string json = chat_json(false, requested_model, "model or mtmd projector is not loaded", 0, 0, 0, 0, 0, 0, 0.0, g_last_memory_mb, "artifact_missing");
        return env->NewStringUTF(json.c_str());
    }
    const bool expects_audio = requested_modality == "audio";
    const bool modality_supported = expects_audio ? g_mtmd_supports_audio : g_mtmd_supports_vision;
    if (!modality_supported) {
        const std::string json = chat_json(false, requested_model, requested_modality + " input is not supported by the active mtmd projector", 0, 0, 0, 0, 0, 0, 0.0, g_last_memory_mb, "unsupported_modality");
        return env->NewStringUTF(json.c_str());
    }
    if (media_path.empty() || !file_exists(media_path)) {
        const std::string json = chat_json(false, requested_model, "media file is unavailable", 0, 0, 0, 0, 0, 0, 0.0, g_last_memory_mb, "media_missing");
        return env->NewStringUTF(json.c_str());
    }

    g_cancel_requested.store(false, std::memory_order_release);
    const auto total_start = std::chrono::steady_clock::now();
    mtmd_helper_bitmap_wrapper media = mtmd_helper_bitmap_init_from_file(g_mtmd_context, media_path.c_str(), false);
    if (media.bitmap == nullptr) {
        const std::string json = chat_json(false, requested_model, "libmtmd failed to decode media", 0, 0, 0, 0, 0, 0, 0.0, g_last_memory_mb, "media_decode_failed");
        return env->NewStringUTF(json.c_str());
    }
    if (media.video_ctx != nullptr) {
        mtmd_bitmap_free(media.bitmap);
        mtmd_helper_video_free(media.video_ctx);
        const std::string json = chat_json(false, requested_model, "video input is not supported", 0, 0, 0, 0, 0, 0, 0.0, g_last_memory_mb, "unsupported_modality");
        return env->NewStringUTF(json.c_str());
    }
    if (mtmd_bitmap_is_audio(media.bitmap) != expects_audio) {
        mtmd_bitmap_free(media.bitmap);
        const std::string json = chat_json(false, requested_model, "decoded media does not match the declared modality", 0, 0, 0, 0, 0, 0, 0.0, g_last_memory_mb, "unsupported_modality");
        return env->NewStringUTF(json.c_str());
    }

    const std::string formatted = format_mtmd_user_prompt(user_prompt);
    mtmd_input_text input_text;
    input_text.text = formatted.c_str();
    input_text.add_special = true;
    input_text.parse_special = true;
    mtmd_input_chunks* chunks = mtmd_input_chunks_init();
    const mtmd_bitmap* bitmaps[] = {media.bitmap};
    const int32_t tokenize_result = mtmd_tokenize(g_mtmd_context, chunks, &input_text, bitmaps, 1);
    if (tokenize_result != 0) {
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(media.bitmap);
        const std::string json = chat_json(false, requested_model, "libmtmd prompt tokenization failed", 0, 0, 0, 0, 0, 0, 0.0, g_last_memory_mb, "model_load_failed");
        return env->NewStringUTF(json.c_str());
    }

    const int32_t n_prompt = static_cast<int32_t>(mtmd_helper_get_n_tokens(chunks));
    llama_pos n_past = 0;
    const auto prompt_eval_start = std::chrono::steady_clock::now();
    const int32_t eval_result = eval_mtmd_chunks_with_media_cache(chunks, 512, &n_past);
    mtmd_input_chunks_free(chunks);
    mtmd_bitmap_free(media.bitmap);
    if (eval_result != 0) {
        const std::string json = chat_json(false, requested_model, "libmtmd media/prompt evaluation failed", n_prompt, 0, 0, 0, 0, 0, 0.0, g_last_memory_mb, "model_load_failed");
        return env->NewStringUTF(json.c_str());
    }
    const auto prompt_eval_end = std::chrono::steady_clock::now();
    const long long prompt_eval_ms = elapsed_ms(prompt_eval_start, prompt_eval_end);

    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    std::string generated;
    int32_t n_decode = 0;
    long long first_token_ms = 0;
    const int32_t capped_max_tokens = std::max(1, std::min(maxTokens, 128));
    const auto decode_start = std::chrono::steady_clock::now();
    bool cancelled = false;
    while (n_decode < capped_max_tokens) {
        if (g_cancel_requested.load(std::memory_order_acquire)) {
            cancelled = true;
            break;
        }
        const llama_token token = llama_sampler_sample(sampler, g_context, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        char piece[512];
        const int32_t n_piece = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, true);
        if (n_piece > 0) generated.append(piece, n_piece);
        llama_sampler_accept(sampler, token);
        n_decode += 1;
        if (n_decode == 1) first_token_ms = elapsed_ms(total_start, std::chrono::steady_clock::now());
        if (n_decode >= capped_max_tokens) break;
        llama_batch batch = llama_batch_get_one(const_cast<llama_token*>(&token), 1);
        if (llama_decode(g_context, batch) != 0) {
            llama_sampler_free(sampler);
            const std::string json = chat_json(false, requested_model, "llama.cpp multimodal token decode failed", n_prompt, n_decode, prompt_eval_ms, first_token_ms, 0, 0, 0.0, g_last_memory_mb, "model_load_failed");
            return env->NewStringUTF(json.c_str());
        }
    }
    const auto decode_end = std::chrono::steady_clock::now();
    llama_sampler_free(sampler);
    if (cancelled) generated = "cancelled";
    if (generated.empty() && !cancelled) generated = "llama.cpp generated an empty response";
    const long long decode_ms = elapsed_ms(decode_start, decode_end);
    const long long total_ms = elapsed_ms(total_start, decode_end);
    const double decode_tps = decode_ms > 0 ? static_cast<double>(n_decode) * 1000.0 / decode_ms : 0.0;
    g_last_prompt_eval_ms = prompt_eval_ms;
    g_last_first_token_ms = first_token_ms;
    g_last_decode_ms = decode_ms;
    g_last_total_ms = total_ms;
    g_last_decode_tokens_per_second = decode_tps;
    g_last_prompt_tokens = n_prompt;
    g_last_completion_tokens = n_decode;
    const std::string json = chat_json(!cancelled, requested_model, generated, n_prompt, n_decode, prompt_eval_ms, first_token_ms, decode_ms, total_ms, decode_tps, g_last_memory_mb, cancelled ? "cancelled" : "");
    return env->NewStringUTF(json.c_str());
#else
    return env->NewStringUTF("{\"ok\":false,\"code\":\"model_load_failed\",\"message\":\"llama.cpp is unavailable\"}");
#endif
}

JNIEXPORT jstring JNICALL
Java_ai_mobilecore_runtime_RuntimeBridge_nativeLoadModel(
    JNIEnv* env,
    jobject /* this */,
    jstring modelPath,
    jint contextLength,
    jint threads
) {
    const char* model_path_utf = env->GetStringUTFChars(modelPath, nullptr);
    g_model_path = model_path_utf == nullptr ? "" : model_path_utf;
    if (model_path_utf != nullptr) {
        env->ReleaseStringUTFChars(modelPath, model_path_utf);
    }

    g_model_id = model_id_from_path(g_model_path);

#ifdef MOBILECORE_USE_LLAMA_CPP
    if (g_model_path.empty() || !file_exists(g_model_path)) {
        g_model_loaded = false;
        std::ostringstream json;
        json << "{"
             << "\"ok\":false,"
             << "\"code\":\"artifact_missing\","
             << "\"modelId\":\"" << escape_json(g_model_id) << "\","
             << "\"backend\":\"llama.cpp\","
             << "\"message\":\"model artifact not found\""
             << "}";
        return env->NewStringUTF(json.str().c_str());
    }

    ensure_backend_initialized();
    release_llama_state();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(g_model_path.c_str(), model_params);
    if (g_model == nullptr) {
        g_model_loaded = false;
        std::ostringstream json;
        json << "{"
             << "\"ok\":false,"
             << "\"modelId\":\"" << escape_json(g_model_id) << "\","
             << "\"backend\":\"llama.cpp\","
             << "\"message\":\"llama_model_load_from_file failed\""
             << "}";
        return env->NewStringUTF(json.str().c_str());
    }

    const uint32_t n_ctx = static_cast<uint32_t>(std::max(128, contextLength));
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = n_ctx;
    ctx_params.n_batch = n_ctx;
    ctx_params.n_ubatch = std::min<uint32_t>(n_ctx, 512);
    const int32_t requested_threads = std::max(1, static_cast<int32_t>(threads));
    ctx_params.n_threads = requested_threads;
    ctx_params.n_threads_batch = requested_threads;
    g_context = llama_init_from_model(g_model, ctx_params);
    if (g_context == nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
        g_model_loaded = false;
        std::ostringstream json;
        json << "{"
             << "\"ok\":false,"
             << "\"modelId\":\"" << escape_json(g_model_id) << "\","
             << "\"backend\":\"llama.cpp\","
             << "\"message\":\"llama_init_from_model failed\""
             << "}";
        return env->NewStringUTF(json.str().c_str());
    }

    g_model_loaded = true;
    g_last_memory_mb = static_cast<long long>(llama_model_size(g_model) / (1024 * 1024));

    std::ostringstream json;
    json << "{"
         << "\"ok\":true,"
         << "\"modelId\":\"" << escape_json(g_model_id) << "\","
         << "\"contextLength\":" << contextLength << ","
         << "\"threads\":" << requested_threads << ","
         << "\"memoryUsedMb\":" << g_last_memory_mb << ","
         << "\"backend\":\"llama.cpp\","
         << "\"message\":\"llama.cpp model and context loaded\""
         << "}";
    return env->NewStringUTF(json.str().c_str());
#else
    g_model_loaded = true;
    std::ostringstream json;
    json << "{"
         << "\"ok\":true,"
         << "\"modelId\":\"" << escape_json(g_model_id) << "\","
         << "\"contextLength\":" << contextLength << ","
         << "\"threads\":" << std::max(1, static_cast<int32_t>(threads)) << ","
         << "\"memoryUsedMb\":120,"
         << "\"backend\":\"llama.cpp-native-stub\","
         << "\"message\":\"model state loaded in JNI stub; real llama.cpp context is not linked yet\""
         << "}";
    return env->NewStringUTF(json.str().c_str());
#endif
}

JNIEXPORT jstring JNICALL
Java_ai_mobilecore_runtime_RuntimeBridge_nativeChat(
    JNIEnv* env,
    jobject /* this */,
    jstring modelId,
    jstring prompt,
    jint maxTokens,
    jfloat temperature
) {
    g_cancel_requested.store(false, std::memory_order_release);
    const char* modelIdUtf = env->GetStringUTFChars(modelId, nullptr);
    const char* promptUtf = env->GetStringUTFChars(prompt, nullptr);

    const std::string requested_model = modelIdUtf == nullptr ? "local-model" : modelIdUtf;
    const std::string prompt_text = promptUtf == nullptr ? "" : promptUtf;

    if (modelIdUtf != nullptr) {
        env->ReleaseStringUTFChars(modelId, modelIdUtf);
    }
    if (promptUtf != nullptr) {
        env->ReleaseStringUTFChars(prompt, promptUtf);
    }

#ifdef MOBILECORE_USE_LLAMA_CPP
    if (g_model != nullptr && g_context != nullptr) {
        const auto total_start = std::chrono::steady_clock::now();
        const llama_vocab* vocab = llama_model_get_vocab(g_model);
        const int32_t n_prompt = -llama_tokenize(
            vocab,
            prompt_text.c_str(),
            static_cast<int32_t>(prompt_text.size()),
            nullptr,
            0,
            true,
            true
        );
        if (n_prompt <= 0) {
            const std::string json = chat_json(false, requested_model, "llama.cpp error: failed to tokenize prompt", 0, 0, 0, 0, 0, 0, 0.0, g_last_memory_mb);
            return env->NewStringUTF(json.c_str());
        }

        std::vector<llama_token> prompt_tokens(n_prompt);
        const int32_t tokenized = llama_tokenize(
            vocab,
            prompt_text.c_str(),
            static_cast<int32_t>(prompt_text.size()),
            prompt_tokens.data(),
            static_cast<int32_t>(prompt_tokens.size()),
            true,
            true
        );
        if (tokenized < 0) {
            const std::string json = chat_json(false, requested_model, "llama.cpp error: prompt tokenization overflow", 0, 0, 0, 0, 0, 0, 0.0, g_last_memory_mb);
            return env->NewStringUTF(json.c_str());
        }

        llama_memory_clear(llama_get_memory(g_context), true);

        llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

        llama_batch batch = llama_batch_get_one(prompt_tokens.data(), static_cast<int32_t>(prompt_tokens.size()));
        const auto prompt_eval_start = std::chrono::steady_clock::now();
        if (llama_model_has_encoder(g_model)) {
            if (llama_encode(g_context, batch) != 0) {
                llama_sampler_free(sampler);
                const std::string json = chat_json(false, requested_model, "llama.cpp error: encoder decode failed", n_prompt, 0, 0, 0, 0, 0, 0.0, g_last_memory_mb);
                return env->NewStringUTF(json.c_str());
            }
            llama_token decoder_start_token_id = llama_model_decoder_start_token(g_model);
            if (decoder_start_token_id == LLAMA_TOKEN_NULL) {
                decoder_start_token_id = llama_vocab_bos(vocab);
            }
            batch = llama_batch_get_one(&decoder_start_token_id, 1);
            if (llama_decode(g_context, batch) != 0) {
                llama_sampler_free(sampler);
                const std::string json = chat_json(false, requested_model, "llama.cpp error: decoder start failed", n_prompt, 0, 0, 0, 0, 0, 0.0, g_last_memory_mb);
                return env->NewStringUTF(json.c_str());
            }
        } else if (llama_decode(g_context, batch) != 0) {
            llama_sampler_free(sampler);
            const std::string json = chat_json(false, requested_model, "llama.cpp error: prompt eval failed", n_prompt, 0, 0, 0, 0, 0, 0.0, g_last_memory_mb);
            return env->NewStringUTF(json.c_str());
        }
        const auto prompt_eval_end = std::chrono::steady_clock::now();
        const long long prompt_eval_ms = elapsed_ms(prompt_eval_start, prompt_eval_end);

        std::string generated;
        int32_t n_decode = 0;
        const int32_t capped_max_tokens = std::max(1, std::min(maxTokens, 256));
        const auto decode_start = std::chrono::steady_clock::now();
        long long first_token_ms = 0;

        bool cancelled = false;
        while (n_decode < capped_max_tokens) {
            if (g_cancel_requested.load(std::memory_order_acquire)) {
                cancelled = true;
                break;
            }
            llama_token token = llama_sampler_sample(sampler, g_context, -1);
            if (llama_vocab_is_eog(vocab, token)) {
                break;
            }

            char piece[256];
            const int32_t n_piece = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, true);
            if (n_piece > 0) {
                generated.append(piece, n_piece);
            }

            batch = llama_batch_get_one(&token, 1);
            llama_sampler_accept(sampler, token);
            n_decode += 1;
            if (n_decode == 1) {
                first_token_ms = elapsed_ms(total_start, std::chrono::steady_clock::now());
            }

            if (n_decode >= capped_max_tokens) {
                break;
            }

            if (g_cancel_requested.load(std::memory_order_acquire)) {
                cancelled = true;
                break;
            }
            if (llama_decode(g_context, batch) != 0) {
                llama_sampler_free(sampler);
                const std::string json = chat_json(false, requested_model, "llama.cpp error: token decode failed", n_prompt, n_decode, prompt_eval_ms, first_token_ms, 0, 0, 0.0, g_last_memory_mb);
                return env->NewStringUTF(json.c_str());
            }
        }

        const auto decode_end = std::chrono::steady_clock::now();
        llama_sampler_free(sampler);

        if (cancelled) {
            generated = "cancelled";
        } else if (generated.empty()) {
            generated = "llama.cpp generated an empty response";
        }

        const long long decode_ms = elapsed_ms(decode_start, decode_end);
        const long long total_ms = elapsed_ms(total_start, decode_end);
        const double decode_tps = decode_ms > 0
            ? static_cast<double>(n_decode) * 1000.0 / static_cast<double>(decode_ms)
            : 0.0;
        const long long memory_mb = g_model != nullptr
            ? static_cast<long long>(llama_model_size(g_model) / (1024 * 1024))
            : g_last_memory_mb;

        g_last_prompt_eval_ms = prompt_eval_ms;
        g_last_first_token_ms = first_token_ms;
        g_last_decode_ms = decode_ms;
        g_last_total_ms = total_ms;
        g_last_decode_tokens_per_second = decode_tps;
        g_last_prompt_tokens = n_prompt;
        g_last_completion_tokens = n_decode;
        g_last_memory_mb = memory_mb;

        const std::string json = chat_json(
            !cancelled,
            requested_model,
            generated,
            n_prompt,
            n_decode,
            prompt_eval_ms,
            first_token_ms,
            decode_ms,
            total_ms,
            decode_tps,
            memory_mb
        );
        return env->NewStringUTF(json.c_str());
    }
#endif

    std::ostringstream result;
    result << "Native stub response: model=" << requested_model
           << ", loaded=" << (g_model_loaded ? "true" : "false")
           << ", maxTokens=" << maxTokens
           << ", temperature=" << temperature
           << ". Prompt: " << prompt_text;

    const int32_t prompt_tokens = static_cast<int32_t>(prompt_text.size() / 4);
    const int32_t completion_tokens = static_cast<int32_t>(result.str().size() / 4);
    const std::string json = chat_json(
        true,
        requested_model,
        result.str(),
        prompt_tokens,
        completion_tokens,
        0,
        0,
        0,
        0,
        0.0,
        g_last_memory_mb
    );
    return env->NewStringUTF(json.c_str());
}

JNIEXPORT void JNICALL
Java_ai_mobilecore_runtime_RuntimeBridge_nativeCancel(JNIEnv* /* env */, jobject /* this */) {
    g_cancel_requested.store(true, std::memory_order_release);
}

JNIEXPORT void JNICALL
Java_ai_mobilecore_runtime_RuntimeBridge_nativeUnload(JNIEnv* /* env */, jobject /* this */) {
#ifdef MOBILECORE_USE_LLAMA_CPP
    release_llama_state();
#else
    g_model_loaded = false;
    g_model_path.clear();
#endif
}

} // extern "C"

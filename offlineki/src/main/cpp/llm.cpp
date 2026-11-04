// Native KI-Kern: lädt ein GGUF-Sprachmodell mit llama.cpp und erzeugt Antworten
// komplett lokal auf dem Gerät (CPU des Snapdragon, ARM-NEON/dotprod/i8mm).
//
// Der Chatverlauf kommt bei jeder Anfrage vollständig aus Kotlin. Damit nicht jedes
// Mal alles neu gerechnet werden muss, merken wir uns, welche Tokens schon im
// KV-Cache liegen, und rechnen nur den neuen Teil (gemeinsamer Präfix wird
// wiederverwendet).

#include <jni.h>
#include <android/log.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "OfflineKI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

llama_model *g_model = nullptr;
llama_context *g_ctx = nullptr;
llama_batch_ext *g_batch = nullptr;
std::vector<llama_token> g_cached;   // Tokens, die aktuell im KV-Cache stehen
std::atomic<bool> g_stop{false};

void log_callback(ggml_log_level level, const char *text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) {
        __android_log_print(level >= GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN,
                            TAG, "%s", text);
    }
}

void free_all() {
    if (g_batch) { llama_batch_ext_free(g_batch); g_batch = nullptr; }
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_cached.clear();
}

std::string jstring_to_std(JNIEnv *env, jstring s) {
    if (!s) return {};
    // Über getBytes("UTF-8"), damit auch Emojis (4-Byte-UTF-8) korrekt ankommen.
    jclass strCls = env->GetObjectClass(s);
    jmethodID getBytes = env->GetMethodID(strCls, "getBytes", "(Ljava/lang/String;)[B");
    jstring charset = env->NewStringUTF("UTF-8");
    auto bytes = (jbyteArray) env->CallObjectMethod(s, getBytes, charset);
    jsize len = env->GetArrayLength(bytes);
    std::string out(len, '\0');
    env->GetByteArrayRegion(bytes, 0, len, reinterpret_cast<jbyte *>(out.data()));
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(strCls);
    return out;
}

// Länge des längsten Präfixes, der aus vollständigen UTF-8-Zeichen besteht.
size_t complete_utf8_prefix(const std::string &s) {
    size_t i = 0, last_ok = 0;
    while (i < s.size()) {
        auto c = (unsigned char) s[i];
        size_t n = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 1;
        if (i + n > s.size()) break;
        i += n;
        last_ok = i;
    }
    return last_ok;
}

bool format_chat(const std::vector<std::string> &roles, const std::vector<std::string> &contents,
                 size_t first, std::string &out) {
    std::vector<llama_chat_message> msgs;
    // Systemnachricht (Index 0) bleibt immer erhalten, ältere Nachrichten ab "first".
    if (!roles.empty() && roles[0] == "system") {
        msgs.push_back({roles[0].c_str(), contents[0].c_str()});
        first = std::max<size_t>(first, 1);
    }
    for (size_t i = first; i < roles.size(); ++i) {
        msgs.push_back({roles[i].c_str(), contents[i].c_str()});
    }
    const char *tmpl = llama_model_chat_template(g_model, nullptr);
    std::vector<char> buf(16384);
    int n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int) buf.size());
    if (n > (int) buf.size()) {
        buf.resize(n);
        n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int) buf.size());
    }
    if (n < 0) return false;
    out.assign(buf.data(), n);
    return true;
}

std::vector<llama_token> tokenize(const std::string &text) {
    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    int n = -llama_tokenize(vocab, text.c_str(), (int) text.size(), nullptr, 0, true, true);
    std::vector<llama_token> toks(std::max(n, 0));
    if (n > 0 && llama_tokenize(vocab, text.c_str(), (int) text.size(), toks.data(), n, true, true) < 0) {
        toks.clear();
    }
    return toks;
}

// Tokens ab Position pos0 dekodieren; Logits nur für das letzte Token.
bool decode(const llama_token *toks, int n, llama_pos pos0) {
    const int n_batch = (int) llama_n_batch(g_ctx);
    for (int i = 0; i < n; i += n_batch) {
        const int m = std::min(n_batch, n - i);
        llama_batch_ext_clear(g_batch);
        for (int j = 0; j < m; ++j) {
            const int32_t idx = llama_batch_ext_add_token(g_batch, 0, toks[i + j]);
            const llama_pos pos = pos0 + i + j;
            llama_batch_ext_set_pos(g_batch, idx, &pos);
        }
        if (i + m == n) llama_batch_ext_set_output_logits(g_batch, m - 1, true);
        if (llama_process(g_ctx, LLAMA_PROCESS_TYPE_DECODE, g_batch) != 0) return false;
        if (g_stop.load()) return false;
    }
    return true;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_de_offline_ki_LlamaNative_nativeInit(JNIEnv *, jobject) {
    llama_log_set(log_callback, nullptr);
    llama_backend_init();
}

JNIEXPORT jstring JNICALL
Java_de_offline_ki_LlamaNative_loadModel(JNIEnv *env, jobject, jstring jpath, jint n_ctx, jint n_threads) {
    free_all();
    const std::string path = jstring_to_std(env, jpath);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;   // reine CPU – auf dem Snapdragon 8 Elite am zuverlässigsten
    g_model = llama_model_load_from_file(path.c_str(), mp);
    if (!g_model) return env->NewStringUTF("Modell konnte nicht geladen werden (ist es eine gültige .gguf-Datei?)");

    const int train_ctx = llama_model_n_ctx_train(g_model);
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) std::min<int>(n_ctx, train_ctx > 0 ? train_ctx : n_ctx);
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) {
        free_all();
        return env->NewStringUTF("Zu wenig Arbeitsspeicher für dieses Modell/diese Kontextgröße");
    }
    g_batch = llama_batch_ext_init(g_ctx);
    LOGI("Modell geladen: %s, n_ctx=%u, threads=%d", path.c_str(), llama_n_ctx(g_ctx), n_threads);
    return nullptr;   // null = Erfolg
}

JNIEXPORT jstring JNICALL
Java_de_offline_ki_LlamaNative_modelInfo(JNIEnv *env, jobject) {
    if (!g_model) return env->NewStringUTF("");
    char desc[256];
    llama_model_desc(g_model, desc, sizeof(desc));
    return env->NewStringUTF(desc);
}

JNIEXPORT void JNICALL
Java_de_offline_ki_LlamaNative_stop(JNIEnv *, jobject) {
    g_stop.store(true);
}

JNIEXPORT void JNICALL
Java_de_offline_ki_LlamaNative_resetCache(JNIEnv *, jobject) {
    if (g_ctx) llama_memory_clear(llama_get_memory(g_ctx), true);
    g_cached.clear();
}

// Erzeugt die Antwort auf den gegebenen Chatverlauf. Jedes neue Textstück wird als
// UTF-8-Bytes an callback.onToken(byte[]) gegeben. Rückgabe: null oder Fehlermeldung.
JNIEXPORT jstring JNICALL
Java_de_offline_ki_LlamaNative_generate(JNIEnv *env, jobject, jobjectArray jroles, jobjectArray jcontents,
                                        jfloat temperature, jint max_tokens, jobject callback) {
    if (!g_ctx) return env->NewStringUTF("Kein Modell geladen");
    g_stop.store(false);

    std::vector<std::string> roles, contents;
    const jsize n_msg = env->GetArrayLength(jroles);
    for (jsize i = 0; i < n_msg; ++i) {
        auto r = (jstring) env->GetObjectArrayElement(jroles, i);
        auto c = (jstring) env->GetObjectArrayElement(jcontents, i);
        roles.push_back(jstring_to_std(env, r));
        contents.push_back(jstring_to_std(env, c));
        env->DeleteLocalRef(r);
        env->DeleteLocalRef(c);
    }

    // Prompt bauen; wenn er nicht in den Kontext passt, die ältesten Nachrichten weglassen.
    const int n_ctx = (int) llama_n_ctx(g_ctx);
    const int reserve = std::min<int>(max_tokens, n_ctx / 2);
    std::vector<llama_token> prompt;
    for (size_t first = 0; ; ++first) {
        std::string text;
        if (!format_chat(roles, contents, first, text)) return env->NewStringUTF("Chat-Vorlage des Modells nicht unterstützt");
        prompt = tokenize(text);
        if ((int) prompt.size() + reserve <= n_ctx) break;
        if (first + 1 >= roles.size()) return env->NewStringUTF("Nachricht ist zu lang");
    }
    if (prompt.empty()) return env->NewStringUTF("Leerer Prompt");

    // Gemeinsamen Anfang mit dem KV-Cache wiederverwenden.
    size_t common = 0;
    while (common < g_cached.size() && common < prompt.size() && g_cached[common] == prompt[common]) ++common;
    if (common == prompt.size()) --common;   // letztes Token neu rechnen, um Logits zu bekommen
    llama_memory_t mem = llama_get_memory(g_ctx);
    if (!llama_memory_seq_rm(mem, 0, (llama_pos) common, -1)) {
        llama_memory_clear(mem, true);
        common = 0;
    }
    g_cached.assign(prompt.begin(), prompt.begin() + (long) common);

    if (!decode(prompt.data() + common, (int) (prompt.size() - common), (llama_pos) common)) {
        llama_memory_clear(mem, true);
        g_cached.clear();
        return g_stop.load() ? nullptr : env->NewStringUTF("Fehler beim Verarbeiten der Eingabe");
    }
    g_cached.insert(g_cached.end(), prompt.begin() + (long) common, prompt.end());

    // Sampler: abwechslungsreich, aber ohne Wiederholungen – klingt natürlicher.
    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(64));
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 256, 1.08f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    jclass cbCls = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbCls, "onToken", "([B)Z");

    std::string pending;
    jstring error = nullptr;
    for (int i = 0; i < max_tokens && !g_stop.load(); ++i) {
        if ((int) g_cached.size() >= n_ctx) break;
        llama_token tok = llama_sampler_sample(smpl, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;

        char buf[256];
        int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, false);
        if (n > 0) pending.append(buf, n);

        const size_t ok = complete_utf8_prefix(pending);
        if (ok > 0) {
            jbyteArray arr = env->NewByteArray((jsize) ok);
            env->SetByteArrayRegion(arr, 0, (jsize) ok, reinterpret_cast<const jbyte *>(pending.data()));
            const jboolean go_on = env->CallBooleanMethod(callback, onToken, arr);
            env->DeleteLocalRef(arr);
            pending.erase(0, ok);
            if (!go_on) g_stop.store(true);
        }
        if (g_stop.load()) break;

        if (!decode(&tok, 1, (llama_pos) g_cached.size())) {
            if (!g_stop.load()) error = env->NewStringUTF("Fehler beim Erzeugen der Antwort");
            // Cache-Zustand ist unklar -> beim nächsten Mal sauber neu rechnen
            llama_memory_clear(mem, true);
            g_cached.clear();
            break;
        }
        g_cached.push_back(tok);
    }
    llama_sampler_free(smpl);
    env->DeleteLocalRef(cbCls);
    return error;
}

JNIEXPORT void JNICALL
Java_de_offline_ki_LlamaNative_unload(JNIEnv *, jobject) {
    free_all();
}

} // extern "C"

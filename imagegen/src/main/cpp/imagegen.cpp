// Native Bildgenerierung mit stable-diffusion.cpp – läuft komplett auf dem Gerät.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstdlib>
#include <string>

#include "stable-diffusion.h"

#define TAG "OfflineKI-Bild"

namespace {

sd_ctx_t *g_sd = nullptr;
JavaVM *g_vm = nullptr;
jobject g_progress = nullptr;      // globale Referenz auf den Kotlin-Callback
jmethodID g_on_progress = nullptr;
std::atomic<bool> g_busy{false};

void log_cb(sd_log_level_t level, const char *text, void *) {
    if (level >= SD_LOG_WARN) {
        __android_log_print(level >= SD_LOG_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, TAG, "%s", text);
    } else {
        __android_log_print(ANDROID_LOG_DEBUG, TAG, "%s", text);
    }
}

void progress_cb(int step, int steps, float, void *) {
    if (!g_vm || !g_progress) return;
    JNIEnv *env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
#ifdef __ANDROID__
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
#else
        if (g_vm->AttachCurrentThread(reinterpret_cast<void **>(&env), nullptr) != JNI_OK) return;
#endif
        attached = true;
    }
    env->CallVoidMethod(g_progress, g_on_progress, (jint) step, (jint) steps);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) g_vm->DetachCurrentThread();
}

std::string to_std(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    sd_set_log_callback(log_cb, nullptr);
    sd_set_progress_callback(progress_cb, nullptr);
    return JNI_VERSION_1_6;
}

/** @return null bei Erfolg, sonst Fehlermeldung */
JNIEXPORT jstring JNICALL
Java_de_offline_ki_bild_ImageNative_load(JNIEnv *env, jobject, jstring jpath, jint n_threads) {
    if (g_sd) { free_sd_ctx(g_sd); g_sd = nullptr; }
    const std::string path = to_std(env, jpath);

    sd_ctx_params_t p;
    sd_ctx_params_init(&p);
    p.model_path = path.c_str();
    p.n_threads = n_threads;
    p.enable_mmap = true;
    g_sd = new_sd_ctx(&p);
    if (!g_sd) return env->NewStringUTF("Bildmodell konnte nicht geladen werden (Format nicht unterstützt?)");
    if (!sd_ctx_supports_image_generation(g_sd)) {
        free_sd_ctx(g_sd);
        g_sd = nullptr;
        return env->NewStringUTF("Diese Datei ist kein Bildmodell");
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "Bildmodell geladen: %s (%s)", path.c_str(),
                        sd_get_model_version_name(g_sd));
    return nullptr;
}

JNIEXPORT jstring JNICALL
Java_de_offline_ki_bild_ImageNative_modelVersion(JNIEnv *env, jobject) {
    return env->NewStringUTF(g_sd ? sd_get_model_version_name(g_sd) : "");
}

/**
 * Erzeugt ein Bild. sampler < 0 = Standard des Modells.
 * @return ARGB-Pixel (width*height) oder null bei Fehler/Abbruch.
 */
JNIEXPORT jintArray JNICALL
Java_de_offline_ki_bild_ImageNative_generate(JNIEnv *env, jobject, jstring jprompt, jstring jnegative,
                                            jint width, jint height, jint steps, jfloat cfg,
                                            jint sampler, jlong seed, jobject progress) {
    if (!g_sd) return nullptr;
    g_busy.store(true);
    sd_cancel_generation(g_sd, SD_CANCEL_RESET);

    g_progress = progress ? env->NewGlobalRef(progress) : nullptr;
    if (g_progress) {
        jclass cls = env->GetObjectClass(g_progress);
        g_on_progress = env->GetMethodID(cls, "onProgress", "(II)V");
        env->DeleteLocalRef(cls);
    }

    const std::string prompt = to_std(env, jprompt);
    const std::string negative = to_std(env, jnegative);

    sd_img_gen_params_t gp;
    sd_img_gen_params_init(&gp);
    gp.prompt = prompt.c_str();
    gp.negative_prompt = negative.c_str();
    gp.width = width;
    gp.height = height;
    gp.seed = seed;
    gp.batch_count = 1;
    gp.sample_params.sample_steps = steps;
    gp.sample_params.guidance.txt_cfg = cfg;
    if (sampler >= 0 && sampler < SAMPLE_METHOD_COUNT) {
        gp.sample_params.sample_method = (sample_method_t) sampler;
    }

    sd_image_t *images = nullptr;
    int n_images = 0;
    const bool ok = generate_image(g_sd, &gp, &images, &n_images);

    if (g_progress) { env->DeleteGlobalRef(g_progress); g_progress = nullptr; }
    g_busy.store(false);

    jintArray result = nullptr;
    if (ok && images && n_images > 0 && images[0].data) {
        const sd_image_t &img = images[0];
        const int n = (int) (img.width * img.height);
        auto *argb = new jint[n];
        for (int i = 0; i < n; ++i) {
            const uint8_t *px = img.data + (size_t) i * img.channel;
            const uint8_t r = px[0];
            const uint8_t g = img.channel > 1 ? px[1] : r;
            const uint8_t b = img.channel > 2 ? px[2] : r;
            argb[i] = (jint) (0xFF000000u | (r << 16) | (g << 8) | b);
        }
        result = env->NewIntArray(n);
        env->SetIntArrayRegion(result, 0, n, argb);
        delete[] argb;
    }
    free_sd_images(images, n_images);
    return result;
}

JNIEXPORT void JNICALL
Java_de_offline_ki_bild_ImageNative_cancel(JNIEnv *, jobject) {
    if (g_sd && g_busy.load()) sd_cancel_generation(g_sd, SD_CANCEL_ALL);
}

JNIEXPORT void JNICALL
Java_de_offline_ki_bild_ImageNative_unload(JNIEnv *, jobject) {
    if (g_sd) { free_sd_ctx(g_sd); g_sd = nullptr; }
}

} // extern "C"

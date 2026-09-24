/*
 * needle_jni.c - JNI bridge between dev.citali.needle.engine.NeedleNative and the
 * Needle C engine (Cactus Compute).
 *
 * The engine owns one process-global, non-thread-safe model, so every call here
 * happens under a single mutex. The Kotlin side additionally serialises calls on
 * one dedicated dispatcher thread.
 *
 * The weight archive is memory-mapped rather than copied: the engine reads it in
 * place, and a second copy of a 35 MB archive would be wasted RAM.
 */

#include <jni.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <android/log.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#if NEEDLE_ENGINE_AVAILABLE
#include "needle.h"
#endif

#define LOG_TAG "NeedleJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* needle_complete writes a NUL-terminated JSON envelope; 256 KB is far more than
   a 512-token reply needs and keeps the fast path allocation-free. */
#define NEEDLE_OUT_CAPACITY (256 * 1024)

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static void *g_weights = NULL;
static size_t g_weights_len = 0;
static char *g_out = NULL;
static int g_loaded = 0;

static char *out_buffer(void) {
    if (g_out == NULL) {
        g_out = (char *) malloc(NEEDLE_OUT_CAPACITY);
        if (g_out != NULL) {
            g_out[0] = '\0';
        }
    }
    return g_out;
}

static jstring new_string(JNIEnv *env, const char *value) {
    if (value == NULL) {
        return NULL;
    }
    return (*env)->NewStringUTF(env, value);
}

JNIEXPORT jboolean JNICALL
Java_dev_citali_needle_engine_NeedleNative_nativeEngineAvailable(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
#if NEEDLE_ENGINE_AVAILABLE
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jint JNICALL
Java_dev_citali_needle_engine_NeedleNative_nativeLoadModel(JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
#if !NEEDLE_ENGINE_AVAILABLE
    (void) path;
    return -1;
#else
    if (path == NULL) {
        return -1;
    }
    const char *c_path = (*env)->GetStringUTFChars(env, path, NULL);
    if (c_path == NULL) {
        return -1;
    }

    pthread_mutex_lock(&g_lock);
    int result = -1;

    if (g_loaded) {
        result = 0;
    } else {
        int fd = open(c_path, O_RDONLY);
        if (fd < 0) {
            LOGE("could not open weights: %s", c_path);
        } else {
            struct stat info;
            if (fstat(fd, &info) != 0 || info.st_size <= 0) {
                LOGE("weights file has no readable size: %s", c_path);
            } else {
                size_t size = (size_t) info.st_size;
                void *mapped = mmap(NULL, size, PROT_READ, MAP_PRIVATE, fd, 0);
                if (mapped == MAP_FAILED) {
                    LOGE("mmap failed for %s (%zu bytes)", c_path, size);
                } else {
                    if (needle_load((const unsigned char *) mapped, (unsigned long long) size) < 0) {
                        const char *error = needle_last_error();
                        LOGE("needle_load failed: %s", error ? error : "unknown error");
                        munmap(mapped, size);
                    } else {
                        g_weights = mapped;
                        g_weights_len = size;
                        g_loaded = 1;
                        result = 0;
                        LOGI("weights loaded: %s (%zu bytes)", c_path, size);
                    }
                }
            }
            close(fd);
        }
    }

    pthread_mutex_unlock(&g_lock);
    (*env)->ReleaseStringUTFChars(env, path, c_path);
    return result;
#endif
}

JNIEXPORT jint JNICALL
Java_dev_citali_needle_engine_NeedleNative_nativeInit(JNIEnv *env, jclass clazz, jstring system,
                                                     jstring tools_json) {
    (void) clazz;
#if !NEEDLE_ENGINE_AVAILABLE
    (void) system;
    (void) tools_json;
    return -1;
#else
    if (!g_loaded) {
        return -2;
    }
    const char *c_system = NULL;
    const char *c_tools = NULL;
    if (system != NULL) {
        c_system = (*env)->GetStringUTFChars(env, system, NULL);
    }
    if (tools_json != NULL) {
        c_tools = (*env)->GetStringUTFChars(env, tools_json, NULL);
    }

    pthread_mutex_lock(&g_lock);
    int prefix = needle_init(c_system != NULL ? c_system : "",
                            c_tools != NULL ? c_tools : "[]",
                            NULL);
    if (prefix < 0) {
        const char *error = needle_last_error();
        LOGE("needle_init failed (%d): %s", prefix, error ? error : "unknown error");
    } else {
        LOGI("needle_init ok, static prefix = %d tokens", prefix);
    }
    pthread_mutex_unlock(&g_lock);

    if (c_system != NULL) {
        (*env)->ReleaseStringUTFChars(env, system, c_system);
    }
    if (c_tools != NULL) {
        (*env)->ReleaseStringUTFChars(env, tools_json, c_tools);
    }
    return prefix;
#endif
}

JNIEXPORT jstring JNICALL
Java_dev_citali_needle_engine_NeedleNative_nativeComplete(JNIEnv *env, jclass clazz, jstring input,
                                                          jint max_new_tokens) {
    (void) clazz;
#if !NEEDLE_ENGINE_AVAILABLE
    (void) input;
    (void) max_new_tokens;
    return NULL;
#else
    if (!g_loaded) {
        return NULL;
    }
    if (input == NULL) {
        return NULL;
    }
    const char *c_input = (*env)->GetStringUTFChars(env, input, NULL);
    if (c_input == NULL) {
        return NULL;
    }

    pthread_mutex_lock(&g_lock);
    jstring reply = NULL;
    char *buffer = out_buffer();
    if (buffer == NULL) {
        LOGE("out of memory allocating the reply buffer");
    } else {
        buffer[0] = '\0';
        int code = needle_complete(c_input, (int) max_new_tokens, buffer, NEEDLE_OUT_CAPACITY);
        if (code < 0) {
            const char *error = needle_last_error();
            LOGE("needle_complete failed (%d): %s", code, error ? error : "unknown error");
        } else {
            reply = new_string(env, buffer);
        }
    }
    pthread_mutex_unlock(&g_lock);

    (*env)->ReleaseStringUTFChars(env, input, c_input);
    return reply;
#endif
}

JNIEXPORT jstring JNICALL
Java_dev_citali_needle_engine_NeedleNative_nativeLastError(JNIEnv *env, jclass clazz) {
    (void) clazz;
#if !NEEDLE_ENGINE_AVAILABLE
    return new_string(env, "This build has no Needle engine for its ABI.");
#else
    const char *error = needle_last_error();
    return new_string(env, error != NULL ? error : "");
#endif
}

JNIEXPORT void JNICALL
Java_dev_citali_needle_engine_NeedleNative_nativeReset(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
#if NEEDLE_ENGINE_AVAILABLE
    if (!g_loaded) {
        return;
    }
    pthread_mutex_lock(&g_lock);
    needle_reset();
    pthread_mutex_unlock(&g_lock);
#endif
}

JNIEXPORT void JNICALL
Java_dev_citali_needle_engine_NeedleNative_nativeUnload(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    pthread_mutex_lock(&g_lock);
    if (g_weights != NULL) {
        munmap(g_weights, g_weights_len);
        g_weights = NULL;
        g_weights_len = 0;
    }
    g_loaded = 0;
    if (g_out != NULL) {
        free(g_out);
        g_out = NULL;
    }
    pthread_mutex_unlock(&g_lock);
}

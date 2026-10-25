#include "engine.h"
#include <jni.h>
#include <exception>
#include <string>
#include <unistd.h>
#include <sys/syscall.h>

namespace {
void fail(JNIEnv * env, const char * message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
std::string text(JNIEnv * env, jbyteArray bytes) {
    std::string result(env->GetArrayLength(bytes), '\0');
    env->GetByteArrayRegion(bytes, 0, result.size(), reinterpret_cast<jbyte *>(result.data()));
    return result;
}
}
extern "C" JNIEXPORT jlong JNICALL
Java_io_github_soichi11208_zinna_ime_ZenzaiNative_load(JNIEnv * env, jobject, jstring path) {
    const char * utf = env->GetStringUTFChars(path, nullptr);
    if (!utf) return 0;
    std::string file(utf);
    env->ReleaseStringUTFChars(path, utf);
    try { return reinterpret_cast<jlong>(new zinna::Zenzai(file)); }
    catch (const std::exception & e) { fail(env, e.what()); return 0; }
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_soichi11208_zinna_ime_ZenzaiNative_generate(
    JNIEnv * env, jobject, jlong handle, jbyteArray prompt, jint max_tokens, jlong generation) {
    if (!handle) return nullptr;
    try {
        std::string input(env->GetArrayLength(prompt), '\0');
        env->GetByteArrayRegion(prompt, 0, input.size(), reinterpret_cast<jbyte *>(input.data()));
        auto result = reinterpret_cast<zinna::Zenzai *>(handle)->generate(input, max_tokens, generation);
        auto bytes = env->NewByteArray(result.size());
        if (bytes) env->SetByteArrayRegion(bytes, 0, result.size(), reinterpret_cast<const jbyte *>(result.data()));
        return bytes;
    } catch (const std::exception & e) { fail(env, e.what()); return nullptr; }
}
extern "C" JNIEXPORT jlong JNICALL
Java_io_github_soichi11208_zinna_ime_ZenzaiNative_cancel(JNIEnv *, jobject) {
    return zinna::cancel_generation();
}
extern "C" JNIEXPORT void JNICALL
Java_io_github_soichi11208_zinna_ime_ZenzaiNative_free(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<zinna::Zenzai *>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_soichi11208_zinna_ime_ZenzaiNative_train(JNIEnv * env, jobject, jlong h, jbyteArray document) {
    try { reinterpret_cast<zinna::Zenzai *>(h)->train(text(env, document)); }
    catch (const std::exception & e) { fail(env, e.what()); }
}
extern "C" JNIEXPORT jint JNICALL
Java_io_github_soichi11208_zinna_ime_ZenzaiNative_learnWord(JNIEnv * env, jobject, jlong h, jbyteArray prefix, jbyteArray output) {
    try { return reinterpret_cast<zinna::Zenzai *>(h)->learn_word(text(env, prefix), text(env, output)); }
    catch (const std::exception & e) { fail(env, e.what()); return 0; }
}
extern "C" JNIEXPORT jlong JNICALL
Java_io_github_soichi11208_zinna_ime_ZenzaiNative_exportVersion(JNIEnv * env, jobject, jlong h) {
    try { return reinterpret_cast<zinna::Zenzai *>(h)->export_version(); }
    catch (const std::exception & e) { fail(env, e.what()); return 0; }
}
extern "C" JNIEXPORT void JNICALL
Java_io_github_soichi11208_zinna_ime_ZenzaiNative_importVersion(JNIEnv * env, jobject, jlong h, jlong version) {
    try { reinterpret_cast<zinna::Zenzai *>(h)->import_version(version); }
    catch (const std::exception & e) { fail(env, e.what()); }
}
extern "C" JNIEXPORT void JNICALL
Java_io_github_soichi11208_zinna_ime_ZenzaiNative_freeVersion(JNIEnv *, jobject, jlong version) {
    zinna::Zenzai::free_version(version);
}
extern "C" JNIEXPORT jint JNICALL
Java_io_github_soichi11208_zinna_ime_ZenzaiNative_memoryFile(JNIEnv * env, jobject) {
    int fd = syscall(__NR_memfd_create, "zinna-ttt", 1);
    if (fd < 0) fail(env, "Cannot allocate snapshot memory");
    return fd;
}
extern "C" JNIEXPORT void JNICALL
Java_io_github_soichi11208_zinna_ime_ZenzaiNative_snapshot(JNIEnv * env, jobject, jlong h, jint fd, jlong cursor) {
    try { reinterpret_cast<zinna::Zenzai *>(h)->snapshot(fd, cursor); }
    catch (const std::exception & e) { fail(env, e.what()); }
}
extern "C" JNIEXPORT jlong JNICALL
Java_io_github_soichi11208_zinna_ime_ZenzaiNative_restore(JNIEnv * env, jobject, jlong h, jint fd) {
    try { return reinterpret_cast<zinna::Zenzai *>(h)->restore(fd); }
    catch (const std::exception & e) { fail(env, e.what()); return 0; }
}

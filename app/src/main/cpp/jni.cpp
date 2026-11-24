#include <jni.h>

#include "lexicon.hpp"
#include "strokes.hpp"
#include "edit.hpp"

#include <codecvt>
#include <cstdint>
#include <limits>
#include <locale>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

// JNI modified UTF-8 encodes NUL and supplementary characters differently from
// the UTF-8 shared by the data files and the native backend. Decode output
// into Java UTF-16 and receive Java UTF-8 as byte arrays.
std::string bytes(JNIEnv* env, jbyteArray input) {
    if (!input) throw std::invalid_argument("Missing UTF-8 input");
    const jsize length = env->GetArrayLength(input);
    std::string result(static_cast<size_t>(length), '\0');
    if (length) env->GetByteArrayRegion(input, 0, length, reinterpret_cast<jbyte*>(result.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("Could not read UTF-8 input");
    return result;
}

jsize lengthOf(size_t count) {
    if (count > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        throw std::length_error("Result exceeds Java collection limits");
    }
    return static_cast<jsize>(count);
}

jstring stringToJava(JNIEnv* env, const std::string& value) {
    std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t> codec;
    const auto utf16 = codec.from_bytes(value);
    return env->NewString(reinterpret_cast<const jchar*>(utf16.data()), lengthOf(utf16.size()));
}

template <class T> T* model(jlong address) {
    if (!address) throw std::invalid_argument("Model is closed");
    return reinterpret_cast<T*>(static_cast<uintptr_t>(address));
}

template <class T> jlong makeModel(JNIEnv* env, jbyteArray input) {
    auto value = std::make_unique<T>(bytes(env, input));
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(value.release()));
}

void raise(JNIEnv* env, const char* className, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass(className);
    if (type) env->ThrowNew(type, message);
}

template<typename T, typename Function>
T guarded(JNIEnv* env, const char* error, Function operation) {
    try { return operation(); }
    catch (const std::bad_alloc&) { raise(env, "java/lang/OutOfMemoryError", "Insufficient memory for Tujia keyboard"); }
    catch (const std::exception& e) { raise(env, error, e.what()); }
    catch (...) { raise(env, error, "Unknown error in Tujia keyboard"); }
    return T{};
}

constexpr auto LOAD_ERROR = "java/io/IOException";
constexpr auto CALL_ERROR = "java/lang/IllegalStateException";
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_biz_tujia_ime_NativeCore_loadLexicon(JNIEnv* env, jclass, jbyteArray data) {
    return guarded<jlong>(env, LOAD_ERROR, [&] { return makeModel<tujia::Lexicon>(env, data); });
}

extern "C" JNIEXPORT void JNICALL
Java_biz_tujia_ime_NativeCore_freeLexicon(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<tujia::Lexicon*>(static_cast<uintptr_t>(handle));
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_biz_tujia_ime_NativeCore_lookup(JNIEnv* env, jclass, jlong handle, jbyteArray raw, jint mode, jint orthography) {
    return guarded<jobjectArray>(env, CALL_ERROR, [&]() -> jobjectArray {
        const auto found = model<tujia::Lexicon>(handle)->lookup(bytes(env, raw), static_cast<tujia::Mode>(mode),
                                                                  static_cast<tujia::Orthography>(orthography));
        jclass item = env->FindClass("biz/tujia/ime/CandidateEngine$Candidate");
        if (!item) return nullptr;
        jmethodID init = env->GetMethodID(item, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;II[IZ)V");
        if (!init) return nullptr;
        jobjectArray result = env->NewObjectArray(lengthOf(found.size()), item, nullptr);
        if (!result) return nullptr;
        for (size_t i = 0; i < found.size(); ++i) {
            const auto& candidate = found[i];
            jstring text = stringToJava(env, candidate.text);
            if (!text) return nullptr;
            jstring meaning = stringToJava(env, candidate.meaning);
            if (!meaning) return nullptr;
            jstring shortMeaning = candidate.shortMeaning == candidate.meaning ? meaning :
                stringToJava(env, candidate.shortMeaning);
            if (!shortMeaning) return nullptr;
            jintArray ranges = env->NewIntArray(lengthOf(candidate.joinedHanRanges.size()));
            if (!ranges) return nullptr;
            if (!candidate.joinedHanRanges.empty()) {
                env->SetIntArrayRegion(ranges, 0, lengthOf(candidate.joinedHanRanges.size()),
                                       candidate.joinedHanRanges.data());
                if (env->ExceptionCheck()) return nullptr;
            }
            jobject value = env->NewObject(item, init, text, meaning, shortMeaning,
                                            static_cast<jint>(candidate.kind), static_cast<jint>(candidate.consumed),
                                            ranges, static_cast<jboolean>(candidate.syllableOnly));
            if (!value) return nullptr;
            env->SetObjectArrayElement(result, static_cast<jsize>(i), value);
            env->DeleteLocalRef(value);
            env->DeleteLocalRef(ranges);
            if (shortMeaning != meaning) env->DeleteLocalRef(shortMeaning);
            env->DeleteLocalRef(meaning);
            env->DeleteLocalRef(text);
            if (env->ExceptionCheck()) return nullptr;
        }
        return result;
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_biz_tujia_ime_NativeCore_suffix(JNIEnv* env, jclass, jbyteArray raw, jint consumed) {
    return guarded<jstring>(env, CALL_ERROR, [&] {
        return stringToJava(env, tujia::suffixAfterChoice(bytes(env, raw), consumed));
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_biz_tujia_ime_NativeCore_space(JNIEnv* env, jclass, jbyteArray raw, jchar before, jchar after) {
    return guarded<jstring>(env, CALL_ERROR, [&] {
        return stringToJava(env, tujia::spaceWord(bytes(env, raw), before, after));
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_biz_tujia_ime_NativeCore_loadStrokes(JNIEnv* env, jclass, jbyteArray data) {
    return guarded<jlong>(env, LOAD_ERROR, [&] { return makeModel<tujia::Strokes>(env, data); });
}

extern "C" JNIEXPORT void JNICALL
Java_biz_tujia_ime_NativeCore_freeStrokes(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<tujia::Strokes*>(static_cast<uintptr_t>(handle));
}

extern "C" JNIEXPORT jint JNICALL
Java_biz_tujia_ime_NativeCore_strokeCount(JNIEnv* env, jclass, jlong handle) {
    return guarded<jint>(env, CALL_ERROR, [&] { return lengthOf(model<tujia::Strokes>(handle)->size()); });
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_biz_tujia_ime_NativeCore_recognize(JNIEnv* env, jclass, jlong handle, jobjectArray input, jint limit) {
    return guarded<jobjectArray>(env, CALL_ERROR, [&]() -> jobjectArray {
        if (!input) throw std::invalid_argument("Missing handwriting strokes");
        const jsize count = env->GetArrayLength(input);
        std::vector<std::vector<float>> strokes;
        strokes.reserve(count);
        for (jsize i = 0; i < count; ++i) {
            auto points = static_cast<jfloatArray>(env->GetObjectArrayElement(input, i));
            if (!points) throw std::invalid_argument("Missing handwriting points");
            const jsize length = env->GetArrayLength(points);
            if (length < 2 || length % 2 != 0) throw std::invalid_argument("Invalid handwriting stroke");
            auto& stroke = strokes.emplace_back(static_cast<size_t>(length));
            env->GetFloatArrayRegion(points, 0, length, stroke.data());
            env->DeleteLocalRef(points);
            if (env->ExceptionCheck()) return nullptr;
        }
        const auto found = model<tujia::Strokes>(handle)->recognize(strokes, limit);
        jclass item = env->FindClass("java/lang/String");
        if (!item) return nullptr;
        jobjectArray result = env->NewObjectArray(lengthOf(found.size()), item, nullptr);
        if (!result) return nullptr;
        for (size_t i = 0; i < found.size(); ++i) {
            jstring glyph = stringToJava(env, found[i]);
            if (!glyph) return nullptr;
            env->SetObjectArrayElement(result, static_cast<jsize>(i), glyph);
            env->DeleteLocalRef(glyph);
            if (env->ExceptionCheck()) return nullptr;
        }
        return result;
    });
}

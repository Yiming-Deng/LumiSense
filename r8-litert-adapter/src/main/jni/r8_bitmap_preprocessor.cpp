#include <android/bitmap.h>
#include <jni.h>
#include <stdint.h>

namespace {

void throw_illegal_argument(JNIEnv* env, const char* message) {
    jclass exception = env->FindClass("java/lang/IllegalArgumentException");
    if (exception != nullptr) env->ThrowNew(exception, message);
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_oppovisual_r8litert_R8BitmapPreprocessor_nativePackInt8(
    JNIEnv* env,
    jclass,
    jobject bitmap,
    jint input_size,
    jbyteArray output_array,
    jbyteArray lookup_array) {
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        static_cast<int>(info.width) != input_size || static_cast<int>(info.height) != input_size) {
        throw_illegal_argument(env, "bitmap must be square RGBA_8888 input pixels");
        return;
    }

    void* source_pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &source_pixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
        source_pixels == nullptr) {
        throw_illegal_argument(env, "unable to lock bitmap pixels");
        return;
    }

    auto* output = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(output_array, nullptr));
    auto* lookup = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(lookup_array, nullptr));
    if (output == nullptr || lookup == nullptr) {
        if (lookup != nullptr) env->ReleasePrimitiveArrayCritical(lookup_array, lookup, JNI_ABORT);
        if (output != nullptr) env->ReleasePrimitiveArrayCritical(output_array, output, 0);
        AndroidBitmap_unlockPixels(env, bitmap);
        throw_illegal_argument(env, "unable to access preprocessing buffers");
        return;
    }

    const int plane_size = input_size * input_size;
    const auto* source = static_cast<const uint8_t*>(source_pixels);
    for (int y = 0; y < input_size; ++y) {
        const auto* row = source + y * info.stride;
        const int row_offset = y * input_size;
        for (int x = 0; x < input_size; ++x) {
            const auto* pixel = row + x * 4;
            const int index = row_offset + x;
            output[index] = lookup[pixel[0]];
            output[plane_size + index] = lookup[pixel[1]];
            output[2 * plane_size + index] = lookup[pixel[2]];
        }
    }

    env->ReleasePrimitiveArrayCritical(lookup_array, lookup, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(output_array, output, 0);
    AndroidBitmap_unlockPixels(env, bitmap);
}

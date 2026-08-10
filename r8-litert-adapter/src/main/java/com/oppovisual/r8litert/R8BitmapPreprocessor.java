package com.oppovisual.r8litert;

import android.graphics.Bitmap;

/** Fused native resize, letterbox, and RGB-to-CHW INT8 preprocessing. */
public final class R8BitmapPreprocessor {
    static {
        System.loadLibrary("r8_preprocess");
    }

    private R8BitmapPreprocessor() {}

    public static void packInt8(Bitmap bitmap, int inputSize, byte[] output, byte[] lookup) {
        if (bitmap == null) throw new IllegalArgumentException("bitmap must not be null");
        if (bitmap.getWidth() != inputSize || bitmap.getHeight() != inputSize) {
            throw new IllegalArgumentException("bitmap must match the square input tensor");
        }
        if (output == null || output.length != 3 * inputSize * inputSize) {
            throw new IllegalArgumentException("output must match the NCHW input tensor size");
        }
        if (lookup == null || lookup.length != 256) {
            throw new IllegalArgumentException("lookup must contain 256 entries");
        }
        nativePackInt8(bitmap, inputSize, output, lookup);
    }

    private static native void nativePackInt8(
        Bitmap bitmap,
        int inputSize,
        byte[] output,
        byte[] lookup
    );
}

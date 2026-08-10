package com.oppovisual.r8qnn;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.File;
import java.util.Objects;

/** Online-composed QNN HTP runtime for the final quantized gesture graph. */
public final class R8QnnRuntime implements AutoCloseable {
    public static final int INPUT_FLOAT_COUNT = 640 * 640 * 3;
    public static final int OUTPUT_FLOAT_COUNT = 101 * 8400;
    public static final int COMPACT_OUTPUT_FLOAT_COUNT = 300 * 69;

    private long nativeHandle;
    private final String performanceMode;

    public R8QnnRuntime(Context context, String modelLibraryName) {
        this(context, modelLibraryName, "sustained");
    }

    public R8QnnRuntime(Context context, String modelLibraryName, String performanceMode) {
        Objects.requireNonNull(context, "context");
        if (modelLibraryName == null || !modelLibraryName.matches("lib[A-Za-z0-9_.-]+\\.so")) {
            throw new IllegalArgumentException("modelLibraryName must be a packaged lib*.so name");
        }
        if (!"default".equals(performanceMode) &&
            !"burst".equals(performanceMode) &&
            !"sustained".equals(performanceMode) &&
            !"powersave".equals(performanceMode)) {
            throw new IllegalArgumentException(
                "performanceMode must be default, burst, sustained, or powersave"
            );
        }
        this.performanceMode = performanceMode;
        File nativeDirectory = new File(context.getApplicationInfo().nativeLibraryDir);
        File backend = new File(nativeDirectory, "libQnnHtp.so");
        File model = new File(nativeDirectory, modelLibraryName);
        if (!backend.isFile()) throw new IllegalStateException("Missing QNN HTP backend: " + backend);
        if (!model.isFile()) throw new IllegalStateException("Missing QNN model: " + model);
        nativeHandle = nativeCreate(
            nativeDirectory.getAbsolutePath(),
            backend.getAbsolutePath(),
            model.getAbsolutePath(),
            performanceMode
        );
        if (nativeHandle == 0L) throw new IllegalStateException("QNN runtime returned a null handle");
    }

    public synchronized float[] run(float[] nhwcValues) {
        ensureOpen();
        if (nhwcValues == null || nhwcValues.length != INPUT_FLOAT_COUNT) {
            throw new IllegalArgumentException(
                "input must contain " + INPUT_FLOAT_COUNT + " NHWC FP32 values"
            );
        }
        float[] output = nativeRun(nativeHandle, nhwcValues);
        if (output == null || output.length != OUTPUT_FLOAT_COUNT) {
            throw new IllegalStateException("QNN returned an invalid output tensor");
        }
        return output;
    }

    /** Runs a square RGBA bitmap as normalized RGB values in NHWC order. */
    public synchronized float[] run(Bitmap bitmap) {
        ensureOpen();
        if (bitmap == null || bitmap.getWidth() != 640 || bitmap.getHeight() != 640 ||
            bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
            throw new IllegalArgumentException("bitmap must be a 640x640 ARGB_8888 image");
        }
        float[] output = nativeRunBitmap(nativeHandle, bitmap);
        if (output == null || output.length != OUTPUT_FLOAT_COUNT) {
            throw new IllegalStateException("QNN returned an invalid output tensor");
        }
        return output;
    }

    /** Runs into a reusable output array to avoid allocating 3.4 MB for every frame. */
    public synchronized void run(Bitmap bitmap, float[] output) {
        ensureOpen();
        if (bitmap == null || bitmap.getWidth() != 640 || bitmap.getHeight() != 640 ||
            bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
            throw new IllegalArgumentException("bitmap must be a 640x640 ARGB_8888 image");
        }
        if (output == null || output.length != OUTPUT_FLOAT_COUNT) {
            throw new IllegalArgumentException(
                "output must contain " + OUTPUT_FLOAT_COUNT + " float values"
            );
        }
        nativeRunBitmapInto(nativeHandle, bitmap, output);
    }

    /** Returns the top 300 candidates using the app's 69-value fused-row contract. */
    public synchronized void runCompact(Bitmap bitmap, float[] output) {
        ensureOpen();
        if (bitmap == null || bitmap.getWidth() != 640 || bitmap.getHeight() != 640 ||
            bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
            throw new IllegalArgumentException("bitmap must be a 640x640 ARGB_8888 image");
        }
        if (output == null || output.length != COMPACT_OUTPUT_FLOAT_COUNT) {
            throw new IllegalArgumentException(
                "output must contain " + COMPACT_OUTPUT_FLOAT_COUNT + " float values"
            );
        }
        nativeRunCompact(nativeHandle, bitmap, output);
    }

    public synchronized long getLastInferenceNanos() {
        ensureOpen();
        return nativeGetLastInferenceNanos(nativeHandle);
    }

    public synchronized long getInitializationNanos() {
        ensureOpen();
        return nativeGetInitializationNanos(nativeHandle);
    }

    public synchronized String getBackendBuildId() {
        ensureOpen();
        return nativeGetBackendBuildId(nativeHandle);
    }

    public String getPerformanceMode() {
        return performanceMode;
    }

    @Override
    public synchronized void close() {
        if (nativeHandle == 0L) return;
        nativeDestroy(nativeHandle);
        nativeHandle = 0L;
    }

    private void ensureOpen() {
        if (nativeHandle == 0L) throw new IllegalStateException("QNN runtime is closed");
    }

    private static native long nativeCreate(
        String nativeLibraryDirectory,
        String backendPath,
        String modelPath,
        String performanceMode
    );
    private static native float[] nativeRun(long handle, float[] input);
    private static native float[] nativeRunBitmap(long handle, Bitmap bitmap);
    private static native void nativeRunBitmapInto(long handle, Bitmap bitmap, float[] output);
    private static native void nativeRunCompact(long handle, Bitmap bitmap, float[] output);
    private static native long nativeGetLastInferenceNanos(long handle);
    private static native long nativeGetInitializationNanos(long handle);
    private static native String nativeGetBackendBuildId(long handle);
    private static native void nativeDestroy(long handle);

    static {
        System.loadLibrary("r8_qnn_adapter");
    }
}

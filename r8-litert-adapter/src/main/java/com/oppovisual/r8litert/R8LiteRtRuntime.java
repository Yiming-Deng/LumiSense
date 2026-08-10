package com.oppovisual.r8litert;

import android.content.Context;
import android.os.Build;
import com.google.ai.edge.litert.BuiltinNpuAcceleratorProvider;
import com.google.ai.edge.litert.Accelerator;
import com.google.ai.edge.litert.CompiledModel;
import com.google.ai.edge.litert.Environment;
import com.google.ai.edge.litert.LiteRtException;
import com.google.ai.edge.litert.NpuCompatibilityChecker;
import com.google.ai.edge.litert.TensorBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Java boundary keeps LiteRT's newer Kotlin metadata out of the app's Kotlin compiler. */
public final class R8LiteRtRuntime implements AutoCloseable {
    private static final int MODEL_INDEX = 0;
    private static final int FUSED_OUTPUT_FLOAT_COUNT = 1 * 300 * 69;
    private static final int RAW_OUTPUT_FLOAT_COUNT = 1 * 101 * 8400;

    private final int inputFloatCount;
    private final CompiledModel model;
    private final Environment environment;
    private final TensorBuffer input;
    private final TensorBuffer output;
    private final String acceleratorName;
    private final String inputTensorType;
    private final float inputScale;
    private final int inputZeroPoint;
    private final String outputTensorType;
    private final float outputScale;
    private final int outputZeroPoint;
    private final boolean npuRegistered;

    public R8LiteRtRuntime(
        Context context,
        String assetName,
        int inputSize,
        int cpuThreads,
        String acceleratorName,
        String inputTensorType,
        float inputScale,
        int inputZeroPoint,
        String outputTensorType,
        float outputScale,
        int outputZeroPoint,
        String gpuPrecision,
        String npuPerformanceMode,
        String npuProfiling,
        String npuLogLevel,
        String npuOptimizationLevel
    ) throws LiteRtException {
        if (inputSize <= 0 || inputSize % 32 != 0) {
            throw new IllegalArgumentException("inputSize must be a positive multiple of 32");
        }
        if (cpuThreads <= 0) {
            throw new IllegalArgumentException("cpuThreads must be positive");
        }
        this.acceleratorName = parseAccelerator(acceleratorName);
        this.inputTensorType = parseTensorType(inputTensorType, "inputTensorType");
        this.inputScale = validateQuantization(this.inputTensorType, inputScale, inputZeroPoint, "input");
        this.inputZeroPoint = inputZeroPoint;
        this.outputTensorType = parseTensorType(outputTensorType, "outputTensorType");
        this.outputScale = validateQuantization(this.outputTensorType, outputScale, outputZeroPoint, "output");
        this.outputZeroPoint = outputZeroPoint;
        inputFloatCount = 3 * inputSize * inputSize;
        CompiledModel.Options options;
        if (this.acceleratorName.equals("gpu_cpu")) {
            options = new CompiledModel.Options(Accelerator.GPU, Accelerator.CPU);
        } else if (this.acceleratorName.equals("npu_cpu")) {
            options = new CompiledModel.Options(Accelerator.NPU, Accelerator.CPU);
        } else if (this.acceleratorName.equals("npu_gpu_cpu")) {
            options = new CompiledModel.Options(Accelerator.NPU, Accelerator.GPU, Accelerator.CPU);
        } else {
            options = new CompiledModel.Options(Accelerator.valueOf(this.acceleratorName.toUpperCase(Locale.ROOT)));
        }
        if (!this.acceleratorName.equals("gpu") && !this.acceleratorName.equals("npu")) {
            options.setCpuOptions(new CompiledModel.CpuOptions(cpuThreads, null, null));
        }
        if (this.acceleratorName.contains("gpu")) {
            options.setGpuOptions(gpuOptions(gpuPrecision));
        }
        String npuVendor = npuVendorForDevice();
        if (this.acceleratorName.contains("npu") && npuVendor.equals("qualcomm")) {
            options.setQualcommOptions(
                qualcommOptions(
                    npuPerformanceMode,
                    npuProfiling,
                    npuLogLevel,
                    npuOptimizationLevel
                )
            );
        }
        Environment createdEnvironment = null;
        CompiledModel createdModel;
        boolean registered = false;
        try {
            if (this.acceleratorName.contains("npu")) {
                createdEnvironment = Environment.create(
                    new BuiltinNpuAcceleratorProvider(
                        context.getApplicationContext(),
                        npuCompatibilityChecker(npuVendor)
                    )
                );
                registered = createdEnvironment.getAvailableAccelerators().contains(Accelerator.NPU);
                if (!registered) {
                    throw new IllegalStateException("LiteRT NPU accelerator was not registered");
                }
                createdModel = CompiledModel.create(
                    context.getAssets(), assetName, options, createdEnvironment
                );
            } else {
                createdModel = CompiledModel.create(context.getAssets(), assetName, options);
            }
        } catch (RuntimeException | LiteRtException exception) {
            if (createdEnvironment != null) createdEnvironment.close();
            throw exception;
        }
        environment = createdEnvironment;
        npuRegistered = registered;
        model = createdModel;
        List<TensorBuffer> inputs = model.createInputBuffers(MODEL_INDEX);
        List<TensorBuffer> outputs = model.createOutputBuffers(MODEL_INDEX);
        if (inputs.size() != 1 || outputs.size() != 1) {
            closeBuffers(inputs);
            closeBuffers(outputs);
            model.close();
            throw new IllegalStateException(
                "model must expose exactly one input and one output, got " + inputs.size() + "/" + outputs.size()
            );
        }
        input = inputs.get(0);
        output = outputs.get(0);
    }

    public String getAcceleratorName() {
        return acceleratorName;
    }

    public boolean isNpuRegistered() {
        return npuRegistered;
    }

    static String npuVendor(String socManufacturer, String socModel) {
        String manufacturer = socManufacturer == null
            ? ""
            : socManufacturer.trim().toLowerCase(Locale.ROOT);
        String model = socModel == null ? "" : socModel.trim().toUpperCase(Locale.ROOT);
        if (manufacturer.contains("mediatek") || model.startsWith("MT")) return "mediatek";
        if (manufacturer.contains("qualcomm") || manufacturer.equals("qti") ||
            model.startsWith("SM")) return "qualcomm";
        if (manufacturer.contains("google") || model.startsWith("TENSOR")) return "google";
        return "default";
    }

    private static String npuVendorForDevice() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return "default";
        return npuVendor(Build.SOC_MANUFACTURER, Build.SOC_MODEL);
    }

    private static NpuCompatibilityChecker npuCompatibilityChecker(String vendor) {
        switch (vendor) {
            case "mediatek":
                return NpuCompatibilityChecker.Companion.getMediatek();
            case "qualcomm":
                return NpuCompatibilityChecker.Companion.getQualcomm();
            case "google":
                return NpuCompatibilityChecker.Companion.getGoogleTensor();
            default:
                return NpuCompatibilityChecker.Companion.getDefault();
        }
    }

    public float[] run(float[] values) throws LiteRtException {
        if (values.length != inputFloatCount) {
            throw new IllegalArgumentException(
                "input tensor must contain " + inputFloatCount + " FP32 values, got " + values.length
            );
        }
        if (inputTensorType.equals("int8")) {
            input.writeInt8(quantizeInt8(values, inputScale, inputZeroPoint));
        } else {
            input.writeFloat(values);
        }
        return invokeAndReadOutput();
    }

    public float[] runInt8(byte[] values) throws LiteRtException {
        if (!inputTensorType.equals("int8")) {
            throw new IllegalStateException("runInt8 requires an INT8 input tensor");
        }
        if (values.length != inputFloatCount) {
            throw new IllegalArgumentException(
                "input tensor must contain " + inputFloatCount + " INT8 values, got " + values.length
            );
        }
        input.writeInt8(values);
        return invokeAndReadOutput();
    }

    private float[] invokeAndReadOutput() throws LiteRtException {
        model.run(Arrays.asList(input), Arrays.asList(output), MODEL_INDEX);
        float[] result = outputTensorType.equals("int8")
            ? dequantizeInt8(output.readInt8(), outputScale, outputZeroPoint)
            : output.readFloat();
        if (result.length != FUSED_OUTPUT_FLOAT_COUNT && result.length != RAW_OUTPUT_FLOAT_COUNT) {
            throw new IllegalStateException(
                "output tensor must contain " + FUSED_OUTPUT_FLOAT_COUNT + " fused or " +
                RAW_OUTPUT_FLOAT_COUNT + " raw values, got " + result.length
            );
        }
        return result;
    }

    @Override
    public void close() {
        output.close();
        input.close();
        model.close();
        if (environment != null) environment.close();
    }

    private static void closeBuffers(List<TensorBuffer> buffers) {
        for (TensorBuffer buffer : buffers) buffer.close();
    }

    private static String parseAccelerator(String value) {
        if (value == null) throw new IllegalArgumentException("acceleratorName must not be null");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("cpu") && !normalized.equals("gpu") &&
            !normalized.equals("gpu_cpu") && !normalized.equals("npu") &&
            !normalized.equals("npu_cpu") &&
            !normalized.equals("npu_gpu_cpu")) {
            throw new IllegalArgumentException(
                "acceleratorName must be cpu, gpu, gpu_cpu, npu, npu_cpu, or npu_gpu_cpu, got " + value
            );
        }
        return normalized;
    }

    private static String parseTensorType(String value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " must not be null");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("float32") && !normalized.equals("int8")) {
            throw new IllegalArgumentException(name + " must be float32 or int8, got " + value);
        }
        return normalized;
    }

    private static float validateQuantization(String type, float scale, int zeroPoint, String name) {
        if (type.equals("int8")) {
            if (!Float.isFinite(scale) || scale <= 0f) {
                throw new IllegalArgumentException(name + " INT8 scale must be finite and positive");
            }
            if (zeroPoint < -128 || zeroPoint > 127) {
                throw new IllegalArgumentException(name + " INT8 zero point must be in [-128, 127]");
            }
        }
        return scale;
    }

    static byte[] quantizeInt8(float[] values, float scale, int zeroPoint) {
        byte[] quantized = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            int value = Math.round(values[index] / scale) + zeroPoint;
            quantized[index] = (byte) Math.max(-128, Math.min(127, value));
        }
        return quantized;
    }

    public static byte[] buildUnitByteToInt8Lookup(float scale, int zeroPoint) {
        validateQuantization("int8", scale, zeroPoint, "input");
        byte[] lookup = new byte[256];
        for (int channel = 0; channel < lookup.length; channel++) {
            int value = Math.round((channel / 255.0f) / scale) + zeroPoint;
            lookup[channel] = (byte) Math.max(-128, Math.min(127, value));
        }
        return lookup;
    }

    static float[] dequantizeInt8(byte[] values, float scale, int zeroPoint) {
        float[] dequantized = new float[values.length];
        for (int index = 0; index < values.length; index++) {
            dequantized[index] = (values[index] - zeroPoint) * scale;
        }
        return dequantized;
    }

    private static CompiledModel.GpuOptions gpuOptions(String precision) {
        CompiledModel.GpuOptions.Precision parsed = CompiledModel.GpuOptions.Precision.valueOf(
            normalizeEnum(precision, "gpuPrecision")
        );
        return new CompiledModel.GpuOptions(
            null, null, null, parsed, null, null, null, null,
            null, null, null, null, null, null, null
        );
    }

    private static CompiledModel.QualcommOptions qualcommOptions(
        String performanceMode,
        String profiling,
        String logLevel,
        String optimizationLevel
    ) {
        CompiledModel.QualcommOptions.HtpPerformanceMode parsedPerformance =
            CompiledModel.QualcommOptions.HtpPerformanceMode.valueOf(
                normalizeEnum(performanceMode, "npuPerformanceMode")
            );
        CompiledModel.QualcommOptions.Profiling parsedProfiling =
            CompiledModel.QualcommOptions.Profiling.valueOf(
                normalizeEnum(profiling, "npuProfiling")
            );
        CompiledModel.QualcommOptions.LogLevel parsedLogLevel =
            CompiledModel.QualcommOptions.LogLevel.valueOf(
                normalizeEnum(logLevel, "npuLogLevel")
            );
        CompiledModel.QualcommOptions.OptimizationLevel parsedOptimization =
            CompiledModel.QualcommOptions.OptimizationLevel.valueOf(
                normalizeEnum(optimizationLevel, "npuOptimizationLevel")
            );
        return new CompiledModel.QualcommOptions(
            parsedLogLevel, true, null, null, null, null, null, parsedPerformance,
            parsedProfiling, null, null, null, null, parsedOptimization
        );
    }

    private static String normalizeEnum(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}

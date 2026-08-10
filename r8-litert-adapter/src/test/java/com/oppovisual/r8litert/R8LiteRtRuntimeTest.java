package com.oppovisual.r8litert;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public final class R8LiteRtRuntimeTest {
    @Test
    public void int8RoundTripUsesFrozenScaleAndZeroPoint() {
        float scale = 1.0f / 255.0f;
        int zeroPoint = -128;
        float[] source = new float[] {0.0f, 0.5f, 1.0f};

        byte[] quantized = R8LiteRtRuntime.quantizeInt8(source, scale, zeroPoint);
        float[] restored = R8LiteRtRuntime.dequantizeInt8(quantized, scale, zeroPoint);

        assertArrayEquals(source, restored, scale);
    }

    @Test
    public void int8QuantizationClampsOutOfRangeValues() {
        byte[] quantized = R8LiteRtRuntime.quantizeInt8(
            new float[] {-100.0f, 100.0f},
            0.1f,
            0
        );

        assertArrayEquals(new byte[] {(byte) -128, (byte) 127}, quantized);
    }

    @Test
    public void unitByteLookupMatchesFrozenRgbNormalization() {
        float scale = 1.0f / 255.0f;
        int zeroPoint = -128;

        byte[] lookup = R8LiteRtRuntime.buildUnitByteToInt8Lookup(scale, zeroPoint);
        byte[] expected = R8LiteRtRuntime.quantizeInt8(
            new float[] {0.0f, 128.0f / 255.0f, 1.0f},
            scale,
            zeroPoint
        );

        assertArrayEquals(expected, new byte[] {lookup[0], lookup[128], lookup[255]});
    }
}

package com.oppovisual.r8litert;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class R8LiteRtRuntimeVendorTest {
    @Test
    public void dimensity9400UsesMediatekNpuConfiguration() {
        assertEquals("mediatek", R8LiteRtRuntime.npuVendor("Mediatek", "MT6991"));
    }

    @Test
    public void snapdragonUsesQualcommNpuConfiguration() {
        assertEquals("qualcomm", R8LiteRtRuntime.npuVendor("Qualcomm", "SM8650"));
        assertEquals("qualcomm", R8LiteRtRuntime.npuVendor("QTI", "SM8750"));
    }

    @Test
    public void unknownDevicesUseDefaultCompatibilityChecker() {
        assertEquals("default", R8LiteRtRuntime.npuVendor(null, null));
    }
}

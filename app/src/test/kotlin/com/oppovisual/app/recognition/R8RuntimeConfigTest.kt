package com.oppovisual.app.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class R8RuntimeConfigTest {
    @Test
    fun `runtime config defaults preserve the six-thread CPU fallback`() {
        val config = R8RuntimeConfig()

        assertEquals("cpu", config.accelerator)
        assertEquals(6, config.cpuThreads)
    }

    @Test
    fun `production factory tries portable NPU before GPU and CPU`() {
        val config = R8LiteRtFrameRecognizerFactory.productionRuntimeConfig

        assertEquals("npu_gpu_cpu", config.accelerator)
        assertEquals("gesture_pose_final_fullqat_w8a16_640.tflite", config.modelAsset)
        assertEquals("gesture-pose-final-fullqat-w8a16-640", config.modelVersion)
        assertEquals(6, config.cpuThreads)
    }

    @Test
    fun `GPU is an explicit supported accelerator`() {
        assertEquals("gpu", R8RuntimeConfig(accelerator = "gpu").accelerator)
        assertEquals("gpu_cpu", R8RuntimeConfig(accelerator = "gpu_cpu").accelerator)
    }

    @Test
    fun `NPU profiles are explicit and never inferred`() {
        assertEquals("npu", R8RuntimeConfig(accelerator = "npu").accelerator)
        assertEquals("npu_cpu", R8RuntimeConfig(accelerator = "npu_cpu").accelerator)
        assertEquals(
            "npu_gpu_cpu",
            R8RuntimeConfig(accelerator = "npu_gpu_cpu").accelerator,
        )
    }

    @Test
    fun `INT8 IO requires frozen positive scales`() {
        val config = R8RuntimeConfig(
            inputTensorType = "int8",
            inputScale = 1f / 255f,
            inputZeroPoint = -128,
            outputTensorType = "int8",
            outputScale = 0.05f,
            outputZeroPoint = 0,
        )
        assertEquals("int8", config.inputTensorType)
        assertThrows(IllegalArgumentException::class.java) {
            R8RuntimeConfig(inputTensorType = "int8", inputScale = 0f)
        }
    }

    @Test
    fun `mixed precision and NPU power profiles are explicit`() {
        assertEquals("fp16", R8RuntimeConfig(gpuPrecision = "fp16").gpuPrecision)
        assertEquals(
            "power_saver",
            R8RuntimeConfig(npuPerformanceMode = "power_saver").npuPerformanceMode,
        )
        val profiled = R8RuntimeConfig(
            npuProfiling = "optrace",
            npuLogLevel = "info",
            npuOptimizationLevel = "htp_optimize_for_inference_o3",
        )
        assertEquals("optrace", profiled.npuProfiling)
        assertEquals("info", profiled.npuLogLevel)
        assertEquals("htp_optimize_for_inference_o3", profiled.npuOptimizationLevel)
        assertThrows(IllegalArgumentException::class.java) {
            R8RuntimeConfig(gpuPrecision = "auto")
        }
        assertThrows(IllegalArgumentException::class.java) {
            R8RuntimeConfig(npuProfiling = "auto")
        }
    }

    @Test
    fun `unknown accelerator is rejected instead of falling back`() {
        assertThrows(IllegalArgumentException::class.java) {
            R8RuntimeConfig(accelerator = "auto")
        }
    }
}

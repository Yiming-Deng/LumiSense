#include <jni.h>

#include <android/bitmap.h>
#include <android/log.h>
#include <arm_neon.h>
#include <dlfcn.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <stdexcept>
#include <string>
#include <queue>
#include <utility>
#include <vector>

#include "QnnInterface.h"
#include "HTP/QnnHtpDevice.h"

namespace {

constexpr char kLogTag[] = "OppoVisualQnn";
constexpr size_t kExpectedInputCount = 640U * 640U * 3U;
constexpr size_t kExpectedOutputCount = 101U * 8400U;
constexpr size_t kRawCandidateCount = 8400U;
constexpr size_t kClassCount = 34U;
constexpr size_t kRawClassOffset = 4U;
constexpr size_t kKeypointCount = 21U;
constexpr size_t kRawKeypointOffset = kRawClassOffset + kClassCount;
constexpr size_t kCompactCandidateCount = 300U;
constexpr size_t kCompactRowSize = 6U + kKeypointCount * 3U;
constexpr size_t kCompactOutputCount = kCompactCandidateCount * kCompactRowSize;
constexpr float kClassificationScore = 0.15F;

enum ModelError : uint32_t {
  MODEL_NO_ERROR = 0,
};

struct GraphInfo {
  Qnn_GraphHandle_t graph;
  char* graphName;
  Qnn_Tensor_t* inputTensors;
  uint32_t numInputTensors;
  Qnn_Tensor_t* outputTensors;
  uint32_t numOutputTensors;
};

struct GraphConfigInfo {
  char* graphName;
  const QnnGraph_Config_t** graphConfigs;
};

using ComposeGraphsFn = ModelError (*)(Qnn_BackendHandle_t,
                                       QNN_INTERFACE_VER_TYPE,
                                       Qnn_ContextHandle_t,
                                       const GraphConfigInfo**,
                                       uint32_t,
                                       GraphInfo***,
                                       uint32_t*,
                                       bool,
                                       QnnLog_Callback_t,
                                       QnnLog_Level_t);
using FreeGraphsInfoFn = ModelError (*)(GraphInfo***, uint32_t);
using GetProvidersFn = Qnn_ErrorHandle_t (*)(const QnnInterface_t*** providerList,
                                              uint32_t* numProviders);

void qnnLog(const char* format, QnnLog_Level_t level, uint64_t, va_list args) {
  int priority = ANDROID_LOG_INFO;
  if (level <= QNN_LOG_LEVEL_ERROR) priority = ANDROID_LOG_ERROR;
  else if (level <= QNN_LOG_LEVEL_WARN) priority = ANDROID_LOG_WARN;
  __android_log_vprint(priority, kLogTag, format, args);
}

std::string dlErrorMessage(const std::string& action, const std::string& path) {
  const char* detail = dlerror();
  return action + " " + path + ": " + (detail == nullptr ? "unknown loader error" : detail);
}

template <typename T>
T requireSymbol(void* library, const char* name) {
  dlerror();
  void* symbol = dlsym(library, name);
  if (symbol == nullptr) throw std::runtime_error(dlErrorMessage("Missing symbol", name));
  return reinterpret_cast<T>(symbol);
}

const Qnn_TensorV2_t& tensorV2(const Qnn_Tensor_t& tensor) {
  if (tensor.version != QNN_TENSOR_VERSION_2) {
    throw std::runtime_error("QNN model exposed an unsupported tensor descriptor version");
  }
  return tensor.v2;
}

Qnn_TensorV2_t& tensorV2(Qnn_Tensor_t& tensor) {
  if (tensor.version != QNN_TENSOR_VERSION_2) {
    throw std::runtime_error("QNN model exposed an unsupported tensor descriptor version");
  }
  return tensor.v2;
}

size_t elementCount(const Qnn_Tensor_t& tensor) {
  const auto& descriptor = tensorV2(tensor);
  if (descriptor.rank == 0 || descriptor.dimensions == nullptr) {
    throw std::runtime_error("QNN tensor has no static dimensions");
  }
  size_t count = 1;
  for (uint32_t index = 0; index < descriptor.rank; ++index) {
    if (descriptor.dimensions[index] == 0 ||
        count > SIZE_MAX / descriptor.dimensions[index]) {
      throw std::runtime_error("QNN tensor dimensions overflow");
    }
    count *= descriptor.dimensions[index];
  }
  return count;
}

size_t elementBytes(Qnn_DataType_t type) {
  switch (type) {
    case QNN_DATATYPE_BOOL_8:
    case QNN_DATATYPE_INT_8:
    case QNN_DATATYPE_UINT_8:
    case QNN_DATATYPE_SFIXED_POINT_8:
    case QNN_DATATYPE_UFIXED_POINT_8:
      return 1;
    case QNN_DATATYPE_FLOAT_16:
    case QNN_DATATYPE_INT_16:
    case QNN_DATATYPE_UINT_16:
    case QNN_DATATYPE_SFIXED_POINT_16:
    case QNN_DATATYPE_UFIXED_POINT_16:
      return 2;
    case QNN_DATATYPE_FLOAT_32:
    case QNN_DATATYPE_INT_32:
    case QNN_DATATYPE_UINT_32:
    case QNN_DATATYPE_SFIXED_POINT_32:
    case QNN_DATATYPE_UFIXED_POINT_32:
      return 4;
    case QNN_DATATYPE_FLOAT_64:
    case QNN_DATATYPE_INT_64:
    case QNN_DATATYPE_UINT_64:
      return 8;
    default:
      throw std::runtime_error("QNN tensor uses an unsupported data type");
  }
}

float halfToFloat(uint16_t half) {
  const uint32_t sign = static_cast<uint32_t>(half & 0x8000U) << 16U;
  uint32_t exponent = (half >> 10U) & 0x1fU;
  uint32_t mantissa = half & 0x03ffU;
  uint32_t bits;
  if (exponent == 0) {
    if (mantissa == 0) {
      bits = sign;
    } else {
      exponent = 1;
      while ((mantissa & 0x0400U) == 0) {
        mantissa <<= 1U;
        --exponent;
      }
      mantissa &= 0x03ffU;
      bits = sign | ((exponent + 112U) << 23U) | (mantissa << 13U);
    }
  } else if (exponent == 0x1fU) {
    bits = sign | 0x7f800000U | (mantissa << 13U);
  } else {
    bits = sign | ((exponent + 112U) << 23U) | (mantissa << 13U);
  }
  float result;
  std::memcpy(&result, &bits, sizeof(result));
  return result;
}

class Runtime {
 public:
  Runtime(std::string nativeLibraryDirectory,
          const std::string& backendPath,
          const std::string& modelPath,
          const std::string& performanceMode) {
    const auto started = std::chrono::steady_clock::now();
    try {
      setenv("ADSP_LIBRARY_PATH", nativeLibraryDirectory.c_str(), 1);
      backendLibrary_ = dlopen(backendPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
      if (backendLibrary_ == nullptr) throw std::runtime_error(dlErrorMessage("Cannot load", backendPath));

      auto getProviders = requireSymbol<GetProvidersFn>(backendLibrary_, "QnnInterface_getProviders");
      const QnnInterface_t** providers = nullptr;
      uint32_t providerCount = 0;
      if (getProviders(&providers, &providerCount) != QNN_SUCCESS || providers == nullptr) {
        throw std::runtime_error("QnnInterface_getProviders failed");
      }
      bool foundProvider = false;
      for (uint32_t index = 0; index < providerCount; ++index) {
        const QnnInterface_t* provider = providers[index];
        if (provider != nullptr &&
            provider->apiVersion.coreApiVersion.major == QNN_API_VERSION_MAJOR &&
            provider->apiVersion.coreApiVersion.minor >= QNN_API_VERSION_MINOR) {
          interface_ = provider->QNN_INTERFACE_VER_NAME;
          foundProvider = true;
          break;
        }
      }
      if (!foundProvider) throw std::runtime_error("No compatible QNN interface provider found");

      modelLibrary_ = dlopen(modelPath.c_str(), RTLD_NOW | RTLD_LOCAL);
      if (modelLibrary_ == nullptr) throw std::runtime_error(dlErrorMessage("Cannot load", modelPath));
      composeGraphs_ = requireSymbol<ComposeGraphsFn>(modelLibrary_, "QnnModel_composeGraphs");
      freeGraphsInfo_ = requireSymbol<FreeGraphsInfoFn>(modelLibrary_, "QnnModel_freeGraphsInfo");

      if (interface_.logCreate != nullptr &&
          interface_.logCreate(qnnLog, QNN_LOG_LEVEL_ERROR, &log_) != QNN_SUCCESS) {
        log_ = nullptr;
      }
      if (interface_.backendCreate(log_, nullptr, &backend_) != QNN_BACKEND_NO_ERROR) {
        throw std::runtime_error("QNN backendCreate failed");
      }
      if (interface_.deviceCreate != nullptr) {
        const Qnn_ErrorHandle_t status = interface_.deviceCreate(log_, nullptr, &device_);
        if (status != QNN_SUCCESS && status != QNN_DEVICE_ERROR_UNSUPPORTED_FEATURE) {
          throw std::runtime_error("QNN deviceCreate failed");
        }
      }
      configurePerformance(performanceMode);
      if (interface_.contextCreate(backend_, device_, nullptr, &context_) != QNN_CONTEXT_NO_ERROR) {
        throw std::runtime_error("QNN contextCreate failed");
      }
      if (composeGraphs_(backend_, interface_, context_, nullptr, 0, &graphs_, &graphCount_, false,
                         qnnLog, QNN_LOG_LEVEL_ERROR) != MODEL_NO_ERROR) {
        throw std::runtime_error("QNN model compose failed");
      }
      if (graphs_ == nullptr || graphCount_ != 1) {
        throw std::runtime_error("QNN model must expose exactly one graph");
      }
      if (interface_.graphFinalize((*graphs_)[0].graph, nullptr, nullptr) != QNN_GRAPH_NO_ERROR) {
        throw std::runtime_error("QNN graphFinalize failed");
      }
      setupTensors((*graphs_)[0]);

      const char* buildId = nullptr;
      if (interface_.backendGetBuildId != nullptr &&
          interface_.backendGetBuildId(&buildId) == QNN_SUCCESS && buildId != nullptr) {
        backendBuildId_ = buildId;
      }
      initializationNanos_ = elapsedNanos(started);
    } catch (...) {
      shutdown();
      throw;
    }
  }

  ~Runtime() { shutdown(); }

  std::vector<float> run(const float* input, size_t count) {
    if (count != kExpectedInputCount) throw std::runtime_error("Unexpected QNN input size");
    auto& inputDescriptor = tensorV2(inputs_[0]);
    const auto& quantization = inputDescriptor.quantizeParams;
    if (inputDescriptor.dataType != QNN_DATATYPE_SFIXED_POINT_16 ||
        quantization.quantizationEncoding != QNN_QUANTIZATION_ENCODING_SCALE_OFFSET ||
        quantization.scaleOffsetEncoding.scale <= 0.0F) {
      throw std::runtime_error("Unexpected QNN input quantization contract");
    }
    auto* quantized = static_cast<int16_t*>(inputDescriptor.clientBuf.data);
    const float scale = quantization.scaleOffsetEncoding.scale;
    const int32_t offset = quantization.scaleOffsetEncoding.offset;
    for (size_t index = 0; index < count; ++index) {
      const long value = std::lround(input[index] / scale - static_cast<float>(offset));
      quantized[index] = static_cast<int16_t>(std::clamp(value, -32768L, 32767L));
    }

    return execute();
  }

  std::vector<float> runBitmap(JNIEnv* env, jobject bitmap) {
    executeBitmap(env, bitmap);
    std::vector<float> result(kExpectedOutputCount);
    copyOutput(result.data(), result.size());
    return result;
  }

  void executeBitmap(JNIEnv* env, jobject bitmap) {
    AndroidBitmapInfo info{};
    if (bitmap == nullptr ||
        AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        info.width != 640U || info.height != 640U) {
      throw std::invalid_argument("QNN bitmap input must be 640x640 RGBA_8888");
    }
    auto& inputDescriptor = tensorV2(inputs_[0]);
    const auto& quantization = inputDescriptor.quantizeParams;
    if (inputDescriptor.dataType != QNN_DATATYPE_SFIXED_POINT_16 ||
        quantization.quantizationEncoding != QNN_QUANTIZATION_ENCODING_SCALE_OFFSET ||
        quantization.scaleOffsetEncoding.scale <= 0.0F) {
      throw std::runtime_error("Unexpected QNN input quantization contract");
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
        pixels == nullptr) {
      throw std::runtime_error("Cannot lock QNN bitmap pixels");
    }
    try {
      auto* quantized = static_cast<int16_t*>(inputDescriptor.clientBuf.data);
      const auto* source = static_cast<const uint8_t*>(pixels);
      quantizeBitmap(source, info, quantized, quantization);
      AndroidBitmap_unlockPixels(env, bitmap);
    } catch (...) {
      AndroidBitmap_unlockPixels(env, bitmap);
      throw;
    }
    executeGraph();
  }

  void copyOutput(float* result, size_t count) const {
    if (result == nullptr || count != kExpectedOutputCount) {
      throw std::invalid_argument("Unexpected QNN output destination size");
    }
    const auto& outputDescriptor = tensorV2(outputs_[outputIndex_]);
    if (outputDescriptor.dataType != QNN_DATATYPE_FLOAT_16) {
      throw std::runtime_error("Unexpected QNN output data type");
    }
    const auto* halfValues = static_cast<const uint16_t*>(outputDescriptor.clientBuf.data);
    std::transform(halfValues, halfValues + count, result, halfToFloat);
  }

  void copyCompactOutput(float* result, size_t count) const {
    if (result == nullptr || count != kCompactOutputCount) {
      throw std::invalid_argument("Unexpected compact QNN output destination size");
    }
    const auto& outputDescriptor = tensorV2(outputs_[outputIndex_]);
    if (outputDescriptor.dataType != QNN_DATATYPE_FLOAT_16) {
      throw std::runtime_error("Unexpected QNN output data type");
    }
    const auto* values = static_cast<const uint16_t*>(outputDescriptor.clientBuf.data);
    const auto rawValue = [values](size_t channel, size_t candidate) {
      return halfToFloat(values[channel * kRawCandidateCount + candidate]);
    };
    struct Candidate {
      size_t index;
      float score;
      size_t classId;
    };
    const auto lowerScore = [](const Candidate& first, const Candidate& second) {
      if (first.score != second.score) return first.score > second.score;
      return first.index < second.index;
    };
    std::priority_queue<Candidate, std::vector<Candidate>, decltype(lowerScore)> strongest(lowerScore);
    for (size_t candidate = 0; candidate < kRawCandidateCount; ++candidate) {
      size_t bestClass = 0;
      float bestScore = -INFINITY;
      for (size_t classId = 0; classId < kClassCount; ++classId) {
        const float score = rawValue(kRawClassOffset + classId, candidate);
        if (std::isfinite(score) && score > bestScore) {
          bestClass = classId;
          bestScore = score;
        }
      }
      if (bestScore < kClassificationScore) continue;
      const Candidate scored{candidate, bestScore, bestClass};
      if (strongest.size() < kCompactCandidateCount) {
        strongest.push(scored);
      } else if (bestScore > strongest.top().score ||
                 (bestScore == strongest.top().score && candidate < strongest.top().index)) {
        strongest.pop();
        strongest.push(scored);
      }
    }
    std::vector<Candidate> sorted;
    sorted.reserve(strongest.size());
    while (!strongest.empty()) {
      sorted.push_back(strongest.top());
      strongest.pop();
    }
    std::sort(sorted.begin(), sorted.end(), [](const Candidate& first, const Candidate& second) {
      if (first.score != second.score) return first.score > second.score;
      return first.index < second.index;
    });
    std::fill(result, result + count, 0.0F);
    for (size_t row = 0; row < sorted.size(); ++row) {
      const Candidate& candidate = sorted[row];
      const size_t destination = row * kCompactRowSize;
      for (size_t channel = 0; channel < 4U; ++channel) {
        result[destination + channel] = rawValue(channel, candidate.index);
      }
      result[destination + 4U] = candidate.score;
      result[destination + 5U] = static_cast<float>(candidate.classId);
      for (size_t channel = 0; channel < kKeypointCount * 3U; ++channel) {
        result[destination + 6U + channel] =
            rawValue(kRawKeypointOffset + channel, candidate.index);
      }
    }
  }

  int64_t lastInferenceNanos() const { return lastInferenceNanos_; }
  int64_t initializationNanos() const { return initializationNanos_; }
  const std::string& backendBuildId() const { return backendBuildId_; }

 private:
  static uint32x4_t divideBy255(uint32x4_t value) {
    constexpr uint32_t kDivide255Multiplier = 0x80808081U;
    const uint32x2_t low = vmovn_u64(vshrq_n_u64(
        vmull_u32(vget_low_u32(value), vdup_n_u32(kDivide255Multiplier)), 39));
    const uint32x2_t high = vmovn_u64(vshrq_n_u64(
        vmull_u32(vget_high_u32(value), vdup_n_u32(kDivide255Multiplier)), 39));
    return vcombine_u32(low, high);
  }

  static int16x8_t quantizeRgb8(uint8x8_t values) {
    const uint16x8_t wide = vmovl_u8(values);
    const uint32x4_t lowNumerator = vaddq_u32(
        vshlq_n_u32(vmovl_u16(vget_low_u16(wide)), 15), vdupq_n_u32(127U));
    const uint32x4_t highNumerator = vaddq_u32(
        vshlq_n_u32(vmovl_u16(vget_high_u16(wide)), 15), vdupq_n_u32(127U));
    uint16x8_t result = vcombine_u16(
        vmovn_u32(divideBy255(lowNumerator)),
        vmovn_u32(divideBy255(highNumerator)));
    result = vminq_u16(result, vdupq_n_u16(32767U));
    return vreinterpretq_s16_u16(result);
  }

  void quantizeBitmap(const uint8_t* source,
                      const AndroidBitmapInfo& info,
                      int16_t* destination,
                      const Qnn_QuantizeParams_t& quantization) const {
    const float expectedScale = 1.0F / 32768.0F;
    const bool neonCompatible =
        quantization.scaleOffsetEncoding.offset == 0 &&
        std::fabs(quantization.scaleOffsetEncoding.scale - expectedScale) <= 1.0e-9F;
    size_t outputIndex = 0;
    for (uint32_t y = 0; y < info.height; ++y) {
      const auto* row = source + y * info.stride;
      uint32_t x = 0;
      if (neonCompatible) {
        for (; x + 8U <= info.width; x += 8U) {
          const uint8x8x4_t rgba = vld4_u8(row + x * 4U);
          const int16x8x3_t rgb = {
              {quantizeRgb8(rgba.val[0]), quantizeRgb8(rgba.val[1]), quantizeRgb8(rgba.val[2])}};
          vst3q_s16(destination + outputIndex, rgb);
          outputIndex += 24U;
        }
      }
      for (; x < info.width; ++x) {
        const auto* pixel = row + x * 4U;
        destination[outputIndex++] = inputLookup_[pixel[0]];
        destination[outputIndex++] = inputLookup_[pixel[1]];
        destination[outputIndex++] = inputLookup_[pixel[2]];
      }
    }
  }

  void configurePerformance(const std::string& performanceMode) {
    if (performanceMode == "default") return;
    const bool burst = performanceMode == "burst";
    const bool sustained = performanceMode == "sustained";
    const bool powersave = performanceMode == "powersave";
    if (!burst && !sustained && !powersave) {
      throw std::invalid_argument("Unsupported QNN HTP performance mode");
    }
    if (interface_.deviceGetInfrastructure == nullptr) {
      throw std::runtime_error("QNN HTP performance infrastructure is unavailable");
    }
    QnnDevice_Infrastructure_t infrastructure = nullptr;
    if (interface_.deviceGetInfrastructure(&infrastructure) != QNN_SUCCESS ||
        infrastructure == nullptr ||
        infrastructure->infraType != QNN_HTP_DEVICE_INFRASTRUCTURE_TYPE_PERF) {
      throw std::runtime_error("Cannot obtain QNN HTP performance infrastructure");
    }
    perfInfrastructure_ = infrastructure->perfInfra;
    if (perfInfrastructure_.createPowerConfigId == nullptr ||
        perfInfrastructure_.destroyPowerConfigId == nullptr ||
        perfInfrastructure_.setPowerConfig == nullptr ||
        perfInfrastructure_.createPowerConfigId(0U, 0U, &powerConfigId_) != QNN_SUCCESS) {
      throw std::runtime_error("Cannot create QNN HTP power configuration");
    }
    powerConfigCreated_ = true;

    QnnHtpPerfInfrastructure_PowerConfig_t dcvs{};
    dcvs.option = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_DCVS_V3;
    dcvs.dcvsV3Config.contextId = powerConfigId_;
    dcvs.dcvsV3Config.setDcvsEnable = 1U;
    dcvs.dcvsV3Config.dcvsEnable = burst ? 0U : 1U;
    dcvs.dcvsV3Config.powerMode = powersave
        ? QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_POWER_SAVER_MODE
        : QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_PERFORMANCE_MODE;
    dcvs.dcvsV3Config.setSleepLatency = 1U;
    dcvs.dcvsV3Config.sleepLatency = burst ? 40U : 100U;
    dcvs.dcvsV3Config.setSleepDisable = 1U;
    dcvs.dcvsV3Config.sleepDisable = burst ? 1U : 0U;
    dcvs.dcvsV3Config.setBusParams = 1U;
    dcvs.dcvsV3Config.busVoltageCornerMin = powersave
        ? DCVS_VOLTAGE_VCORNER_SVS
        : (sustained ? DCVS_VOLTAGE_VCORNER_NOM : DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER);
    dcvs.dcvsV3Config.busVoltageCornerTarget = powersave
        ? DCVS_VOLTAGE_VCORNER_NOM_PLUS
        : (sustained ? DCVS_VOLTAGE_VCORNER_TURBO : DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER);
    dcvs.dcvsV3Config.busVoltageCornerMax = powersave
        ? DCVS_VOLTAGE_VCORNER_TURBO
        : DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
    dcvs.dcvsV3Config.setCoreParams = 1U;
    dcvs.dcvsV3Config.coreVoltageCornerMin = dcvs.dcvsV3Config.busVoltageCornerMin;
    dcvs.dcvsV3Config.coreVoltageCornerTarget = dcvs.dcvsV3Config.busVoltageCornerTarget;
    dcvs.dcvsV3Config.coreVoltageCornerMax = dcvs.dcvsV3Config.busVoltageCornerMax;

    QnnHtpPerfInfrastructure_PowerConfig_t polling{};
    polling.option = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_RPC_POLLING_TIME;
    polling.rpcPollingTimeConfig = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIG_MAX_RPC_POLLING_TIME;
    const QnnHtpPerfInfrastructure_PowerConfig_t* configs[] = {
        &dcvs,
        burst ? &polling : nullptr,
        nullptr,
    };
    if (perfInfrastructure_.setPowerConfig(powerConfigId_, configs) != QNN_SUCCESS) {
      throw std::runtime_error("Cannot apply QNN HTP burst configuration");
    }
  }

  std::vector<float> execute() {
    executeGraph();
    std::vector<float> result(kExpectedOutputCount);
    copyOutput(result.data(), result.size());
    return result;
  }

  void executeGraph() {
    const auto started = std::chrono::steady_clock::now();
    if (interface_.graphExecute((*graphs_)[0].graph,
                                inputs_.data(), static_cast<uint32_t>(inputs_.size()),
                                outputs_.data(), static_cast<uint32_t>(outputs_.size()),
                                nullptr, nullptr) != QNN_GRAPH_NO_ERROR) {
      throw std::runtime_error("QNN graphExecute failed");
    }
    lastInferenceNanos_ = elapsedNanos(started);
  }
  static int64_t elapsedNanos(std::chrono::steady_clock::time_point started) {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
               std::chrono::steady_clock::now() - started)
        .count();
  }

  void setupTensors(const GraphInfo& graph) {
    const bool supportedOutputCount =
        graph.numOutputTensors == 1 || graph.numOutputTensors == 4;
    if (graph.numInputTensors != 1 || graph.inputTensors == nullptr ||
        !supportedOutputCount || graph.outputTensors == nullptr) {
      throw std::runtime_error("Unexpected QNN graph IO count");
    }
    inputs_.assign(graph.inputTensors, graph.inputTensors + graph.numInputTensors);
    outputs_.assign(graph.outputTensors, graph.outputTensors + graph.numOutputTensors);
    inputBuffers_.resize(inputs_.size());
    outputBuffers_.resize(outputs_.size());

    for (size_t index = 0; index < inputs_.size(); ++index) {
      const size_t bytes = elementCount(inputs_[index]) * elementBytes(tensorV2(inputs_[index]).dataType);
      inputBuffers_[index].resize(bytes);
      auto& descriptor = tensorV2(inputs_[index]);
      descriptor.clientBuf.data = inputBuffers_[index].data();
      descriptor.clientBuf.dataSize = static_cast<uint32_t>(bytes);
    }
    bool foundOutput = false;
    for (size_t index = 0; index < outputs_.size(); ++index) {
      const auto& sourceDescriptor = tensorV2(outputs_[index]);
      const size_t bytes = elementCount(outputs_[index]) * elementBytes(sourceDescriptor.dataType);
      outputBuffers_[index].resize(bytes);
      auto& descriptor = tensorV2(outputs_[index]);
      descriptor.clientBuf.data = outputBuffers_[index].data();
      descriptor.clientBuf.dataSize = static_cast<uint32_t>(bytes);
      if (descriptor.name != nullptr && std::strcmp(descriptor.name, "output0") == 0) {
        outputIndex_ = index;
        foundOutput = true;
      }
    }
    if (elementCount(inputs_[0]) != kExpectedInputCount || !foundOutput ||
        elementCount(outputs_[outputIndex_]) != kExpectedOutputCount) {
      throw std::runtime_error("Unexpected materialized QNN tensor shape");
    }
    const auto& inputDescriptor = tensorV2(inputs_[0]);
    const auto& quantization = inputDescriptor.quantizeParams;
    if (inputDescriptor.dataType != QNN_DATATYPE_SFIXED_POINT_16 ||
        quantization.quantizationEncoding != QNN_QUANTIZATION_ENCODING_SCALE_OFFSET ||
        quantization.scaleOffsetEncoding.scale <= 0.0F) {
      throw std::runtime_error("Unexpected QNN input quantization contract");
    }
    const float scale = quantization.scaleOffsetEncoding.scale;
    const int32_t offset = quantization.scaleOffsetEncoding.offset;
    for (size_t value = 0; value < inputLookup_.size(); ++value) {
      const float normalized = static_cast<float>(value) / 255.0F;
      const long quantized = std::lround(normalized / scale - static_cast<float>(offset));
      inputLookup_[value] = static_cast<int16_t>(std::clamp(quantized, -32768L, 32767L));
    }
  }

  void shutdown() noexcept {
    inputs_.clear();
    outputs_.clear();
    inputBuffers_.clear();
    outputBuffers_.clear();
    if (graphs_ != nullptr && freeGraphsInfo_ != nullptr) {
      freeGraphsInfo_(&graphs_, graphCount_);
      graphs_ = nullptr;
    }
    if (context_ != nullptr && interface_.contextFree != nullptr) {
      interface_.contextFree(context_, nullptr);
      context_ = nullptr;
    }
    if (powerConfigCreated_ && perfInfrastructure_.destroyPowerConfigId != nullptr) {
      perfInfrastructure_.destroyPowerConfigId(powerConfigId_);
      powerConfigCreated_ = false;
      powerConfigId_ = 0U;
    }
    if (device_ != nullptr && interface_.deviceFree != nullptr) {
      interface_.deviceFree(device_);
      device_ = nullptr;
    }
    if (backend_ != nullptr && interface_.backendFree != nullptr) {
      interface_.backendFree(backend_);
      backend_ = nullptr;
    }
    if (log_ != nullptr && interface_.logFree != nullptr) {
      interface_.logFree(log_);
      log_ = nullptr;
    }
    if (modelLibrary_ != nullptr) {
      dlclose(modelLibrary_);
      modelLibrary_ = nullptr;
    }
    if (backendLibrary_ != nullptr) {
      dlclose(backendLibrary_);
      backendLibrary_ = nullptr;
    }
  }

  void* backendLibrary_ = nullptr;
  void* modelLibrary_ = nullptr;
  QNN_INTERFACE_VER_TYPE interface_ = QNN_INTERFACE_VER_TYPE_INIT;
  ComposeGraphsFn composeGraphs_ = nullptr;
  FreeGraphsInfoFn freeGraphsInfo_ = nullptr;
  Qnn_LogHandle_t log_ = nullptr;
  Qnn_BackendHandle_t backend_ = nullptr;
  Qnn_DeviceHandle_t device_ = nullptr;
  Qnn_ContextHandle_t context_ = nullptr;
  QnnHtpDevice_PerfInfrastructure_t perfInfrastructure_ =
      QNN_HTP_DEVICE_PERF_INFRASTRUCTURE_INIT;
  uint32_t powerConfigId_ = 0U;
  bool powerConfigCreated_ = false;
  GraphInfo** graphs_ = nullptr;
  uint32_t graphCount_ = 0;
  std::vector<Qnn_Tensor_t> inputs_;
  std::vector<Qnn_Tensor_t> outputs_;
  std::vector<std::vector<uint8_t>> inputBuffers_;
  std::vector<std::vector<uint8_t>> outputBuffers_;
  std::array<int16_t, 256U> inputLookup_{};
  size_t outputIndex_ = 0;
  int64_t initializationNanos_ = 0;
  int64_t lastInferenceNanos_ = 0;
  std::string backendBuildId_;
};

Runtime* fromHandle(jlong handle) {
  if (handle == 0) throw std::runtime_error("QNN runtime handle is null");
  return reinterpret_cast<Runtime*>(handle);
}

void throwJava(JNIEnv* env, const std::exception& error) {
  jclass type = env->FindClass("java/lang/IllegalStateException");
  if (type != nullptr) env->ThrowNew(type, error.what());
}

std::string javaString(JNIEnv* env, jstring value) {
  if (value == nullptr) throw std::invalid_argument("Native path must not be null");
  const char* chars = env->GetStringUTFChars(value, nullptr);
  if (chars == nullptr) throw std::runtime_error("Cannot read native path");
  std::string result(chars);
  env->ReleaseStringUTFChars(value, chars);
  return result;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_oppovisual_r8qnn_R8QnnRuntime_nativeCreate(JNIEnv* env,
                                                     jclass,
                                                     jstring nativeDirectory,
                                                     jstring backendPath,
                                                     jstring modelPath,
                                                     jstring performanceMode) {
  try {
    return reinterpret_cast<jlong>(new Runtime(javaString(env, nativeDirectory),
                                                javaString(env, backendPath),
                                                javaString(env, modelPath),
                                                javaString(env, performanceMode)));
  } catch (const std::exception& error) {
    throwJava(env, error);
    return 0;
  }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_oppovisual_r8qnn_R8QnnRuntime_nativeRun(JNIEnv* env,
                                                  jclass,
                                                  jlong handle,
                                                  jfloatArray input) {
  try {
    if (input == nullptr || static_cast<size_t>(env->GetArrayLength(input)) != kExpectedInputCount) {
      throw std::invalid_argument("QNN input array has the wrong length");
    }
    jfloat* values = env->GetFloatArrayElements(input, nullptr);
    if (values == nullptr) throw std::runtime_error("Cannot access QNN input array");
    std::vector<float> output;
    try {
      output = fromHandle(handle)->run(values, kExpectedInputCount);
    } catch (...) {
      env->ReleaseFloatArrayElements(input, values, JNI_ABORT);
      throw;
    }
    env->ReleaseFloatArrayElements(input, values, JNI_ABORT);
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(output.size()));
    if (result == nullptr) throw std::runtime_error("Cannot allocate QNN output array");
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(output.size()), output.data());
    return result;
  } catch (const std::exception& error) {
    throwJava(env, error);
    return nullptr;
  }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_oppovisual_r8qnn_R8QnnRuntime_nativeRunBitmap(JNIEnv* env,
                                                        jclass,
                                                        jlong handle,
                                                        jobject bitmap) {
  try {
    std::vector<float> output = fromHandle(handle)->runBitmap(env, bitmap);
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(output.size()));
    if (result == nullptr) throw std::runtime_error("Cannot allocate QNN output array");
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(output.size()), output.data());
    return result;
  } catch (const std::exception& error) {
    throwJava(env, error);
    return nullptr;
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_oppovisual_r8qnn_R8QnnRuntime_nativeRunBitmapInto(JNIEnv* env,
                                                            jclass,
                                                            jlong handle,
                                                            jobject bitmap,
                                                            jfloatArray output) {
  try {
    if (output == nullptr || static_cast<size_t>(env->GetArrayLength(output)) != kExpectedOutputCount) {
      throw std::invalid_argument("QNN output array has the wrong length");
    }
    Runtime* runtime = fromHandle(handle);
    runtime->executeBitmap(env, bitmap);
    auto* values = static_cast<jfloat*>(env->GetPrimitiveArrayCritical(output, nullptr));
    if (values == nullptr) throw std::runtime_error("Cannot access QNN output array");
    try {
      runtime->copyOutput(values, kExpectedOutputCount);
    } catch (...) {
      env->ReleasePrimitiveArrayCritical(output, values, 0);
      throw;
    }
    env->ReleasePrimitiveArrayCritical(output, values, 0);
  } catch (const std::exception& error) {
    throwJava(env, error);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_oppovisual_r8qnn_R8QnnRuntime_nativeRunCompact(JNIEnv* env,
                                                         jclass,
                                                         jlong handle,
                                                         jobject bitmap,
                                                         jfloatArray output) {
  try {
    if (output == nullptr || static_cast<size_t>(env->GetArrayLength(output)) != kCompactOutputCount) {
      throw std::invalid_argument("Compact QNN output array has the wrong length");
    }
    Runtime* runtime = fromHandle(handle);
    runtime->executeBitmap(env, bitmap);
    auto* values = static_cast<jfloat*>(env->GetPrimitiveArrayCritical(output, nullptr));
    if (values == nullptr) throw std::runtime_error("Cannot access compact QNN output array");
    try {
      runtime->copyCompactOutput(values, kCompactOutputCount);
    } catch (...) {
      env->ReleasePrimitiveArrayCritical(output, values, 0);
      throw;
    }
    env->ReleasePrimitiveArrayCritical(output, values, 0);
  } catch (const std::exception& error) {
    throwJava(env, error);
  }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_oppovisual_r8qnn_R8QnnRuntime_nativeGetLastInferenceNanos(JNIEnv* env,
                                                                   jclass,
                                                                   jlong handle) {
  try {
    return fromHandle(handle)->lastInferenceNanos();
  } catch (const std::exception& error) {
    throwJava(env, error);
    return 0;
  }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_oppovisual_r8qnn_R8QnnRuntime_nativeGetInitializationNanos(JNIEnv* env,
                                                                    jclass,
                                                                    jlong handle) {
  try {
    return fromHandle(handle)->initializationNanos();
  } catch (const std::exception& error) {
    throwJava(env, error);
    return 0;
  }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_oppovisual_r8qnn_R8QnnRuntime_nativeGetBackendBuildId(JNIEnv* env,
                                                               jclass,
                                                               jlong handle) {
  try {
    return env->NewStringUTF(fromHandle(handle)->backendBuildId().c_str());
  } catch (const std::exception& error) {
    throwJava(env, error);
    return nullptr;
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_oppovisual_r8qnn_R8QnnRuntime_nativeDestroy(JNIEnv* env, jclass, jlong handle) {
  try {
    delete fromHandle(handle);
  } catch (const std::exception& error) {
    throwJava(env, error);
  }
}

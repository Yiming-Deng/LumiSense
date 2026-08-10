# 模型与厂商资产说明

公开源码不包含模型权重、Android 签名材料或厂商专有运行库。这些文件不能通过不明镜像获取，也不能提交到 Git。官方 GitHub Release APK 可以在适用许可允许时内嵌运行所需模型和可再分发运行库，但这不改变 Git 源码边界。

## Google MediaPipe 模型

从 Google 官方模型地址获取并放入 `app/src/main/assets`：

| 文件 | 官方来源 |
|---|---|
| `face_landmarker.task` | `https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task` |
| `gesture_recognizer.task` | `https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task` |

下载和再分发应遵循 Google AI Edge 模型条款。当前代码默认的人脸功能需要 Face Landmarker；Gesture Recognizer 仅用于保留的兼容识别实现。

## 最终手势模型

应用期望 `app/src/main/assets/gesture_pose_final_fullqat_w8a16_640.tflite`。该文件是 W8A16 QAT 模型的 LiteRT 版本。它不随源码仓库提供。官方 APK 如内嵌该模型，发布者必须先确认 Ultralytics 基础模型和 HaGRIDv2 等训练数据对派生权重的再分发要求，并在 Release 中保留归属与适用许可说明。

缺少该文件时可以编译调试源码，但手势推理不能正常运行，Release 的资产校验会明确失败。

## Qualcomm QNN/HTP

QNN 适配器的自研代码位于 `r8-qnn-adapter`。以下内容必须由开发者从 Qualcomm Software Center 合法获取，仓库不会提供：

- QAIRT/QNN API 头文件；
- HTP backend、stub、skeleton 和 system runtime；
- LiteRT Qualcomm compiler/dispatch plugin；
- 由 QNN 工具链生成的模型库。

将 `QAIRT_SDK_ROOT` 环境变量或 Gradle 属性 `qairtSdkRoot` 指向 SDK 根目录后，适配器会从 `${QAIRT_SDK_ROOT}/include/QNN` 编译。没有 SDK 时，公开调试构建会跳过 QNN JNI 编译并使用 LiteRT 代码路径。

完整 Release 还会检查所需 HTP 运行库和最终模型是否存在，避免生成看似成功但不能使用 NPU 的安装包。APK 只能打包 Qualcomm 许可明确允许再分发的运行时文件；SDK 头文件、编译工具、示例和其他开发资产不得进入 APK 或 Release 附件。

## GitHub Release 边界

- Git 标签和源码归档不包含模型、APK、签名材料或厂商 SDK。
- 官方签名 APK 作为单独的 Release 附件发布，不提交进 Git 历史。
- APK 发布前必须记录版本、支持设备范围、SHA-256、构建后端和第三方声明。
- 模型或运行库的许可条件不明确时，停止发布相应 APK，不能用 Apache License 2.0 代替第三方许可。

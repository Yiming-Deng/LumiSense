# 灵映开源审计

审计日期：2026-08-10

## 结论

自研源码已经确定采用 Apache License 2.0，可以按 `public-source` 白名单目录建立公开仓库。最终手势模型、MediaPipe 模型和 Qualcomm 运行库不随 Git 源码发布；它们如内嵌于官方 GitHub Release APK，仍须分别满足各自的再分发条件并保留第三方声明。

当前 Git 仓库没有已提交文件，因此密钥和厂商二进制尚未进入 Git 历史。首次提交必须只从 `public-source` 目录建立。

## 可公开的内容

| 内容 | 结论 | 条件 |
|---|---|---|
| 灵映自研 Kotlin、Java、C++ 代码 | 可公开 | Apache License 2.0，保留 `LICENSE` 与 `NOTICE` |
| AndroidX、Compose、CameraX、DataStore | 可使用 | Apache License 2.0，保留声明 |
| Kotlin 与 kotlinx.coroutines | 可使用 | Apache License 2.0，保留声明 |
| LiteRT | 可使用 | Apache License 2.0，保留声明 |
| Filament | 可使用 | Apache License 2.0，保留声明 |
| Roboto Condensed、得意黑 | 可再分发 | 随字体附带 SIL OFL 1.1 文本 |
| VRM 示例虚拟形象派生资源 | 可在许可条件下分发 | 保留 pixiv 归属与 VRM Public License 1.0 |

## 需单独许可或暂不公开的内容

| 内容 | 处理方式 | 原因 |
|---|---|---|
| Qualcomm QNN/HTP 头文件 | 不进入公开目录 | 文件标注为 Qualcomm Confidential and Proprietary |
| Qualcomm QAIRT/QNN/HTP `.so` | 不进入公开目录 | 厂商专有 SDK 与运行库 |
| QNN 编译模型库 `.so` | 不进入公开目录 | 属于厂商工具链生成的部署资产，需按 SDK 条款分发 |
| Android 签名密钥和口令 | 不进入公开目录 | 私密凭据 |
| 最终手势 `.tflite` 模型 | 不进入 Git 源码；官方 APK 可按适用条款内嵌 | 需确认 Ultralytics 与 HaGRIDv2 派生模型许可 |
| MediaPipe `.task` 模型 | 不进入 Git 源码；官方 APK 可按适用条款内嵌 | 模型需遵循 Google AI Edge 模型条款 |
| 训练集、测试集和派生图片 | 不进入公开目录 | 数据集许可、隐私和仓库体积边界 |
| APK、评测输入输出和历史交付包 | 不进入 Git 源码 | 官方签名 APK 可作为 GitHub Release 附件；其余产物不公开 |
| Blender 工程、历史头套和实验 GLB | 不进入公开目录 | 非最终功能，且来源许可不同 |

## 模型与数据许可风险

- 手势模型以 Ultralytics YOLO 系列实现训练。公开派生权重前，需要以实际使用的 Ultralytics 许可证或商业授权为准，不能只按本项目代码许可证发布。
- HaGRIDv2 数据按 CC BY-SA 4.0 使用。数据不进入仓库；如发布由其产生的模型权重，应保留数据集归属并复核 ShareAlike 对权重的适用范围。
- COCO 2017、Places365 和 IPN Hand 只用于训练或评测，不在仓库中再分发。
- Face Landmarker、Gesture Recognizer 模型通过 Google 官方地址获取，公开仓库只记录来源和校验方式。

## 敏感信息审计

已识别并排除：

- `keystore.properties`、`oppovisual-demo.jks` 和 `local.properties`；
- 本机绝对路径、服务器路径、无线 ADB 地址及内部调试日志；
- 训练数据、用户录制视频、设备截图和 UI 层级转储；
- 历史压缩包、APK、构建缓存、Python 虚拟环境和模型转换产物。

源码模式扫描未发现硬编码 API Key、OAuth Secret、Bearer Token 或私钥正文。构建脚本中的 `storePassword` 和 `keyPassword` 只是从本地属性文件读取的字段名，不是凭据值。

## 发布检查

首次 GitHub 提交前必须满足：

1. 从 `public-source` 新建仓库，不复制当前工作目录的私有文件。
2. 保留完整 `LICENSE`、`NOTICE`、`THIRD_PARTY_NOTICES.md` 和第三方许可证文本。
3. 运行敏感信息扫描，确认源码中不存在密钥、绝对路径、IP 地址、QNN 头文件、`jniLibs`、APK 和大文件。
4. 公开 CI 只构建不依赖私有资产的调试源码；Release 由持有合法模型、QAIRT 运行库和签名材料的维护者在受控环境中完成。
5. 发布 APK 前按 `RELEASE_POLICY.md` 核对模型和运行库再分发依据，并附版本、设备范围、SHA-256 与第三方声明。

## 公开目录验证结果

- 公开目录采用白名单复制，不从内部工程继承忽略文件。
- 未发现密钥、Token、私钥正文、本机绝对路径、无线 ADB 地址或服务器路径。
- 未发现 `.so`、`.task`、`.tflite`、`.glb`、APK、签名文件或 QNN SDK 头文件。
- 目录包含 147 个文件、约 3.804 MiB，最大文件约 2.51 MiB，不需要 Git LFS。
- `recognition-core:test` 和 `app:testDebugUnitTest` 通过。
- Android Lint 为 0 error、29 warning。
- Debug Kotlin 编译和 Debug APK 打包通过。由于本机 Android SDK 路径包含空格，验证时显式跳过 LiteRT 的 `ndk-build` 任务；完整原生打包应在无空格 SDK 路径或 Linux CI 上运行。

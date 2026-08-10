# 灵映（LumiSense）

灵映是一款完全离线运行的 Android 视觉交互应用。它使用前置摄像头完成手部检测、21 点关键点定位、静态手势分类、动态手势交互，以及面部表情、头部动作和虚拟形象驱动。相机帧只在设备本地处理。

## 代码结构

- `app`：CameraX、Compose 界面、识别链路、全局手势控制和虚拟形象。
- `recognition-core`：跟踪、时间平滑、动态手势有限状态机和领域模型。
- `r8-litert-adapter`：LiteRT 推理适配器。
- `r8-qnn-adapter`：Qualcomm QNN/HTP 适配器源码；厂商 SDK 头文件和运行库不随仓库分发。

历史 VLM、benchmark、训练中间产物、数据集、APK、签名文件和厂商二进制不属于公开源码。

## 构建边界

环境要求为 JDK 17、Android SDK 36、Build Tools 36.0.0。公开目录默认只能构建不含私有模型和 QNN 运行库的调试源码：

```powershell
./gradlew.bat :recognition-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

完整运行还需要按 [模型与厂商资产说明](docs/MODEL_AND_VENDOR_ASSETS.md) 放置模型文件。QNN/HTP Release 构建需要开发者自行从 Qualcomm 获取许可允许的 QAIRT SDK 和运行库。仓库不提供签名密钥。

## 下载与发布

- 自研源码采用 [Apache License 2.0](LICENSE)。第三方代码、模型、字体、虚拟形象和厂商运行库仍分别遵循其原始许可。
- Git 源码仓库不提交 APK、AAB、模型权重、签名材料或 Qualcomm SDK。
- 维护者可以在 GitHub Releases 提供经过签名的官方 APK。Release 附件与源码边界相互独立，必须附版本、设备支持范围、SHA-256 和第三方声明。
- 官方 APK 可以内嵌运行所必需的模型及许可允许再分发的运行库；不得包含 Qualcomm SDK 头文件、开发工具、签名密钥或其他不可再分发内容。

完整规则见 [发布政策](RELEASE_POLICY.md)。

## 文档

- [算法原理说明书](docs/delivery/算法原理说明书.md)
- [使用说明书](docs/delivery/使用说明书.md)
- [端侧性能测试报告](docs/delivery/端侧性能测试报告.md)
- [开源审计](OPEN_SOURCE_AUDIT.md)
- [第三方声明](THIRD_PARTY_NOTICES.md)
- [发布政策](RELEASE_POLICY.md)

Copyright 2026 LumiSense contributors. Licensed under the Apache License, Version 2.0.

# 灵映构建与运行

## 环境

- Windows 11
- Eclipse Temurin JDK 17
- Android SDK Platform 36
- Android SDK Build Tools 36.0.0
- Android Platform Tools
- Gradle Wrapper 8.13

项目不依赖全局 Gradle。首次构建会从 Google Maven 和 Maven Central 下载依赖。

Windows 注意：Android NDK 的 `ndk-build` 不接受包含空格的 SDK/NDK 路径。请把 Android SDK 安装或映射到不含空格的目录，再设置 `ANDROID_SDK_ROOT` 或 `local.properties`。

## 公开源码构建

```powershell
$env:JAVA_HOME='<JDK 17 安装目录>'
./gradlew.bat :recognition-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

公开源码默认不包含模型权重和 Qualcomm SDK。缺少模型时源码可以编译，但手势、面部和虚拟形象功能不能完整运行。所需文件和许可边界见 [模型与厂商资产说明](MODEL_AND_VENDOR_ASSETS.md)。

## 真机安装

1. 在 Android 手机上开启开发者选项和 USB 调试。
2. 连接设备并确认授权：`adb devices`。
3. 安装：`adb install -r app/build/outputs/apk/debug/app-debug.apk`。
4. 首次启动后同意本地处理说明并授权相机。

## 完整 Release

Release 构建还需要：

- 最终 LiteRT 手势模型；
- 合法取得的 Qualcomm QAIRT/QNN/HTP 运行库和 QNN 模型库；
- Android Release 签名材料。

将 `QAIRT_SDK_ROOT` 环境变量或 Gradle 属性 `qairtSdkRoot` 指向 QAIRT SDK 根目录。公开仓库不提供 Qualcomm 头文件、运行库或模型库。

## Release 签名

Release 密钥和 `keystore.properties` 不提交 Git。维护者应为正式发行生成独立密钥。

`keystore.properties` 格式如下；`storeFile` 相对项目根目录解析：

```properties
storeFile=oppovisual-demo.jks
storePassword=<local-only>
keyAlias=oppovisual-demo
keyPassword=<local-only>
```

存在该文件时，`:app:assembleRelease` 自动签名。完整 Release 还会校验最终模型和 QNN/HTP 运行库，缺少任一项时构建会失败。交付前使用 SDK `apksigner verify --verbose --print-certs` 验证最终 APK。

签名 APK 可以作为 GitHub Release 附件发布，但不得提交进 Git。发布前还必须完成 [发布政策](../RELEASE_POLICY.md) 中的许可、资产清单、设备范围和 SHA-256 检查。

## 数据

训练和测试数据不存放在公开仓库内。HaGRIDv2、COCO 2017、Places365、IPN Hand 和 MPEblink 2.0 必须分别遵循其数据集条款。

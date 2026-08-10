# 公开目录结构

```text
app/                    Android 应用、界面、CameraX、识别链和生产测试
recognition-core/       跟踪、时间平滑、有限状态机和领域模型
r8-litert-adapter/      LiteRT 适配器
r8-qnn-adapter/         QNN/HTP 适配器源码（SDK 由使用者提供）
docs/                   构建、资产边界和最终交付说明
third_party/licenses/   已随源码重新分发的字体和素材归属记录
```

公开目录没有 `data`、`artifacts`、`benchmark`、`vlm-*`、训练脚本、模型权重、APK、签名文件、厂商 SDK、QNN 头文件或构建缓存。它是面向代码审查和后续开源提交的最小源码候选，不是已经带齐运行资产的完整交付包。

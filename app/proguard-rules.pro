-keep class com.google.mediapipe.** { *; }
-keep class com.google.common.flogger.** { *; }
-keep class com.google.protobuf.** { *; }
# LiteRT JNI resolves runtime and exception classes by their original names.
-keep class com.google.ai.edge.litert.** { *; }
# LiteRT-LM JNI also resolves configuration and content methods by name.
-keep class com.google.ai.edge.litertlm.** { *; }
-keepattributes SourceFile,LineNumberTable
-keep class com.oppovisual.core.** { *; }
# CatMocapFilamentView replaces ModelViewer's opaque default SwapChain with a
# transparent one after each TextureView surface creation.
-keepclassmembers class com.google.android.filament.utils.ModelViewer {
    com.google.android.filament.SwapChain swapChain;
}

# Optional MediaPipe graph profiling/template APIs are not used by Tasks Vision.
-dontwarn com.google.mediapipe.proto.CalculatorProfileProto$CalculatorProfile
-dontwarn com.google.mediapipe.proto.GraphTemplateProto$CalculatorGraphTemplate

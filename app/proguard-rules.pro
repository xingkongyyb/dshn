# Keep readable stacktraces for diagnostics
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod

# Android manifest entrypoints & services
-keep class dev.dshn.app.DshnApplication { *; }
-keep class dev.dshn.app.MainActivity { *; }
-keep class dev.dshn.app.runtime.RuntimeService { *; }
-keep class dev.dshn.app.bridge.DshnAccessibilityService { *; }

# Cross-process AIDL interfaces & stubs
-keep interface dev.dshn.app.runtime.IRuntimeService { *; }
-keep interface dev.dshn.app.runtime.IRuntimeCallback { *; }
-keep class dev.dshn.app.runtime.IRuntimeService$** { *; }
-keep class dev.dshn.app.runtime.IRuntimeCallback$** { *; }

# kotlinx.serialization models, generated serializers & Navigation Compose @Serializable routes
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
-keep,includedescriptorclasses class dev.dshn.**$$serializer { *; }
-keepclassmembers class dev.dshn.** {
    *** Companion;
}
-keepclasseswithmembers class dev.dshn.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class dev.dshn.** { *; }

# JNI native core & callback contracts (Rust libdshn_core.so)
-keep class dev.dshn.app.runtime.DshnNativeCore { *; }
-keep interface dev.dshn.app.runtime.DshnNativeCore$** { *; }
-keepclassmembers class * {
    native <methods>;
}

# Android 14+ background virtual display shell agent launched via app_process
-keep class dev.dshn.app.bridge.virtualdisplay.** { *; }

# Reflection fallback for java.lang.Process pid extraction on older runtimes
-dontwarn java.lang.ProcessHandle
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**

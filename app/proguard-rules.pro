# Release builds are not minified (see app/build.gradle.kts). These rules keep
# a future minified build working with the reflection-based libraries in use.
-keepattributes *Annotation*, InnerClasses, Signature
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-keep class com.tom_roush.pdfbox.** { *; }
# sherpa-onnx's JNI code looks its Kotlin classes and fields up by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.inkwell.notes.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

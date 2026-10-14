-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }

-keep,includedescriptorclasses class com.kherio.gitwatch.**$$serializer { *; }
-keepclassmembers class com.kherio.gitwatch.** { *** Companion; }
-keepclasseswithmembers class com.kherio.gitwatch.** { kotlinx.serialization.KSerializer serializer(...); }

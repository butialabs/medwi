# kotlinx.serialization (backup format, catalog, reminder texts)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class br.com.butialabs.medwi.**$$serializer { *; }
-keepclassmembers class br.com.butialabs.medwi.** {
    *** Companion;
}
-keepclasseswithmembers class br.com.butialabs.medwi.** {
    kotlinx.serialization.KSerializer serializer(...);
}

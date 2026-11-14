# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class app.hushtype.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class app.hushtype.service.HushtypeAccessibilityService

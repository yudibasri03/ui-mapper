# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class app.uimapper.model.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class app.uimapper.service.InspectorService

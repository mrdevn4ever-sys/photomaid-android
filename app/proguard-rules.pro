# kotlinx.serialization: generierte Serializer der persistierten Domainwerte behalten.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class app.photomaid.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.photomaid.**$$serializer { *; }
-dontnote kotlinx.serialization.**
# Release-Logging: keine Debug-/Verbose-Logs im Release.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

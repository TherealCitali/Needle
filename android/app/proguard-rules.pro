# JNI entry points are resolved by name, so the bridge class and its methods
# must survive R8 untouched.
-keep class dev.citali.needle.engine.NeedleNative { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# org.json is part of the platform on Android.
-dontwarn org.json.**

# Compose ships its own keep rules; nothing else in this app relies on reflection.

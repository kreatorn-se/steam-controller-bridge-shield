# Shizuku instantiates the user service and talks to its provider by class name.
-keep class rikka.shizuku.** { *; }

# App classes stay whole: UInputService is created by name in the shell process, the AIDL
# stub/proxy must match on both sides, and cpp/uinput_jni.cpp binds to UInputNative's
# method names. Only third-party code is shrunk.
-keep class com.steamcontroller.android.** { *; }

# dadb (ADB client used to start Shizuku after boot): keep it whole, it is small and parses
# key material by reflection-free but name-sensitive code paths we have not audited for R8.
-keep class dadb.** { *; }
-dontwarn dadb.**
-dontwarn okio.**
# Test-only classes some of dadb's transitive artifacts reference.
-dontwarn org.junit.**
-dontwarn org.graalvm.**

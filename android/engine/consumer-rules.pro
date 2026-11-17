# JNA binds native functions by reflecting over the generated bindings' interfaces and
# structures, so R8 must keep their names.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class io.github.jpsaud501.kern.engine.ffi.** { *; }
-dontwarn java.awt.**

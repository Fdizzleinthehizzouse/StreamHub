# Debug builds are not minified; this is here so a release build works too.
-keep class com.felix.streamhub.** { *; }
-dontwarn org.nanohttpd.**

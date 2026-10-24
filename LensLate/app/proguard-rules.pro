# Tess-two registers JNI methods by Java class/method name; R8 must keep them intact.
-keep class com.googlecode.tesseract.android.** { *; }
-keepclassmembers class * {
    native <methods>;
}
-dontwarn com.googlecode.tesseract.android.**

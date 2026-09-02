# MediaPipe GenAI ships JNI-backed classes that must survive shrinking.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**

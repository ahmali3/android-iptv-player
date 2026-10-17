# The FFmpeg renderer is loaded by reflection from DefaultRenderersFactory.
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

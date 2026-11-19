# Libraries that are reached through reflection / JNI -- R8 must not rename or strip them.

# usb-serial-for-android: the prober instantiates the drivers reflectively
-keep class com.hoho.android.usbserial.driver.** { *; }

# Paho MQTT: network modules, persistence and logger are loaded by class name
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-dontwarn org.eclipse.paho.client.mqttv3.**

# H3 (libh3-java.so) and zstd-jni: native code calls back into / looks up these classes by name
-keep class com.uber.h3core.** { *; }
-dontwarn com.uber.h3core.**
-keep class com.github.luben.zstd.** { *; }
-dontwarn com.github.luben.zstd.**

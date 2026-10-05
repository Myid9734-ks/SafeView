# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep TubeGuard service and receiver classes
-keep class com.safeguard.tubeguard.service.** { *; }
-keep class com.safeguard.tubeguard.receiver.** { *; }
-keep class com.safeguard.tubeguard.util.** { *; }

# Keep accessibility service config
-keep class * extends android.accessibilityservice.AccessibilityService { *; }

# Keep VPN service
-keep class * extends android.net.VpnService { *; }

# Keep broadcast receivers
-keep class * extends android.content.BroadcastReceiver { *; }

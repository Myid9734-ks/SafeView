# Add project specific ProGuard rules here.

# Activities
-keep class com.safeguard.tubeguard.MainActivity { *; }
-keep class com.safeguard.tubeguard.BlockedActivity { *; }
-keep class com.safeguard.tubeguard.PinSetupActivity { *; }
-keep class com.safeguard.tubeguard.PinVerifyActivity { *; }
-keep class com.safeguard.tubeguard.LogActivity { *; }

# Services (Android Framework 호출 필수)
-keep class com.safeguard.tubeguard.service.TubeGuardVpnService { *; }
-keep class com.safeguard.tubeguard.service.TubeGuardAccessibilityService { *; }

# Receivers
-keep class com.safeguard.tubeguard.receiver.** { *; }

# PIN 암호화 클래스
-keep class com.safeguard.tubeguard.util.PinManager {
    public boolean verifyPin(java.lang.String);
    public boolean setPin(java.lang.String);
    public boolean hasPin();
    public void clearPin();
    public boolean changePin(java.lang.String, java.lang.String);
}
-keep class com.safeguard.tubeguard.util.PrefsManager {
    public static com.safeguard.tubeguard.util.PrefsManager getInstance(android.content.Context);
    public *;
}
-keep class com.safeguard.tubeguard.util.BlockLogEntry { *; }

# Billing
-keep class com.safeguard.tubeguard.billing.BillingManager { *; }
-keep class com.safeguard.tubeguard.billing.TrialManager { *; }
-keep class com.safeguard.tubeguard.PurchaseActivity { *; }
-keep class com.android.billingclient.** { *; }

# Firebase Crashlytics & Firestore
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception
-keep class com.google.firebase.crashlytics.** { *; }
-keep class com.google.firebase.firestore.** { *; }

# Remove Log calls in release build
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

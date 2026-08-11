# uvcpad ProGuard rules (adapted from hdmi2mp)
#
# 1. Do not obfuscate the app's own classes: crash self-capture is a diagnostic tool, and the
#    stack traces in crash logs must keep real class names/line numbers
#    (R8 renames non-entry-point classes by default, producing frames like a.a that cannot be located).
#    This also keeps BluetoothHidDevice.Callback implementation (BluetoothController) intact:
#    the callback object is handed to the system via registerApp and must keep its method
#    signatures/names for the binder dispatch (DESIGN §1.2 note).
-keep class com.github.ifeel.uvcpad.** { *; }

# 2. None of the three AUSBC AARs (libausbc/libuvc/libnative) ship consumer proguard rules
#    (proguard.txt is empty/missing, so library internal classes may be wrongly stripped when
#    R8 runs on release), and the library relies internally on calls R8 cannot track statically,
#    such as JNI/reflection/enum iteration. Keep the whole packages conservatively.
-keep class com.jiangdg.ausbc.** { *; }

# 3. libuvc's com.jiangdg.uvc.** (the package containing UVCCamera) must be kept in full:
#    UVCCamera's native methods (nativeSetStatusCallback/nativeCreate/nativeRelease, etc.) are
#    referenced by name as strings from the C++ JNI registration table, so R8 cannot statically
#    trace these call sites. Without the keep rule R8 removes native methods unreferenced from
#    Java, and JNI_OnLoad registration cannot find them by name → NoSuchMethodError crash.
-keep class com.jiangdg.uvc.** { *; }

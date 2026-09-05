# Keep the stack traces in release builds readable.
#
# Without these, every crash reported by Play Console arrives with obfuscated class names and
# no line numbers, which makes them close to useless. The mapping file the build produces is
# uploaded alongside the bundle and turns them back into real names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Bouncy Castle is used through its low-level org.bouncycastle.crypto.* API rather than a JCA
# provider, so most of the library is genuinely unreachable and should be stripped. What must
# survive is anything looked up by name: the JCA plumbing does that even when unused, and R8
# cannot see through it.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# UnifiedPush resolves the distributor through broadcast intents and instantiates the
# receiver by name from the manifest, so its entry points cannot be traced from code.
-keep class org.unifiedpush.android.connector.** { *; }

# Our own components are named in the manifest and instantiated by the framework.
-keep class com.boxlabs.hexdroid.MainActivity { *; }
-keep class com.boxlabs.hexdroid.HexDroidApp { *; }
-keep class * extends android.app.Service { *; }
-keep class * extends android.content.BroadcastReceiver { *; }

# Coroutines carries a debug agent and a service loader entry that R8 warns about but which
# are not shipped.
-dontwarn kotlinx.coroutines.debug.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# Compose keeps what it needs through its own rules shipped in the library. Nothing to add.

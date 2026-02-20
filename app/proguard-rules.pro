# ProGuard rules

# Reproducible builds - remove debugging info that contains timestamps
-dontpreverify
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*

# Keep attributes but ensure deterministic order
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

-keep class kotlinx.coroutines.CoroutineExceptionHandler
-keep class kotlinx.coroutines.internal.MainDispatcherFactory

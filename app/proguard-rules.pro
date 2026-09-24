# Retrofit/Gson use these field names on the wire. Older installed versions also
# persisted unannotated model field names, so Release must preserve that format.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keep class com.jev.relationship.data.remote.** { *; }
-keep class com.jev.relationship.core.model.** { *; }
-keep class com.jev.relationship.ipc.** { *; }

# Loaded by LSPosed from META-INF rather than an application call site.
-keep class com.jev.relationship.xposed.JevXposedModule { *; }
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

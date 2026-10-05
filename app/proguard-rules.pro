# Nuntra —— 混淆规则
# 目前 release 未开启 minify，本文件作为占位与后续加固入口保留。

# kotlinx.serialization：保留 @Serializable 类的序列化器
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.shihua66666.nuntra.**$$serializer { *; }
-keepclassmembers class com.shihua66666.nuntra.** {
    *** Companion;
}
-keepclasseswithmembers class com.shihua66666.nuntra.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# NotificationListenerService 由系统反射实例化，不能混淆
-keep class * extends android.service.notification.NotificationListenerService { *; }

# DataStore
-keepclassmembers class * extends androidx.datastore.core.Serializer { *; }

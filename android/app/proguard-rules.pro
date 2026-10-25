# Модели ответов сайта разбираются kotlinx.serialization: правила для
# сгенерированных сериализаторов идут с самой библиотекой, здесь — только
# страховка для наших классов, чтобы R8 не переименовал поля компаньонов.
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers @kotlinx.serialization.Serializable class kz.ordaops.android.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class kz.ordaops.android.**$$serializer { *; }

# Служба принтера Sunmi вызывается через Binder по имени интерфейса.
-keep class kz.ordaops.android.feature.operator.sale.SunmiPrinterService { *; }

# OkHttp тянет необязательные TLS-провайдеры.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

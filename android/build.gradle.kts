// Версии — та же связка, что собирается у QTermAndroid (AGP 9: Kotlin встроен, kotlin.android не нужен)
plugins {
    id("com.android.application") version "9.3.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10" apply false
}

package clawberry.aiworm.cn

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

enum class AppLanguage(
  val rawValue: String,
  val languageTag: String,
) {
  English(rawValue = "en", languageTag = "en"),
  ChineseSimplified(rawValue = "zh", languageTag = "zh-Hans"),
  ;

  companion object {
    fun fromRawValue(raw: String?): AppLanguage =
      entries.firstOrNull { it.rawValue.equals(raw, ignoreCase = true) } ?: English
  }
}

object AppLocaleManager {
  fun apply(language: AppLanguage) {
    AppCompatDelegate.setApplicationLocales(
      LocaleListCompat.forLanguageTags(language.languageTag),
    )
  }
}

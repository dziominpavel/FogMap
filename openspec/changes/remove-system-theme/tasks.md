## 1. Хранение и миграция (D1, D2)

- [x] 1.1 В `data/SettingsRepository.kt`: убрать `ThemeModes.SYSTEM`, сузить `setThemeMode` до `require(v in {dark, light})`, обновить doc-комментарий ключа `THEME_MODE` (только `dark|light`) — проверка: `.\gradlew.bat :app:compileDebugKotlin` завершается `BUILD SUCCESSFUL`
- [x] 1.2 Реализовать нормализацию при чтении (design D1): функция чтения значения темы возвращает `light` только при точном совпадении, иначе `dark`, и перезаписывает нормализованное значение в DataStore — проверка: unit-тест из 3.1 проходит (`.\gradlew.bat :app:testDebugUnitTest`)
- [x] 1.3 Подключить нормализацию в потоке `themeMode` так, чтобы MainActivity и экраны получали только `dark`/`light` — проверка: `.\gradlew.bat :app:testDebugUnitTest` зелёный, в `SettingsRepository` нет чтения `system`

## 2. Применение темы и карта (D2, D3)

- [x] 2.1 В `ui/theme/Theme.kt`: убрать ветку `else -> isSystemInDarkTheme()` из `FogMapTheme` и параметр `systemDark` из `isDarkTheme`, удалить импорт `isSystemInDarkTheme` — проверка: `.\gradlew.bat :app:compileDebugKotlin` зелёный
- [x] 2.2 В `ui/screens/MapScreen.kt` и `ui/screens/HistoryScreens.kt`: перевести вызовы на `isDarkTheme(mode)`, удалить импорты `isSystemInDarkTheme`, обновить комментарии («system → за системой» больше не актуален) — проверка: `.\gradlew.bat :app:compileDebugKotlin` зелёный
- [x] 2.3 Grep-проверка репозитория: по `app/src/main` нет `isSystemInDarkTheme`, `ThemeModes.SYSTEM`, `prefers` — проверка: grep не находит совпадений (кроме `themes.xml`, который не трогаем)

## 3. UI настроек (D4)

- [x] 3.1 В `ui/screens/SettingsScreen.kt`: оставить в `options` только `DARK to "Тёмная"` и `LIGHT to "Светлая"`, удалить кнопку «Система» — проверка: `.\gradlew.bat :app:compileDebugKotlin` зелёный

## 4. Тесты

- [x] 4.1 Добавить unit-тест нормализации: `system`→`dark`, мусор→`dark`, `light`→`light`, `dark`→`dark`, и тест `isDarkTheme` для обоих режимов — проверка: `.\gradlew.bat :app:testDebugUnitTest` зелёный

## 5. Гейт: сборка и ручной проход

- [x] 5.1 Полная сборка и установка: `build-apk.bat debug install` завершается успехом (release-режим ABI-фильтрован под arm, эмулятор x86_64 — гейт по debug), версия пакета после установки совпала (`versionCode=60`) — проверка: вывод скрипта
- [x] 5.2 Ручной проход на устройстве/эмуляторе: в настройках ровно две кнопки (Тёмная/Светлая), «Системы» нет; переключение без перезапуска; карта day/night и мини-карта в деталях следуют выбранной теме; дефолт при первом запуске — тёмная — проверка: чек-лист пройден визуально
- [x] 5.3 Миграция: установить поверх билда, у которого в DataStore сохранено `system` (или вручную записать `theme_mode=system` через `adb`), открыть приложение — применяется тёмная тема, в настройках выбрана «Тёмная», в DataStore лежит `dark` — проверка: `adb shell` чтение DataStore/наблюдение UI
- [x] 5.4 Сценарии дельта-спеки `app-shell` («Тёмная тема по умолчанию», «Смена темы применяется сразу», «Миграция устаревшего системного режима», «Выбор темы в настройках») пройдены вручную — проверка: каждый сценарий отмечен выполненным в отчёте

## 6. Changelog

- [x] 6.1 В `CHANGELOG.md` секцию `## [Unreleased]` добавить пользовательский блок (**Изменено**: убран режим «Как в системе», только тёмная/светлая тема; миграция `system`→`dark`) по правилам корневого AGENTS.md — проверка: `python scripts/check-version.py` exit 0 (или PowerShell-аналог по `AGENTS.md` проекта FogMap)

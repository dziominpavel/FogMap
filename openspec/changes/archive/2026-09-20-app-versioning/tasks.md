## 1. База версии и Gradle

- [x] 1.1 Создать файл `version` с `1.0.0` и проверить, что файл читается как одна ASCII-строка без BOM
- [x] 1.2 Вычислять в `app/build.gradle.kts` `versionCode` (`git rev-list --count HEAD`, минимум 3), `versionName` (`<base>.<count>-g<sha>[-dirty-<TS>]`) и `BuildConfig`-поля `FULL_VERSION`/`GIT_SHA`/`GIT_COUNT`/`GIT_DIRTY`, проверить выводом `./gradlew :app:printFullVersion` и совпадением `versionCode` с `git rev-list --count HEAD`
- [x] 1.3 Добавить фолбэки без git (count 0, sha unknown, dirty с меткой времени) и проверить сборкой с недоступным git — сборка не падает, версия содержит `unknown-dirty`

## 2. Сборщик и установка

- [x] 2.1 Переименовать выход `build-apk.bat` на `FogMap-<fullVersion>-<release|debug>.apk` с сохранением `*-latest.apk` и версионированной копии в `dist/archive/`, проверить именами файлов после `build-apk.bat`
- [x] 2.2 Печатать полную версию и `versionCode` до `adb install` и сверять версию пакета после установки (`dumpsys package ru.fogmap` / `ru.fogmap.dev`), проверить текстом консоли при `build-apk.bat install` (код готов и ревью пройдено; прогон install на телефоне — за владельцем)
- [x] 2.3 Обеспечить `fetch-depth: 0` в `ci.yml` для корректного count и проверить release-сборкой CI с версией в имени артефакта (YAML валиден, 6 шагов; прогон CI — на пуше)

## 3. Экран «О программе»

- [x] 3.1 Показывать в `SettingsScreen.kt` полную версию и `BUILD_TIME` с явной `-dirty-` меткой грязных сборок, проверить открытием «О программе» на чистом и грязном билде (код готов, release-компиляция успешна, dirty-суффикс проверен в `printFullVersion`; открытие на телефоне — за владельцем)
- [ ] 3.2 Проверить установку нового билда поверх старого без сноса и сохранением данных, а также видимость версии в системном установщике пакета

## 4. Спеки и валидация

- [x] 4.1 Синхронизировать дельту `app-shell` в основную спеку при архивации и проверить `openspec validate --change "app-versioning" --strict` без ошибок

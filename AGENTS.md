# AGENTS.md — обязательные правила для каждой сессии

Проект: FogMap, Android-трекер с туманом (Kotlin + Compose, один app-модуль).
Карта — Яндекс MapKit Full 4.42.0. Сборка: `build-apk.bat` (релиз) / Gradle.
Секреты: `local.properties` целиком НЕ открывать, только маскированные проверки.

## Версии (обязательно)

База версии — файл `version` (`MAJOR.MINOR.PATCH`) = **последний выпущенный
релиз APK**. Полная версия и `versionCode` вычисляются из git в
`app/build.gradle.kts` — там ничего не трогать. Полные правила и примеры:
`docs/versioning.md`.

- **В обычной сессии файл `version` НЕ трогать.** Пользовательские изменения
  (код/поведение) копятся в секцию `## [Unreleased]` в `CHANGELOG.md`;
  чистые доки/спеки/тесты/рефактор — в `CHANGELOG` ничего не писать.
- **Бамп — только в момент релиза APK** (`build-apk.bat release`): классифицировать
  всё из `[Unreleased]` (MAJOR — ломающее, MINOR — новая фича, PATCH — только фиксы),
  записать новую базу в `version`, свернуть `[Unreleased]` в версионную секцию,
  `python scripts/check-version.py` → коммит с упоминанием версии.
- Строка `Версия:` в `docs/sessions.md` — обязательна всегда:
  `без бампа (в Unreleased; бамп на релизе)` либо `x.y.z → x.y.z (УРОВЕНЬ)`,
  если сессия и есть релиз.

## Инструменты и типичные грабли (проверено на этой машине)

**JDK/Gradle.** Системный `java` — 25.0.2, Kotlin DSL Gradle на нём падает
с `IllegalArgumentException: 25.0.2`. Перед ЛЮБОЙ gradle-командой выставить:

```powershell
$env:JAVA_HOME = "C:\Users\Dziom\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2"
```

Резервные кандидаты (тот же список, что в `build-apk.bat`):
`C:\Program Files\Java\jdk-17`, `C:\Program Files\Java\jdk-17.0.7`.
Флаг `-Dorg.gradle.java.home=...` НЕ работает (старый daemon уже на 25) —
менять только `JAVA_HOME`. `build-apk.bat` ставит JDK сам.

**Команды Gradle** (все с `$env:JAVA_HOME` выше):
- компиляция: `.\gradlew.bat :app:compileDebugKotlin`
- unit-тесты: `.\gradlew.bat :app:testDebugUnitTest`
- instrumented: `.\gradlew.bat :app:connectedDebugAndroidTest` — нужен эмулятор:
  `& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd fogmap-test -no-window -no-audio -gpu swiftshader_indirect`
  (запускать фоном), затем `adb wait-for-device` и ожидание
  `adb shell getprop sys.boot_completed == 1`; в конце — `adb emu kill`.
- сборка/релиз APK: `build-apk.bat [debug|install|release]` (JDK ставит сам).

**Кодировка.** `CHANGELOG.md`, `docs/*.md`, `AGENTS.md`, спеки — UTF-8:
читать инструментами `read`/`grep`, НЕ `Get-Content`/`type` (в выводе кракозябры,
из-за них уже ловили ложные несовпадения). `git status`/`git diff` кодируют сами.

**PowerShell.** Код возврата — `$LASTEXITCODE` (`$?` врёт для native-команд);
`2>&1` на stderr gradle/python приходит как `NativeCommandError` — это не падение
команды, смотреть на код возврата/итоговый `BUILD SUCCESSFUL`. Проверка версий:
`python scripts/check-version.py` → ожидается `exit=0`.

**Личные данные.** `dist/` (полевые логи `fog-*.jsonl`, `track-debug_*`, скриншоты,
APK) под `.gitignore` — в `git status` их быть не должно. Секреты:
`local.properties` целиком НЕ открывать, только маскированные проверки.

## Git (обязательно для всех моделей ИИ, без исключений)

- **Пуш запрещён.** `git push` (включая `--force` и любые флаги) — только по прямому приказу владельца в текущем диалоге. Прошлые разрешения не переносятся на новые сессии. Нарушение — критическая ошибка.
- **Коммит только по команде.** Изменения держать в рабочей копии; `git commit` — только после явной команды владельца («коммить», «сделай коммит»). До команды — показать `git status`/`diff --stat` и черновик сообщения.
- **Никакой самодеятельности с историей.** Без приказа запрещены: переписывание истории (`reset`, `rebase`, `amend` опубликованного), удаление веток, `gc --prune`, правки чужих незакоммиченных файлов.
- **Персональные данные не коммитить.** Полевые логи (`dist/fog-*.jsonl`, `dist/track-debug_*`), скриншоты (`dist/photo_*`), любые координаты/треки — только локально, они закрыты в `.gitignore`. Перед каждым коммитом проверять `git status`, что их нет в stage.

## Конец сессии (обязательно)

Запись в `docs/sessions.md`: что сделали, что решили, открытые вопросы,
строка `Версия:` (бамп или `без изменений`). Влияющее на скоуп/архитектуру —
в `docs/decisions.md` (новое сверху). Перед уходом с машины: показать `git status`
и черновик коммита, ждать команды; пуш — только по прямому приказу.

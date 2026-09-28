## 1. Телеметрия отказов старта

- [x] 1.1 Добавить в `TrackingService` логирование исхода `start()`: параметр `via` (`map`/`onboarding`/`boot`/`watchdog`, дефолт `code`), событие `TRACK/start_attempt` с `outcome=started|start_failed`, `err_class`/`err_msg` (обрезка до 200 символов) в `runCatching` — проверка: `grep -n "start_attempt" TrackingService.kt` находит обе ветки, сигнатура совпадает по всем 4 вызывающим точкам
- [x] 1.2 Обновить вызывающие точки под сигнатуру: `MapScreen.kt:158` (`via=map`), `OnboardingScreen.kt:123` (`via=onboarding`), `BootWorker.kt:49` (`via=boot`), `TrackingWatchdogWorker.kt:37` (`via=watchdog`) — проверка: `.\gradlew.bat :app:compileDebugKotlin` проходит без ошибок вызова
- [x] 1.3 Залогировать ранний выход `onCreate()` (`TrackingService.kt:137-146`) событием `TRACK/service_init_skip` с `reason=no_play_services|no_permission|foreground_exception` **до** `stopSelf()`; `service_start` оставить на прежнем месте — проверка: в `onCreate` есть `DevLog.w` перед каждым `stopSelf()`, `compileDebugKotlin` проходит
- [x] 1.4 Залогировать все исходы `TrackingWatchdogWorker.doWork()` (`:27-39`) событием `TRACK/watchdog` с `outcome=started|start_failed|skip_paused|skip_play_services|skip_no_permission` (исход `start_failed` берётся из ветки `start_failed`, которую `start()` уже записал в 1.1, — в воркере логируется только `started`/`skip_*`) — проверка: все 4 ранних возврата и финальный `started` покрыты событием, `compileDebugKotlin` проходит
- [x] 1.5 Залогировать исход `TrackingWatchdogWorker.schedule()` в `FogMapApp.kt:86` и `BootWorker.kt:42` событием `TRACK/watchdog_schedule` с `outcome=scheduled|schedule_failed` — проверка: оба `runCatching` содержат `else`-ветку с `DevLog.w`, `compileDebugKotlin` проходит

## 2. Экран диагностики: список логов

- [x] 2.1 В `DiagDiagnosticsScreen` добавить блок «Файлы лога»: список `devLogFile.allFiles()` с именем, размером (`File.length()`) и временем изменения (`File.lastModified()`) под строкой кнопок, поверх карточки хвоста — проверка: `compileDebugKotlin`, на экране видны строки всех файлов из `cacheDir/logs/`
- [ ] 2.2 Тап по строке шарит выбранный файл через существующий FileProvider (`${packageName}.devlog`); кнопка «Поделиться» продолжает отдавать `currentFile()` — проверка: ручной тест на устройстве — выбор файла за прошлые сутки открывает chooser с нужным файлом (сверить имя в шаринге)
- [ ] 2.3 Обработать пустой список (нет файлов/ошибка чтения) текстом-заглушкой, не ломая существующие кнопки и хвост — проверка: после «Очистить логи» экран показывает заглушку, кнопки работают

## 3. Тесты и проверка

- [x] 3.1 Расширить `app/src/test/java/ru/fogmap/DevLogPrivacyTest.kt`: события `start_attempt`/`service_init_skip`/`watchdog`/`watchdog_schedule` формируются без полей lat/lon и с `err_msg` не длиннее 200 символов — проверка: `.\gradlew.bat :app:testDebugUnitTest` зелёный
- [x] 3.2 Добавить тест формата payload событий отказа (точка в числах, плоский JSON без вложенностей глубже 1 уровня) рядом с существующими `DevLogTest` — проверка: `.\gradlew.bat :app:testDebugUnitTest` зелёный
- [ ] 3.3 Полная сборка и установка `build-apk.bat install` (JDK выставляет сам) — проверка: `BUILD SUCCESSFUL`, приложение установлено
- [ ] 3.4 Ручной сценарий на устройстве: открыть «Диагностику» → выбрать лог за прошлые сутки → отшарить; затем временно отозвать разрешение/включить паузу и убедиться, что в хвосте/файле появляется `skip_*`/`service_init_skip` — проверка: события присутствуют в выбранном файле, координат в них нет
- [ ] 3.5 Срочно: до ~00:00 отшарить `fog-2026-09-27.jsonl` в `dist/` и зафиксировать таймлайн `service_start`/`service_stop`/`watchdog` за 27.09 — проверка: файл лежит в `dist/`, по нему установлено, был ли процесс жив в окне 12:42–22:30

## 4. Документация

- [x] 4.1 Запись в `docs/sessions.md` (что сделано, открытые вопрос, строка `Версия: без бампа (в Unreleased; бамп на релизе)`) и пункт в `CHANGELOG.md` в секцию `## [Unreleased]` — проверка: оба файла обновлены, `python scripts/check-version.py` → `exit=0` (на этой машине python не установлен — проверка воспроизведена PowerShell'ом по тем же инвариантам: version=`1.5.2` = первая версионная секция CHANGELOG, exit=0)

## Why

Поездка 26–28.09 дала дыру в треке `2026-09-27 12:18 → 22:30` (36 764 с, 175 км) и три «мёртвых» окна уже 28.09 (`11:00–14:00`: ноль строк DevLog, ноль raw-fix, ноль `service_stop`), причём 15-минутный `TrackingWatchdogWorker`, обязанный поднять сервис, не поднял его ни разу за 3 ч 12 мин. Сейчас отличить «телефон был выключен» от «Huawei убил приложение и фоновый старт FGS отклонён» невозможно: **ни один отказ попытки старта не попадает ни в DevLog, ни в счётчики** — `runCatching` в `TrackingService.start` глотает исключение, а `onCreate` делает `stopSelf()` *до* строки `service_start`. Вдобавок экран Диагностики шарит только «самый новый» JSONL (`DiagScreens.kt:58-61`), поэтому ключевой файл `fog-2026-09-27.jsonl`, который единственный даёт таймлайн жизни процесса за день дыры, недоступен, а TTL 24 ч удалит его при первом старте процесса после ~00:00 сегодня.

## What Changes

- Экран «Диагностика» показывает **список всех файлов** лога в `cache/logs/` (имя, размер, время изменения) и шарит **выбранный** файл; кнопка «Поделиться» без выбора по-прежнему отдаёт текущий (сегодняшний) файл.
- Каждая **попытка старта** сервиса фиксируется в DevLog с исходом и причиной отказа:
  - `TrackingService.start()` — успех / класс+сообщение исключения из `startForegroundService`;
  - `TrackingService.onCreate()` — причина раннего выхода (`no_play_services`, `no_permission`, `foreground_exception`) **до** `stopSelf()`;
  - `TrackingWatchdogWorker.doWork()` — решение воркера: `skip_paused`, `skip_play_services`, `skip_no_permission`, `started`;
  - `FogMapApp` — исход `TrackingWatchdogWorker.schedule()`.
- Отказы старта пишутся тегом `TRACK` в существующий JSONL-формат DevLog (без координат) и дублируются в logcat; поведение старта **не меняется** — ни одного фикса надёжности в этом change.
- **Non-goals**: починка самого watchdog/фонового старта FGS, визуальная резка линии по временному разрыву, баг `loc.time` → ложные `jump` — это отдельные changes.

## Capabilities

### New Capabilities

*(нет)*

### Modified Capabilities

- `dev-logging`: требование о событиях тега `TRACK` дополняется фиксацией **исхода каждой попытки старта/перезапуска** (сейчас «старты/остановки сервиса, watchdog-рестарты» декларированы, но отказы не пишутся вовсе); требование об экране «Диагностика» меняется с «Share файла JSONL» на «список файлов лога + Share выбранного».
- `tracking-reliability`: требование о периодическом перезапуске дополняется наблюдаемостью: каждый цикл watchdog и каждая попытка старта SHALL оставлять событие с исходом, чтобы дыра в треке была объяснена отказом старта, а не пустотой.

## Impact

- `app/src/main/java/ru/fogmap/ui/screens/DiagScreens.kt` — список файлов + share выбранного (FileProvider authority `${applicationId}.devlog`, `devlog_paths.xml` уже отдаёт весь `cache/logs/` — дополнительной конфигурации не нужно).
- `app/src/main/java/ru/fogmap/diag/DevLogFile.kt` — возможно, метаданные списка (имя/размер/mtime); API `allFiles()` уже есть.
- `app/src/main/java/ru/fogmap/tracking/TrackingService.kt` — `start()` (:1121), `onCreate()` (:132), `onStartCommand()` (:1032).
- `app/src/main/java/ru/fogmap/tracking/TrackingWatchdogWorker.kt` — `doWork()` (:27), `schedule()` (:44).
- `app/src/main/java/ru/fogmap/FogMapApp.kt:86` — исход `runCatching { TrackingWatchdogWorker.schedule(this) }`.
- Тесты: `app/src/test/java/ru/fogmap/DevLogTest.kt`, `DevLogPrivacyTest.kt` (приватность: в событиях отказа НЕ должно быть координат).
- Риск: рост объёма `fog-*.jsonl` — события разовые (на старт), допустимо; TTL 24 ч и ротация 2 МБ остаются как есть.

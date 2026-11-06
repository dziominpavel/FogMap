# Tasks

## 1. Wake-lock движения

- [x] 1.1 `TrackingWake`: правило `neededFor(profile, paused)` и идемпотентный `Holder` (PARTIAL_WAKE_LOCK, ref-count off).
- [x] 1.2 Manifest: `android.permission.WAKE_LOCK`.
- [x] 1.3 `TrackingService`: `applyWake()` на старте сервиса, смене эко-профиля, паузе; снятие в `onDestroy`.
- [x] 1.4 DevLog `TRACK/wakelock` с `held` и `profile` только на смене состояния.

## 2. Агрегат `day_branches`

- [x] 2.1 `BranchLogGate`: интервал `MIN_LOG_INTERVAL_MS` = 5 минут, `shouldLog`, `merge`.
- [x] 2.2 `TrackingService.logBranches`: накопитель между событиями, слияние без потерь, событие с итогом.
- [x] 2.3 `counters.json` не зависит от троттлинга (`recordBranches` на каждом flush, как раньше).

## 3. Отложенная пачка

- [x] 3.1 `StaleReplay`: правило `recvFor(now, fixTime, lastRecv)` и `replayed`.
- [x] 3.2 `TrackingService.onRawLocation`: `recv` из метки фикса для отложенных, счётчик `stale_replay`.
- [x] 3.3 `EcoLogPayload.flushPayload`: необязательное поле `stale_replay` (пишется только когда > 0).

## 4. Проверка

- [x] 4.1 `TrackingWakeTest`, `StaleReplayTest`, дополнения `BranchLogGateTest`.
- [x] 4.2 `:app:compileDebugKotlin` — без ошибок (JDK 21, Gradle 8.10.2).
- [x] 4.3 `:app:testDebugUnitTest` — зелёные: 300 тестов, 0 падений, 1 skipped.
- [x] 4.4 `openspec validate --all` — change ✓.
- [x] 4.5 Delta-спеки синхронизированы в основные `openspec/specs/{tracking-reliability,battery-eco,dev-logging,gps-trust}`; change архивирован.
- [x] 4.6 `:app:assembleRelease` — `dist/FogMap-field-hardening.apk` (143 МБ), подпись FogMap, ставится поверх 1.8.0.

## 5. Документация

- [x] 5.1 `CHANGELOG.md` → `[Unreleased]`: пользовательский блок и детали.
- [x] 5.2 `docs/sessions.md` — запись сессии, `Версия:` без бампа; `docs/decisions.md` — решение по wake-lock и метке приёма.

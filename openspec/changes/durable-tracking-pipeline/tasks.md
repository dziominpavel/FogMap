# Tasks

## 1. Схема данных и миграция

- [x] 1.1 `FixInboxEntity` + `FixInboxDao` (`insertIgnore`, `pendingBatch(limit)`, `markDone(ids)`, `countByState`) в `data/db`; проверка — `:app:compileDebugKotlin` и instrumented `InboxPipelineTest.inboxDeduplicatesByKeyAndKeepsOrder`: повторный `insertIgnore` того же ключа не создаёт строку, `pendingBatch` отдаёт по возрастанию `fixTime`.
- [x] 1.2 `DayEntity` + `DayDao` (`byDay`, `insertIgnore`, `rebind`, `all`) с `day` как первичным ключом; проверка — `InboxPipelineTest.openDayIsIdempotentAndWritesRegistry`: повторное открытие дня возвращает тот же `trackId`, в реестре одна строка.
- [x] 1.3 Миграция Room v7→v8 (в проекте уже v7, не v1): создать `fix_inbox` и `days`, бэкфилл `days` из `tracks` по локальной дате `startedAt` (новейшая строка на дату); проверка — SQL бэкфилла вынесен в `AppDatabase.DAYS_BACKFILL_SQL` и проверен instrumented `InboxPipelineTest.migrationBackfillFillsRegistryFromTracks`.
- [x] 1.4 `TrackRepository.openDay(day)` — find-or-create строки дня и записи в `days` одной транзакцией, с перевязкой, если строка дня исчезла; проверка — `DatabaseTest.openDayChunk*` и `appendPointsRecoversStaleTrackIdAfterRebuild` зелёные.

## 2. Приём фиксов вне процесса

- [x] 2.1 `LocationFixReceiver` (`tracking/`), манифест `receiver android:exported="false"`; ресивер парсит extras в строку журнала и пишет `INSERT OR IGNORE`; проверка — unit `FixEnvelopeTest` + полевой прогон на P60 Pro 08–09.11: 3416 доставленных фиксов в `fix_inbox`, ресивер виден в `dumpsys package`.
- [x] 2.2 Подписка через `PendingIntent.getBroadcast` в `TrackingService.subscribeFused`/`resubscribe`, удаление колбэка трекинга; проверка — на устройстве фиксы приходят непрерывно (`inbox_drain` 1920 раз за сутки), второй подписки нет.
- [ ] 2.3 Триггер обработки из ресивера: `OneTimeWorkRequest` с уникальным именем `inbox-process`; проверка — instrumented: после доставки фикса work запускается и `fix_inbox` очищается.

## 3. Консьюмер журнала

- [x] 3.1 `restoreTrust`: хвост точек дня за окно доверия + `PrevState` из последней точки при старте сервиса; проверка — на устройстве 4 `proc_start` и ровно 4 события `TRACK/trust_restored`.
- [x] 3.5 (найдено полевым прогоном) Строки журнала, не давшие точек (STAND в STANDBY, отбросы без точек), не помечались обработанными: очередь росла до 1236 строк и разбиралась только в движении. Исправлено — пометка в starved-ветке и после записи без точек; проверка — на устройстве очередь упала с 21 до 1 сразу после установки.
- [x] 3.2 `drainInbox()`: выборка `state=0` по времени пачками по 200, разбор в существующий конвейер вердикта; проверка — instrumented `pendingBatchPaginatesWithoutGapsOrOverlaps`: 450 строк разбираются тремя пачками без пропусков и пересечений.
- [x] 3.3 Отметка «обработано» в той же транзакции, что и точки (`FogRepository.appendPoints(..., inboxIds)`); проверка — instrumented `appendPointsMarksInboxRowsInSameTransaction`: после записи точек `pendingCount()==0`.
- [ ] 3.4 Устойчивость: исключение в середине пачки откатывает её целиком, строки остаются `state=0`; проверка — нужен тест с падающим коммитом (не написан).

## 4. Реестр дней в пути записи

- [x] 4.1 Замена кэша `trackId` на `openDay(day)` в обработке журнала и `flushLocked`; проверка — instrumented: рестарт сервиса в тот же день не создаёт вторую строку, запись продолжается в ту же.
- [x] 4.2 Пачка через полночь кладёт точки в строки своих дат; проверка — `BatchDaysTest` (5 сценариев) и `DayAuditTest` зелёные, `DayAudit` переносит чужие точки.
- [x] 4.3 `latestInRange` больше не решает, куда писать: реестр первичен, `latestInRange` остался только страховкой миграции внутри `openDay`; проверка — `openDay` покрыт instrumented-тестом, поведение протухшего id не регрессировало.

## 5. Без потерь в flush

- [x] 5.1 Очистка буферов и счётчиков после успешного коммита, возврат снимка при исключении (`requeueFlush`); проверка — `:app:testDebugUnitTest` и `TrackStatsTest` без регрессий.
- [x] 5.2 `CancellationException` пробрасывается после возврата пачки в буферы (`catch` вместо `runCatching` вокруг записи); проверка — `compileDebugKotlin` + отсутствие регрессий в тестах.
- [ ] 5.3 Счётчики не рассинхронизируются: сценарий «взяли snapshot, коммит упал, повторили» даёт ровно один инкремент; проверка — нужен тест с падающим DAO (не написан).

## 6. Аудит дня

- [x] 6.1 `DayAudit.recompute(points)` — чистая функция (pointsCount, time_s по `TrackChunking`, distance по MOVING+trust); проверка — `DayAuditTest` (4 сценария).
- [x] 6.2 Аудит при закрытии дня: сверка строки и дневных счётчиков, событие `TRACK/audit`; проверка — `DayAuditRepository.audit` вызывается в `flushLocked` на смене суток, `compileDebugKotlin` зелёный.
- [x] 6.3 Ремонт: перезапись значений строки и дневных счётчиков абсолютными значениями; проверка — instrumented `auditMovesForeignPointsAndFixesCount`.
- [x] 6.4 Перенос чужих точек в строку своей даты с пересчётом обеих дат; проверка — тот же instrumented-тест: 2 точки уехали в 07.11, в 08.11 осталась 1.

## 7. Наблюдаемость конвейера

- [x] 7.1 Событие `TRACK/inbox_drain` (сколько разобрано, сколько осталось) на каждом проходе с непустым журналом; проверка — `compileDebugKotlin`, событие пишется из `drainInbox`.
- [x] 7.2 Карточка дня в истории показывает «Ожидает обработки: N фиксов», если журнал не пуст; проверка — `compileDebugKotlin`, виджет берёт `pendingCount()`.

## 8. Приёмка

- [x] 8.1 `:app:testDebugUnitTest` — зелёные; проверка — вывод задачи и число тестов.
- [ ] 8.2 Интеграционный сценарий на эмуляторе: `am kill` посреди обработки, `cmd deviceidle force-idle`, двойная доставка, полночь с очередью — ни одна точка не потеряна, дублей нет; проверка — сводка сценария и `counters` против ручного пересчёта.
- [x] 8.3 Полевой прогон на P60 Pro 08–09.11 (20 ч, 3 416 фиксов): база и лог сняты через `run-as`, в обеих строках дней 0 точек чужой даты, реестр переключил 08.11→09.11 сам, 8 `freeze_gap` на 2,3 ч, 4 самоподъёма процесса, аудит на полуночи поправил счётчики 08.11. Найдена и исправлена течь журнала (3.5).

## Workflow follow-up

- `CHANGELOG.md` → `[Unreleased]`: пользовательский блок и инженерные детали.
- `docs/sessions.md` — запись сессии, `Версия:` без бампа до отдельного решения о релизе.
- `docs/decisions.md` — решение о журнале фиксов и реестре дней (новое сверху).
- `openspec archive durable-tracking-pipeline` после приёмки 8.2/8.3: синхронизация дельт в `openspec/specs`.

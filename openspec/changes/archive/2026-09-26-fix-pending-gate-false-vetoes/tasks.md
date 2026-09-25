# Tasks — fix-pending-gate-false-vetoes

## 1. Логика ворот C (PendingGate)

- [x] 1.1 Временная монотонность: `roundTrip` требует `c.time > anch.time`, якорь продвигается только вперед (`anch = c` только при `c.time > anch.time`) — проверить компиляцией `.\gradlew.bat :app:compileDebugKotlin` с `$env:JAVA_HOME = "C:\Users\Dziom\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2"`
- [x] 1.2 Дренаж LAG по wall-clock: в `actionable` входят также последние LAG-точки старше `MAX_PENDING_AGE_S` относительно `newest.time` — компиляция успешна
- [x] 1.3 Unit-тесты в `PendingGateTest`: (a) кандидат старше якоря с «круговым вылетом» геометрии подтверждается (инверсия не ветуется); (b) кандидат новее якоря с честным круговым вылетом ветуется `veto_return` (регрессия не сломана — покрыта существующими `круговой вылет ветируется`/`реплей аэропорта`); (c) якорь не откатывается назад по времени после подтвержденного старого кандидата; (d) LAG-точка старше 600 с входит в разбор и получает вердикт; (e) старые MOVING в LAG подтверждаются, а не `veto_stale` + контроль «свежий LAG-хвост ждет» — `.\gradlew.bat :app:testDebugUnitTest --tests ru.fogmap.PendingGateTest` зеленый

## 2. Окно хвоста и линкинг (FogRepository / Entities)

- [x] 2.1 Убрать `LIMIT` из запроса `unopenedTail` (сохранить `ORDER BY time DESC`), вызов `confirmPending` без `PendingGate.TAIL_LIMIT`; `TAIL_LIMIT` удалить как константу и убрать все его упоминания (включая комментарии TrackDebugImport/DatabaseTest) — компиляция (включая androidTest) успешна, `testDebugUnitTest` зеленый
- [x] 2.2 В `confirmPending` линк `anchorRaw`→первая подтвержденная точка только при `первая.time > anchorRaw.time` — вынесен в чистую функцию `FogRepository.linkAnchorFor` (комpanion), вызов через нее; компиляция успешна
- [x] 2.3 Unit-тесты `LinkAnchorTest` (новые): обычный порядок — линк рисуется (середина сегмента открыта); инверсия — линка нет, середина прямой закрыта, кисти точек открыты; пустые подтвержденные/нет якоря → null — `testDebugUnitTest` зеленый

## 3. Валидация реплеем на данных 25.09

- [x] 3.1 Расширить реплей (`replay_fixed.py`, temp): прогон исправленной логики (монотонность + без лимита хвоста + дренаж) на `tracks/2026-09-25.jsonl` + flush-события — **результат: 0 sim `veto_return`, 51/51 целевая точка открыта, 0 висящих, diffs vs real = ровно 51 восстановленная**
- [x] 3.2 Контроль честных вето: на машине нет выгрузок других дней (`dist` = только ZIP 25.09, где `veto_stale`=0 и все 49 `veto_return` доказанно ложные) — контроль сделан синтетическими днями на том же конвейере (`replay_control.py`, temp): честный круговой вылет 4 км → `veto_return` сохранен; статика 20 мин → `veto_stale` сохранен; ближний отход 100 м → подтверждается; exit=0. Дополнительно — юнит-тесты 1.3 на реальных формах (аэропорт 18.09)

## 4. Восстановление 25.09 (E)

- [x] 4.1 `FogRepository.repairVetoed()`: находит треки с `fogOpened=2 AND rejectReason='veto_return'` и застрявшими `fogOpened=0` старше `MAX_PENDING_AGE_S`, переобрабатывает чистой функцией `FogRepository.planRepair` (комpanion; **адаптация к дизайну**: не один `adjudicate` на трек с `anchor=lastOpenedPoint`/`newest=новейшая точка`, а контекст на каждого кандидата — якорь = самая новая открытая точка строго старше кандидата, `newest` = первая точка после кандидата; иначе честный вылет из-за инверсии D1 подтверждался бы, а геометрия «дом→маршрут→дом» против новейшей точки повторяла бы ложный вылет; предикаты roundTrip/stale — те же, что в `PendingGate`), подтвержденные открывает (`openBaseCells` с линком по времени → `filterCovered` → `insertChunked` → `promoteCascade` → `markReopened` (вето снимается вместе с причиной) → инкремент `area_cells_*` и региональных счетчиков по дню трека), повторно ветованные закрывает с прежней причиной (честные = no-op) — компиляция успешна; реплей плана на реальных данных (`replay_repair.py`, temp): **51/51 кандидат подтвержден, 0 повторных вето, повторный проход пуст, exit=0**
- [x] 4.2 Флаг `pending_gate_repair_v1` в `counters` пишется после успешного прохода (без пораженных строк тоже закрывается флагом — «не чаще раза»); запуск один раз при старте приложения (`FogMapApp.onCreate` → `appScope.launch` после инициализации БД и тумблера DevLog, до открытия карты; ошибка не роняет старт и не пишет флаг — повтор в следующий запуск); DevLog-эвент `pending_gate_repaired` с `tracks/points/cells` (+ `pending_gate_repair_failed` по ошибке, по аналогии с `day_chunk_recovered`) — `testDebugUnitTest` зеленый
- [x] 4.3 Unit-тест ремонта (`RepairPlanTest`, чистая функция `planRepair`): ложные `veto_return` переоткрываются (день 25.09 в миниатюре), честный круговой вылет остается `veto_return`, честное `veto_stale`/вердиктно закрытые строки не трогаются, застрявший хвост дренируется (MOVING подтверждается, STAND → `veto_stale`), повторный проход идемпотентен — `testDebugUnitTest` зеленый (6/6)

## 5. Финальная проверка

- [x] 5.1 Полный прогон: `.\gradlew.bat :app:testDebugUnitTest` + `.\gradlew.bat :app:compileDebugKotlin` зеленые (JAVA_HOME по AGENTS.md) — BUILD SUCCESSFUL, exit=0 (28 сьютов, 0 failures; RepairPlanTest 6/6, PendingGateTest 12/12, LinkAnchorTest 3/3)
- [x] 5.2 Итоговый реплей 25.09 с финальным кодом (0 `veto_return`, 51/51) — приложить вывод в сессию: живой конвейер `replay_fixed.py` → sim veto_return=0, TARGETS OPENED 51/51, pending=0, diffs=51, exit=0; план ремонта `replay_repair.py` → confirm=51 veto=0, повторный проход пуст, честные вето сохранены, exit=0
- [x] 5.3 `openspec validate fix-pending-gate-false-vetoes` без ошибок — "Change ... is valid", exit=0
- [x] 5.4 Запись в `docs/sessions.md` (что сделано/решено/открытые вопросы, `Версия: без бампа (в Unreleased; бамп на релизе)`), при влиянии на архитектуру — `docs/decisions.md` (решение D1–D5 внесено 25.09); показать `git status`/`diff --stat` и черновик коммита, ждать команды владельца

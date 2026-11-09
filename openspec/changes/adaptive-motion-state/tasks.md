# Tasks

## 1. Машина состояний

- [ ] 1.1 `tracking/MotionState`: чистый объект `STILL/MOVING/UNKNOWN` с переходами по фиксу, активности и тишине; проверка — JVM-тест на таблицу переходов.
- [ ] 1.2 `planFor(state)`: эко-профиль, интервал и wake-lock из состояния; проверка — JVM-тест: STILL→STANDBY без lock, MOVING→ACTIVE с lock, UNKNOWN→последний профиль.
- [ ] 1.3 Подключить `MotionState` в `TrackingService` вместо прямых вызовов `enterProfile`; проверка — `:app:compileDebugKotlin`, `EcoGovernorTest`/`TrackingWakeTest` без регрессий.

## 2. Пробуждение по активности

- [ ] 2.1 `ActivityTransitionReceiver` + подписка через PendingIntent; проверка — instrumented: тестовый transition поднимает профиль в BURST.
- [ ] 2.2 Разрешение и деградация: `ACTIVITY_RECOGNITION`, `TRACK/wake_source_unavailable` без GMS/разрешения; проверка — эмулятор без GMS: событие есть, запись не падает.
- [ ] 2.3 Перезапуск подписки после ребута/обновления; проверка — `adb install -r` и ребут: подписка восстановлена (событие в логе).

## 3. Быстрый старт и замер

- [ ] 3.1 Fast-path: первый фикс после паузы > 2× интервала при `acc ≤ 25` и `speed ≥ 1` → MOVING; проверка — JVM-тест + полевой `idle_ms`.
- [ ] 3.2 `idle_ms` в `TRACK/eco_state`; проверка — `EcoLogPayloadTest`.
- [ ] 3.3 `standbyInterval(arAvailable)`: 60 с без AR, до 180 с с AR; проверка — JVM-тест.

## 4. Приёмка

- [ ] 4.1 `:app:testDebugUnitTest` — зелёные.
- [ ] 4.2 Полевые сутки с AR: медиана `idle_ms` ≤ 60 с, хвост ≤ 3 мин, прыжков > 500 м в движении не больше двух за день.
- [ ] 4.3 `docs/decisions.md`, `CHANGELOG.md`; `openspec validate --all` — 0 падений.

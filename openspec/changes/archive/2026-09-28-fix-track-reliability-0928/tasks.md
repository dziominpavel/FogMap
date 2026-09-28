## 1. Вердикт по времени приёма (баг C)

- [x] 1.1 Добавить `recv: Long = time` в `TrustEngine.HistPoint`, во всех вычислениях `dt` внутри `TrustEngine` (`dtS`, окно истории/якорь, тишина, медиана, максимум implied, прuning) перейти на `recv`, `time` не менять
- [x] 1.2 Заполнять `recv = System.currentTimeMillis()` в `TrackingService` (создание `HistPoint`), `RawTrace.build(recv)` как зеркало вердикта; `TrackDebugImport` и тесты оставлены на дефолте `recv = time`
- [x] 1.3 Тест `повторяющаяся метка фикса не дает ложный jump`: контроль старого поведения (recv = time → SUSPECT/jump) и новое (приём через 8 с → MOVING без jump)
- [x] 1.4 Реплей сырых логов 26.09 **невоспроизводим автоматически**: в сохранённых строках есть только время фикса, метки приёма в данных нет — сравнить «до/после» на них нельзя. Количество ложных кандидатов измерено ранее на тех же данных (27 строк с `dt ≤ 1 с` при смещениях в сотни метров из 76 `jump`), проверка исправления — установкой APK и разбором DevLog за 29.09 (задачи 4.4)

## 2. Резка линии по разрыву доставки (баг B)

- [x] 2.1 `lineSegments(times, gapMs = LINE_BREAK_GAP_MS = 10 мин)` в `HistoryScreens.kt`
- [x] 2.2 Отрисовка полилиний дня по сегментам с `trustRuns` внутри каждого; дистанция/туман/точечный состав не тронуты
- [x] 2.3 Тесты: дыра 12:18 → 22:30 даёт `[0..2, 3..4]`, пауза ровно 10 мин не режет, 10 мин 1 с — режет, пусто — пусто

## 3. Второй канал фонового перезапуска (баг A)

- [x] 3.1 Общая проверка вынесена в `TrackingWatchdog.checkAndStart(context, source)`, `doWork()` вызывает её с `source="work"`
- [x] 3.2 `WatchdogAlarmReceiver` + `setAndAllowWhileIdle` (фаза +7 мин, период 15 мин), receiver в manifest, `TRACK/watchdog` с полем `source`, `watchdog_schedule` с полем `channel`
- [x] 3.3 `TrackingWatchdog.scheduleAll(context)` планирует оба канала; вызовы в `FogMapApp` и `BootWorker` переведены на неё
- [x] 3.4 На «Диагностике» — карточка «Фоновый перезапуск»: `isIgnoringBatteryOptimizations` (перечитывается на `ON_RESUME`) и кнопка `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`

## 4. Проверка и сдача

- [x] 4.1 `:app:compileDebugKotlin` и `:app:testDebugUnitTest` — зелёные (311 тестов, 0 падений; +1 TrustEngineTest, +3 HistoryUiTest)
- [x] 4.2 `openspec validate fix-track-reliability-0928 --strict` и `openspec validate --specs` — зелёные
- [x] 4.3 `build-apk.bat` (release) → `dist/FogMap-release-latest.apk` 1.5.2.39-gf6ed2ed-dirty (64 549 800 байт)
- [ ] 4.4 Device-задачи предыдущего change (2.2, 2.3, 3.3-install, 3.4, 3.5) + проверка обоих каналов watchdog и разрыва линии — за телефоном по кабелю вечером 29.09

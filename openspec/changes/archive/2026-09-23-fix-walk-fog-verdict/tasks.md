## 1. Единый порог и speed-гейт TrustEngine

- [x] 1.1 Добавить константу `SPEED_MIN_MPS = 1.0` в общее место (EcoGovernor/TrustEngine), перевести speed-гейт STAND и eco speed-latch на неё (убрать порог 5.0), проверить unit-тестом: `speed≥1.0 И acc≤25` → STAND запрещён → MOVING; `acc>25` гейт не срабатывает; latch 1.0 в `isSpeedLatch`
- [x] 1.2 Прокинуть `speed` Fused в `HistPoint`/окно истории TrustEngine (заполнение в том же колбэке, nullable), проверить unit-тестом: HistPoint несёт speed и гейт читает её без второго источника
- [x] 1.3 Оставить `openFog` по series trust без авт-openFog при speed-гейте, проверить unit-тестом: одна точка speed≥1.0 MOVING, но openFog=false до набора successors

## 2. Классификация MotionKind

- [x] 2.1 Реализовать `classify(speed, acc)` с полосами STILL/WALK/BIKE/VEHICLE и гистерезисом ±0.3 (чистая функция, без AR API), проверить unit-тестом границы 1.0/2.5/8.0 и отсутствие мигания на шуме ±0.2 от границы
- [x] 2.2 Ограничить выдачу STAND только `kind==STILL` в TrustEngine, проверить unit-тестом: kind WALK/BIKE/VEHICLE → STAND невозможен
- [x] 2.3 Добавить `kind` в raw_fixes/лог дня (без координат), проверить чтением JSONL: поле присутствует и парсится

## 3. Per-type accuracy для тумана

- [x] 3.1 Ввести пороги STILL/WALK 25, BIKE 40, VEHICLE 100 в LocationFilter/решение openFog (замена единого 25), проверить unit-тестом: acc 35 kind BIKE проходит, acc 45 BIKE отбрасывается, acc 80 VEHICLE проходит, acc 60 WALK отбрасывается
- [x] 3.2 Точки хуже порога kind не писать в `track_points` и не открывать ячейки, но сохранять в raw + историю trust с низким весом, проверить unit-тестом: reject для тумана, участие в истории
- [x] 3.3 Сохранить отброс `speed>150 км/ч` и mock-пометки, проверить unit-тестом регресс существующих кейсов LocationFilter

## 4. Запись STAND в трек (ACTIVE/BURST)

- [x] 4.1 Разрешить запись STAND в `track_points` при профилях ACTIVE и BURST с `openFog=false` (в STANDBY по-прежнему дроп), проверить unit-тестом: ACTIVE+STAND → строка есть, openFog=false; STANDBY+STAND → 0 строк; дистанция не растёт
- [x] 4.2 Подтвердить `batchDistance`/`timeS` считают только MOVING (STAND-строки не накручивают км/мин), проверить unit-тестом: батч «поездка 12 км + STAND-паузы» → 12 км, время только движения
- [x] 4.3 Проверить регресс спеки «6 часов дома = 0 точек» при STANDBY, unit-тестом: долгий STAND в STANDBY не пишется

## 5. Эко: окно BURST 180с и guard MOVING

- [x] 5.1 Изменить `BURST_WINDOW_MS` 75_000 → 180_000 (согласовано с `ACTIVE_SPEED_HOLD_MS`), проверить unit-тестом: wifi/motion-BURST держится до 180с, TIMEOUT возвращает в STANDBY после
- [x] 5.2 Перевести `burstTarget`/`activeMayStandby` на latch 1.0 и добавить guard `state==MOVING → activeMayStandby=false`, проверить unit-тестом: MOVING не уходит в STANDBY даже при набранной STAND-серии; пробка 2 мин не роняет ACTIVE
- [x] 5.3 Обновить `EcoLogPayload`/`TRACK/eco_state`: источник wifi/motion с окном 180с, без координат, проверить чтением JSONL: событие есть, поля прежние

## 6. Счётчики веток TrustEngine

- [x] 6.1 Добавить инкременты `static/wake/silence/jump/teleport/turn/speed_gate/kind_STILL/kind_WALK/kind_BIKE/kind_VEHICLE` при срабатывании ветки, проверить unit-тестом: каждый кейс вердикта двигает свой счётчик
- [x] 6.2 Писать счётчики в `counters.json` экспорта и в событие дня DevLog (видимый 0, не отсутствие ключа), проверить экспортом ZIP: ключи присутствуют
- [x] 6.3 Показать счётчики в диагностике дня UI (наравне с rejected), проверить открытием карточки после синтетического дня

## 7. Реплей-тест 2026-09-23 (приёмка до поля)

- [x] 7.1 Написать unit/скрипт реплея `days/2026-09-23.jsonl` через новый TrustEngine+LocationFilter+пороги, проверить PASS-критерии: 0× STAND при speed≥1.0; wifi-BURST 180с в логе; распределение Kind правдоподобно (не всё STILL на улице); ячейки ≥2000; дистанция ≫ 338м
  - Примечание: критерий «ячейки ≥2000» из design недостижим на данных 23.09 (openBase=168, openFog=19, soft-path исключает 139 точек, kind=STILL → кисть 15 м; бейзлайн fog.jsonl 65 клеток z=21 / area_cells=213). Порог в тесте снижен до openBase≥150 (168 ≫ 65) по решению владельца; ≥2000 требует bbox-заливки ~2270 клеток — геометрия thin-corridor не даёт. См. sessions.md.
- [x] 7.2 Прогнать существующие suites (`TrustEngineTest`, `EcoGovernorTest`, `LocationFilterTest`, `PendingGateTest`, `FogGridTest`, `RawTraceTest`) `./gradlew :app:testDebugUnitTest` — все зелёные
- [x] 7.3 Прогнать `python scripts/check-version.py` (пока NO_BUMP/без бампа — бамп перед коммитом по команде) и `openspec validate --change fix-walk-fog-verdict` — без ошибок
  - `openspec validate fix-walk-fog-verdict` → valid. `python` на машине — Store-заглушка (пустая версия, `check-version.py` не запускается); сверка вручную: `version`=1.5.0 = верх `CHANGELOG` `[1.5.0]`, дерево грязное с `app/` → подсказка PATCH (бамп 1.5.1 — по команде перед коммитом).

## 8. Сборка APK и полевой чеклист (вечер)

- [x] 8.1 Собрать релиз `build-apk.bat`, положить APK в `dist`, проверить установку на устройство и старт трекинга без краша
  - APK собран: `dist\FogMap-release-latest.apk` (1.5.0.26-g0dd24cb-dirty-20260923-1354, ~61 МБ). Установка на устройство — владельцем (отдельно от этой сессии).
- [x] 8.2 Составить чеклист вечерней прогулки (что снимать: fog-*.jsonl, track-debug zip, counters, eco_state source=wifi, openFog-точки, Kind на улице) и записать в `docs/sessions.md` строку о скоупе сессии
  - Чеклист и итоги — в `docs/sessions.md` (сессия fix-walk-fog-verdict).
- [ ] 8.3 Вечером после прогулки: разобрать выгрузку, сверить с критериями 7.1, зафиксировать результат в `docs/sessions.md` (отдельная сессия — поле не в этом change до APK)

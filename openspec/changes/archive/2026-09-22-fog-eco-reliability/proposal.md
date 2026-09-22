## Why

Поездка на машине в город 2026-09-22 почти не записалась: выезд из STANDBY не перешел в ACTIVE, префиксы 6–8 км вместо бюджета 200 м, карта осталась под глухой вуалью. Телефон лежал на сиденье без motion — GPS-смещение в BALANCED с точностью 36–349 м не смогло подтвердить движение, BURST протухал по таймауту по кругу. Экономия батареи теряет смысл, если ломает основную функцию.

## What Changes

- STANDBY переводится с `BALANCED 90с/150м` на `HIGH 30–60с/0м`: GPS-смещение всегда считается сигналом пробуждения, motion/wifi только ускоряют, а не разрешают.
- Убирается вето сна после холостого BURST (`POST_IDLE_SLEEP 180с + wake 200м`): GPS-пробуждение в сне работает так же как вне сна, холостые BURST считаются только в метрику.
- BURST остается короткой проверкой HIGH, но переход `BURST -> ACTIVE` принимается по `MOVING`-вердикту TrustEngine ИЛИ по скорости Fused `>5 м/с с accuracy <=25 м` (машина/поезд с грязным окном истории).
- `ACTIVE -> STANDBY` дебаунс увеличивается с `30с` до `3–5 мин` после скорости, чтобы пробка/паркинг не роняли профиль mid-trip.
- Бюджет префикса 200 м медианы сохраняется как критерий регресса; разрывы после тишины по-прежнему отдельная метрика `gap`, а не `prefix`.
- Тумблеров эко нет как раньше: режим всегда `eco`, теги логов и счетчики не меняются.

## Capabilities

### New Capabilities

- Нет новых capability: поведение укладывается в существующие `tracking` и `battery-eco`.

### Modified Capabilities

- `tracking`: интервалы опроса STANDBY и условия переходов STANDBY/BURST/ACTIVE (HIGH вместо BALANCED, GPS-wake без вето сна, speed-latch в ACTIVE, длинный дебаунс выхода из ACTIVE).
- `battery-eco`: профили и сигналы пробуждения (STANDBY HIGH редкий, BURST-проверка, motion/wifi только ранние хинты, бюджет префикса без изменений как метрика).

## Impact

- Код: `tracking/EcoGovernor.kt` (константы профилей, сон, wake-пороги), `tracking/TrackingService.kt` (`ecoTargetAfterVerdict`, `enterProfile`, `requestBurstFromSensor`, подписка Fused), без изменений `TrustEngine`, `PendingGate`, `FogRepository`, UI карты.
- Тесты: `EcoGovernorTest`, `TrustVerdictTest`/`TrustEngineTest` (регресс wake без motion), полевая проверка машина/поезд/пешком.
- Совместимость: формат БД, `counters.json`, `TRACK/flush` и `TRACK/eco_state` не меняются; поведение батареи: GPS-время в покое вырастет (HIGH вместо BALANCED), в поездке без изменений.

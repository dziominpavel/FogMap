## Why

Прогулка 2026-09-23: рендер здоров (`avg_total_ms≈0.2`, `null_proj=0`), а в БД почти пусто — 4 `openFog=1`, 213 ячеек вместо коридора. Причина цепочком: редкий STANDBY-GPS → `LocationFilter` отбрасывает 47% по `acc>25` → `TrustEngine` не получает `speed` device (в `HistPoint` только time/lat/lon/acc) → 143/147 принятых точек со `speed≥1.5–2.8` получают STAND при ходьбе → STAND не пишется в `track_points` и не открывает туман. wifi-WAKE срабатывал, но окно BURST 75с и speed-latch `>5 м/с` не дают удержать ACTIVE для пешехода. Итог: день «ходил, а на карте клякса» — метрики врут (338м / 18с), доверия к сигналу нет.

## What Changes

- **Speed-гейт в TrustEngine:** `speed ≥ 1.0 м/с И acc ≤ 25 м` → STAND запрещён, вердикт MOVING; `openFog` остаётся по series trust (без авт-openFog с одного рывка). Один общий порог `SPEED_MIN_MPS = 1.0` для гейта и eco speed-latch (вместо разрыва 1.0/5.0).
- **Классификация движения `MotionKind`** (speed+acc полосы, гистерезис ±0.3, без Activity Recognition API): `STILL` (<1.0) / `WALK` (1.0–2.5) / `BIKE` (2.5–8.0) / `VEHICLE` (≥8.0). STAND доступен только при `kind==STILL`; вердикт в точке остаётся binary STAND/MOVING.
- **Per-type accuracy для тумана** (история trust — всегда, с низким весом): STILL/WALK ≤25 м, BIKE ≤40 м, VEHICLE ≤100 м. Точки `acc>25` не пишутся в `track_points` (как сейчас для fog-порога), но участвуют в истории вердикта.
- **STAND в трек для непрерывности линии:** pause-точки STAND при `kind==STILL` пишутся в `track_points` (openFog=false), чтобы паузы не рвали коридор; дистанция/время дня по-прежнему только по движению.
- **Эко/wifi-вход (пакет A):** `BURST_WINDOW_MS = 180_000` (wifi/motion), speed-latch BURST→ACTIVE при `speed ≥ 1.0 И acc ≤ 25`, `activeMayStandby=false` при `state==MOVING` + существующий speed-hold 3 мин.
- **Счётчики всех веток TrustEngine** (`static / wake / silence / jump / teleport / turn / speed_gate / kind_*`) в `counters.json` и dev-логе дня — основа для следующего сдвига «счётчики → удаление мёртвых правил».
- **Приёмка без полевого доверия:** реплей-тест на `days/2026-09-23.jsonl` (комплексный PASS: 0× STAND при speed≥1.0; wifi-BURST 180с; правдоподобное распределение Kind; ячейки ≥2000; дистанция ≫ 338м).

## Capabilities

### New Capabilities

- Нет новых capabilities: поведение ложится в существующие `gps-trust`, `tracking`, `battery-eco`, `tracking-reliability`, `track-debug`.

### Modified Capabilities

- `gps-trust`: speed-гейт запрета STAND при `speed≥1.0 И acc≤25`; классификация `MotionKind` как вход для гейта/порогов; счётчики срабатывания всех веток вердикта.
- `tracking`: пороги accuracy для открытия тумана по типу движения (25/40/100); запись STAND-пауз в `track_points` без openFog; `HistPoint` несёт `speed` для вердикта; окно BURST 180с; speed-latch `≥1.0` вместо `>5`.
- `battery-eco`: speed-latch и удержание ACTIVE при ходьбе (`≥1.0 м/с`); окно wifi/motion-BURST 180с; запрет ухода ACTIVE→STANDBY при MOVING-вердикте.
- `tracking-reliability`: материализованные счётчики веток TrustEngine (не только отбросы LocationFilter) в `counters.json` и диагностике дня.
- `track-debug`: экспорт `counters` включает счётчики веток TrustEngine для реплея/калибровки.

## Impact

- **Код:** `TrustEngine.kt` (гейт, MotionKind, счётчики), `LocationFilter.kt` (per-type acc), `EcoGovernor.kt` (1.0 / 180с / guard), `TrackingService.kt` (HistPoint.speed, STAND→трек, профиль), `FogRepository`/flush (acc>25→только история), `EcoLogPayload`/`counters.json` (ветки TrustEngine), unit-тесты + реплей-скрипт на логе 23.09.
- **Схема БД:** без миграции сущностей (STAND уже пишется как строки `track_points` с openFog=false — меняется только, когда разрешён дроп); `MotionKind`/счётчики — в выгрузке и логе, не в миграции schema.
- **Совместимость:** старые выгрузки без новых ключей читаются с defaults; rebuild тумана переживает смену порогов (stored verdicts игнорируются как сейчас).
- **Риск:** speed-latch 1.0 может чаще держать ACTIVE на границе стоянки — смягчается acc≤25, STAND-серийным guard и полевым разбором `eco_state`; per-type acc BIKE/VEHICLE может добавить шум в ячейки — принимается ради непрерывности коридора на скорости, контролируется счётчиками `kind_*`.
- **Версия:** PATCH → 1.5.1 (решение владельца; по AGENTS.md ближе к MINOR — зафиксировано как PATCH).
- **Не в скоупе:** Activity Recognition API, UI карты/маски (рендер доказан здоров), коммит/пуш (только по команде владельца), полевая вечерняя проверка — после APK, отдельной сессией.

## MODIFIED Requirements

### Requirement: Батч-экспорт за период
Система SHALL выгружать диапазон дат от-до в единый ZIP: `meta.json` (format version, пороги TrustEngine/PendingGate/FogGrid, версия кода), `days/<дата>.jsonl` (по строке на raw-fix), `tracks/<дата>.jsonl` (по строке на принятую точку дня с полным вердиктом — разбор дней, записанных до появления raw), snapshot `visited_cells` и `counters`. **`counters` SHALL включать счетчики веток TrustEngine** (`static`, `wake`, `silence`, `jump`, `teleport`, `turn`, `speed_gate`, `kind_STILL`/`kind_WALK`/`kind_BIKE`/`kind_VEHICLE`) наравне с существующими rejected/no-fix/eco-ключами, чтобы реплей и калибровка видели, какие правила вердикта реально срабатывали. Экспорт SHALL запускаться из истории с выбором диапазона и отдаваться через системный chooser без варнингов. Экспорт пустого дня SHALL давать пустые `days/<дата>.jsonl` и `tracks/<дата>.jsonl` и корректный meta. Старый tolerant-парсер SHALL игнорировать неизвестные entries (включая отсутствие новых ключей counters в старых выгрузках).

#### Scenario: Выгрузка сегодняшней поездки
- **WHEN** пользователь выбирает диапазон сегодня–сегодня и жмет Поделиться
- **THEN** система отдает ZIP с одним `days/<сегодня>.jsonl`, meta с текущей версией порогов, snapshot ячеек и counters с ветками TrustEngine

#### Scenario: Выгрузка недели для переноса
- **WHEN** пользователь выбирает диапазон 7 дней
- **THEN** ZIP содержит 7 файлов дней (пустые дни — пустыми файлами) и полные counters, включая ветки TrustEngine

#### Scenario: Старая выгрузка без новых ключей
- **WHEN** импортируется ZIP, собранный до появления счетчиков TrustEngine
- **THEN** парсер не падает, отсутствующие ключи трактуются как отсутствие данных (0 / no data), импорт проходит

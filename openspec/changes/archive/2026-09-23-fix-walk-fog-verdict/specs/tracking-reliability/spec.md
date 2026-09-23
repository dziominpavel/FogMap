## ADDED Requirements

### Requirement: Счётчики веток TrustEngine
Система SHALL материализовать счетчики срабатывания каждой ветки вердикта TrustEngine отдельно от счетчиков отбросов LocationFilter: `static` (правило статичного кластера), `wake` (пробуждение/смена якоря), `silence` (сброс по тишине dt), `jump` (прыжок/потолок), `teleport` (телепорт-гейт), `turn` (смена курса), `speed_gate` (срабатывание speed-гейта запрета STAND), `kind_STILL` / `kind_WALK` / `kind_BIKE` / `kind_VEHICLE` (распределение классификации движения). Счетчики SHALL писаться в `counters.json` выгрузки и в диагностическое событие дня DevLog. Ненулевые значения и нули SHALL быть одинаково видимы (ноль ветки = кандидат на удаление правила в следующем change, не «нет данных»).

#### Scenario: Реплей дня показывает мертвые ветки
- **WHEN** исследователь открывает `counters.json` за день с прогулкой
- **THEN** видит счетчики всех веток TrustEngine, включая `speed_gate` и распределение `kind_*`, а не только rejected_accuracy

#### Scenario: Диагностика дня включает ветки TrustEngine
- **WHEN** пользователь открывает диагностику дня
- **THEN** помимо отбросов LocationFilter видит счётчики веток вердикта (static/wake/silence/jump/teleport/turn/speed_gate/kind_*)

#### Scenario: Ноль ветки отличим от отсутствия
- **WHEN** за день ветка `teleport` не сработала ни разу
- **THEN** в counters присутствует `teleport: 0`, а не ключ отсутствует

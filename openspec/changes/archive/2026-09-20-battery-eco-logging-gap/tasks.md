## 1. Payload flush

- [x] 1.1 Добавить в `TRACK/flush` и `slow_flush` поля эко-снимка пачки (число фиксов, число STAND, GPS-время профиля) и проверить чтением JSONL: поля присутствуют в каждом flush за день
- [x] 1.2 Добавить unit-тест сборки payload flush (ключи, отсутствие lat/lon) и проверить командой `:app:testDebugUnitTest --tests "ru.fogmap.*Eco*"`

## 2. Причина wake в eco_state

- [x] 2.1 Добавить в `TRACK/eco_state` поля причины перехода (предыдущий профиль, дистанция до якоря целым числом метров, вердикт) и проверить чтением JSONL: STANDBY->BURST содержит wake_m и verdict
- [x] 2.2 Прогнать полный `:app:testDebugUnitTest` и убедиться что TrustEngine/PendingGate/FogGrid/EcoGovernor зеленые

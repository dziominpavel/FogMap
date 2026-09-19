## 1. Карта без поворота

- [x] 1.1 Запретить rotate-жест в SDK при создании MapView (`setRotateGesturesEnabled(false)` рядом с tilt-локом) и проверить сборкой, что флаг применён
- [x] 1.2 Удалить компас целиком (FAB, `showCompass`/`northOff`, `rotate`-импорт, `compass_reset`, сброс-анимацию) и проверить, что правой колонки с компасом нет, чип зума и «Где я» на месте
- [x] 1.3 Удалить `camAzimuth` (состояние, обновление в слушателе, ключ `remember`) и проверить, что пересчёт дырок зависит только от клеток, зума и target

## 2. Диагностика без azimuth

- [x] 2.1 Удалить поле `azimuth` из `DevCameraStats` (`agg`/`storm`, сигнатура `onEvent`) и проверить компиляцией diag-модуля
- [x] 2.2 Обновить `DevCameraStormTest` (фазы без azimuth) и проверить `./gradlew :app:testDebugUnitTest --tests "ru.fogmap.DevCameraStormTest"` зелёным
- [x] 2.3 Удалить `tilt`/`tilt_nonzero`/`tilt_fix` и параметр `tiltFixed` из `DevCameraStats` (сигнатура `onEvent(zoom, nowMono)`, вызов в `MapScreen`, тест шторма) и проверить полным юнит-набором `:app:testDebugUnitTest` зелёным

## 3. Приёмка

- [ ] 3.1 Прогнать на устройстве: двупальцевый поворот ничего не делает, компаса нет, север с первого кадра, полосы после поворота не воспроизводятся, и зафиксировать результат

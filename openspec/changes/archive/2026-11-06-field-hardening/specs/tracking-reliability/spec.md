# Spec Delta

## ADDED Requirements

### Requirement: CPU удерживается, пока пишется движение

Сервис SHALL удерживать `PARTIAL_WAKE_LOCK` с именем `FogMap:tracking` в профилях эко `ACTIVE` и `BURST`, чтобы цикл записи не терял тики, пока устройство спит. В профиле `STANDBY`, на паузе и после остановки сервиса wake-lock MUST NOT удерживаться. Смена состояния SHALL писать событие `TRACK/wakelock` с полями `held` и `profile` только на переходе, без повторов на каждом тике.

#### Scenario: Движение

- **WHEN** профиль эко `ACTIVE` или `BURST`, пауза выключена
- **THEN** сервис держит wake-lock, в `dumpsys power` виден `FogMap:tracking`, а в `DevLog` ровно одно событие `TRACK/wakelock` с `held=true` на переход

#### Scenario: Стоянка

- **WHEN** сервис ушёл в `STANDBY`
- **THEN** wake-lock снят, в `DevLog` есть `TRACK/wakelock` с `held=false`, устройство может уходить в сон

#### Scenario: Пауза

- **WHEN** пользователь включил паузу
- **THEN** wake-lock снят сразу, не дожидаясь смены профиля

#### Scenario: Остановка сервиса

- **WHEN** сервис уничтожен системой или остановлен пользователем
- **THEN** wake-lock освобождён, удерживаемого lock'а в системе не остаётся

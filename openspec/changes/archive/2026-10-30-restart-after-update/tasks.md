# Tasks

## 1. Спека и код рестарта после обновления

- [x] 1.1 Добавить delta-спеки `tracking` и `app-shell` (без proposal/design по решению владельца) и этот tasks-файл.
- [x] 1.2 Manifest: добавить `android.intent.action.MY_PACKAGE_REPLACED` в intent-filter `BootReceiver`.
- [x] 1.3 `BootReceiver`: принимать boot-actions и `MY_PACKAGE_REPLACED`, определить `via=boot|update`, использовать `TrackingService.start(context, via)`, логировать `restart_skip` для паузы/Play Services/разрешения и отказ постановки worker.
- [x] 1.4 `BootWorker`: читать `via` из inputData, использовать в `TrackingService.start`, нейтральный текст уведомления; receiver передаёт `via`.
- [x] 1.5 `BgHealth`: исключить `package_updated` из `isSystemStop`, обновить KDoc; `BgHealthTest` — тест на `exitFrom` и отсутствие `KILLED`.

## 2. Проверка

- [x] 2.1 `.\gradlew.bat :app:compileDebugKotlin` — без ошибок.
- [x] 2.2 `.\gradlew.bat :app:testDebugUnitTest` — все тесты зелёные (291 тест, 0 падений, 1 skipped).
- [x] 2.3 `.\gradlew.bat :app:assembleDebug` — APK собирается.
- [x] 2.4 Эмулятор: сервис запущен, `adb install -r` без открытия приложения → сервис поднялся, в DevLog `start_attempt` с `via=update`; при отозванном разрешении — `restart_skip no_permission`.
- [x] 2.5 Синхронизировать delta-спеки в основные `openspec/specs/tracking` и `app-shell`; `openspec validate --specs` — 15/15 (strict остаётся с прежними warnings о длинных требованиях).

## 3. Документация

- [x] 3.1 `CHANGELOG.md` → `[Unreleased]`: пользовательский блок и инженерные детали change `restart-after-update`.
- [x] 3.2 `docs/sessions.md` — запись сессии, `Версия: без бампа (в Unreleased; бамп на релизе)`.
- [x] 3.3 `docs/decisions.md` — решение о переиспользовании BootReceiver/BootWorker для обновления и об исключении `package_updated` из системных остановок.
- [x] 3.4 `py -3 scripts/check-version.py` → exit 0, версия не менялась.

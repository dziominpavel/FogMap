## 1. Код чистки TTL

- [x] 1.1 В `app/src/main/java/ru/fogmap/diag/DevLogFile.kt` увеличить `TTL_MS` с 24 ч до 7 дней (168 ч), переименовать `cleanupOlderThan24h` → `cleanupExpired` с обновлённым KDoc, в `FogMapApp.kt` обновить вызов и комментарий. Проверка: `$env:JAVA_HOME = "C:\Users\Dziom\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2"; .\gradlew.bat :app:compileDebugKotlin` — BUILD SUCCESSFUL

## 2. Тест границы TTL

- [x] 2.1 Вынести чистый предикат `isExpired(lastModifiedMs, nowMs)` (companion `DevLogFile`) и добавить юнит-тест границы: возраст ровно TTL — файл жив, TTL + 1 мс — удаляется, 6 дней — жив, 8 дней — удаляется. Проверка: `.\gradlew.bat :app:testDebugUnitTest` — зелёный, новый тест проходит

## 3. Спека и полевой контроль

- [x] 3.1 Прогнать `openspec validate --change "fix-devlog-retention-7d"` — без ошибок (delta `specs/dev-logging/spec.md` соответствует формату). Проверка: команда завершается с exit 0
- [ ] 3.2 После установки APK владельцем: на экране «Диагностика» в списке файлов видны логи прошлых дней (не только свежий), после перезапуска приложения файлы старше 7 дней удаляются. Проверка: шаренный список файлов/логи в `dist/`

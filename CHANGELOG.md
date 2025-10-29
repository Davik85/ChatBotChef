# Changelog

## [Unreleased]

### Security
- Маскированы токены при запуске (`BOOT-TOKENS`, `BOOT-OPENAI-HEADERS`), сокращены дампы ответов OpenAI/Telegram.
- Добавлен аудит-лог админ-действий (`admin_audit`) и единый префикс `ADMIN-*` в логах.
- Ограничено сохранение пользовательских сообщений в логах (только длина текста).

### Надёжность
- Добавлен репозиторий `ProcessedUpdatesRepo` для идемпотентной обработки апдейтов Telegram.
- Улучшены ретраи OpenAI/Telegram, корректная обработка 403/429/5xx и ограничение длины входящих сообщений.
- Миграции БД расширены новой таблицей `admin_audit`, включён `PRAGMA foreign_keys=ON` после подключения.
- Добавлен скрипт `scripts/sqlite_backup.sh` для резервного копирования SQLite с ротацией.

### Операционка и документация
- Полностью обновлён `README.md` и добавлены документы: `docs/ENV.md`, `docs/DEPLOY.md`, `docs/SECURITY.md`,
  `docs/DB_SCHEMA.md`, `docs/ADMIN_GUIDE.md`, `docs/OPERATIONS.md`, `docs/RELEASE_CHECKLIST.md`.
- Задокументирован процесс деплоя, операции, чек-лист перед релизом и политика безопасности.

### Контроль изменений
- Для верификации используйте префиксы логов: `OPENAI-HTTP-ERR`, `TG-HTTP-ERR`, `PAYMENT-*`, `ADMIN-*`, `DB-*`.
- Проверяйте бэкапы (`scripts/sqlite_backup.sh`) и аудит-таблицу `admin_audit` после административных операций.

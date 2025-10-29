# DB_SCHEMA.md — схема БД ChatBotChef

Все данные хранятся в SQLite. Подключение выполняется с `PRAGMA foreign_keys=ON`, уровень изоляции —
`Connection.TRANSACTION_SERIALIZABLE`. Таблицы создаются и мигрируются при старте через `DatabaseFactory.init()`.

## Таблицы и ключи

| Таблица              | Основные поля / индексы                                          | Назначение                                                    |
|----------------------|------------------------------------------------------------------|----------------------------------------------------------------|
| `users`              | `user_id` (PK), `first_seen`, `blocked`, `blocked_ts`            | Каталог всех пользователей, отметка блокировки.               |
| `messages`           | `id` (PK AUTOINCREMENT), `user_id` (index), `ts` (index), `text`, `role` | История активности пользователей (для статистики и paywall). |
| `memory_notes_v2`    | `id` (PK), `user_id` (index), `note`, `ts`                        | Локальные заметки/память бота (в текущей версии не используется активно). |
| `user_stats`         | `user_id` (PK), `day`, `free_used`                               | Агрегированные лимиты (для backfill/совместимости).          |
| `usage_counters`     | `user_id` (PK), `total_used`                                     | Счётчик сообщений для paywall.                               |
| `processed_updates`  | `update_id` (PK)                                                 | Идемпотентность Telegram long-polling.                        |
| `premium_users`      | `user_id` (PK), `until_ts`                                       | Активные подписки (метка окончания).                         |
| `premium_reminders`  | `id` (PK), `user_id` (index), `kind` (`user_id`+`kind` — unique) | Напоминания об окончании подписки (2 и 1 день).              |
| `payments`           | `payload` (PK), индексы по `user_id`, поля статуса и идентификаторов | Учёт инвойсов и состояний платежей.                          |
| `chat_history`       | `id` (PK), `user_id` (index), `mode` (index), `role`, `text`, `ts` | История диалогов с LLM по режимам (chef/calc/product).       |
| `admin_audit`        | `id` (PK), `admin_id` (index), `action`, `target`, `meta`, `ts`   | Аудит действий администраторов.                              |

## Индексы и ограничения

- `messages` — индексы по `user_id` и `ts` для быстрых выборок статистики.
- `premium_reminders` — уникальный индекс `(user_id, kind)` предотвращает повторную отправку.
- `premium_users`, `users`, `usage_counters` — уникальный ключ по `user_id`.
- `payments` — primary key на `payload`, индексы для `user_id` и временных полей.
- `chat_history` — дополнительные индексы по `(user_id, mode)` для быстрого восстановления контекста.

SQLite не поддерживает внешние ключи в `SchemaUtils.createMissingTablesAndColumns`, но `PRAGMA foreign_keys=ON` включён для
сценариев, где ограничения добавляются вручную.

## Миграции

`DatabaseFactory.init()` выполняет следующие шаги:

1. Создаёт каталоги и подключается к БД (`jdbc:sqlite?...`).
2. Выполняет ручные миграции/переопределения (переименование колонок, пересоздание таблиц без PK и т.д.).
3. Добавляет отсутствующие колонки и индексы (`SchemaUtils.createMissingTablesAndColumns`).
4. Выполняет backfill пользователей и ремонт осиротевших записей (`UserRegistry.backfillFromExistingData`, `UsersRepo.repairOrphans`).

При добавлении новых таблиц/полей:

- добавьте объект `Table` в `DatabaseFactory.kt` и включите его в `SchemaUtils.createMissingTablesAndColumns`;
- для новых NOT NULL колонок задайте `DEFAULT` и предусмотрите миграцию существующих данных;
- избегайте `ALTER TABLE DROP COLUMN` — вместо этого пересоздавайте таблицу через временную (`*_new`), как сделано для `messages`;
- помните о включенном `PRAGMA foreign_keys=ON` — при добавлении FK нужно заботиться о порядке вставок/удалений.

## Инварианты

- `users.blocked = true` ⇔ `blocked_ts > 0`.
- `premium_users.until_ts` всегда в миллисекундах (UTC). Значение `<= now` означает, что премиум истёк.
- В `payments` статус принимает значения `invoice`, `precheck`, `paid`, `failed`.
- Таблица `processed_updates` хранит только последние ~200k update_id; старые очищаются автоматически.
- Таблица `admin_audit` используется для расследования действий админов — не очищайте её без резервного копирования.

## Бэкапы и восстановление

- Используйте `scripts/sqlite_backup.sh` — он создаёт резервные копии через `sqlite3 VACUUM INTO`, что гарантирует целостность.
- Для восстановления остановите сервис (`systemctl stop chatbotchef`), скопируйте нужный бэкап на место рабочего файла и снова
  запустите службу.

# OPERATIONS.md — эксплуатация и рутины

## Мониторинг и проверка статуса

- Проверка сервиса:
  ```bash
  sudo systemctl status chatbotchef.service
  ```
- Непрерывный просмотр логов (с фильтрацией по подсистемам при необходимости):
  ```bash
  sudo journalctl -u chatbotchef.service -f
  sudo journalctl -u chatbotchef.service | grep OPENAI-
  ```
- Основные префиксы логов:
  - `TG-POLL-ERR`, `TG-HTTP-ERR` — проблемы с Telegram API;
  - `OPENAI-HEALTH-ERR`, `OPENAI-HTTP-ERR` — ошибки OpenAI;
  - `PAYMENT-*` — жизненный цикл платежей;
  - `ADMIN-*` — действия администраторов;
  - `DB-*` — операции с базой данных.

## Перезапуск и обновление конфигурации

Изменение `.env` требует перезапуска сервиса:

```bash
sudo systemctl restart chatbotchef.service
```

После перезапуска убедитесь, что в логах нет ошибок health-check OpenAI и инициализации БД (`BOOT-TOKENS`, `DB-INIT-ERR`).

## Резервное копирование базы данных

- Скрипт `scripts/sqlite_backup.sh` копирует БД в `/root/data/backup` и удаляет файлы старше `KEEP_DAYS` (14 по умолчанию).
- Рекомендуется настроить systemd timer:
  ```ini
  # /etc/systemd/system/chatbotchef-backup.timer
  [Unit]
  Description=Daily ChatBotChef SQLite backup

  [Timer]
  OnCalendar=*-*-* 03:00:00
  Persistent=true

  [Install]
  WantedBy=timers.target
  ```

  ```ini
  # /etc/systemd/system/chatbotchef-backup.service
  [Unit]
  Description=Run ChatBotChef SQLite backup

  [Service]
  Type=oneshot
  User=root
  WorkingDirectory=/root/app
  EnvironmentFile=/root/.env
  ExecStart=/root/app/scripts/sqlite_backup.sh
  ```

  ```bash
  sudo systemctl daemon-reload
  sudo systemctl enable --now chatbotchef-backup.timer
  ```

## Проверка напоминаний и cron-задач

- Напоминания о премиуме отправляются ежечасно (корутина `ReminderJob`). В логах ищите `ReminderJob` и `ADMIN-AUDIT`.
- Для диагностики можно выполнить запрос:
  ```sql
  SELECT * FROM premium_reminders ORDER BY sent_ts DESC LIMIT 20;
  ```

## Чек-лист после релиза

1. `journalctl -u chatbotchef.service -b` — убедиться в отсутствии ошибок `OPENAI-*`, `TG-HTTP-ERR`, `PAYMENT-WARN`.
2. Прогнать тестовую команду `/whoami` и `/admin` от рабочего аккаунта администратора.
3. Проверить `/premiumstatus <test_id>` и `/grantpremium <test_id> 1` на тестовой учётке, затем откатить изменения.
4. Убедиться, что резервный скрипт создал свежий файл в `/root/data/backup`.
5. Зафиксировать результаты в [CHANGELOG.md](../CHANGELOG.md) или внутреннем журнале релизов.

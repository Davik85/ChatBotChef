# ChatBotChef

ChatBotChef — Telegram-бот на Kotlin, который помогает подбирать идеи рецептов и управлять рационом с помощью OpenAI Chat
Completions. Проект поддерживает платную подписку через Telegram Payments/ЮKassa, хранит состояние в SQLite и разворачивается
в виде systemd-сервиса на Ubuntu.

## Архитектура и ключевые компоненты

- **Интерфейс с Telegram** — модуль `app.web` реализует long-polling, нормализацию апдейтов, защиту от вложений и
  административный интерфейс (статистика, рассылки, выдача премиума).
- **OpenAI-клиент** (`app.llm.OpenAIClient`) — с валидацией ключей, ретраями, ограничением длины ответов и безопасными логами.
- **Слой хранения** — Kotlin Exposed + SQLite. Таблицы и миграции живут в `app.db.DatabaseFactory`, есть репозитории для
  пользователей, платежей, напоминаний и аудит-лога админ-действий.
- **Бизнес-логика** — модули `app.logic` реализуют лимиты, подсказки, историю диалога и напоминания об окончании подписки.
- **Платежи** — `app.pay.PaymentService` и `ReceiptBuilder` готовят инвойсы, проверяют ограничения Telegram, управляют чеками
  54-ФЗ и режимом НПД.

## Быстрый старт (локально)

1. Установите JDK 21 (Gradle Wrapper скачает нужный JDK при первом запуске).
2. Создайте файл `.env` и заполните обязательные значения — минимум `TELEGRAM_TOKEN` и `OPENAI_API_KEY` (см. пример ниже).
3. Соберите проект и запустите тесты:
   ```bash
   ./gradlew clean test
   ```
4. Соберите "толстый" JAR для последующего деплоя:
   ```bash
   ./gradlew fatJar
   ```
5. Для локального прогона используйте:
   ```bash
   ./gradlew run
   ```
   или
   ```bash
   java -jar build/libs/ChatBotChef-all.jar
   ```

### Минимальный пример `.env`

```dotenv
TELEGRAM_TOKEN=123456789:example
OPENAI_API_KEY=sk-...
DB_PATH=data/chatbotchef.sqlite
```

Полный список переменных с описанием — в [docs/ENV.md](docs/ENV.md).

## Тесты и качество

- `./gradlew clean test fatJar` — проверка перед релизом (юнит-тесты + сборка артефакта).
- Логи формируются в STDOUT/STERR с префиксами подсистем (`OPENAI-*`, `TG-HTTP-ERR`, `PAYMENT-*`, `ADMIN-*`, `DB-*`).
- Скрипт `scripts/sqlite_backup.sh` создаёт резервные копии БД с ротацией и предназначен для привязки к cron/systemd timer.

## Документация

| Документ | Назначение |
|----------|------------|
| [docs/ENV.md](docs/ENV.md) | Полное описание переменных окружения, флаги НПД и чеков. |
| [docs/DEPLOY.md](docs/DEPLOY.md) | Пошаговый деплой на Ubuntu, systemd-unit и обновления без даунтайма. |
| [docs/SECURITY.md](docs/SECURITY.md) | Правила обращения с секретами, требования к логам, доступам и firewall. |
| [docs/DB_SCHEMA.md](docs/DB_SCHEMA.md) | Актуальная схема БД, ключи, индексы и стратегия миграций. |
| [docs/ADMIN_GUIDE.md](docs/ADMIN_GUIDE.md) | Руководство для админов бота: статистика, рассылки, выдача премиума. |
| [docs/OPERATIONS.md](docs/OPERATIONS.md) | Эксплуатация и рутины: бэкапы, ротация логов, мониторинг systemd. |
| [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md) | Чек-лист перед выкатыванием новой версии. |

Список доработок и инструкции по проверке изменений фиксируются в [CHANGELOG.md](CHANGELOG.md).

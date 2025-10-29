# DEPLOY.md — развёртывание ChatBotChef на Ubuntu

Документ описывает пошаговый деплой production-версии бота на сервер под управлением Ubuntu 22.04 LTS (аналогично для
современных Debian/Ubuntu). Предполагается запуск под `root` или системным пользователем с доступом к `/root/data`.

## 1. Подготовка окружения

1. Обновите систему и установите необходимые пакеты:
   ```bash
   sudo apt update && sudo apt upgrade -y
   sudo apt install -y openjdk-21-jre sqlite3
   ```
2. Создайте рабочие каталоги:
   ```bash
   sudo mkdir -p /root/app /root/data /root/data/backup
   sudo chown -R root:root /root/app /root/data
   ```
3. Скопируйте собранный JAR (см. `./gradlew fatJar`) в `/root/app/ChatBotChef-all.jar`.
4. Создайте файл `/root/.env` с нужными переменными окружения (см. [docs/ENV.md](ENV.md)). Разрешения: `chmod 600 /root/.env`.

## 2. Скрипты обслуживания

- Поместите `scripts/sqlite_backup.sh` в `/root/app` и сделайте исполняемым (`chmod +x`).
- Настройте ежедневный бэкап через `cron` или systemd timer (см. [docs/OPERATIONS.md](OPERATIONS.md)).

## 3. Юнит для systemd

Создайте файл `/etc/systemd/system/chatbotchef.service` со следующим содержимым:

```ini
[Unit]
Description=ChatBotChef Telegram bot
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/root/app
EnvironmentFile=/root/.env
ExecStart=/usr/bin/java -jar /root/app/ChatBotChef-all.jar
Restart=always
RestartSec=5
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=full
ProtectHome=true
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
```

> Примечание. Если бот запускается не от `root`, скорректируйте `User=`, `WorkingDirectory` и права на `/root/.env`.

Загрузите unit и включите сервис:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now chatbotchef.service
```

Проверка статуса и логов:

```bash
sudo systemctl status chatbotchef.service
sudo journalctl -u chatbotchef.service -f
```

## 4. Обновление без даунтайма

1. Скопируйте новый JAR во временное имя, затем замените боевой файл:
   ```bash
   sudo cp ChatBotChef-all.jar ChatBotChef-all.jar.$(date +%Y%m%d-%H%M%S)
   sudo cp /tmp/ChatBotChef-all.jar /root/app/ChatBotChef-all.jar
   ```
2. Перезапустите сервис:
   ```bash
   sudo systemctl restart chatbotchef.service
   ```
3. Убедитесь по логам (`journalctl -u chatbotchef.service -f`), что бот успешно запустился и прошёл health-check OpenAI.

При неудачном релизе верните предыдущий JAR (резервная копия из шага 1) и повторно перезапустите службу.

## 5. Проверка после деплоя

Используйте [docs/RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md) для контроля:

- прогон `./gradlew clean test fatJar` перед заливкой;
- проверка тестового платежа на минимальную сумму;
- подтверждение, что лимиты и напоминания работают (по логам `ADMIN-*`, `PAYMENT-*`);
- сверка статистики в админ-панели.

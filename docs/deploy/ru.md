# Руководство по развертыванию бэкенда RavenTag

Это руководство охватывает полное производственное развертывание только **бэкенда RavenTag**.

**Примечание:** Android-приложения RavenTag (Verify и Brand Manager) подключаются напрямую к вашему бэкендному API. Развертывание фронтенда не требуется.

---

## Обзор архитектуры

```
Android-приложения (Verify + Brand Manager)
              ↓
    Ваш бэкенд API (это руководство)
              ↓
    Сеть Ravencoin + IPFS
```

---

## Предварительные требования

- Linux VPS с публичным статическим IP (минимум 1 ГБ ОЗУ, 10 ГБ диска, рекомендуется Ubuntu 24.04 LTS)
- Доменное имя (например, `api.raventag.com`)
- Учетная запись Cloudflare (бесплатно), управляющая DNS вашего домена

---

## Шаг 1: Подготовка сервера

Подключитесь к серверу через SSH и выполните:

```bash
sudo apt update && sudo apt upgrade -y

# Docker
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER
newgrp docker

# nginx + nano
sudo apt install -y nginx nano

# Certbot (Let's Encrypt)
sudo snap install --classic certbot
sudo ln -s /snap/bin/certbot /usr/bin/certbot
```

---

## Шаг 2: Клонирование репозитория

```bash
git clone https://github.com/ALENOC/RavenTag.git --depth=1
cd RavenTag
```

---

## Шаг 3: Создание директории Docker secrets

```bash
mkdir -p secrets
```

---

## Шаг 4: Генерация и хранение секретных ключей

Выполните эти команды для генерации безопасных случайных ключей. **Сохраните вывод в менеджере паролей** - эти ключи нельзя изменить после программирования первого NFC-чипа.

```bash
# Ключ администратора API (для отзыва, отмены отзыва, регистрации чипа)
openssl rand -hex 24 > secrets/admin_key

# Ключ оператора API (только для регистрации чипа)
openssl rand -hex 24 > secrets/operator_key

# Мастер-ключ бренда (AES-128 для вывода ключей для каждого чипа)
openssl rand -hex 16 > secrets/brand_master_key

# Соль бренда (для вычисления nfc_pub_id)
openssl rand -hex 16 > secrets/brand_salt
```

**Важно:** Каждый файл должен содержать ТОЛЬКО шестнадцатеричную строку, без новой строки. Приведенные выше команды обрабатывают это автоматически.

---

## Шаг 5: Получение отпечатка сертификата Android-приложения

Для работы Android App Links вам понадобится отпечаток сертификата SHA-256 вашего релизного APK:

```bash
keytool -list -v -keystore android/signing/raventag-release.keystore -alias raventag | grep SHA256
```

Извлеките только отпечаток (удалите двоеточия, верхний регистр):
```
<YOUR_COLON_FINGERPRINT> → <YOUR_HEX_FINGERPRINT>
```

**Текущий отпечаток (новый ключ 2026-03-25):**
```
3E:A5:B9:F3:75:63:1A:4E:1D:E9:5D:E1:DA:9C:22:45:14:1E:4A:D8:FA:7A:63:78:7D:6A:B9:81:96:B4:A3:BE
```

Без двоеточий (для `.env`):
```
3EA5B9F375631A4E1DE95DE1DA9C2245141E4AD8FA7A63787D6AB98196B4A3BE
```

---

## Шаг 6: Настройка несекретных переменных окружения

Создайте файл `.env` для несекретной конфигурации:

```bash
cat > .env << 'EOF'
NODE_ENV=production
PORT=3001
DB_PATH=/data/raventag.db
# Публичная конечная точка Ravencoin RPC (резерв, когда локальный узел недоступен)
RVN_PUBLIC_RPC_URL=https://rvn-rpc.publicnode.com
# Разрешенные имена пакетов Android-приложений для подключения
ALLOWED_ORIGINS=io.raventag.app,io.raventag.app.brand
# Шлюз IPFS для получения метаданных
IPFS_GATEWAY=https://ipfs.io/ipfs/
# Отпечаток сертификата Android App Links (из шага 5)
ANDROID_APP_FINGERPRINT=<YOUR_FINGERPRINT_HERE>
EOF
```

**Важно:**
- Замените `ANDROID_APP_FINGERPRINT` на фактический отпечаток сертификата из шага 5
- `ALLOWED_ORIGINS` должен содержать имена пакетов Android-приложений, которым разрешено подключаться к вашему бэкенду
- Никогда не коммитьте файл `.env` в систему контроля версий

---

## Шаг 7: Запуск бэкенда

```bash
docker compose up -d backend
docker compose logs backend
```

Проверьте, что он работает:

```bash
curl http://localhost:3001/health
# Ожидается: {"status":"ok","version":"1.0.0","protocol":"RTP-1"}
```

Проверьте, что Android App Links настроены:

```bash
curl https://api.yourdomain.com/.well-known/assetlinks.json
# Должен вернуть JSON с отпечатком вашего приложения
```

---

## Шаг 8: Настройка nginx

```bash
sudo nano /etc/nginx/sites-available/raventag
```

Вставьте:

```nginx
server {
    listen 80;
    server_name api.yourdomain.com;

    location / {
        proxy_pass http://127.0.0.1:3001;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

Включите и перезагрузите:

```bash
sudo ln -s /etc/nginx/sites-available/raventag /etc/nginx/sites-enabled/
sudo rm /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx
```

---

## Шаг 9: Настройка DNS в Cloudflare

В панели DNS Cloudflare добавьте эту запись (прокси пока ВЫКЛ):

| Тип | Имя | Содержимое |
|------|------|---------|
| A | `api` | IP вашего сервера |

Дождитесь распространения DNS (обычно 2-5 минут с Cloudflare).

---

## Шаг 10: Получение SSL-сертификата

```bash
sudo certbot --nginx -d api.yourdomain.com
```

Если certbot не может автоматически установить сертификат, обновите `server_name` nginx с фактическим доменом и выполните:

```bash
sudo certbot install --cert-name api.yourdomain.com
```

---

## Шаг 11: Обновление DNS Cloudflare

После получения SSL-сертификата включите прокси (оранжевое облако) на записи `api` в Cloudflare:

| Тип | Имя | Содержимое | Прокси |
|------|------|---------|-------|
| A | `api` | IP вашего сервера | On (Proxied) |

Установите **режим SSL/TLS на Full (strict)** в Cloudflare.

---

## Шаг 12: Финальная проверка

```bash
curl https://api.yourdomain.com/health
```

Ожидаемый ответ:
```json
{"status":"ok","version":"1.0.0","protocol":"RTP-1"}
```

---

## Шаг 13: Настройка Android-приложений

После развертывания бэкенда настройте Android-приложения для подключения к вашему бэкенду:

1. Откройте приложение RavenTag
2. Перейдите в Настройки
3. Введите URL вашего бэкенда: `https://api.yourdomain.com`
4. Введите ваш ключ администратора или оператора API (из `secrets/admin_key` или `secrets/operator_key`)
5. Сохраните

Теперь приложения будут подключаться к вашему бэкенду для всех операций.

---

## Обновление развертывания

Для получения нового кода и перезапуска бэкенда:

```bash
cd ~/RavenTag
git pull
docker compose down backend
docker compose up -d --build backend
```

---

## Резервное копирование

База данных SQLite автоматически резервируется ежедневно службой резервного копирования. Резервные копии:
- Шифруются с помощью AES-256-CBC с использованием ключа администратора
- Хранятся в томе Docker `raventag_backups`
- Сохраняются за последние 7 дней

Для ручного резервного копирования:

```bash
docker run --rm -v raventag_raventag_data:/data -v $(pwd):/backup alpine \
  cp /data/raventag.db /backup/raventag_$(date +%Y%m%d).db
```

Для восстановления из зашифрованной резервной копии:

```bash
openssl enc -d -aes-256-cbc -pbkdf2 -iter 100000 \
  -pass file:secrets/admin_key \
  -in raventag_TIMESTAMP.db.enc \
  -out raventag.db
```

---

## Документация API

Ваш бэкенд предоставляет эти конечные точки:

### Публичные конечные точки
- `GET /health` - Проверка работоспособности
- `POST /api/verify/tag` - Полная верификация (SUN + блокчейн + отзыв)
- `GET /api/assets/:name/revocation` - Проверка статуса отзыва
- `GET /.well-known/assetlinks.json` - Проверка Android App Links

### Конечные точки оператора (требуется X-Operator-Key)
- `POST /api/brand/register-chip` - Регистрация UID чипа
- `GET /api/brand/chips` - Список всех чипов
- `GET /api/brand/chip/:assetName` - Получение конкретного чипа

### Конечные точки администратора (требуется X-Admin-Key)
- `POST /api/brand/revoke` - Отзыв актива
- `DELETE /api/brand/revoke/:name` - Отмена отзыва
- `GET /api/brand/revoked` - Список отозванных активов
- `POST /api/brand/derive-chip-key` - Вывод AES-ключей чипа

Полную документацию API см. в спецификации [protocol.md](../protocol.md).

---

## Устранение неполадок

### Бэкенд не запускается
```bash
docker compose logs backend
```

Проверьте:
- Наличие файлов секретов в директории `./secrets/`
- Не занят ли порт
- Права доступа к пути базы данных

### Android App Links не работают
```bash
curl https://api.yourdomain.com/.well-known/assetlinks.json
```

Убедитесь:
- `ANDROID_APP_FINGERPRINT` установлен в `.env`
- Отпечаток в верхнем регистре, без двоеточий
- Бэкенд был перезапущен после установки отпечатка

### Проблемы с SSL-сертификатом
```bash
sudo certbot certificates
sudo certbot renew --dry-run
```

### Повреждение базы данных
```bash
docker compose down backend
docker run --rm -v raventag_raventag_data:/data alpine ls -la /data
```

---

## Лучшие практики безопасности

1. **Используйте Docker secrets**: Все конфиденциальные ключи загружаются из файлов `./secrets/`, никогда из переменных окружения
2. **Храните ключи в секрете**: Никогда не коммитьте `.env` или `secrets/` в систему контроля версий
3. **Используйте надежные ключи**: Все ключи должны быть не менее 32 шестнадцатеричных символов
4. **Включите прокси Cloudflare**: Всегда проксируйте вашу конечную точку API через Cloudflare
5. **Регулярное резервное копирование**: Служба резервного копирования работает ежедневно автоматически
6. **Мониторинг логов**: Регулярно проверяйте `docker compose logs backend`
7. **Регулярное обновление**: Получайте и развертывайте обновления ежемесячно

---

## Формат файлов секретов

Каждый файл секрета в `./secrets/` должен содержать ТОЛЬКО значение шестнадцатеричной строки:

```bash
# Правильно (без новой строки):
echo -n "a1b2c3d4e5f6..." > secrets/admin_key

# Или используйте openssl, который обрабатывает это автоматически:
openssl rand -hex 24 > secrets/admin_key
```

Docker Compose читает эти файлы и монтирует их в `/run/secrets/<name>` внутри контейнера. Бэкенд читает из переменных окружения `<KEY>_FILE` для безопасной загрузки секретов.

---

## Справочник переменных окружения

| Переменная | Обязательно | Описание |
|----------|----------|-------------|
| `NODE_ENV` | Да | Установить в `production` |
| `PORT` | Да | Порт бэкенда (по умолчанию: 3001) |
| `DB_PATH` | Да | Путь к базе данных SQLite (`/data/raventag.db`) |
| `RVN_PUBLIC_RPC_URL` | Нет | Публичная конечная точка Ravencoin RPC (резерв) |
| `ALLOWED_ORIGINS` | Да | Разделенные запятыми имена пакетов Android-приложений |
| `IPFS_GATEWAY` | Да | URL шлюза IPFS |
| `ANDROID_APP_FINGERPRINT` | Да | Отпечаток сертификата SHA-256 для Android App Links |

---

**Copyright 2026 Alessandro Nocentini. All rights reserved.**

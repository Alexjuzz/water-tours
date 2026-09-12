# Инвентаризация доступа и секретов (задача 6.1/6.4)

Составлено 2026-09-12. Значения секретов здесь не приводятся — только расположение и потребители.

## Секреты и их расположение

| Секрет | Где хранится | Кто использует | Зависимость при ротации |
|---|---|---|---|
| `DB_USERNAME` / `DB_PASSWORD` | `.env` (корень репозитория), передаётся в Spring через `application.yml` (`spring.datasource.*`) | Spring (Hikari) | Требует перезапуска приложения; активные транзакции завершаются штатно |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | `.env` | Spring Mail (Yandex SMTP, `TicketEmailDeliveryJob`) | Доставка писем сейчас выключена (`tickets.email-delivery.enabled=false`) — низкий риск при ротации |
| `YOOKASSA_SHOP_ID` / `YOOKASSA_SECRET_KEY` | `.env` | `PaymentService`, webhook-эндпоинт | Активные заказы в PENDING_PAYMENT зависят от той же связки id/ключ на стороне провайдера — см. runbook |
| `TELEGRAM_BOT_TOKEN` | `.env` | `TelegramPollingJob`, `TelegramHttpConfig` | Смена токена рвёт активный long-polling до перезапуска |
| `STAFF_USERNAME` / `STAFF_PASSWORD` | `.env` → `staff.*` (было: дефолт `staff12345` в `application.yml` — убран в рамках 6.3) | `SecurityConfig` (`InMemoryUserDetailsManager`) | Единственный staff-аккаунт; ротация = смена env + перезапуск |
| `STAFF_REMEMBER_ME_KEY` | `.env` (было: дефолт `change-me-in-production-please` — убран в рамках 6.3) | Spring Security remember-me cookie | Смена аннулирует все текущие remember-me cookie (не сессии) |
| Order access token | БД (`orders`), генерируется приложением, не оператором | Ссылки на скачивание PDF/статус заказа | Не ротируется вручную; выпускается на заказ |
| SSH-ключ на боевой сервер | `C:/Users/user/.ssh/water_tours_timeweb` (владелец), не в этом репозитории | Деплой на `root@5.23.49.99` | Ротация — решение владельца, вне кода приложения |
| WordPress admin-логин/пароль | WordPress DB (`water-tours-wordpress/`), не в этом репозитории | Владелец/админ сайта | Вне рамок задачи 6 — отдельная система |

## Приоритизированные находки (задача 6.4)

1. **Устранено в 6.3**: `application.yml` содержал рабочие дефолты `staff.password=staff12345` и `staff.remember-me-key=change-me-in-production-please` — если переменные окружения не заданы, приложение тихо стартовало с публично известными значениями. Теперь оба свойства обязательны (`${STAFF_PASSWORD}` без дефолта) — приложение не запустится без них.
2. **Устранено в 6.3**: не было защиты от подбора пароля staff-аккаунта — `LoginRateLimitFilter` + `LoginAttemptService` блокируют IP на 15 минут после 10 неудачных попыток входа за 15 минут.
3. Уже было в порядке (проверено, не менялось): `UsernameNotFoundException` не отличается от `BadCredentialsException` в стандартном `DaoAuthenticationProvider` — сообщение об ошибке входа не раскрывает, существует ли аккаунт. Секреты не логируются (`GlobalExceptionHandler` и сервисы не пишут в лог значения password/token/secret).
4. Actuator: только `/actuator/health` публичен, остальные эндпоинты (`env`, `metrics` и т.п.) требуют роль STAFF — проверено в `SecurityConfig`.
5. `.env` и `ops/disaster-recovery/.env` не отслеживаются git (см. `.gitignore`) — секреты не попадают в историю репозитория.
6. **Не устранено, вне рамок 6 без решения владельца**: SSH-ключ и firewall боевого сервера не проверялись (нужен отдельный доступ и решение владельца при изменении).

## Владелец/потребители по системе

- **Spring (staff/admin)**: один встроенный аккаунт, роль `STAFF`, доступ к `/t/**`, `/staff/**`, погашению билетов, защищённым actuator-эндпоинтам.
- **PostgreSQL**: доступен только из docker-сети приложения (см. `compose.yml`), не публикуется наружу.
- **Redis**: аналогично, внутренняя сеть; используется для идемпотентности платежей.
- **WordPress admin**: отдельная система учёток, управляется штатными средствами WordPress — не менялась в рамках задачи 6.

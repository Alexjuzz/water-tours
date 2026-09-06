# Water-Tours — план доведения до продакшена

Ветка: `feature/production-readiness`. Backend: Spring Boot 3.5.6, Java 21, PostgreSQL, Redis, Maven (`mvnw`).

## Как пользоваться этим файлом1

Каждая задача ниже самодостаточна: указана проблема, конкретные файлы, точные шаги и критерий готовности.
Задачу можно скопировать целиком (от заголовка `### N.` до следующего `### N.`) и отдать в отдельную сессию
любой AI (в том числе бесплатной) одним сообщением примерно так:

> Вот тикет для проекта Water-Tours (Spring Boot, Java 21, репозиторий в папке `Water-Tours`).
> Внеси только указанные изменения, не трогай остальной код. Вот тикет: <вставить блок задачи>

После выполнения — проверить по разделу «Как проверить», прогнать `./mvnw -q -o compile`,
закоммитить с сообщением вида `fix: <номер> <короткое описание>` и отметить `[x]` здесь.

**Правила для исполнителя (в т.ч. для делегирования):**
- Не смешивать несколько задач в одном коммите.
- Не переименовывать/не рефакторить код, не относящийся к задаче.
- Если задача требует новых полей в БД — `hibernate.ddl-auto=update` сам создаст колонку на dev-окружении, миграции заводить не нужно (см. задачу №18).
- Секреты (`YOOKASSA_SHOP_ID`, `YOOKASSA_SECRET_KEY`, `DB_*`, `MAIL_*`) уже лежат в `.env` в корне (`D:\Water-tours-v2 example\.env`), подключаются через `dotenv-java` и `${...}` в `application.yml`.

---

## Критично (блокеры продакшена / безопасность)

### 1. Webhook оплаты не проверяет подлинность запроса

**Приоритет:** критично. **Файлы:** `src/main/java/ru/Water_Tours/ticket/service/PaymentService.java`, `src/main/java/ru/Water_Tours/ticket/ticketController/Web.java`, `src/main/java/ru/Water_Tours/ticket/model/Webhook/WebhookRequestDTO.java`.

**Проблема:** `POST /api/v1/payments/webhook` открыт (`permitAll` в `SecurityConfig`) и принимает тело
`{"paymentId": "...", "paymentStatus": "SUCCEEDED"}` — это придуманный формат, не формат ЮKassa, и
самое главное — сервис **безоговорочно доверяет** статусу из тела запроса. Любой внешний вызывающий,
зная `paymentId` (UUID, возвращается клиенту в ответе `startPayment`), может POST-запросом пометить
платёж как `SUCCEEDED` и бесплатно получить билеты.

Реальный формат уведомления ЮKassa (POST на ваш webhook URL):
```json
{
  "type": "notification",
  "event": "payment.succeeded",
  "object": { "id": "2d3...", "status": "succeeded", "amount": {...}, "metadata": {...}, ... }
}
```
Возможные `event`: `payment.waiting_for_capture`, `payment.succeeded`, `payment.canceled`, `refund.succeeded`.
ЮKassa не подписывает тело секретом — рекомендованная защита: **не доверять телу webhook**, а по
`object.id` синхронно запросить актуальный статус через `GET https://api.yookassa.ru/v3/payments/{payment_id}`
(Basic Auth: `shopId`:`secretKey`) и обновлять локальный статус по ответу API, а не по телу вебхука.
Дополнительно (не вместо, а как доп. слой) — сверять IP отправителя со списком ЮKassa:
`185.71.76.0/27, 185.71.77.0/27, 77.75.153.0/25, 77.75.156.11, 77.75.156.35, 77.75.154.128/25, 2a02:5180::/32`.

**Что сделать:**
1. Заменить `WebhookRequestDTO` на модель, соответствующую реальному формату: поля `type`, `event`,
   `object` (объект с `id` (String — это ID платежа в ЮKassa, `providerPaymentId`, НЕ ваш внутренний UUID) и `status` (String)).
2. В `PaymentService` добавить метод `fetchPaymentStatusFromProvider(String providerPaymentId)`, который
   делает `GET /v3/payments/{id}` с Basic Auth (`yookassa.shopId` / `yookassa.secretKey` из конфига) через
   `RestClient` (Spring 6.1+, уже есть в classpath через `spring-boot-starter-web`).
3. В `handleWebhook`: найти `Payment` по `providerPaymentId` (см. задачу №2 — это поле должно
   заполняться при создании платежа), запросить актуальный статус через шаг 2, и уже по нему (не по
   телу вебхука) выполнять текущую логику смены `PaymentStatus`/`OrderStatus`.
4. Endpoint должен всегда отвечать `200 OK` даже если платёж не найден локально (иначе ЮKassa будет
   ретраить), но не должен ничего менять в этом случае — только залогировать.
5. (Опционально, доп. слой) добавить проверку IP из списка выше через `HttpServletRequest.getRemoteAddr()`
   с учётом `X-Forwarded-For`, если за прокси/балансировщиком.

**Как проверить:** отправить `curl -X POST /api/v1/payments/webhook` с поддельным телом на несуществующий
`providerPaymentId` — не должно происходить никаких изменений в БД, ответ 200. С реальным тестовым платежом
ЮKassa (тестовый магазин, `YOOKASSA_SECRET_KEY=test_...` уже в `.env`) — статус заказа должен обновляться
только после подтверждения через `GET /v3/payments/{id}`, а не по значению из тела запроса.

---

### 2. Реальная интеграция с ЮKassa не реализована

**Приоритет:** критично. **Файлы:** `PaymentService.java`, `PaymentStartResponse.java`, `application.yml`.

**Проблема:** `startPayment` содержит `//TODO: generate paymentUrl` и всегда возвращает `null` вместо
ссылки на оплату. Оплатить заказ по-настоящему нельзя.

**Что сделать:**
1. Добавить бин `RestClient` для ЮKassa (base URL `https://api.yookassa.ru/v3`, Basic Auth interceptor
   с `yookassa.shopId`/`yookassa.secretKey`, которые уже читаются в `application.yml` как
   `${YOOKASSA_SHOP_ID}`/`${YOOKASSA_SECRET_KEY}`).
2. В `startPayment`, после создания локальной сущности `Payment` (см. текущий код — она уже создаётся
   со статусом `PENDING`), выполнить:
   ```
   POST https://api.yookassa.ru/v3/payments
   Headers: Idempotence-Key: <новый UUID на каждый вызов, например payment.getId()>
   Body: {
     "amount": { "value": "<order.totalAmount, формат "1234.00">", "currency": "RUB" },
     "confirmation": { "type": "redirect", "return_url": "<app.base-url>/api/v1/orders/{orderId}/pay/return" },
     "capture": true,
     "description": "Оплата заказа Water Tours " + orderId,
     "metadata": { "orderId": orderId.toString(), "paymentId": payment.getId().toString() }
   }
   ```
3. Из ответа сохранить `response.id` в `payment.setProviderPaymentId(...)` (для задачи №1) и обновить
   `payment.setStatus(...)` по маппингу статусов ЮKassa → внутренний enum (см. таблицу ниже).
4. Вернуть `confirmation.confirmation_url` из ответа как `paymentUrl` в `PaymentStartResponse` (сейчас
   там жёстко `null`).
5. Обернуть HTTP-вызов в try/catch: при ошибке — откатить транзакцию (не оставлять `Payment`/`Order`
   в `PENDING_PAYMENT`, если платёж в ЮKassa не создан), бросить понятное исключение.

**Маппинг статусов ЮKassa → `PaymentStatus`:** `pending`→`PENDING`, `waiting_for_capture`→`PENDING`,
`succeeded`→`SUCCEEDED`, `canceled`→`CANCELED`. (Текущий enum `PaymentStatus` содержит `NEW, PENDING,
SUCCEEDED, CANCELED` — этого достаточно, `waiting_for_capture` можно смэпить на `PENDING`, т.к. в
запросе используется `capture: true`, то есть эта промежуточная стадия по факту не должна встречаться.)

**Как проверить:** вызвать `POST /api/v1/orders/{orderId}/pay` для заказа в статусе `DRAFT` — в ответе
должен быть непустой `paymentUrl`, ведущий на реальную тестовую страницу оплаты ЮKassa; в БД у `Payment`
должен заполниться `providerPaymentId`.

---

### 3. XSS на странице проверки билета

**Приоритет:** критично. **Файлы:** `TicketService.java` (метод `renderCheckPage`), `CheckController.java`.

**Проблема:** HTML собирается конкатенацией строк (`StringBuilder`) и напрямую подставляет `code` и
`t.getPurchaseEmail()` без экранирования. `code` — это `@PathVariable String`, не валидируется как UUID,
поэтому можно передать `/t/<script>alert(1)</script>` и получить отражённый XSS (ветка "не найден").

**Что сделать (минимально достаточный вариант, без смены технологии):**
1. Добавить приватный статический метод `escapeHtml(String s)` в `TicketService`, который заменяет
   `& < > " '` на HTML-entities (`&amp; &lt; &gt; &quot; &#39;`), либо использовать
   `org.springframework.web.util.HtmlUtils.htmlEscape(String)` (уже доступен в classpath через
   `spring-boot-starter-web`, ничего дополнительно подключать не нужно).
2. Обернуть в `HtmlUtils.htmlEscape(...)` все места, где переменные (`code`, `t.getPurchaseEmail()`,
   `t.getTicketType()`, значения дат и статусов — они enum/Instant, но на всякий случай тоже эскейпить
   через `String.valueOf(...)`, экранирование безвредно) подставляются в HTML в `renderCheckPage`.
3. В `CheckController.check(...)` валидировать `code` в начале метода: если не соответствует
   формату UUID (`java.util.UUID.fromString(code)` в try/catch) — сразу возвращать страницу "не найден"
   без обращения к БД (не обязательно строго под UUID, если формат кода билета другой — тогда
   ограничить допустимые символы регуляркой `^[A-Za-z0-9-]{1,64}$` и отклонять всё остальное).
4. Параметр `error` (query param) в `CheckController` уже ограничен `switch` с фиксированными строками
   в `default` — это безопасно, не трогать.

**Как проверить:** `GET /t/%3Cscript%3Ealert(1)%3C/script%3E` должен вернуть страницу "билет не найден"
без исполняемого `<script>` в теле ответа (в исходном HTML должно быть `&lt;script&gt;`).

---

### 4. CSRF отключён при активной сессионной аутентификации персонала

**Приоритет:** критично. **Файл:** `SecurityConfig.java`.

**Проблема:** `.csrf(csrf -> csrf.disable())` отключает CSRF глобально, а `.formLogin(...)` использует
сессионные куки для персонала (роль `STAFF`). Значит, вредоносная страница может отправить форму
`POST /t/{code}/redeem` от имени залогиненного сотрудника (его браузер автоматически приложит cookie
сессии) — это классическая CSRF-атака, позволяющая гасить чужие билеты без ведома сотрудника.

**Что сделать:**
1. Убрать `.csrf(csrf -> csrf.disable())`.
2. Настроить CSRF так, чтобы он был выключен только там, где нет сессионной аутентификации (публичные
   API заказа/оплаты/вебхука — они и так не защищены сессией, значит CSRF-токен там физически негде
   взять клиенту без браузерной сессии, поэтому их стоит явно исключить):
   ```java
   .csrf(csrf -> csrf.ignoringRequestMatchers(
           "/api/v1/orders/**",
           "/api/v1/payments/webhook",
           "/api/v1/tickets/**"
   ))
   ```
   Оставить CSRF включённым для `/t/**` (форма редима билета персоналом — она отправляется из HTML,
   который отдаёт сам сервер, значит должен содержать `_csrf`-токен).
3. В `CheckController.check(...)` (метод, который рендерит HTML со страницей билета и формой редима)
   добавить в форму скрытое поле с CSRF-токеном. Токен доступен через
   `CsrfToken` — прокинуть его в `renderCheckPage`/`check` как параметр метода
   (`@ModelAttribute` или напрямую из `HttpServletRequest.getAttribute(CsrfToken.class.getName())`,
   т.к. страница рендерится не через Thymeleaf, а вручную строкой) и вставить в форму:
   ```html
   <input type="hidden" name="<токен.getParameterName()>" value="<токен.getToken()>">
   ```

**Как проверить:** страница `/t/{code}` (авторизованным STAFF) должна содержать скрытое поле с
CSRF-токеном в форме редима; `POST /t/{code}/redeem` без корректного CSRF-токена (например через
`curl` без токена, с валидной сессионной cookой) должен вернуть `403 Forbidden`.

---

### 5. `GlobalExceptionHandler` отдаёт клиенту сырое сообщение исключения

**Приоритет:** критично. **Файл:** `GlobalExceptionHandler.java`, метод `handleGeneralException`.

**Проблема:** на любую необработанную ошибку (`Exception.class`) клиенту в JSON уходит
`e.getMessage()` — это может раскрыть детали реализации (текст SQL-ошибки, имена классов, внутренние
пути), что облегчает атаку и не соответствует best practice для прод-API.

**Что сделать:**
1. Добавить в класс `org.slf4j.Logger` (`LoggerFactory.getLogger(GlobalExceptionHandler.class)`).
2. В `handleGeneralException`: залогировать полное исключение со стектрейсом
   (`log.error("Unhandled exception on {}", request.getRequestURI(), e);`).
3. В `ExceptionResponse`, отправляемый клиенту, вместо `e.getMessage()` подставить фиксированную
   строку, например `"Внутренняя ошибка сервера. Обратитесь в поддержку."` — без деталей исключения.
4. Остальные хендлеры (`NoSuchElementException`, `IllegalArgumentException`, `IllegalStateException`,
   `MethodArgumentNotValidException`) не трогать — там сообщения формируются осознанно и не содержат
   внутренних деталей (это бизнес-исключения с контролируемым текстом).

**Как проверить:** искусственно вызвать 500 (например, временно кинуть `NullPointerException` из
любого метода) — в HTTP-ответе не должно быть текста исключения, только общая фраза; в логах сервера —
полный стектрейс.

---

## Высокий приоритет (корректность / целостность данных)

### 6. Redis-профиль для идемпотентности не активирован по умолчанию

**Приоритет:** высокий. **Файлы:** `IdempotencyConfig.java`, `application.yml`, `compose.yml`.

**Проблема:** `IdempotencyConfig` регистрирует `RedisIdempotencyStore` только под Spring-профилем
`redis` (`@Profile("redis")`), а `InMemoryIdempotencyStore` — под `@Profile("!redis")`, то есть
дефолтно (профиль не активирован нигде). При этом Redis реально поднят в `compose.yml` и настроен в
`application.yml`, но не используется. In-memory стор теряет данные при рестарте приложения и не
работает при нескольких инстансах — повторный запрос с тем же `Idempotency-Key` после деплоя/рестарта
создаст дублирующий заказ.

**Что сделать:**
1. В `application.yml` (или в отдельном `application-prod.yml`, если уже сделана задача №18) добавить:
   ```yaml
   spring:
     profiles:
       active: redis
   ```
   Либо, если хочется управлять из окружения — задавать переменную `SPRING_PROFILES_ACTIVE=redis`
   при запуске (в `compose.yml`/Dockerfile/systemd-юните приложения).
2. Убедиться, что Redis поднимается раньше приложения (в `compose.yml` добавить `depends_on: - redis`
   к сервису приложения, если/когда он будет туда добавлен).

**Как проверить:** запустить приложение, создать заказ с `Idempotency-Key: test-123`, перезапустить
приложение, повторить тот же запрос с тем же ключом — должен вернуться тот же `orderId` (HTTP 200,
не 201), а не создан новый заказ.

---

### 7. Идемпотентность заказа не сохраняется в БД

**Приоритет:** высокий. **Файлы:** `OrderService.java`, `Order.java`.

**Проблема:** в `Order` есть колонка `idempotencyCode`, но `OrderService.createOrder` никогда её не
заполняет. Защита от дублей полностью полагается на внешний стор (Redis/in-memory) без резервного
уникального констрейнта на уровне БД.

**Что сделать (выбрать один вариант):**
- **Вариант А (рекомендуется, надёжнее):** прокинуть `idempotencyKey` в `OrderService.createOrder(...)`
  как параметр (сейчас он передаётся из `Web.create` в `Supplier`, но не доходит до `createOrder`),
  сохранять его в `newOrder.setIdempotencyCode(idempotenceKey)`, и добавить в `Order` аннотацию
  `@Column(name = "idempotency_code", unique = true)`. При повторном создании с тем же ключом —
  Redis-стор уже вернёт закешированный `orderId` раньше, чем дойдёт до `createOrder`, так что
  уникальный констрейнт сработает только как fallback, если кеш идемпотентности потерян
  (например, TTL истёк, а исходный запрос ещё выполнялся дольше `idempotency.value-ttl`).
- **Вариант Б (проще, если решили не усложнять):** удалить неиспользуемое поле `idempotencyCode` из
  `Order` целиком, задокументировав в комментарии, что идемпотентность обеспечивается только Redis
  (актуально после задачи №6).

**Как проверить (вариант А):** создать два заказа с одинаковым `Idempotency-Key`, но так, чтобы второй
запрос пришёл уже после истечения `idempotency.value-ttl` (в `application.yml` сейчас `10m` — для теста
можно временно поставить `5s`) — второй запрос должен получить `409 Conflict`/ошибку уникальности,
а не создать дублирующий заказ.

---

### 8. Заказ с нулевым количеством билетов проходит валидацию

**Приоритет:** высокий. **Файл:** `OrderService.java`, методы `createOrder`/`getOrderItems`.

**Проблема:** `createOrder` проверяет только что исходная карта `order.tickets()` не `null`/не пуста.
Внутри `getOrderItems` элементы с `qty == null || qty <= 0` тихо пропускаются (`continue`). Если
клиент пришлёт `{"tickets": {"ADULT": 0}}`, карта не пуста, но после фильтрации `orderItems` окажется
пустым — заказ на 0 билетов и сумму 0 успешно создастся.

**Что сделать:**
1. В `createOrder`, после вызова `getOrderItems(...)`, добавить проверку:
   ```java
   if (newOrder.getOrderItems().isEmpty()) {
       throw new IllegalArgumentException("Order must contain at least one ticket with positive quantity.");
   }
   ```
2. Убедиться, что это исключение (`IllegalArgumentException`) уже корректно обрабатывается
   `GlobalExceptionHandler` (да, обрабатывается, вернёт 409 — см. текущий `handleIllegalArgumentException`).

**Как проверить:** `POST /api/v1/orders` с телом `{"email":"a@a.com","tickets":{"ADULT":0}}` должен
вернуть ошибку (409), а не создавать заказ.

---

### 9. Нет проверки владения заказом на публичных эндпоинтах

**Приоритет:** высокий. **Файлы:** `Web.java`, `Order.java`, `OrderService.java`, `TicketEmailService.java`.

**Проблема:** `GET /api/v1/orders/{orderId}/tickets`, `GET .../tickets/pdf`, `POST .../tickets/email`
открыты всем (`permitAll`) и защищены только "секретностью" самого `orderId` (UUID, генерируется как
первичный ключ). Любой, кто узнал/подсмотрел `orderId` (лог, реферер, история браузера), может:
скачать PDF с QR-кодом чужого билета (кража билета) или бесконечно триггерить повторную отправку писем
на email покупателя (спам-рассылка через ваш сервер).

**Что сделать:**
1. Добавить в `Order` новое поле `accessToken` (`UUID`, `@Column(unique = true, nullable = false)`),
   генерируемое в `@PrePersist` через `UUID.randomUUID()` — отдельно от первичного ключа `id`.
2. В `OrderResponse` добавить это поле в ответ на `POST /api/v1/orders` (единственный момент, когда
   покупатель узнаёт свой `accessToken` — сразу после создания заказа).
3. В `Web.java` для эндпоинтов `getTickets`, `getTicketsPdf`, `sendTicketsEmail` добавить обязательный
   параметр `@RequestParam UUID accessToken` (или заголовок `X-Order-Access-Token`), и в начале каждого
   метода проверять `order.getAccessToken().equals(accessToken)`, иначе бросать
   `org.springframework.security.access.AccessDeniedException` (замэппить на 403 в `GlobalExceptionHandler`)
   до выполнения основной логики.
4. Для `sendTicketsEmail` дополнительно добавить простое ограничение частоты — не чаще одного письма в
   минуту на заказ (например, проверка `order.getTicketsEmailedAt() != null &&
   Duration.between(order.getTicketsEmailedAt(), Instant.now()).toMinutes() < 1` → 429/409).

**Как проверить:** запрос `GET /api/v1/orders/{orderId}/tickets/pdf` без `accessToken` или с неверным —
403; с правильным (полученным при создании заказа) — 200 с PDF.

---

### 10. Некорректный `TicketType`/битый JSON в теле заказа даёт 500 вместо 400

**Приоритет:** высокий. **Файл:** `GlobalExceptionHandler.java`.

**Проблема:** если клиент пришлёт JSON с ключом, не входящим в enum `TicketType` (например
`{"tickets":{"FOO":1}}`), Jackson бросает `HttpMessageNotReadableException` при десериализации тела
запроса — этот тип исключения не обработан явно, попадает в generic `@ExceptionHandler(Exception.class)`
и уходит клиенту как 500 (внутренняя ошибка), хотя на самом деле это ошибка **клиента** (400).

**Что сделать:**
1. Добавить в `GlobalExceptionHandler` новый метод:
   ```java
   @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
   public ResponseEntity<ExceptionResponse> handleNotReadable(HttpMessageNotReadableException e, HttpServletRequest request) {
       ExceptionResponse body = new ExceptionResponse(Instant.now(), HttpStatus.BAD_REQUEST.value(),
               "Malformed request body", "Request body is malformed or contains invalid values", request.getRequestURI());
       return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
   }
   ```
   (Сообщение клиенту — общее, без деталей парсинга Jackson, по той же логике, что и задача №5.)

**Как проверить:** `POST /api/v1/orders` с `{"email":"a@a.com","tickets":{"NOT_A_TYPE":1}}` должен
вернуть 400, не 500.

---

### 11. `StillProcessingException` не обработан в `GlobalExceptionHandler`

**Приоритет:** высокий. **Файл:** `GlobalExceptionHandler.java`, `StillProcessingException.java`.

**Проблема:** бросается из `IdempotencyService.resolve` при истечении времени ожидания
(`waitTimeout`), но специального хендлера нет — падает в generic `Exception` → 500, хотя семантически
это "запрос ещё обрабатывается, повторите позже" (клиент должен понимать, что нужно ретраить, а не
что сервер сломался).

**Что сделать:**
1. Добавить в `GlobalExceptionHandler`:
   ```java
   @ExceptionHandler(StillProcessingException.class)
   public ResponseEntity<ExceptionResponse> handleStillProcessing(StillProcessingException e, HttpServletRequest request) {
       ExceptionResponse body = new ExceptionResponse(Instant.now(), HttpStatus.CONFLICT.value(),
               "Still processing", "The original request is still being processed, retry shortly.", request.getRequestURI());
       return ResponseEntity.status(HttpStatus.CONFLICT).header("Retry-After", "2").body(body);
   }
   ```
   (409 с `Retry-After` — разумный выбор; альтернативно можно использовать 425 Too Early.)

**Как проверить:** сложно воспроизвести без нагрузочного теста; достаточно unit-теста, который
напрямую кидает `StillProcessingException` через `MockMvc`/тестовый контроллер и проверяет код ответа.

---

## Средний приоритет (production readiness / эксплуатация)

### 12. Секреты и захардкоженные учётные данные персонала

**Приоритет:** средний. **Файлы:** `.env` (в `D:\Water-tours-v2 example\.env`, вне git-репозитория
`Water-Tours`), `SecurityConfig.java`.

**Что сделать:**
1. Убедиться, что `.env` никогда не попадёт в git (сейчас он физически вне репозитория `Water-Tours` —
   при переносе/копировании проекта на новую машину проверить, что `.gitignore` содержит `.env`, если
   файл окажется внутри репозитория).
2. В `SecurityConfig`: вынести логин/пароль сотрудника (`staff`/`staff12345`) и ключ remember-me
   (`"water-tours-remember-me-key"`) в `application.yml`/переменные окружения (`@Value("${staff.username}")`
   и т.д.), сгенерировать новый случайный пароль и remember-me ключ перед реальным продакшен-релизом.
3. Задокументировать в README, какие переменные окружения обязательны для запуска в проде.

**Как проверить:** в коде `SecurityConfig` не должно остаться литералов пароля/ключа; приложение
запускается и логин под staff работает с паролем из переменной окружения.

---

### 13. Нет rate limiting

**Приоритет:** средний. **Файлы:** новый компонент-фильтр, `SecurityConfig.java`.

**Что сделать:**
1. Добавить зависимость `com.bucket4j:bucket4j_jdk17-core` (или использовать
   `RateLimiter` из Resilience4j, если предпочтительнее) в `pom.xml`.
2. Создать `OncePerRequestFilter`, ограничивающий по IP (или по IP+эндпоинту) частоту запросов к:
   `POST /api/v1/orders` (например, 5 запросов/минуту с одного IP), `POST /t/*/redeem` и `/login`
   (защита от брутфорса пароля персонала — например, 10 попыток/5 минут).
3. Зарегистрировать фильтр в `SecurityConfig` через `.addFilterBefore(...)`.
4. При превышении лимита — возвращать 429 Too Many Requests.

**Как проверить:** скрипт, отправляющий N+1 запросов подряд на защищённый эндпоинт — (N+1)-й должен
вернуть 429.

---

### 14. Нет структурированного логирования и мониторинга

**Приоритет:** средний. **Файлы:** `PaymentService.java`, `OrderService.java`, `TicketService.java`, `pom.xml`, `application.yml`.

**Что сделать:**
1. Добавить `spring-boot-starter-actuator` в `pom.xml`, включить `/actuator/health` и `/actuator/metrics`
   (в `application.yml`: `management.endpoints.web.exposure.include: health,metrics`), закрыть остальные
   actuator-эндпоинты от публичного доступа в `SecurityConfig`.
2. Добавить `Logger` (SLF4J) в ключевые сервисы и логировать бизнес-события на уровне INFO: заказ
   создан (`orderId`), платёж начат/подтверждён/отменён (`orderId`, `paymentId`, новый статус), билет
   погашен (`code`, `orderId`). Не логировать email/телефон покупателя в открытом виде без необходимости
   (частично маскировать, если того требует политика ПДн).
3. (Опционально, если будет инфраструктура) подключить Micrometer + Prometheus для метрик количества
   заказов/платежей по статусам.

**Как проверить:** `GET /actuator/health` возвращает `{"status":"UP"}`; в логах при создании заказа и
оплате появляются структурированные записи с `orderId`.

---

### 15. Health-check эндпоинт для оркестратора/балансировщика

**Приоритет:** средний. Пересекается с №14 — закрывается тем же `spring-boot-starter-actuator`
(`/actuator/health`). Отдельный самописный `Health.java`/`Ping.java` (были удалены в этом коммите)
заводить заново не нужно — Actuator покрывает эту потребность лучше (включая проверку соединения с БД
и Redis "из коробки" через `management.health.db.enabled`/`management.health.redis.enabled`).

**Что сделать:** после задачи №14 — убедиться, что `/actuator/health` показывает статус подключения к
PostgreSQL и Redis (`components.db.status`, `components.redis.status` в ответе), а не только "UP" от
самого приложения.

---

### 16. Нет тестов

**Приоритет:** средний-высокий (но объёмная задача — делить на подзадачи). **Файлы:** новая папка `src/test/java/...`.

**Что сделать (минимальный набор для старта):**
1. `IdempotencyServiceTest` (unit, с `InMemoryIdempotencyStore`): проверить, что (а) второй вызов
   `resolve` с тем же `scope+key` возвращает закешированное значение и `reused=true`, не вызывая
   `supplier` повторно; (б) конкурентные вызовы с одним и тем же ключом (`ExecutorService` с несколькими
   потоками) — `supplier.get()` вызывается ровно один раз.
2. `TicketServiceRedeemTest` (integration, `@SpringBootTest` + `@Transactional` + Testcontainers
   Postgres, либо `@DataJpaTest` с H2 на время теста): проверить, что повторный вызов
   `redeemByCode` на уже использованный билет бросает `IllegalArgumentException`; что конкурентный
   редим одного и того же билета (два потока одновременно) погашает его ровно один раз (проверка, что
   `findByCodeForUpdate` с пессимистичной блокировкой реально работает).
3. `PaymentServiceWebhookTest` (unit, с мокнутым `RestClient`/HTTP-клиентом ЮKassa из задачи №1-2):
   проверить, что вебхук с поддельным/недействительным статусом не меняет `OrderStatus`, пока
   `GET /v3/payments/{id}` (замоканный) не подтвердит `succeeded`.
4. `OrderServiceTest`: проверить кейс из задачи №8 (заказ с нулевым количеством билетов должен
   бросать исключение).

**Как проверить:** `./mvnw -q -o test` проходит зелёным.

---

### 17. Нет обработки просроченных заказов (`OrderStatus.EXPIRED`)

**Приоритет:** средний. **Файлы:** новый `@Component` с `@Scheduled`, `OrderRepository.java`.

**Проблема:** статус `EXPIRED` объявлен в enum, но никогда не устанавливается — заказы, зависшие в
`PENDING_PAYMENT` (клиент ушёл со страницы оплаты, не завершив её), остаются в этом статусе навсегда.

**Что сделать:**
1. В `OrderRepository` добавить метод:
   `List<Order> findAllByStatusAndCreatedAtBefore(OrderStatus status, Instant cutoff);`
2. Создать `@Component @EnableScheduling`-класс `OrderExpirationJob` с методом
   `@Scheduled(fixedDelay = 300000)` (раз в 5 минут), который находит все заказы в
   `PENDING_PAYMENT` старше N минут (вынести N в конфиг, например `order.expiration-timeout: 30m`)
   и переводит их в `OrderStatus.EXPIRED` через `OrderService.changeOrderStatus`.
3. Добавить `@EnableScheduling` на `WaterToursApplication` (или на отдельный `@Configuration`).

**Как проверить:** создать заказ, перевести в `PENDING_PAYMENT`, вручную (в тесте) откатить
`createdAt` в прошлое / уменьшить таймаут до нескольких секунд для теста — после срабатывания job
статус должен стать `EXPIRED`.

---

### 18. CI/CD и конфигурация окружений отсутствуют

**Приоритет:** средний. **Файлы:** `pom.xml`, новые `application-prod.yml`, `.github/workflows/*.yml` (если GitHub).

**Что сделать:**
1. Добавить зависимость Flyway (`org.flywaydb:flyway-core` + `flyway-database-postgresql`), перевести
   `spring.jpa.hibernate.ddl-auto` с `update` на `validate` в проде, перенести текущую (сгенерированную
   Hibernate) схему в первую Flyway-миграцию `V1__init.sql` (можно сгенерировать через
   `hibernate.hbm2ddl.auto=update` на dev-БД, затем `pg_dump --schema-only` и оформить как миграцию).
2. Создать `application-prod.yml` с прод-специфичными настройками (`show-sql: false`,
   `spring.profiles.active: prod,redis`, реальные (не test-) ключи ЮKassa через переменные окружения).
3. Добавить простой CI-пайплайн (GitHub Actions/GitLab CI — уточнить у пользователя, где будет
   размещаться репозиторий): шаги `mvnw -B verify` на каждый push/PR.

**Как проверить:** `./mvnw -q -o verify` зелёный локально; при поднятии профиля `prod` приложение не
пытается менять схему БД (`ddl-auto: validate` падает, если миграции не применены — это ожидаемо и
безопаснее, чем тихая автомиграция в проде).

---

## Низкий приоритет (чистка кода)

### 19. Убрать посторонний архив в исходниках
Удалить `src/main/java/ru/Water_Tours/Water_Tours.zip` (не отслеживается git, лежит прямо среди `.java`-файлов).

### 20. Удалить/доработать неиспользуемый `TicketInit`
`example/TicketInit.java`: `@Component` закомментирован, класс создаёт `Ticket` без обязательного
(`nullable = false`) поля `order` — если случайно включить аннотацию обратно, вставка в БД упадёт.
Либо удалить файл целиком, либо (если нужен dev-сид данных) доработать под `@Profile("dev")` с
созданием валидного `Order` перед `Ticket`.

### 21. Вынести константы цен/скидок из `TicketProperties` в конфигурацию
Сейчас цены и скидки — `static` поля с хардкодом в Java (`TicketProperties`), это отмечено `TODO` в
самом коде. Перенести в `application.yml` через `@ConfigurationProperties(prefix = "tickets")` —
структура вида `tickets.prices.adult`, `tickets.discounts.child` и т.д., инжектить бин вместо
статических полей.

### 22. Убрать мёртвый/закомментированный код
- Закомментированный метод `calculateTotalAmount(OrderRequestDTO)` в `OrderService.java`.
- Закомментированные зависимости в `pom.xml`: `spring-boot-docker-compose`, `spring-security-test`
  (либо раскомментировать и реально использовать, либо удалить).
- Закомментированная строка `if (ticketRepository.existsByOrderId(order.getId())) ...` в
  `TicketService.issueTickets`.

---

**Порядок работы:** сверху вниз, критичные — в первую очередь (№1 и №2 логически связаны — можно
делать вместе, так как №1 требует поле `providerPaymentId`, которое заполняется в №2). После каждой
задачи — отдельный коммит `fix: N <краткое описание>` и отметка `[x]` в чеклисте ниже.

## Чеклист

- [ ] 1. Проверка подлинности webhook оплаты
- [ ] 2. Реальная интеграция создания платежа с ЮKassa
- [ ] 3. Экранирование HTML на странице проверки билета (XSS)
- [ ] 4. Включить CSRF для сессионных (staff) эндпоинтов
- [ ] 5. Не отдавать текст исключения клиенту на 500-ошибках
- [ ] 6. Активировать Redis-профиль идемпотентности
- [ ] 7. Сохранять/использовать `idempotencyCode` заказа (или удалить поле)
- [ ] 8. Запрет заказа с нулевым количеством билетов
- [ ] 9. Токен доступа к заказу для публичных эндпоинтов
- [ ] 10. 400 вместо 500 на некорректный JSON/enum в теле запроса
- [ ] 11. Обработка `StillProcessingException`
- [ ] 12. Секреты и учётные данные персонала — вынести из кода
- [ ] 13. Rate limiting на создание заказа/редим/логин
- [ ] 14. Логирование бизнес-событий + Actuator
- [ ] 15. Health-check через Actuator (проверка БД/Redis)
- [ ] 16. Базовый набор unit/integration тестов
- [ ] 17. Job истечения заказов (`EXPIRED`)
- [ ] 18. Flyway-миграции + прод-профиль + CI
- [ ] 19. Удалить посторонний zip-архив
- [ ] 20. Удалить/доработать `TicketInit`
- [ ] 21. Цены/скидки — в конфигурацию
- [ ] 22. Удалить мёртвый закомментированный код

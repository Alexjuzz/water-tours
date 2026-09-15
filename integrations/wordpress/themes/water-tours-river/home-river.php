<?php if (!defined('ABSPATH')) { exit; } ?>
<!doctype html>
<html <?php language_attributes(); ?>>
<head><meta charset="<?php bloginfo('charset'); ?>"><meta name="viewport" content="width=device-width, initial-scale=1">
<link rel="icon" type="image/svg+xml" href="<?php echo esc_url(get_template_directory_uri()); ?>/assets/favicon.svg">
<?php wp_head(); ?></head>
<body <?php body_class('river-theme'); ?>><?php wp_body_open(); ?>
<a class="skip" href="#main">Перейти к содержимому</a>
<header class="site-header"><div class="nav-shell">
<a class="brand" href="#top" aria-label="Water Tours — на главную"><span class="brand-mark" aria-hidden="true">≈</span>water tours<span class="brand-place">САНКТ-ПЕТЕРБУРГ</span></a>
<button class="menu-toggle" aria-expanded="false" aria-controls="river-nav">Меню <span aria-hidden="true">☰</span></button>
<nav id="river-nav" aria-label="Основная навигация"><a href="#experience">О прогулке</a><a href="#how">Как это работает</a><a href="#faq">Вопросы</a><a class="btn btn-route" href="#boat">Свой маршрут</a><a class="btn btn-ticket" href="#tickets">Выбрать билет <span aria-hidden="true">↗</span></a></nav>
</div></header>
<main id="main">
<section class="hero" id="top" aria-labelledby="hero-title">
<img class="hero-photo" src="<?php echo esc_url(get_template_directory_uri()); ?>/assets/img/hero-canal.jpg" srcset="<?php echo esc_attr(wt_river_image_srcset('hero-canal.jpg', array(640, 960, 1200))); ?>" sizes="100vw" alt="Канал и вечерний Петербург" width="1400" height="936" fetchpriority="high" decoding="async">
<div class="hero-shade"></div><div class="hero-content wrap"><p class="eyebrow">ПЕТЕРБУРГ. ВОДА. ВЫ.</p><h1 id="hero-title">Город знакомый.<br><em>Впечатления — новые.</em></h1><p class="hero-lead">Оставьте суету на берегу.<br>Увидьте Петербург с другой стороны.</p><div class="hero-actions"><a class="btn btn-ticket" href="#tickets">Выбрать билет <span aria-hidden="true">↗</span></a><a class="btn btn-route" href="#boat">Свой маршрут</a></div><div class="hero-bottom"><span>Речные прогулки<br>и катер для своей компании</span><a href="#experience">Ближе к воде <span aria-hidden="true">↓</span></a></div></div>
</section>
<div class="facts wrap" aria-label="Как действует билет"><div><strong>72 часа</strong><span>на прогулку после оплаты</span></div><div><strong>PDF с QR-кодом</strong><span>билет всегда под рукой</span></div><div><strong>Ваш выбор</strong><span>прогулка или свой катер</span></div></div>
<section class="experience wrap section" id="experience"><div><p class="eyebrow">ДРУГОЙ РАКУРС</p><h2>Чуть ближе<br><em>к Петербургу.</em></h2></div><div class="experience-copy"><p class="large">Отражения фасадов. Простор воды.<br>Город, на который есть время.</p><p>Выберите прогулку для себя или арендуйте катер для своей компании. Билет действует три дня с момента подтверждённой оплаты — найдите время для своего Петербурга.</p></div></section>
<section class="booking-section" id="tickets"><div class="wrap"><div class="section-heading"><div><p class="eyebrow">ДВА СПОСОБА БЫТЬ НА ВОДЕ</p><h2>Как пройдёт<br><em>ваша прогулка?</em></h2></div><p>Один город.<br>Разное настроение.</p></div><div class="booking-grid">
<article class="booking-card ticket-card"><div class="card-topline"><span>01 / РЕЧНАЯ ПРОГУЛКА</span><span class="pill">Для себя и близких</span></div><h3>Ваш билет<br>на маленькое путешествие.</h3><p>Взрослый, детский или льготный — выберите состав компании в форме заказа.</p><ul class="features"><li>Электронный билет с QR-кодом</li><li>Один проход в течение 72 часов</li><li>Без закрепления рейса и места</li></ul><div class="booking-bottom"><?php wt_river_booking('ticket'); ?><p class="fine">Актуальная стоимость — в форме заказа.</p></div></article>
<article class="booking-card boat-card" id="boat"><div class="card-topline"><span>02 / ЧАСТНЫЙ КАТЕР</span><span class="pill">До 6 гостей</span></div><h3>Своя компания.<br>Свой маршрут.</h3><p>Катер целиком для вас. Предложите маршрут или попросите нас помочь с выбором.</p><div class="duration-options" aria-label="Варианты продолжительности"><span>30 мин</span><span>60 мин</span><span>90 мин</span><span>120 мин</span></div><p class="boat-detail">Время выхода и маршрут согласуются отдельно.</p><div class="booking-bottom"><?php wt_river_booking('boat'); ?><p class="fine">Один заказ и один QR-билет на компанию.</p></div></article>
</div></div></section>
<section class="water-moment" aria-label="Вечерний Петербург"><img src="<?php echo esc_url(get_template_directory_uri()); ?>/assets/img/evening-boat.jpg" srcset="<?php echo esc_attr(wt_river_image_srcset('evening-boat.jpg', array(640))); ?>" sizes="100vw" alt="Прогулочные суда на канале в вечернем Петербурге" width="1000" height="668" loading="lazy" decoding="async"><div class="wrap"><p class="eyebrow">ВРЕМЯ ЗАМЕДЛИТЬСЯ</p><p class="moment-title">Лучшие планы —<br><em>ближе к воде.</em></p><span class="image-caption">Виды Петербурга. Конкретный маршрут уточняйте перед прогулкой.</span></div></section>
<section class="wrap section how" id="how"><div class="section-heading"><div><p class="eyebrow">ОТ ПЛАНА ДО ПРОГУЛКИ</p><h2>Всего три шага.</h2></div></div><div class="steps"><article><span class="step-number">01</span><h3>Выберите формат</h3><p>Билеты на прогулку или катер для своей компании. Проверьте состав заказа и сумму.</p></article><article><span class="step-number">02</span><h3>Сохраните билет</h3><p>После подтверждения оплаты скачайте PDF с QR-кодом. Срок действия указан в билете.</p></article><article><span class="step-number">03</span><h3>Встретимся у воды</h3><p>Уточните причал и расписание. При посадке покажите QR-код сотруднику.</p></article></div></section>
<section class="faq-section" id="faq"><div class="wrap faq-layout"><div><p class="eyebrow">ПЕРЕД ПРОГУЛКОЙ</p><h2>Хороший вопрос.</h2><p>Самое важное<br>о вашем билете.</p><p class="ask-line" id="wt-ask-line" hidden><span>Не нашли ответ?</span> <button type="button" class="btn btn-ask" id="wt-ask-open" aria-haspopup="dialog">Задать вопрос</button></p></div><div class="questions">
<details><summary>Когда начинают действовать 72 часа?</summary><p>С момента подтверждения оплаты. Точные даты начала и окончания действия указаны в билете. Неоплаченный заказ не запускает срок действия.</p></details>
<details><summary>Нужно ли выбирать рейс заранее?</summary><p>Билет не закрепляет рейс или место. Выберите отправление по расписанию в пределах срока действия. Наличие мест, причал и условия посадки уточняйте перед прогулкой.</p></details>
<details><summary>Где найти билет после оплаты?</summary><p>После подтверждения оплаты и выпуска билета в окне заказа появится кнопка скачивания PDF. Билет также отправляется на указанную почту. Если письма нет, проверьте папку «Спам».</p></details>
<details><summary>Можно ли воспользоваться билетом повторно?</summary><p>Нет. Билет даёт право на один проход. После погашения QR-кода он становится недействительным.</p></details>
<details><summary>Как выбрать маршрут для катера?</summary><p>В форме аренды выберите «Предложу свой» или «Помогите выбрать». Маршрут и время выхода согласуются отдельно.</p></details>
</div></div></section>
<section class="closing wrap"><p class="eyebrow">УВИДИМСЯ НА ВОДЕ</p><h2>Добавьте Петербургу<br><em>новых впечатлений.</em></h2><a class="btn btn-ticket" href="#tickets">Выбрать билет <span aria-hidden="true">↗</span></a><a class="btn btn-route" href="#boat">Свой маршрут</a></section>
<div class="wt-ask-modal" id="wt-ask-modal" hidden>
<div class="wt-ask-backdrop" data-wt-ask-close></div>
<div class="wt-ask-panel" role="dialog" aria-modal="true" aria-labelledby="wt-ask-title" aria-describedby="wt-ask-note">
<button type="button" class="wt-ask-close" id="wt-ask-close" aria-label="Закрыть">&times;</button>
<h2 id="wt-ask-title">Задать вопрос</h2>
<p id="wt-ask-note" class="wt-ask-note">Ваш вопрос и указанный контакт будут отправлены в поддержку через Telegram. Ответ придёт на этот контакт. Не указывайте пароли, коды из СМС и данные банковской карты.</p>
<form id="wt-ask-form" novalidate>
<div class="wt-ask-field"><label for="wt-ask-message">Ваш вопрос</label>
<textarea id="wt-ask-message" name="message" rows="5" maxlength="2000" required aria-describedby="wt-ask-message-hint"></textarea>
<span class="wt-ask-hint" id="wt-ask-message-hint">От 10 до 2000 символов.</span></div>
<div class="wt-ask-field"><label for="wt-ask-contact">Email или телефон для ответа</label>
<input id="wt-ask-contact" name="contact" type="text" maxlength="160" required autocomplete="off" aria-describedby="wt-ask-contact-hint">
<span class="wt-ask-hint" id="wt-ask-contact-hint">Укажите целиком — иначе ответить будет некуда.</span></div>
<div class="wt-ask-field"><label for="wt-ask-order">Номер заказа <span class="wt-ask-optional">(если есть)</span></label>
<input id="wt-ask-order" name="orderReference" type="text" maxlength="64" autocomplete="off"></div>
<div class="wt-ask-hp" aria-hidden="true"><label for="wt-ask-website">Оставьте это поле пустым</label><input id="wt-ask-website" name="website" type="text" tabindex="-1" autocomplete="off"></div>
<button type="submit" class="wt-ask-submit" id="wt-ask-submit">Отправить вопрос</button>
</form>
<p class="wt-ask-status" id="wt-ask-status" role="status" aria-live="polite"></p>
</div></div>
</main><footer class="site-footer wrap"><a class="brand" href="#top"><span class="brand-mark" aria-hidden="true">≈</span>water tours</a><p>Речные прогулки в Петербурге</p><a href="#faq">Правила билета</a></footer>
<?php wp_footer(); ?></body></html>

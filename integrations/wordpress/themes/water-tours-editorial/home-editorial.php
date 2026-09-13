<?php if (!defined('ABSPATH')) { exit; } ?>
<!DOCTYPE html><html <?php language_attributes(); ?>>
<head><meta charset="<?php bloginfo('charset'); ?>"><meta name="viewport" content="width=device-width, initial-scale=1"><?php wp_head(); ?></head>
<body <?php body_class('editorial'); ?>><?php wp_body_open(); ?>
<a class="skip-link" href="#main">Перейти к содержимому</a>

<header class="masthead" id="top">
  <div class="masthead-top">
    <a class="brand" href="#top">≈ water&nbsp;tours</a>
    <p class="dateline">Санкт-Петербург · речные прогулки · электронный билет</p>
  </div>
  <nav class="masthead-nav" aria-label="Основная навигация">
    <button id="navToggle" type="button" aria-expanded="false" aria-controls="navLinks" aria-label="Открыть меню">Разделы</button>
    <ul id="navLinks">
      <li><a href="#about">О прогулке</a></li>
      <li><a href="#rules">Как действует билет</a></li>
      <li><a href="#boat">Частная прогулка</a></li>
      <li><a href="#faq">Вопросы</a></li>
      <li><a class="ed-button ed-button-sand" href="#buy">Выбрать билеты</a></li>
    </ul>
  </nav>
</header>

<main id="main">

  <section class="ed-hero">
    <div class="ed-hero-text">
      <p class="kicker">Раздел I · Прогулка</p>
      <h1>Город, прочитанный<br><em>с воды.</em></h1>
      <p class="standfirst">Набережные выглядят иначе, когда смотришь на них с реки. Возьмите билет на прогулку и оставьте привычный маршрут на берегу.</p>
      <p class="ed-cta-row">
        <a class="ed-button ed-button-sand" href="#buy">Выбрать билеты</a>
        <a class="ed-button ed-button-lime" href="#boat">Свой маршрут</a>
      </p>
      <dl class="ed-meta">
        <div><dt>Срок действия</dt><dd>72 часа после подтверждения оплаты</dd></div>
        <div><dt>Проход</dt><dd>Один по одному билету</dd></div>
        <div><dt>Формат</dt><dd>PDF с QR-кодом</dd></div>
      </dl>
    </div>
    <figure class="ed-hero-figure">
      <?php water_tours_editorial_image('hero-canal', 'Канал Санкт-Петербурга на рассвете, вид с воды', array('loading' => 'eager', 'width' => 1400, 'height' => 936)); ?>
      <figcaption>Фотография города с воды</figcaption>
    </figure>
  </section>

  <section class="ed-lede">
    <div class="ed-shell">
      <p class="ed-pullquote">Прогулка по реке — это не поездка из точки в точку, а повод замедлиться и посмотреть по сторонам.</p>
    </div>
  </section>

  <section class="ed-article ed-section" id="about">
    <div class="ed-shell ed-article-grid">
      <div class="ed-article-head">
        <p class="kicker">Раздел II · О прогулке</p>
        <h2>Меньше суеты,<br>больше вида</h2>
      </div>
      <div class="ed-article-body">
        <p class="ed-drop">Речная прогулка — повод замедлиться, побыть вместе и увидеть знакомый город иначе. Вы выбираете отправление сами, в пределах срока действия билета.</p>
        <p>Билет действует три дня с момента подтверждённой оплаты. Конкретный рейс и место за билетом не закрепляются: расписание, причал и условия посадки уточняйте перед прогулкой.</p>
      </div>
      <aside class="ed-margin-note">
        <?php water_tours_editorial_image('canal-boat', 'Прогулочный теплоход в канале Санкт-Петербурга', array('width' => 800, 'height' => 502)); ?>
        <p>Маршрут, причал и расписание пока уточняются. Срок действия билета не означает гарантированную посадку на любой рейс.</p>
      </aside>
    </div>
  </section>

  <section class="ed-steps ed-section" id="rules">
    <div class="ed-shell">
      <p class="kicker">Раздел III · Как действует билет</p>
      <h2>От выбора до посадки</h2>
      <ol class="ed-step-list">
        <li>
          <span class="ed-numeral">01</span>
          <div><h3>Выберите билеты</h3><p>Укажите количество взрослых, детских и льготных билетов. Проверьте итоговую сумму перед оформлением.</p></div>
        </li>
        <li>
          <span class="ed-numeral">02</span>
          <div><h3>Сохраните PDF</h3><p>После подтверждения оплаты скачайте билет с QR-кодом. Точное время окончания действия указано в билете.</p></div>
        </li>
        <li>
          <span class="ed-numeral">03</span>
          <div><h3>Покажите QR-код</h3><p>Предъявите билет сотруднику при посадке. Один билет даёт один проход и после погашения становится недействительным.</p></div>
        </li>
      </ol>
    </div>
  </section>

  <section class="ed-tickets ed-section" id="buy">
    <div class="ed-shell ed-tickets-grid">
      <div class="ed-tickets-copy">
        <p class="kicker">Раздел IV · Билеты</p>
        <h2>Билеты на речную прогулку</h2>
        <p>Купите билет на теплоход в Санкт-Петербурге онлайн — выберите состав компании и проверьте стоимость в форме заказа.</p>
        <p class="ed-validity"><span class="ed-validity-figure">72</span><span class="ed-validity-text">часа действия<br>после подтверждения оплаты</span></p>
        <figure class="ed-tickets-figure">
          <?php water_tours_editorial_image('evening-boat', 'Прогулочное судно на вечернем канале', array('width' => 1000, 'height' => 668)); ?>
        </figure>
      </div>
      <div class="ed-card">
        <p class="ed-card-label">Электронный билет</p>
        <h3>Одна прогулка.<br>Три дня на выбор.</h3>
        <ul class="ed-card-list">
          <li>PDF с QR-кодом</li>
          <li>Один проход на прогулку</li>
          <li>Без закрепления рейса и места</li>
        </ul>
        <?php if (shortcode_exists('water_tours_buy')) { echo do_shortcode('[water_tours_buy]'); } else { echo '<p class="ed-note">Оформление временно недоступно.</p>'; } ?>
        <p class="ed-note">Перед покупкой уточните маршрут, причал, расписание и условия выбранной категории билета.</p>
      </div>
    </div>
  </section>

  <section class="ed-band" aria-hidden="true">
    <?php water_tours_editorial_image('bridge-night', '', array('class' => 'ed-band-image', 'width' => 1920, 'height' => 960)); ?>
  </section>

  <section class="ed-boat ed-section" id="boat">
    <div class="ed-shell ed-boat-grid">
      <figure class="ed-boat-figure">
        <?php water_tours_editorial_image('deck', 'Палуба прогулочного катера с видом на воду', array('width' => 1500, 'height' => 1000)); ?>
        <figcaption>Фотография прогулочного судна</figcaption>
      </figure>
      <div class="ed-boat-copy">
        <p class="kicker">Раздел V · Частная прогулка</p>
        <h2>Свой маршрут.<br><em>Свой ритм.</em></h2>
        <p class="ed-boat-lead">Арендуйте катер целиком для компании до шести гостей. Выберите продолжительность и предложите свой маршрут или попросите нас помочь.</p>
        <ul class="ed-boat-facts">
          <li>до 6 гостей</li>
          <li>один QR-билет</li>
          <li>72 часа после оплаты</li>
        </ul>
        <div class="ed-card ed-card-dark">
          <p class="ed-card-label">Аренда катера</p>
          <?php $ed_prices = water_tours_editorial_boat_prices(); ?>
          <?php if ($ed_prices) : ?>
            <table class="ed-price-table">
              <caption class="screen-reader-text">Стоимость аренды по продолжительности</caption>
              <tbody>
              <?php foreach ($ed_prices as $ed_minutes => $ed_price) : ?>
                <tr><th scope="row"><?php echo esc_html($ed_minutes); ?> минут</th><td><?php echo esc_html(water_tours_editorial_format_price($ed_price)); ?> ₽</td></tr>
              <?php endforeach; ?>
              </tbody>
            </table>
          <?php else : ?>
            <p class="ed-note">Стоимость аренды показывается в форме заказа.</p>
          <?php endif; ?>
          <?php if (shortcode_exists('water_tours_boat')) { echo do_shortcode('[water_tours_boat]'); } else { echo '<p class="ed-note">Оформление аренды скоро появится.</p>'; } ?>
          <p class="ed-note">Один заказ оформляется на всю компанию. Время выхода и маршрут согласуются отдельно.</p>
        </div>
      </div>
    </div>
  </section>

  <section class="ed-faq ed-section" id="faq">
    <div class="ed-shell ed-faq-grid">
      <div>
        <p class="kicker">Раздел VI · Вопросы</p>
        <h2>Перед прогулкой</h2>
      </div>
      <div class="ed-faq-list">
        <?php foreach (water_tours_editorial_faq() as $ed_question => $ed_answer) : ?>
          <details><summary><?php echo esc_html($ed_question); ?></summary><p><?php echo esc_html($ed_answer); ?></p></details>
        <?php endforeach; ?>
      </div>
    </div>
  </section>

  <section class="ed-close ed-section">
    <div class="ed-shell">
      <h2>Встретимся у воды</h2>
      <p class="ed-cta-row">
        <a class="ed-button ed-button-sand" href="#buy">Выбрать билеты</a>
        <a class="ed-button ed-button-lime" href="#boat">Свой маршрут</a>
      </p>
    </div>
  </section>

</main>

<footer class="ed-colophon">
  <div class="ed-shell ed-colophon-grid">
    <a class="brand" href="#top">≈ water&nbsp;tours</a>
    <p>Речные прогулки. Электронные билеты.<br>Билет действует 72 часа с момента подтверждённой оплаты.</p>
    <ul>
      <li><a href="#rules">Правила билета</a></li>
      <li><a href="#faq">Вопросы</a></li>
      <?php if (get_privacy_policy_url()) : ?><li><a href="<?php echo esc_url(get_privacy_policy_url()); ?>">Конфиденциальность</a></li><?php endif; ?>
    </ul>
  </div>
</footer>
<?php wp_footer(); ?></body></html>

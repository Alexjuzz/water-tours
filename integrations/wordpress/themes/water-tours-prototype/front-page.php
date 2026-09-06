<?php
if (!defined('ABSPATH')) {
    exit;
}
?>
<!DOCTYPE html>
<html <?php language_attributes(); ?>>
<head>
<meta charset="<?php bloginfo('charset'); ?>">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title><?php bloginfo('name'); ?></title>
<?php wp_head(); ?>
</head>
<body <?php body_class(); ?>>
<a class="skip-link" href="#main">Перейти к основному содержимому</a>

<header class="site">
  <nav class="nav-bar" aria-label="Основная навигация">
    <a class="brand" href="#top">
      <svg width="34" height="34" viewBox="0 0 34 34" aria-hidden="true">
        <circle cx="17" cy="17" r="16" fill="#1f8a83"/>
        <path d="M6 20c3-3 6 2 9 0s6-3 9 0" stroke="#f6efdf" stroke-width="2" fill="none" stroke-linecap="round"/>
        <path d="M6 24c3-3 6 2 9 0s6-3 9 0" stroke="#f6efdf" stroke-width="2" fill="none" stroke-linecap="round" opacity=".6"/>
        <path d="M17 8l6 9h-12z" fill="#f6efdf"/>
      </svg>
      Water Tours
    </a>
    <button class="nav-toggle" id="navToggle" aria-expanded="false" aria-controls="navLinks" aria-label="Открыть меню">
      <svg width="20" height="20" viewBox="0 0 20 20" aria-hidden="true">
        <path d="M2 5h16M2 10h16M2 15h16" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
      </svg>
    </button>
    <ul class="nav-links" id="navLinks">
      <li><a href="#about">О прогулке</a></li>
      <li><a href="#rules">Правила билета</a></li>
      <li><a href="#buy">Билеты</a></li>
      <li><a href="#faq">Вопросы</a></li>
      <li><a class="nav-cta" href="#buy">Купить билет</a></li>
    </ul>
  </nav>
</header>

<main id="main">
  <section class="hero" id="top">
    <div class="hero-inner">
      <h1>Речная прогулка по воде — с видом на город</h1>
      <p class="lead">Билет действует 72 часа с момента подтверждённой оплаты и даёт один проход — рейсы и места не закрепляются, вы выбираете удобное отправление на месте.</p>
      <div class="hero-ctas">
        <a class="btn btn-primary" href="#buy">Выбрать билеты</a>
        <a class="btn btn-ghost" href="#rules">Как это работает</a>
      </div>
    </div>
    <svg class="river-art" viewBox="0 0 1200 220" preserveAspectRatio="none" aria-hidden="true">
      <path d="M0 120 C 200 90, 300 150, 500 120 S 800 90, 1000 130 S 1200 100 1200 100 L1200 220 L0 220 Z" fill="#123a5c"/>
      <path d="M0 150 C 220 120, 320 180, 520 150 S 820 120, 1020 160 S 1200 140 1200 140 L1200 220 L0 220 Z" fill="#1f8a83" opacity="0.85"/>
      <g class="fade-boat" transform="translate(620,95)">
        <path d="M0 30 L100 30 L85 55 L15 55 Z" fill="#f6efdf"/>
        <rect x="30" y="8" width="40" height="24" rx="3" fill="#f6efdf"/>
        <rect x="42" y="-14" width="4" height="24" fill="#f6efdf"/>
        <path d="M46 -14 L70 -2 L46 8 Z" fill="#2ba89f"/>
      </g>
      <circle cx="150" cy="40" r="18" fill="#f6efdf" opacity="0.9"/>
    </svg>
  </section>

  <section id="about">
    <div class="container">
      <div class="section-title">
        <h2>О прогулке</h2>
        <p class="sub">Спокойный маршрут по воде, свежий воздух и виды на набережные — без привязки к конкретному времени отправления.</p>
      </div>
      <div class="cards">
        <div class="card">
          <svg width="40" height="40" viewBox="0 0 40 40" aria-hidden="true">
            <circle cx="20" cy="20" r="19" fill="none" stroke="#1f8a83" stroke-width="2"/>
            <path d="M12 22c2-2 4 1 6 0s4-2 6 0 4 1 6 0" stroke="#1f8a83" stroke-width="2" fill="none" stroke-linecap="round"/>
          </svg>
          <h3>Маршрут по воде</h3>
          <p>Маршрут, причал и продолжительность прогулки будут опубликованы перед запуском.</p>
        </div>
        <div class="card">
          <svg width="40" height="40" viewBox="0 0 40 40" aria-hidden="true">
            <circle cx="20" cy="20" r="19" fill="none" stroke="#1f8a83" stroke-width="2"/>
            <path d="M20 10v10l7 4" stroke="#1f8a83" stroke-width="2" fill="none" stroke-linecap="round"/>
          </svg>
          <h3>Свободное время посещения</h3>
          <p>Билет не привязан к конкретному рейсу или месту — время посещения выбирается в пределах срока действия и расписания прогулок.</p>
        </div>
        <div class="card">
          <svg width="40" height="40" viewBox="0 0 40 40" aria-hidden="true">
            <circle cx="20" cy="20" r="19" fill="none" stroke="#1f8a83" stroke-width="2"/>
            <path d="M13 20l5 5 9-10" stroke="#1f8a83" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          <h3>Билет с QR-кодом</h3>
          <p>После оплаты билет с QR-кодом приходит на почту в PDF — его показывают при проходе.</p>
        </div>
      </div>
    </div>
  </section>

  <section id="rules" class="rules-section">
    <div class="container">
      <div class="section-title">
        <h2>Правила билета</h2>
        <p class="sub">Условия одинаковы для всех типов билетов.</p>
      </div>
      <div class="rules-grid">
        <div class="rule-card">
          <h3>⏱ 72 часа с момента оплаты</h3>
          <p>Отсчёт начинается не с покупки, а с момента, когда оплата подтверждена. Билет действует ровно 72 часа с этой отметки — точное время окончания указывается в билете.</p>
        </div>
        <div class="rule-card">
          <h3>🎟 Один проход</h3>
          <p>Билет даёт право на одно посещение. После погашения на входе он становится недействительным — повторно пройти по нему нельзя.</p>
        </div>
        <div class="rule-card">
          <h3>🚫 Без брони мест и рейсов</h3>
          <p>Билет не закрепляет конкретный рейс, время отправления или место на борту. Вы выбираете удобный момент в пределах срока действия.</p>
        </div>
      </div>
    </div>
  </section>

  <section id="buy" class="buy">
    <div class="container">
      <div class="section-title">
        <h2>Выбор билетов</h2>
        <p class="sub">Цены соответствуют текущим тарифам сервиса.</p>
      </div>
      <div class="buy-wrap">
        <div class="ticket-panel">
          <fieldset>
            <legend>Типы билетов</legend>

            <div class="ticket-row">
              <div class="ticket-info">
                <strong>Взрослый</strong>
                <span>1500 ₽</span>
              </div>
            </div>

            <div class="ticket-row">
              <div class="ticket-info">
                <strong>Детский</strong>
                <span>800 ₽ — скидка 20%</span>
              </div>
            </div>

            <div class="ticket-row">
              <div class="ticket-info">
                <strong>Льготный</strong>
                <span>1020 ₽ — скидка 15%</span>
              </div>
            </div>
          </fieldset>

          <p class="buy-note">Билет действует 72 часа с момента подтверждённой оплаты и даёт один проход без брони рейса или места.</p>

          <div class="buy-cta">
            <?php if (shortcode_exists('water_tours_buy')) : ?>
              <?php echo do_shortcode('[water_tours_buy]'); ?>
            <?php else : ?>
              <p class="buy-note">Покупка билетов временно недоступна. Попробуйте позже.</p>
            <?php endif; ?>
          </div>
        </div>

        <div class="ticket-panel valid-panel">
          <h3>Что произойдёт после оплаты</h3>
          <ul>
            <li>Оплата подтверждается платёжным провайдером.</li>
            <li>Билет с QR-кодом автоматически формируется в PDF.</li>
            <li>Билет приходит на указанную почту с точным временем истечения (оплата + 72 часа).</li>
            <li>При проходе сотрудник сканирует QR — билет гасится один раз.</li>
          </ul>
        </div>
      </div>
    </div>
  </section>

  <section id="faq">
    <div class="container">
      <div class="section-title">
        <h2>Частые вопросы</h2>
      </div>
      <div class="faq-list">
        <details class="faq-item">
          <summary>С какого момента считаются 72 часа? <span class="icon">＋</span></summary>
          <p>Отсчёт начинается с момента, когда оплата подтверждена платёжной системой, а не с момента оформления заказа. Точное время окончания указывается в самом билете.</p>
        </details>
        <details class="faq-item">
          <summary>Нужно ли выбирать время отправления? <span class="icon">＋</span></summary>
          <p>Нет. Билет не закрепляет рейс или место — отправление выбирается по расписанию в пределах срока действия билета.</p>
        </details>
        <details class="faq-item">
          <summary>Можно ли использовать билет дважды? <span class="icon">＋</span></summary>
          <p>Нет. Билет даёт право на один проход. После погашения сотрудником на входе он становится недействительным.</p>
        </details>
        <details class="faq-item">
          <summary>Как я получу билет? <span class="icon">＋</span></summary>
          <p>После подтверждённой оплаты билет с QR-кодом придёт в PDF на указанную вами почту.</p>
        </details>
      </div>
    </div>
  </section>
</main>

<footer>
  <div class="container">
    <p>Water Tours — речные прогулки. Реальные тарифы, рейсы и способы связи будут добавлены на этапе запуска.</p>
  </div>
</footer>

<?php wp_footer(); ?>
</body>
</html>

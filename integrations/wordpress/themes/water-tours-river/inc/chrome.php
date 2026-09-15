<?php
/**
 * The document shell for pages that are not the landing page.
 *
 * `home-river.php` carries its own `<head>`, header and footer inline and is left exactly as it
 * is - it is one long hand-written page and every byte of it has been checked against a live
 * render. This file is not an extraction of that markup and is not meant to become one. It is a
 * second, much smaller shell, and it has to be separate for a reason that is not tidiness: the
 * landing page's navigation is a row of same-page anchors (`#tickets`, `#boat`, `#faq`). On any
 * other URL those anchors point at sections that are not on the page. The header here links to
 * the same places through `home_url()` instead, so the links work from wherever they are read.
 *
 * Everything below uses classes that already exist in `river.css`. No colour, font or spacing is
 * introduced here.
 *
 * @package water-tours-river
 */

if (!defined('ABSPATH')) { exit; }

/**
 * Opening markup: doctype through the start of `<main>`.
 *
 * `wp_head()` runs here, which is what gives these pages the same robots, canonical and title
 * handling as the front page - handled by core and by `inc/seo.php`, not repeated here.
 */
function wt_river_chrome_header() {
    $home = home_url('/');
    ?><!doctype html>
<html <?php language_attributes(); ?>>
<head><meta charset="<?php bloginfo('charset'); ?>"><meta name="viewport" content="width=device-width, initial-scale=1">
<link rel="icon" type="image/svg+xml" href="<?php echo esc_url(get_template_directory_uri()); ?>/assets/favicon.svg">
<?php wp_head(); ?></head>
<body <?php body_class('river-theme river-inner'); ?>><?php wp_body_open(); ?>
<a class="skip" href="#main">Перейти к содержимому</a>
<header class="site-header"><div class="nav-shell">
<a class="brand" href="<?php echo esc_url($home); ?>" aria-label="Water Tours — на главную"><span class="brand-mark" aria-hidden="true">≈</span>water tours<span class="brand-place">САНКТ-ПЕТЕРБУРГ</span></a>
<nav aria-label="Основная навигация"><a href="<?php echo esc_url($home . '#experience'); ?>">О прогулке</a><a href="<?php echo esc_url($home . '#faq'); ?>">Вопросы</a><a class="btn btn-route" href="<?php echo esc_url($home . '#boat'); ?>">Свой маршрут</a><a class="btn btn-ticket" href="<?php echo esc_url($home . '#tickets'); ?>">Выбрать билет <span aria-hidden="true">↗</span></a></nav>
</div></header>
<main id="main" class="inner-main wrap">
<?php
}

/**
 * Closing markup: the end of `<main>` through `</html>`.
 *
 * The footer is the landing page's, minus the `#faq` anchor, which does not exist here.
 */
function wt_river_chrome_footer() {
    $home = home_url('/');
    ?>
</main><footer class="site-footer wrap"><a class="brand" href="<?php echo esc_url($home); ?>"><span class="brand-mark" aria-hidden="true">≈</span>water tours</a><p>Речные прогулки в Петербурге</p><a href="<?php echo esc_url($home . '#faq'); ?>">Правила билета</a></footer>
<?php wp_footer(); ?></body></html>
<?php
}

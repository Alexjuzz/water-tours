<?php
/**
 * Not found.
 *
 * Previously a 404 answered with the whole landing page - the correct status code wrapped around
 * markup that claims to be the ticket page, which is confusing for a person and pointless for a
 * crawler. This says what happened and offers the two things the site actually sells.
 *
 * It stays `noindex` through `inc/seo.php`, which keeps `is_404()` in its floor list whatever
 * template renders it.
 *
 * @package water-tours-river
 */

if (!defined('ABSPATH')) { exit; }

wt_river_chrome_header();
?>
<article class="inner-article">
    <h1 class="inner-title">Страница не найдена</h1>
    <div class="inner-content">
        <p>Такой страницы на сайте нет — возможно, адрес изменился или в ссылке опечатка.</p>
        <p class="inner-actions">
            <a class="btn btn-ticket" href="<?php echo esc_url(home_url('/#tickets')); ?>">Выбрать билет <span aria-hidden="true">↗</span></a>
            <a class="btn btn-route" href="<?php echo esc_url(home_url('/#boat')); ?>">Свой маршрут</a>
        </p>
    </div>
</article>
<?php
wt_river_chrome_footer();

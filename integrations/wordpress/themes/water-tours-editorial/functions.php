<?php
if (!defined('ABSPATH')) {
    exit;
}

function water_tours_editorial_enqueue_assets() {
    $theme_version = wp_get_theme()->get('Version');

    wp_enqueue_style(
        'water-tours-editorial-style',
        get_stylesheet_uri(),
        array(),
        $theme_version
    );

    wp_enqueue_style(
        'water-tours-editorial-main',
        get_template_directory_uri() . '/assets/editorial.css',
        array('water-tours-editorial-style'),
        (string) filemtime(__DIR__ . '/assets/editorial.css')
    );

    wp_enqueue_script(
        'water-tours-editorial-main',
        get_template_directory_uri() . '/assets/editorial.js',
        array(),
        (string) filemtime(__DIR__ . '/assets/editorial.js'),
        true
    );
}
add_action('wp_enqueue_scripts', 'water_tours_editorial_enqueue_assets');

add_action('after_setup_theme', function () { add_theme_support('title-tag'); });

// Same reasoning as the prototype theme: the core user sitemap only exposes admin usernames.
add_filter('wp_sitemaps_add_provider', function ($provider, $name) {
    return $name === 'users' ? false : $provider;
}, 10, 2);
add_filter('pre_get_document_title', function ($title) {
    return is_front_page() ? 'Речные прогулки — электронные билеты | Water Tours' : $title;
});

/**
 * Boat rental prices. The purchase plugin owns the call to the backend's published prices;
 * this theme never carries its own price constants, so it cannot drift from what is charged.
 * If the plugin is inactive the section renders without figures rather than inventing them.
 */
function water_tours_editorial_boat_prices() {
    if (function_exists('water_tours_boat_prices')) {
        $prices = water_tours_boat_prices();
        if (is_array($prices) && $prices) {
            return $prices;
        }
    }

    return array();
}

function water_tours_editorial_format_price($amount) {
    if (function_exists('water_tours_buy_format_price')) {
        return water_tours_buy_format_price($amount);
    }

    return number_format((float) $amount, 0, ',', ' ');
}

/**
 * Replaceable image slot. Photographs live in assets/img/<slug>.jpg and can be swapped for the
 * owner's own files without touching a template; a missing file falls back to a drawn panel so
 * the composition never collapses into an empty box.
 */
function water_tours_editorial_image($slug, $alt, $args = array()) {
    $defaults = array('class' => '', 'loading' => 'lazy', 'width' => 0, 'height' => 0);
    $args = array_merge($defaults, $args);
    $relative = '/assets/img/' . $slug . '.jpg';

    if (!file_exists(get_template_directory() . $relative)) {
        printf(
            '<span class="ed-figure-blank %s" role="img" aria-label="%s"></span>',
            esc_attr($args['class']),
            esc_attr($alt)
        );

        return;
    }

    printf(
        '<img class="%s" src="%s" alt="%s" loading="%s"%s%s decoding="async">',
        esc_attr($args['class']),
        esc_url(get_template_directory_uri() . $relative),
        esc_attr($alt),
        esc_attr($args['loading']),
        $args['width'] ? ' width="' . (int) $args['width'] . '"' : '',
        $args['height'] ? ' height="' . (int) $args['height'] . '"' : ''
    );
}

/**
 * Ticket questions. Kept identical in substance to the prototype theme so the two designs
 * describe the same product and nothing has to be re-checked when the owner switches.
 */
function water_tours_editorial_faq() {
    return array(
        'С какого момента действуют 72 часа?' => 'С момента подтверждения оплаты. Оформление неоплаченного заказа не запускает срок действия. Точные даты начала и окончания указаны в билете.',
        'Нужно ли выбирать конкретный рейс?' => 'Билет не закрепляет рейс или место. Выберите отправление по расписанию в пределах срока действия. Условия посадки и наличие мест уточняйте перед прогулкой.',
        'Где получить электронный билет?' => 'После подтверждения оплаты в форме заказа появляется ссылка для скачивания PDF с QR-кодом. Сохраните файл, чтобы показать его сотруднику при посадке.',
        'Можно ли пройти по билету повторно?' => 'Нет. Один билет даёт право на один проход. После погашения сотрудником повторное использование невозможно.',
        'Где посмотреть причал и расписание?' => 'Маршрут, причал и расписание пока уточняются. Перед покупкой проверьте информацию об отправлении: срок действия билета не означает гарантированную посадку на любой рейс.'
    );
}

add_action('wp_head', function () {
    if (!is_front_page() || defined('WPSEO_VERSION') || defined('RANK_MATH_VERSION')) { return; }
    $description = 'Билеты на речные прогулки Water Tours. Один проход, 72 часа с момента подтверждения оплаты. Выберите билеты и сохраните PDF с QR-кодом.';
    $title = 'Речные прогулки — электронные билеты | Water Tours';
    $url = home_url('/');
    echo '<meta name="description" content="' . esc_attr($description) . '">' . "\n";
    echo '<meta property="og:type" content="website">' . "\n";
    echo '<meta property="og:site_name" content="Water Tours">' . "\n";
    echo '<meta property="og:title" content="' . esc_attr($title) . '">' . "\n";
    echo '<meta property="og:description" content="' . esc_attr($description) . '">' . "\n";
    echo '<meta property="og:url" content="' . esc_url($url) . '">' . "\n";
    echo '<meta name="twitter:card" content="summary">' . "\n";
    echo '<meta name="twitter:title" content="' . esc_attr($title) . '">' . "\n";
    echo '<meta name="twitter:description" content="' . esc_attr($description) . '">' . "\n";

    $faq_entities = array();
    foreach (water_tours_editorial_faq() as $question => $answer) {
        $faq_entities[] = array(
            '@type' => 'Question',
            'name' => $question,
            'acceptedAnswer' => array('@type' => 'Answer', 'text' => $answer),
        );
    }
    echo '<script type="application/ld+json">' . wp_json_encode(array(
        '@context' => 'https://schema.org',
        '@type' => 'FAQPage',
        'mainEntity' => $faq_entities,
    ), JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES) . '</script>' . "\n";
});

<?php
if (!defined('ABSPATH')) {
    exit;
}

function water_tours_prototype_enqueue_assets() {
    $theme_version = wp_get_theme()->get('Version');

    wp_enqueue_style(
        'water-tours-prototype-style',
        get_stylesheet_uri(),
        array(),
        $theme_version
    );

    wp_enqueue_style(
        'water-tours-prototype-theme',
        get_template_directory_uri() . '/assets/modern.css',
        array('water-tours-prototype-style'),
        (string) filemtime(__DIR__ . '/assets/modern.css')
    );

    wp_enqueue_script(
        'water-tours-prototype-theme',
        get_template_directory_uri() . '/assets/theme.js',
        array(),
        $theme_version,
        true
    );
}
add_action('wp_enqueue_scripts', 'water_tours_prototype_enqueue_assets');

add_action('after_setup_theme', function () { add_theme_support('title-tag'); });

// The core XML sitemap otherwise publishes /wp-sitemap-users-1.xml, listing admin usernames
// (e.g. /author/wtadmin/) - no SEO value here and needless account-enumeration surface.
add_filter('wp_sitemaps_add_provider', function ($provider, $name) {
    return $name === 'users' ? false : $provider;
}, 10, 2);
add_filter('pre_get_document_title', function ($title) {
    return is_front_page() ? 'Речные прогулки — электронные билеты | Water Tours' : $title;
});
function water_tours_home_faq() {
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
    // No og:image: no real photo exists yet, and a placeholder would misrepresent the product.
    echo '<meta property="og:type" content="website">' . "\n";
    echo '<meta property="og:site_name" content="Water Tours">' . "\n";
    echo '<meta property="og:title" content="' . esc_attr($title) . '">' . "\n";
    echo '<meta property="og:description" content="' . esc_attr($description) . '">' . "\n";
    echo '<meta property="og:url" content="' . esc_url($url) . '">' . "\n";
    echo '<meta name="twitter:card" content="summary">' . "\n";
    echo '<meta name="twitter:title" content="' . esc_attr($title) . '">' . "\n";
    echo '<meta name="twitter:description" content="' . esc_attr($description) . '">' . "\n";

    $faq_entities = array();
    foreach (water_tours_home_faq() as $question => $answer) {
        $faq_entities[] = array(
            '@type' => 'Question',
            'name' => $question,
            'acceptedAnswer' => array(
                '@type' => 'Answer',
                'text' => $answer,
            ),
        );
    }
    $faq_schema = array(
        '@context' => 'https://schema.org',
        '@type' => 'FAQPage',
        'mainEntity' => $faq_entities,
    );
    echo '<script type="application/ld+json">' . wp_json_encode($faq_schema, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES) . '</script>' . "\n";
});

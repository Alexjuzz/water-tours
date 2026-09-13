<?php
if (!defined('ABSPATH')) { exit; }
add_action('after_setup_theme', function () { add_theme_support('title-tag'); });
add_action('wp_enqueue_scripts', function () {
    wp_enqueue_style('water-tours-river', get_template_directory_uri() . '/assets/river.css', array(), (string) filemtime(__DIR__ . '/assets/river.css'));
    wp_enqueue_script('water-tours-river', get_template_directory_uri() . '/assets/river.js', array(), (string) filemtime(__DIR__ . '/assets/river.js'), true);
});
add_filter('wp_sitemaps_add_provider', function ($provider, $name) { return $name === 'users' ? false : $provider; }, 10, 2);
add_filter('pre_get_document_title', function ($title) { return is_front_page() ? 'Речные прогулки в Петербурге | Water Tours' : $title; });
function wt_river_booking($kind) {
    $shortcode = $kind === 'boat' ? 'water_tours_boat' : 'water_tours_buy';
    if (shortcode_exists($shortcode)) { echo do_shortcode('[' . $shortcode . ']'); }
    else { echo '<p class="booking-unavailable">Оформление временно недоступно. Попробуйте позже.</p>'; }
}

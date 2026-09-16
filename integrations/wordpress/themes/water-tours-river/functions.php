<?php
if (!defined('ABSPATH')) { exit; }

require_once __DIR__ . '/inc/seo.php';
require_once __DIR__ . '/inc/chrome.php';
require_once __DIR__ . '/inc/contacts.php';

add_action('after_setup_theme', function () { add_theme_support('title-tag'); });
add_action('wp_enqueue_scripts', function () {
    wp_enqueue_style('water-tours-river', get_template_directory_uri() . '/assets/river.css', array(), (string) filemtime(__DIR__ . '/assets/river.css'));
    wp_enqueue_script('water-tours-river', get_template_directory_uri() . '/assets/river.js', array(), (string) filemtime(__DIR__ . '/assets/river.js'), true);
});
function wt_river_booking($kind) {
    $shortcode = $kind === 'boat' ? 'water_tours_boat' : 'water_tours_buy';
    if (shortcode_exists($shortcode)) { echo do_shortcode('[' . $shortcode . ']'); }
    else { echo '<p class="booking-unavailable">Оформление временно недоступно. Попробуйте позже.</p>'; }
}

/**
 * The hero photo at three widths.
 *
 * One 1400px file was being handed to a 390px phone, which is most of the page weight for the
 * device least able to afford it. The derivatives are generated from the same optimised copy that
 * is already in the repository; the original is untouched. If a derivative is missing the srcset
 * simply omits it and the full-size file is still used, so this degrades to what it replaced.
 */
function wt_river_image_srcset($basename, array $widths) {
    $dir = __DIR__ . '/assets/img/';
    $uri = get_template_directory_uri() . '/assets/img/';
    $name = pathinfo($basename, PATHINFO_FILENAME);
    $ext = pathinfo($basename, PATHINFO_EXTENSION);
    $parts = array();
    foreach ($widths as $width) {
        $candidate = $name . '-' . $width . 'w.' . $ext;
        if (file_exists($dir . $candidate)) {
            $parts[] = $uri . $candidate . ' ' . $width . 'w';
        }
    }
    if (file_exists($dir . $basename)) {
        $size = @getimagesize($dir . $basename);
        if ($size && !empty($size[0])) {
            $parts[] = $uri . $basename . ' ' . (int) $size[0] . 'w';
        }
    }
    return implode(', ', $parts);
}

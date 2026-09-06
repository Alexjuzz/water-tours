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
        get_template_directory_uri() . '/assets/theme.css',
        array('water-tours-prototype-style'),
        $theme_version
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

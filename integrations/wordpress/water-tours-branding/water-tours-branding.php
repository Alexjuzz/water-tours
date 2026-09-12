<?php
/**
 * Plugin Name: Water Tours Branding
 * Description: Sets the site favicon/tab icon to the Water Tours logo already in the media library.
 * Version: 0.1.0
 * Author: Water Tours
 */

if (!defined('ABSPATH')) {
    exit;
}

const WATER_TOURS_LOGO_URL_SUFFIX = 'uploads/2025/06/logo2.webp';

/**
 * If WordPress's own Site Icon setting is empty, point it at the Water Tours logo that is
 * already uploaded (cropped-logo2-*.webp files in the media library show it was set once
 * before but is not currently active). Using the real site_icon option - rather than only
 * printing our own <link> tags - also gets WordPress's built-in apple-touch-icon, android
 * icon, and REST API/oEmbed icon output for free.
 */
function water_tours_ensure_site_icon() {
    if (get_option('site_icon')) {
        return;
    }

    $logo_url = content_url(WATER_TOURS_LOGO_URL_SUFFIX);
    $attachment_id = attachment_url_to_postid($logo_url);

    if ($attachment_id) {
        update_option('site_icon', $attachment_id);
    }
}
add_action('init', 'water_tours_ensure_site_icon');

/**
 * Fallback: if the logo isn't registered as a media library attachment (e.g. the uploads
 * folder was restored from a file backup without the matching database rows), WordPress's
 * site_icon mechanism has nothing to point at. Print the icon tags directly from the known
 * file so the tab icon still shows up.
 */
function water_tours_fallback_favicon() {
    if (get_option('site_icon')) {
        return; // WordPress's own wp_site_icon() already handles it.
    }

    $logo_url = content_url(WATER_TOURS_LOGO_URL_SUFFIX);
    $base = content_url('uploads/2025/06/cropped-logo2');
    ?>
    <link rel="icon" href="<?php echo esc_url($base . '-32x32.webp'); ?>" sizes="32x32" type="image/webp">
    <link rel="icon" href="<?php echo esc_url($base . '-192x192.webp'); ?>" sizes="192x192" type="image/webp">
    <link rel="apple-touch-icon" href="<?php echo esc_url($base . '-180x180.webp'); ?>">
    <link rel="shortcut icon" href="<?php echo esc_url($logo_url); ?>" type="image/webp">
    <?php
}
add_action('wp_head', 'water_tours_fallback_favicon', 1);

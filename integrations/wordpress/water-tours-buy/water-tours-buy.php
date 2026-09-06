<?php
/**
 * Plugin Name: Water Tours Buy (Backend Bridge)
 * Description: Minimal ticket purchase form wired to the Water Tours backend order API. No payment/SMTP is triggered from this plugin.
 * Version: 0.1.0
 * Author: Water Tours
 */

if (!defined('ABSPATH')) {
    exit;
}

if (!defined('WATER_TOURS_BACKEND_URL')) {
    define('WATER_TOURS_BACKEND_URL', 'http://localhost:8080');
}

if (!defined('WATER_TOURS_LOCAL_TEST_MODE')) {
    define('WATER_TOURS_LOCAL_TEST_MODE', false);
}

/**
 * Backend base URL. Override via the WATER_TOURS_BACKEND_URL constant
 * or the water_tours_backend_url filter (e.g. in wp-config.php / a theme's functions.php).
 */
function water_tours_buy_backend_url() {
    $url = apply_filters('water_tours_backend_url', WATER_TOURS_BACKEND_URL);
    return esc_url_raw($url);
}

/**
 * Local test mode toggles fetching live prices from the local-checkout catalog
 * endpoint, which only exists when the backend runs with the local-checkout profile.
 */
function water_tours_buy_local_test_mode() {
    return (bool) apply_filters('water_tours_local_test_mode', WATER_TOURS_LOCAL_TEST_MODE);
}

/**
 * Server-side fallback prices, used whenever local test mode is off. Keep in sync
 * with the backend's TicketProperties (source of truth for pricing).
 */
function water_tours_buy_ticket_prices() {
    $defaults = array(
        'ADULT' => 1500,
        'CHILD' => 800,
        'BENEFIT' => 1020,
    );

    $prices = apply_filters('water_tours_ticket_prices', $defaults);

    $sanitized = array();
    foreach ($defaults as $type => $fallback) {
        $sanitized[$type] = isset($prices[$type]) ? max(0, (int) $prices[$type]) : $fallback;
    }

    return $sanitized;
}

function water_tours_buy_enqueue_assets() {
    $style_version = (string) filemtime(plugin_dir_path(__FILE__) . 'assets/water-tours-buy.css');
    $script_version = (string) filemtime(plugin_dir_path(__FILE__) . 'assets/water-tours-buy.js');

    wp_enqueue_style(
        'water-tours-buy-css',
        plugin_dir_url(__FILE__) . 'assets/water-tours-buy.css',
        array(),
        $style_version
    );

    wp_enqueue_script(
        'water-tours-buy-js',
        plugin_dir_url(__FILE__) . 'assets/water-tours-buy.js',
        array(),
        $script_version,
        true
    );

    wp_localize_script('water-tours-buy-js', 'WaterToursConfig', array(
        'backendUrl' => water_tours_buy_backend_url(),
        'localTestMode' => water_tours_buy_local_test_mode(),
        'catalogPath' => '/api/v1/local-checkout/catalog',
        'ordersPath' => '/api/v1/orders',
        'prices' => water_tours_buy_ticket_prices(),
        'nonce' => wp_create_nonce('water_tours_buy_submit'),
    ));
}
add_action('wp_enqueue_scripts', 'water_tours_buy_enqueue_assets');

function water_tours_buy_ticket_types() {
    return array(
        'ADULT' => 'Взрослый',
        'CHILD' => 'Детский',
        'BENEFIT' => 'Льготный',
    );
}

function water_tours_buy_shortcode() {
    $prices = water_tours_buy_ticket_prices();
    $labels = water_tours_buy_ticket_types();

    ob_start();
    ?>
    <div class="wt-buy">
        <button id="wt-open-modal" class="wt-main-button" type="button">
            Купить билет
        </button>

        <div id="wt-modal" class="wt-modal">
            <div class="wt-modal-content">
                <button id="wt-close-modal" class="wt-close" type="button">&times;</button>

                <h2>Купить билет</h2>

                <form id="wt-ticket-form">
                    <?php wp_nonce_field('water_tours_buy_submit', 'wt_nonce'); ?>

                    <div class="wt-field">
                        <label for="wt-email">Email</label>
                        <input id="wt-email" type="email" required placeholder="example@mail.ru">
                    </div>

                    <div class="wt-field">
                        <label for="wt-phone">Телефон</label>
                        <input id="wt-phone" type="tel" required placeholder="+79990000000">
                    </div>

                    <?php foreach ($labels as $type => $label) : ?>
                        <div class="wt-ticket-row">
                            <div>
                                <strong><?php echo esc_html($label); ?></strong>
                                <span class="wt-muted" id="wt-price-<?php echo esc_attr($type); ?>">
                                    <?php echo esc_html($prices[$type]); ?> ₽
                                </span>
                            </div>
                            <div class="wt-counter">
                                <button type="button" data-type="<?php echo esc_attr($type); ?>" data-delta="-1">−</button>
                                <span id="wt-count-<?php echo esc_attr($type); ?>"><?php echo $type === 'ADULT' ? '1' : '0'; ?></span>
                                <button type="button" data-type="<?php echo esc_attr($type); ?>" data-delta="1">+</button>
                            </div>
                        </div>
                    <?php endforeach; ?>

                    <div class="wt-total">
                        Итого: <span id="wt-total-sum"><?php echo esc_html($prices['ADULT']); ?></span> ₽
                    </div>

                    <button id="wt-submit" class="wt-submit" type="submit">
                        Оформить заказ
                    </button>
                </form>

                <div id="wt-result" class="wt-result"></div>
            </div>
        </div>
    </div>
    <?php
    return ob_get_clean();
}
add_shortcode('water_tours_buy', 'water_tours_buy_shortcode');

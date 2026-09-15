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
 * Where this server talks to the backend. The public WATER_TOURS_BACKEND_URL is empty in
 * production (the browser uses same-origin paths through nginx), which is useless for a
 * server-side call, so prices are read straight from the backend on localhost.
 */
function water_tours_buy_internal_backend_url() {
    $url = defined('WATER_TOURS_BACKEND_INTERNAL_URL') ? WATER_TOURS_BACKEND_INTERNAL_URL : 'http://127.0.0.1:8080';
    return esc_url_raw(apply_filters('water_tours_backend_internal_url', $url));
}

/**
 * Prices published by the backend (GET /api/v1/prices) - the single source of truth, edited by
 * the owner at /staff/prices. Cached briefly so a page view does not wait on the backend, with
 * the last successful answer kept in an option: if the backend is down we show the last real
 * prices rather than a number baked into this file that silently drifts out of date.
 *
 * Every answer carries where it came from, in `source`:
 *
 * - `backend`   - read from /api/v1/prices on this request.
 * - `last_good` - the last answer the backend actually gave on this install. Real published
 *                 prices, possibly a few minutes old.
 * - `defaults`  - the values at the bottom of this file, used only before the backend has ever
 *                 answered here. **These are not published prices.** They match
 *                 `PricingService.SEED_VALUES`, which is where a *fresh* install starts, not what
 *                 this site charges: on 2026-09-15 the live catalogue was version 6 with BENEFIT
 *                 1054 and the 30-minute rental 35010, while the seed says 1020 and 3500. The buy
 *                 form still shows them, because a form with no number in it cannot be used at
 *                 all; anything that publishes a price as a claim to a machine has to check the
 *                 source first and say nothing instead - see `water_tours_buy_prices_are_published()`.
 */
function water_tours_buy_prices() {
    $cached = get_transient('water_tours_prices');
    if (is_array($cached) && isset($cached['tickets'])) {
        return $cached;
    }

    $response = wp_remote_get(
        water_tours_buy_internal_backend_url() . '/api/v1/prices',
        array('timeout' => 2)
    );

    $prices = null;
    if (!is_wp_error($response) && wp_remote_retrieve_response_code($response) === 200) {
        $payload = json_decode(wp_remote_retrieve_body($response), true);
        $prices = water_tours_buy_normalize_prices($payload);
    }

    if ($prices === null) {
        $prices = get_option('water_tours_prices_last_good');
        if (is_array($prices) && isset($prices['tickets']) && isset($prices['boat'])) {
            $prices['source'] = 'last_good';
        } else {
            $prices = water_tours_buy_price_defaults();
        }
        // Short retry window so a brief backend restart does not pin stale prices for long.
        set_transient('water_tours_prices', $prices, 30);
        return $prices;
    }

    update_option('water_tours_prices_last_good', $prices, false);
    set_transient('water_tours_prices', $prices, 60);
    return $prices;
}

/**
 * True when the numbers on this page came from the backend catalogue - now, or the last time it
 * answered here - and false when they are this file's seed values.
 *
 * The difference only matters where a number is published as a claim rather than shown as a hint:
 * structured data, a feed, anything a machine quotes back later. There a guess is worse than a gap.
 */
function water_tours_buy_prices_are_published() {
    $prices = water_tours_buy_prices();
    $source = isset($prices['source']) ? $prices['source'] : 'defaults';
    return $source === 'backend' || $source === 'last_good';
}

/**
 * Last-resort values, used only before the backend has ever answered on this install.
 */
function water_tours_buy_price_defaults() {
    return array(
        'version' => 0,
        'source'  => 'defaults',
        'tickets' => array('ADULT' => 1500.0, 'CHILD' => 800.0, 'BENEFIT' => 1020.0),
        'boat' => array(30 => 3500.0, 60 => 6000.0, 90 => 9000.0, 120 => 11000.0),
    );
}

/**
 * One published amount, or null when the value is not a price.
 *
 * Kept as a float rounded to two decimals, because two decimals is exactly what the backend
 * publishes: the columns are `precision = 12, scale = 2` and `PricingService.validate()` rejects
 * anything with a larger scale. The previous version did `(int) round(...)`, so a catalogue entry
 * of 1500.50 was displayed, marked up and totalled as 1501 while the backend charged 1500.50.
 * Rounding a price is not a formatting choice - it makes the site quote a number nobody is charged.
 *
 * `true`, `null`, `''` and `'1500'` are all things a bare cast would quietly accept or mangle, so
 * the check is explicit.
 */
function water_tours_buy_amount($value) {
    if (is_bool($value) || $value === null || $value === '' || is_array($value)) { return null; }
    if (!is_numeric($value)) { return null; }
    $amount = round((float) $value, 2);
    if (!is_finite($amount) || $amount < 0) { return null; }
    return $amount;
}

/**
 * Turn one backend answer into the shape the rest of this plugin uses, or null.
 *
 * Null means "the backend did not give us a price list" and the caller falls back. A payload
 * missing even one field is null rather than a list with that gap filled in from this file: the
 * half-invented list used to be stored as `water_tours_prices_last_good` and then served for as
 * long as the backend stayed unreachable, which turns one malformed response into a lasting wrong
 * price. The backend publishes all seven fields in one `Map.of`, so a partial payload is a broken
 * answer, not a smaller one.
 */
function water_tours_buy_normalize_prices($payload) {
    if (!is_array($payload) || !isset($payload['tickets']) || !is_array($payload['tickets'])) {
        return null;
    }

    $defaults = water_tours_buy_price_defaults();
    $normalized = array(
        'version' => isset($payload['version']) ? (int) $payload['version'] : 0,
        'source'  => 'backend',
        'tickets' => array(),
        'boat'    => array(),
    );

    foreach ($defaults['tickets'] as $type => $ignored) {
        $amount = isset($payload['tickets'][$type]) ? water_tours_buy_amount($payload['tickets'][$type]) : null;
        if ($amount === null) { return null; }
        $normalized['tickets'][$type] = $amount;
    }

    $boat = isset($payload['boatRentalByDurationMinutes']) && is_array($payload['boatRentalByDurationMinutes'])
        ? $payload['boatRentalByDurationMinutes']
        : array();
    foreach ($defaults['boat'] as $minutes => $ignored) {
        $amount = isset($boat[$minutes]) ? water_tours_buy_amount($boat[$minutes]) : null;
        if ($amount === null) { return null; }
        $normalized['boat'][$minutes] = $amount;
    }

    return $normalized;
}

function water_tours_buy_ticket_prices() {
    $prices = water_tours_buy_prices();
    $sanitized = array();
    foreach ($prices['tickets'] as $type => $price) {
        $amount = water_tours_buy_amount($price);
        $sanitized[$type] = $amount === null ? 0.0 : $amount;
    }

    $filtered = apply_filters('water_tours_ticket_prices', $sanitized);
    foreach ($sanitized as $type => $fallback) {
        $amount = isset($filtered[$type]) ? water_tours_buy_amount($filtered[$type]) : null;
        $sanitized[$type] = $amount === null ? $fallback : $amount;
    }

    return $sanitized;
}

/**
 * Boat rental price per duration in minutes. Also used by the theme's landing page so the
 * headline price and the duration grid cannot drift away from what the backend charges.
 */
function water_tours_boat_prices() {
    $prices = water_tours_buy_prices();
    $sanitized = array();
    foreach ($prices['boat'] as $minutes => $price) {
        $amount = water_tours_buy_amount($price);
        $sanitized[(int) $minutes] = $amount === null ? 0.0 : $amount;
    }
    ksort($sanitized);

    $filtered = apply_filters('water_tours_boat_prices', $sanitized);
    foreach ($sanitized as $minutes => $fallback) {
        $amount = isset($filtered[$minutes]) ? water_tours_buy_amount($filtered[$minutes]) : null;
        $sanitized[$minutes] = $amount === null ? $fallback : $amount;
    }

    return $sanitized;
}

/**
 * Same grouping the purchase script uses, so the price does not visibly reflow once the
 * script takes over the element.
 *
 * Kopecks appear only when the catalogue actually has them. A whole-ruble price renders exactly
 * as it always did - `1 500`, not `1 500,00` - so an ordinary page is byte-for-byte unchanged,
 * while 1500.50 renders `1 500,50` instead of the `1 501` it used to claim.
 */
function water_tours_buy_format_price($amount) {
    $amount = round((float) $amount, 2);
    $decimals = (abs($amount - round($amount)) < 0.005) ? 0 : 2;
    return number_format($amount, $decimals, ',', ' ');
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
        'boatPrices' => water_tours_boat_prices(),
        // No nonce here on purpose. The browser posts straight to the Spring API - there is no
        // admin-ajax handler and no REST route in this plugin, so nothing could ever verify one.
        // Printing a nonce anyway implied a check that did not exist.
    ));
}
add_action('wp_enqueue_scripts', 'water_tours_buy_enqueue_assets');
/**
 * Analytics: registered, and deliberately inert.
 *
 * WATER_TOURS_ANALYTICS_COUNTER_ID is empty here and has no fallback anywhere in the JavaScript.
 * With it empty the module does nothing at all: no banner, no counter script, no requests. The
 * owner turns it on by defining the constant (in wp-config.php) or filtering it - which is a
 * decision about collecting visitors' data and is theirs to make, not something to be guessed
 * into place. WATER_TOURS_ANALYTICS_SINK exists only so the whole flow can be verified against a
 * local mock with no real counter in existence.
 */
function water_tours_analytics_config() {
    $counter = defined('WATER_TOURS_ANALYTICS_COUNTER_ID') ? WATER_TOURS_ANALYTICS_COUNTER_ID : '';
    $sink = defined('WATER_TOURS_ANALYTICS_SINK') ? WATER_TOURS_ANALYTICS_SINK : '';
    return array(
        'counterId'       => (string) apply_filters('water_tours_analytics_counter_id', $counter),
        'provider'        => (string) apply_filters('water_tours_analytics_provider', 'metrica'),
        'debugSink'       => (string) apply_filters('water_tours_analytics_sink', $sink),
        'consentKey'      => 'wt_analytics_consent',
        // Raise this if what is collected ever changes materially: an answer given to the old
        // question stops counting as an answer to the new one.
        'consentVersion'  => (string) apply_filters('water_tours_analytics_consent_version', '1'),
    );
}

function water_tours_analytics_enqueue() {
    $config = water_tours_analytics_config();
    if ($config['counterId'] === '' && $config['debugSink'] === '') {
        // Nothing configured - do not even ship the file.
        return;
    }
    $version = (string) filemtime(plugin_dir_path(__FILE__) . 'assets/water-tours-analytics.js');
    wp_enqueue_script(
        'water-tours-analytics-js',
        plugin_dir_url(__FILE__) . 'assets/water-tours-analytics.js',
        array('water-tours-buy-js'),
        $version,
        true
    );
    wp_localize_script('water-tours-analytics-js', 'WaterToursAnalytics', $config);
}
add_action('wp_enqueue_scripts', 'water_tours_analytics_enqueue', 20);


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

        <div id="wt-modal" class="wt-modal" aria-hidden="true">
            <div class="wt-modal-content" role="dialog" aria-modal="true" aria-labelledby="wt-modal-title">
                <button id="wt-close-modal" class="wt-close" type="button" aria-label="Закрыть">&times;</button>

                <h2 id="wt-modal-title">Купить билет</h2>

                <form id="wt-ticket-form">
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
                                    <?php echo esc_html(water_tours_buy_format_price($prices[$type])); ?> ₽
                                </span>
                            </div>
                            <div class="wt-counter">
                                <button type="button" data-type="<?php echo esc_attr($type); ?>" data-delta="-1">−</button>
                                <span id="wt-count-<?php echo esc_attr($type); ?>">0</span>
                                <button type="button" data-type="<?php echo esc_attr($type); ?>" data-delta="1">+</button>
                            </div>
                        </div>
                    <?php endforeach; ?>

                    <div class="wt-total">
                        Итого: <span id="wt-total-sum">0</span> ₽
                    </div>

                    <button id="wt-submit" class="wt-submit" type="submit">
                        Оформить заказ
                    </button>
                </form>

                <div id="wt-result" class="wt-result" aria-live="polite"></div>
            </div>
        </div>
    </div>
    <?php
    return ob_get_clean();
}
add_shortcode('water_tours_buy', 'water_tours_buy_shortcode');

function water_tours_boat_shortcode() {
    $boat_prices = water_tours_boat_prices();
    $starting_price = $boat_prices ? reset($boat_prices) : 0;
    ob_start();
    ?>
    <div class="wt-buy wt-boat-buy">
        <button id="wt-boat-open-modal" class="wt-main-button wt-boat-main-button" type="button">
            Арендовать катер
        </button>

        <div id="wt-boat-modal" class="wt-modal" aria-hidden="true">
            <div class="wt-modal-content wt-boat-modal-content" role="dialog" aria-modal="true" aria-labelledby="wt-boat-modal-title" aria-describedby="wt-boat-modal-description">
                <button id="wt-boat-close-modal" class="wt-close" type="button" aria-label="Закрыть">&times;</button>

                <p class="wt-boat-kicker">Катер целиком · до 6 гостей</p>
                <h2 id="wt-boat-modal-title">Аренда катера</h2>
                <p id="wt-boat-modal-description" class="wt-muted">Один электронный билет действует 72 часа после оплаты. Маршрут можно предложить свой или выбрать вместе с нами.</p>

                <form id="wt-boat-form">
                    <div class="wt-field">
                        <label for="wt-boat-email">Email</label>
                        <input id="wt-boat-email" type="email" autocomplete="email" required placeholder="example@mail.ru">
                    </div>

                    <div class="wt-field">
                        <label for="wt-boat-phone">Телефон</label>
                        <input id="wt-boat-phone" type="tel" autocomplete="tel" required placeholder="+79990000000">
                    </div>

                    <div class="wt-boat-grid">
                        <div class="wt-field">
                            <label for="wt-boat-guests">Количество гостей</label>
                            <select id="wt-boat-guests" required>
                                <?php for ($guests = 1; $guests <= 6; $guests++) : ?>
                                    <option value="<?php echo esc_attr($guests); ?>"><?php echo esc_html($guests); ?></option>
                                <?php endfor; ?>
                            </select>
                        </div>

                        <div class="wt-field">
                            <label for="wt-boat-duration">Продолжительность</label>
                            <select id="wt-boat-duration" required>
                                <?php foreach ($boat_prices as $minutes => $price) : ?>
                                    <option value="<?php echo esc_attr($minutes); ?>" data-price="<?php echo esc_attr($price); ?>">
                                        <?php echo esc_html($minutes . ' минут — ' . water_tours_buy_format_price($price) . ' ₽'); ?>
                                    </option>
                                <?php endforeach; ?>
                            </select>
                        </div>
                    </div>

                    <fieldset class="wt-route-choice">
                        <legend>Маршрут</legend>
                        <label><input type="radio" name="wt-boat-route" value="CUSTOM" checked> Предложу свой</label>
                        <label><input type="radio" name="wt-boat-route" value="ASSISTED"> Помогите выбрать</label>
                    </fieldset>

                    <div class="wt-field">
                        <label for="wt-boat-route-note">Пожелания к маршруту <span class="wt-muted-inline">(необязательно)</span></label>
                        <textarea id="wt-boat-route-note" maxlength="300" rows="3" placeholder="Например: спокойная прогулка без остановок"></textarea>
                    </div>

                    <div class="wt-total wt-boat-total" aria-live="polite">
                        Итого за катер: <span id="wt-boat-total-sum"><?php echo esc_html(water_tours_buy_format_price($starting_price)); ?></span> ₽
                    </div>

                    <button id="wt-boat-submit" class="wt-submit" type="submit">Оформить аренду</button>
                </form>

                <div id="wt-boat-result" class="wt-result" aria-live="polite"></div>
            </div>
        </div>
    </div>
    <?php
    return ob_get_clean();
}
add_shortcode('water_tours_boat', 'water_tours_boat_shortcode');

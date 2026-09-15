<?php
/**
 * Technical SEO for the River theme.
 *
 * Three rules this file follows, because breaking any of them costs more than the tags are worth:
 *
 * 1. **It never competes with WordPress core or with an SEO plugin.** Core already emits the
 *    document title, `rel=canonical` on singular views and `wp-sitemap.xml`. If a plugin such as
 *    Yoast or Rank Math is ever installed, it takes over completely and everything here stands
 *    down - two sets of canonical or Open Graph tags is worse than none, because search engines
 *    then have to guess which one the site means.
 * 2. **It states only facts that come from a source of truth.** Prices come from the backend
 *    catalogue the owner edits at /staff/prices, never from a number typed into a template. There
 *    is no address, phone, opening time, rating or review here, because none of those has been
 *    confirmed - and structured data that says something untrue is worse than no structured data.
 * 3. **robots.txt is not authorisation.** The disallow rules below keep the order API and the
 *    staff console out of a crawler's way; what actually protects them is the access token and
 *    the login, which are enforced by the application.
 *
 * @package water-tours-river
 */

if (!defined('ABSPATH')) { exit; }

/**
 * True when something else on this site already owns the meta tags. Checked for every well-known
 * SEO plugin rather than just one, because the failure mode is silent duplication.
 */
function wt_river_seo_handled_elsewhere() {
    return defined('WPSEO_VERSION')            // Yoast
        || defined('RANK_MATH_VERSION')        // Rank Math
        || defined('SEOPRESS_VERSION')         // SEOPress
        || defined('AIOSEO_VERSION')           // All in One SEO
        || class_exists('The_SEO_Framework\\Load');
}

/** The one description the front page uses. Filterable so the owner can reword it without code. */
function wt_river_seo_description() {
    return apply_filters(
        'water_tours_seo_description',
        'Речные прогулки по Санкт-Петербургу и аренда катера для своей компании. '
        . 'Электронный билет с QR-кодом действует 72 часа с момента подтверждённой оплаты, '
        . 'один проход. Оплата онлайн, билет приходит на почту и доступен для скачивания.'
    );
}

function wt_river_seo_site_name() {
    return apply_filters('water_tours_seo_site_name', 'Water Tours');
}

/** Absolute URL of the page being rendered, without a query string. */
function wt_river_seo_current_url() {
    if (is_front_page()) {
        return home_url('/');
    }
    $path = isset($_SERVER['REQUEST_URI']) ? strtok((string) $_SERVER['REQUEST_URI'], '?') : '/';
    return home_url($path);
}

/**
 * Open Graph requires an absolute URL - a relative one is silently ignored and the link preview
 * comes out blank. `get_template_directory_uri()` is absolute on a normal install; the guard is
 * for the ones that are not, and costs nothing.
 */
function wt_river_seo_share_image() {
    $image = apply_filters(
        'water_tours_seo_share_image',
        get_template_directory_uri() . '/assets/img/hero-canal.jpg'
    );
    if (strpos($image, 'http://') === 0 || strpos($image, 'https://') === 0) {
        return $image;
    }
    return rtrim(home_url('/'), '/') . '/' . ltrim($image, '/');
}

/**
 * The front page title. Kept here with the rest of the head so there is one place to look.
 */
add_filter('pre_get_document_title', function ($title) {
    if (wt_river_seo_handled_elsewhere()) { return $title; }
    return is_front_page()
        ? apply_filters('water_tours_seo_title', 'Речные прогулки в Петербурге | Water Tours')
        : $title;
});

/**
 * Keep thin, auto-generated views out of the index.
 *
 * A WordPress site accumulates date, author and tag archives, plus a search results page, that
 * nobody wrote and nobody wants found. On this site they are worse than thin: the theme has no
 * archive template, so they fall through to index.php and render the **home page content** at a
 * different URL with a 200. That is the duplicate-content problem the SEO plan calls out as
 * "пустые WordPress-архивы", and this is the cheap half of the fix - the other half is giving
 * those views a template, which is a content decision and is recorded as an owner input.
 *
 * `follow` is deliberate: the links on those pages still lead somewhere real.
 */
add_action('wp_head', function () {
    if (wt_river_seo_handled_elsewhere()) { return; }
    if (is_search() || is_404() || is_date() || is_author() || is_tag() || is_paged()) {
        echo '<meta name="robots" content="noindex,follow">' . "\n";
    }
}, 1);

/**
 * Canonical.
 *
 * Core's `rel_canonical` covers singular views only, so it handles a static front page and every
 * page/post, and nothing else. This adds one for the views core leaves bare - and only for those,
 * so the page never carries two. `is_singular()` is the exact condition core itself uses.
 */
add_action('wp_head', function () {
    if (wt_river_seo_handled_elsewhere()) { return; }
    if (is_singular() && has_action('wp_head', 'rel_canonical')) { return; }
    if (is_search() || is_404()) { return; }
    printf('<link rel="canonical" href="%s">' . "\n", esc_url(wt_river_seo_current_url()));
}, 2);

/**
 * Description and link preview.
 *
 * The description used to be a literal `<meta>` in home-river.php, which meant every view the
 * theme rendered - archives, search, 404 - claimed to be the ticket page. It is emitted here so
 * it appears on the front page and nowhere else.
 *
 * Open Graph is here for one reason: a link to this site shared in a messenger or a chat should
 * show what it is. It is not a ranking mechanism and is not presented as one.
 */
add_action('wp_head', function () {
    if (wt_river_seo_handled_elsewhere()) { return; }
    if (!is_front_page()) { return; }

    $description = wt_river_seo_description();
    $url = wt_river_seo_current_url();
    $image = wt_river_seo_share_image();
    $title = apply_filters('water_tours_seo_title', 'Речные прогулки в Петербурге | Water Tours');

    printf('<meta name="description" content="%s">' . "\n", esc_attr($description));
    printf('<meta property="og:type" content="website">' . "\n");
    printf('<meta property="og:site_name" content="%s">' . "\n", esc_attr(wt_river_seo_site_name()));
    printf('<meta property="og:locale" content="ru_RU">' . "\n");
    printf('<meta property="og:title" content="%s">' . "\n", esc_attr($title));
    printf('<meta property="og:description" content="%s">' . "\n", esc_attr($description));
    printf('<meta property="og:url" content="%s">' . "\n", esc_url($url));
    printf('<meta property="og:image" content="%s">' . "\n", esc_url($image));
    printf('<meta property="og:image:alt" content="%s">' . "\n",
        esc_attr('Канал и вечерний Петербург с воды'));
    printf('<meta name="twitter:card" content="summary_large_image">' . "\n");
    printf('<meta name="twitter:title" content="%s">' . "\n", esc_attr($title));
    printf('<meta name="twitter:description" content="%s">' . "\n", esc_attr($description));
    printf('<meta name="twitter:image" content="%s">' . "\n", esc_url($image));
}, 3);

/**
 * Structured data, and everything it deliberately leaves out.
 *
 * Included: who the site is (Organization), what the site is (WebSite), and the two things it
 * actually sells (Service, each with the real published price).
 *
 * NOT included, and each for a reason:
 * - **LocalBusiness** needs an address. The boarding pier is not the company's office, and no
 *   address has been confirmed. Claiming one would be a lie with a map pin on it.
 * - **aggregateRating / review** - there are no collected reviews, and inventing them is both
 *   against every search engine's policy and against the plan this follows.
 * - **FAQPage** - Google ended FAQ rich results in May 2026 and removed the documentation. The
 *   visible answers stay on the page because customers read them; the markup would buy nothing.
 * - **openingHours, telephone, address, departure times, schedule** - not confirmed. Listed in
 *   the owner-input section of the launch report instead.
 *
 * The prices come from `water_tours_buy_ticket_prices()` / `water_tours_boat_prices()`, which read
 * the backend catalogue. If the backend is unreachable the block is emitted without offers rather
 * than with a stale number.
 */
add_action('wp_head', function () {
    if (wt_river_seo_handled_elsewhere()) { return; }
    if (!is_front_page()) { return; }

    $home = home_url('/');
    $graph = array(
        array(
            '@type' => 'Organization',
            '@id'   => $home . '#organization',
            'name'  => wt_river_seo_site_name(),
            'url'   => $home,
            'image' => wt_river_seo_share_image(),
        ),
        array(
            '@type'     => 'WebSite',
            '@id'       => $home . '#website',
            'url'       => $home,
            'name'      => wt_river_seo_site_name(),
            'inLanguage' => 'ru-RU',
            'publisher' => array('@id' => $home . '#organization'),
        ),
    );

    $ticket_offers = wt_river_seo_ticket_offers();
    if (!empty($ticket_offers)) {
        $graph[] = array(
            '@type'       => 'Service',
            '@id'         => $home . '#service-ticket',
            'name'        => 'Билет на речную прогулку',
            'serviceType' => 'Речная прогулка',
            'provider'    => array('@id' => $home . '#organization'),
            'areaServed'  => array('@type' => 'City', 'name' => 'Санкт-Петербург'),
            'description' => 'Электронный билет с QR-кодом на речную прогулку. Действует 72 часа '
                . 'с момента подтверждённой оплаты, даёт право на один проход. Рейс и место не '
                . 'закрепляются.',
            'offers'      => $ticket_offers,
        );
    }

    $boat_offers = wt_river_seo_boat_offers();
    if (!empty($boat_offers)) {
        $graph[] = array(
            '@type'       => 'Service',
            '@id'         => $home . '#service-boat',
            'name'        => 'Аренда катера',
            'serviceType' => 'Аренда катера с командой',
            'provider'    => array('@id' => $home . '#organization'),
            'areaServed'  => array('@type' => 'City', 'name' => 'Санкт-Петербург'),
            'description' => 'Катер целиком для вашей компании, до 6 гостей. Продолжительность '
                . 'выбирается при оформлении; маршрут и время выхода согласуются отдельно.',
            'offers'      => $boat_offers,
        );
    }

    $payload = array('@context' => 'https://schema.org', '@graph' => $graph);
    echo '<script type="application/ld+json">'
        . wp_json_encode($payload, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES)
        . '</script>' . "\n";
}, 4);

/** Ticket prices as schema.org Offers, straight from the published catalogue. */
function wt_river_seo_ticket_offers() {
    if (!function_exists('water_tours_buy_ticket_prices') || !function_exists('water_tours_buy_ticket_types')) {
        return array();
    }
    $prices = water_tours_buy_ticket_prices();
    $labels = water_tours_buy_ticket_types();
    $offers = array();
    foreach ($labels as $type => $label) {
        if (!isset($prices[$type]) || !is_numeric($prices[$type]) || (float) $prices[$type] <= 0) {
            continue;
        }
        $offers[] = array(
            '@type'         => 'Offer',
            'name'          => $label . ' билет',
            'price'         => (string) (float) $prices[$type],
            'priceCurrency' => 'RUB',
            'availability'  => 'https://schema.org/InStock',
            'url'           => home_url('/#tickets'),
        );
    }
    return $offers;
}

/** Boat prices as Offers, one per confirmed duration. */
function wt_river_seo_boat_offers() {
    if (!function_exists('water_tours_boat_prices')) { return array(); }
    $prices = water_tours_boat_prices();
    $offers = array();
    foreach ($prices as $minutes => $price) {
        if (!is_numeric($price) || (float) $price <= 0) { continue; }
        $offers[] = array(
            '@type'         => 'Offer',
            'name'          => sprintf('Аренда катера, %d минут', (int) $minutes),
            'price'         => (string) (float) $price,
            'priceCurrency' => 'RUB',
            'availability'  => 'https://schema.org/InStock',
            'url'           => home_url('/#boat'),
        );
    }
    return $offers;
}

/**
 * robots.txt.
 *
 * Every rule here is about not wasting a crawler's time on something that can never be a search
 * result: the order API answers JSON, the staff console answers a login redirect, and a ticket
 * page needs a token. None of this is a security control - `Disallow` is a request, and a
 * crawler that ignores it still meets the access token and the login form.
 */
add_filter('robots_txt', function ($output, $public) {
    if (!$public) { return $output; }
    $rules = "\n# Application surfaces. Not search results, and not protected by this file:\n"
        . "# the order API needs an access token and the staff console needs a login.\n"
        . "Disallow: /api/\n"
        . "Disallow: /staff\n"
        . "Disallow: /t/\n"
        . "Disallow: /checkout.html\n"
        . "Disallow: /login\n";
    return $output . $rules;
}, 10, 2);

/**
 * Sitemap. Core generates `wp-sitemap.xml`; this only removes what should not be in it.
 *
 * The users provider was already removed (author archives are noindex above and lead nowhere).
 * Nothing else needs excluding: orders, tickets and PDFs are not WordPress content, so core has
 * no way to list them in the first place.
 */
add_filter('wp_sitemaps_add_provider', function ($provider, $name) {
    return $name === 'users' ? false : $provider;
}, 10, 2);

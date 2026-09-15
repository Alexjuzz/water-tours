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

/**
 * The front page title, in one place.
 *
 * It names both things the page sells. It used to say only речные прогулки, while half the page
 * - the second booking card, the second `Service` in the JSON-LD below - is the boat. A title
 * that describes half the page is a worse answer than one that describes it.
 *
 * The wording is the page's own (`Аренда катера`, as in the card and in the structured data),
 * not a phrase picked to match a keyword list: the research file shows searchers type
 * `прогулка на катере` and never `аренда`, but the site has to call the product what it
 * actually is. Recorded as an owner question instead.
 */
function wt_river_seo_title() {
    return apply_filters(
        'water_tours_seo_title',
        'Речные прогулки и аренда катера в Петербурге | Water Tours'
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
    return is_front_page() ? wt_river_seo_title() : $title;
});

/**
 * Keep copies of the front page out of the index - all of them, and in one robots tag.
 *
 * Two separate things were wrong here.
 *
 * **The rule named the wrong views.** It listed date, author and tag archives, search and paged
 * views, on the grounds that they are thin. Thinness is not the problem. This theme ships exactly
 * two templates, `front-page.php` and `index.php`, and `index.php` renders `home-river.php` - the
 * front page. So *every* view that falls through to `index.php` answers 200 with a copy of the one
 * commercial page at a different URL: category and custom post type archives, the posts page, and
 * the expensive one - every ordinary WordPress page or post, which core additionally lists in
 * `wp-sitemap.xml` and canonicalises to itself. Those were indexable duplicates competing with the
 * page that sells the tickets, and the old list did not cover a single one of them.
 *
 * The rule below is the actual reason: if WordPress chose `index.php` and this is not the front
 * page, the response is a copy, so it is `noindex`. It needs no list to maintain and it switches
 * itself off view by view - which is exactly what happened when `singular.php` arrived: pages and
 * posts stopped resolving to `index.php` and stopped being noindexed, with no edit here. What is
 * still covered is the views that have no template of their own - category, tag and custom post
 * type archives, and the posts page. The old list is kept as a floor, because search, 404 and
 * attachments should stay out of the index whatever template renders them.
 *
 * **It printed a second robots tag.** Core hooks `wp_robots()` to `wp_head` at priority 1 and
 * registers `wp_robots_max_image_preview_large` by default, so every page already carries a
 * `<meta name="robots">`. Echoing another one at the same priority put two of them on exactly the
 * views that mattered.
 *
 * To be accurate about what that was and was not: two robots tags are **not invalid**, and the
 * pages were not being mis-crawled because of it. Google reads the directives from every robots
 * meta tag on the page and combines them, applying the most restrictive of any that conflict, so
 * `max-image-preview:large` plus `noindex,follow` was already understood as intended. The reason
 * to merge them is that one tag is the only form whose meaning cannot change when something else
 * later adds a directive - and that this file's first rule is not to print a tag core already
 * prints. Going through the `wp_robots` filter (WP 5.7+) merges the directives into the tag core
 * emits. `follow` comes from core's `wp_robots_no_robots()`, which also downgrades it to
 * `nofollow` on a site marked non-public.
 */

/**
 * The template WordPress resolved for this request, remembered on the way past.
 *
 * `template_include` has already run by the time `wp_head` fires: the loader filters the path,
 * then includes the file, and the head is inside it.
 */
function wt_river_seo_rendered_template($template = null) {
    static $current = '';
    if ($template !== null) { $current = (string) $template; }
    return $current;
}
add_filter('template_include', function ($template) {
    wt_river_seo_rendered_template($template);
    return $template;
}, PHP_INT_MAX);

/**
 * True when this request is the front page's markup served at some other URL. If `template_include`
 * somehow did not run, this answers false rather than guessing - a needless `noindex` on a real
 * page costs more than one duplicate does.
 */
function wt_river_seo_renders_front_page_copy() {
    if (is_front_page()) { return false; }
    $template = wt_river_seo_rendered_template();
    return $template !== '' && basename($template) === 'index.php';
}

/**
 * Every view this theme keeps out of the index, whatever emits the tag.
 *
 * `is_attachment()` is on the list because `singular.php` sits under attachment pages in the
 * hierarchy too, and an attachment page is a URL whose whole content is one uploaded file - not a
 * page of this site in any sense a searcher would want. On WordPress 7.1 it never fires: core
 * redirects attachment URLs, checked on the stand (`/sinteticheskoe-vlozhenie/` answers 301). It
 * is here for the installs where a plugin turns that redirect off, and it costs one function call.
 */
function wt_river_seo_should_noindex() {
    if (is_search() || is_404() || is_date() || is_author() || is_tag() || is_attachment() || is_paged()) { return true; }
    return wt_river_seo_renders_front_page_copy();
}

add_filter('wp_robots', function ($robots) {
    if (wt_river_seo_handled_elsewhere()) { return $robots; }
    if (!wt_river_seo_should_noindex()) { return $robots; }
    return wp_robots_no_robots($robots);
});

/**
 * WordPress before 5.7 has no `wp_robots` filter and emits no robots tag of its own, so there is
 * nothing to merge into and nothing to duplicate. Printing the tag is correct there, and only
 * there.
 */
add_action('wp_head', function () {
    if (function_exists('wp_robots')) { return; }
    if (wt_river_seo_handled_elsewhere()) { return; }
    if (wt_river_seo_should_noindex()) {
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
    $title = wt_river_seo_title();

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
 * the backend catalogue.
 *
 * This comment used to say the block is emitted without offers if the backend is unreachable.
 * That was not true: the plugin answers with its seed values in that case and the offers were
 * built from them regardless. What is true now is narrower and is enforced in
 * `wt_river_seo_ticket_offers()` - offers are published only when the numbers came from the
 * catalogue, on this request or the last time the backend answered here. A `Service` with no
 * usable price is emitted without an `offers` key rather than with a guessed one, and the visible
 * page is unaffected either way: the order form gets its numbers from the plugin, not from here.
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

    $ticket_service = array(
        '@type'       => 'Service',
        '@id'         => $home . '#service-ticket',
        'name'        => 'Билет на речную прогулку',
        'serviceType' => 'Речная прогулка',
        'provider'    => array('@id' => $home . '#organization'),
        'areaServed'  => array('@type' => 'City', 'name' => 'Санкт-Петербург'),
        'description' => 'Электронный билет с QR-кодом на речную прогулку. Действует 72 часа '
            . 'с момента подтверждённой оплаты, даёт право на один проход. Рейс и место не '
            . 'закрепляются.',
    );
    $ticket_offers = wt_river_seo_ticket_offers();
    if (!empty($ticket_offers)) { $ticket_service['offers'] = $ticket_offers; }
    $graph[] = $ticket_service;

    $boat_service = array(
        '@type'       => 'Service',
        '@id'         => $home . '#service-boat',
        'name'        => 'Аренда катера',
        'serviceType' => 'Аренда катера с командой',
        'provider'    => array('@id' => $home . '#organization'),
        'areaServed'  => array('@type' => 'City', 'name' => 'Санкт-Петербург'),
        'description' => 'Катер целиком для вашей компании, до 6 гостей. Продолжительность '
            . 'выбирается при оформлении; маршрут и время выхода согласуются отдельно.',
    );
    $boat_offers = wt_river_seo_boat_offers();
    if (!empty($boat_offers)) { $boat_service['offers'] = $boat_offers; }
    $graph[] = $boat_service;

    $payload = array('@context' => 'https://schema.org', '@graph' => $graph);
    echo '<script type="application/ld+json">'
        . wp_json_encode($payload, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES)
        . '</script>' . "\n";
}, 4);

/**
 * True when the price list behind the markup is the published catalogue rather than the
 * plugin's seed values.
 *
 * `water_tours_buy_prices_are_published()` is the plugin's own answer and is authoritative. The
 * `function_exists` guard is for the theme running without the plugin, and the answer there is
 * "no" - no plugin, no published prices, no offers.
 */
function wt_river_seo_prices_are_published() {
    return function_exists('water_tours_buy_prices_are_published')
        && water_tours_buy_prices_are_published();
}

/**
 * One amount as schema.org wants it: a plain number, in the currency named beside it, with the
 * kopecks the catalogue actually has and no others.
 *
 * `(string) (float) 1500.5` would emit `1500.5`, which is valid, but `1500.50` is what the
 * catalogue holds and what the customer is charged, so that is what is published.
 */
function wt_river_seo_price_string($amount) {
    $amount = round((float) $amount, 2);
    $decimals = (abs($amount - round($amount)) < 0.005) ? 0 : 2;
    return number_format($amount, $decimals, '.', '');
}

/**
 * Ticket prices as schema.org Offers, straight from the published catalogue - or no offers at all.
 *
 * The gate matters more than the loop. When the backend has never answered on this install, the
 * plugin still hands the page a price list so the order form has something in it, but those are
 * its seed values, not this site's prices: on 2026-09-15 the live catalogue had BENEFIT at 1054
 * and the 30-minute rental at 35010, while the seed says 1020 and 3500. Publishing the seed as an
 * `Offer` tells a search engine the site charges an amount it does not charge. An absent offer is
 * merely less informative; a wrong one is false.
 */
function wt_river_seo_ticket_offers() {
    if (!function_exists('water_tours_buy_ticket_prices') || !function_exists('water_tours_buy_ticket_types')) {
        return array();
    }
    if (!wt_river_seo_prices_are_published()) { return array(); }
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
            'price'         => wt_river_seo_price_string($prices[$type]),
            'priceCurrency' => 'RUB',
            'availability'  => 'https://schema.org/InStock',
            'url'           => home_url('/#tickets'),
        );
    }
    return $offers;
}

/** Boat prices as Offers, one per confirmed duration. Same gate, same reason. */
function wt_river_seo_boat_offers() {
    if (!function_exists('water_tours_boat_prices')) { return array(); }
    if (!wt_river_seo_prices_are_published()) { return array(); }
    $prices = water_tours_boat_prices();
    $offers = array();
    foreach ($prices as $minutes => $price) {
        if (!is_numeric($price) || (float) $price <= 0) { continue; }
        $offers[] = array(
            '@type'         => 'Offer',
            'name'          => sprintf('Аренда катера, %d минут', (int) $minutes),
            'price'         => wt_river_seo_price_string($price),
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
 * A sitemap is a list of pages the site is asking to have indexed, so it must not contain a URL
 * the site simultaneously marks `noindex` - that asks a crawler for two opposite things. Two
 * providers are dropped:
 *
 * - **users**: author archives are noindex and lead nowhere.
 * - **taxonomies**: category and tag archives have no template in this theme, so they render the
 *   front page and are noindex by the rule above. The check is on the template, not hardcoded, so
 *   adding `archive.php` or `category.php` puts them back in the sitemap by itself.
 *
 * The `posts` provider stays. It is the provider core uses to put the **home URL** in the sitemap,
 * so removing it would take the front page - the only page that matters - out with it. Pages and
 * posts belong in it too, now that `singular.php` gives them a template of their own and they are
 * no longer copies of the front page.
 *
 * One entry is still removed from it, below: the page assigned as the posts page.
 *
 * Nothing else needs excluding: orders, tickets and PDFs are not WordPress content, so core has
 * no way to list them in the first place.
 */
add_filter('wp_sitemaps_add_provider', function ($provider, $name) {
    if ($name === 'users') { return false; }
    if ($name === 'taxonomies' && !wt_river_seo_has_archive_template()) { return false; }
    return $provider;
}, 10, 2);

/**
 * Keep the posts page out of the sitemap while it has no template of its own.
 *
 * A static front page plus a posts page is the one case `singular.php` does not reach. WordPress
 * renders the posts page through `home.php`, and with no `home.php` that falls to `index.php` -
 * the landing page again, so the rule above marks it `noindex`. Core meanwhile lists it in the
 * page sitemap like any other page, which is the exact contradiction this block exists to remove.
 *
 * Verified against WordPress 7.1 on an isolated stand with `page_on_front` and `page_for_posts`
 * both set: core replaces the front page's own URL with the home URL by itself, and leaves the
 * posts page in. So this excludes the posts page and nothing else.
 *
 * Like the taxonomy rule, it is written as a question about templates, not as a hardcoded id: add
 * `home.php` and the posts page becomes a real page and returns to the sitemap with no edit here.
 */
add_filter('wp_sitemaps_posts_query_args', function ($args, $post_type) {
    if ($post_type !== 'page') { return $args; }
    if (locate_template(array('home.php')) !== '') { return $args; }
    $posts_page = (int) get_option('page_for_posts');
    if ($posts_page <= 0) { return $args; }
    $excluded = isset($args['post__not_in']) && is_array($args['post__not_in']) ? $args['post__not_in'] : array();
    $excluded[] = $posts_page;
    $args['post__not_in'] = $excluded;
    return $args;
}, 10, 2);

/** Whether the theme (or a child theme) ships any template an archive view could resolve to. */
function wt_river_seo_has_archive_template() {
    return locate_template(array('archive.php', 'category.php', 'tag.php', 'taxonomy.php')) !== '';
}

<?php
/**
 * Contact and boarding information: what is said, and why the wording is this careful.
 *
 * The owner's first proposal combined three phrases into one address: "Кронверкская набережная",
 * "Александровский парк, 8" and "причал номер 1". Checked against public sources before writing
 * a word of this - an operator pier directory (nevatrip.ru) and an independent listing
 * (peterburg2.ru) - and they do not describe one place:
 *
 * - A real, numbered "Причал №1" does exist on Кронверкская набережная - registered "у
 *   Иоанновского моста" (at the Ioannovsky bridge), not at any address called "Александровский
 *   парк, 8".
 * - A different, separately-numbered pier ("Кронверкский мост") has the registered address
 *   "Александровский парк, д. 1" - number 1, not 8. No public source found registers
 *   "Александровский парк, 8" as a pier, or as anything.
 *
 * So the original three phrases named at least two different piers plus a house number no source
 * confirms. Told this, the owner resolved it directly: use причал №1 - which is exactly the real,
 * publicly registered pier on Кронверкская набережная above, not the disputed park address. That
 * is what is published below. Full sourcing and the exchange are in target/CONTACTS-RESULT.md.
 *
 * A pier is a boarding point, not this company's registered office - nothing here is phrased as
 * one, and nothing here is added to the structured data in inc/seo.php, which already reasons
 * through exactly this distinction for LocalBusiness. Every value below is filterable and the
 * Telegram username is overridable by a constant, so none of this needs a code edit or a
 * redeploy to change - matching how `wt_river_seo_title()` and its neighbours already work.
 *
 * @package water-tours-river
 */

if (!defined('ABSPATH')) { exit; }

/** The one city fact this site states about where trips run. */
function wt_river_contact_city() {
    return apply_filters('water_tours_contact_city', 'Санкт-Петербург');
}

/**
 * The boarding point, stated as what it is - a pier, confirmed as this one and not asserted as
 * where every future route or operator departs from.
 */
function wt_river_contact_boarding() {
    return apply_filters(
        'water_tours_contact_boarding',
        'Причал №1, Кронверкская набережная (у Иоанновского моста)'
    );
}

/**
 * The bot's own public @username, Telegram's leading-"@" convention stripped for the URL.
 * Overridable by a constant (set in `wp-config.php`, no theme edit needed) so this stays correct
 * if the bot is ever renamed. The default below is the bot actually running behind this site's
 * support flow - confirmed public on t.me before this was written, not a placeholder.
 */
function wt_river_contact_telegram_username() {
    $username = defined('WATER_TOURS_TELEGRAM_BOT_USERNAME')
        ? WATER_TOURS_TELEGRAM_BOT_USERNAME
        : 'water_tours_bot';
    $username = (string) apply_filters('water_tours_telegram_bot_username', $username);
    return ltrim($username, '@');
}

/**
 * A direct link into the bot's own question flow. `?start=question` is a real, already-handled
 * deep-link payload (`TelegramUpdateHandler.QUESTION_START_PAYLOAD`, dispatched to
 * `startQuestion()`), not invented here - opening it starts exactly the conversation the on-page
 * "Задать вопрос" form already leads to, for a visitor who would rather message directly than
 * fill in a web form.
 *
 * Returns '' when no username is configured, so the caller can skip the link entirely rather than
 * print one that goes nowhere.
 */
function wt_river_contact_telegram_url() {
    $username = wt_river_contact_telegram_username();
    if ($username === '') { return ''; }
    return apply_filters(
        'water_tours_contact_telegram_url',
        'https://t.me/' . $username . '?start=question'
    );
}

/**
 * `[water_tours_contacts]` - the same three facts the footer already shows, rendered as a small
 * block a page's own content can embed. One source (the three functions above) for both places,
 * so editing a filter or the username constant updates the footer and every page using this
 * shortcode together; nothing here duplicates the text as a second copy that could drift.
 *
 * Used by the `/contacts/` page created for the owner's Yandex regional-affiliation submission
 * (see target/CONTACTS-RESULT.md) - not required by anything else, so it degrades harmlessly
 * (renders only the city, or nothing, if boarding/Telegram values are ever filtered empty).
 */
add_shortcode('water_tours_contacts', function () {
    $city = wt_river_contact_city();
    $boarding = wt_river_contact_boarding();
    $telegram = wt_river_contact_telegram_url();

    ob_start();
    ?>
    <p class="wt-contacts-city"><strong><?php echo esc_html($city); ?></strong></p>
    <?php if ($boarding !== '') : ?>
    <p class="wt-contacts-boarding">Посадка: <?php echo esc_html($boarding); ?>. Это причал отправления, а не офис компании — конкретный рейс и время подтверждаются в заказе.</p>
    <?php endif; ?>
    <?php if ($telegram !== '') : ?>
    <p class="wt-contacts-telegram"><a class="btn btn-ask" href="<?php echo esc_url($telegram); ?>" target="_blank" rel="noopener noreferrer" data-wt-contact-cta="contacts_page">Написать в Telegram <span aria-hidden="true">↗</span></a></p>
    <?php endif; ?>
    <?php
    return ob_get_clean();
});

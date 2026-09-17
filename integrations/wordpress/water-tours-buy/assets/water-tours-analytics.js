/**
 * Consent-aware analytics for the purchase flow.
 *
 * Four properties this file is built around, in order of importance:
 *
 * 1. **It is off until somebody configures it.** `counterId` is empty by default and there is no
 *    fallback value anywhere in this file. With no counter configured the module registers no
 *    listeners, shows no banner and loads nothing - the site behaves exactly as it does today.
 * 2. **Nothing leaves the browser before consent.** Events that happen before a decision are held
 *    in memory, not sent and not written to storage. The provider's own script is not loaded
 *    either, because loading it *is* the tracking. Declining clears the buffer and the module
 *    goes quiet permanently for that browser.
 * 3. **Its own event payloads carry no way to identify the customer.** The payload is an event
 *    name, which product it was *when the caller actually knows one* (never guessed - see
 *    `payloadFor()`), an order id used purely for de-duplication, and an amount. No e-mail, no
 *    phone, no access token, no ticket code, no QR, and never a URL with a query string - the
 *    order status and PDF URLs carry the access token, so the path is sent without it.
 * 4. **Neither does the vendor's own automatic tracking.** Yandex Metrica's tag would, unmanaged,
 *    report `location.href` and `document.referrer` verbatim on every load, and treat every
 *    clicked link as worth reporting the destination of. `location.href` on this site has already
 *    carried a real secret once - the PDF download link embeds the order's access token in its
 *    `href` even in browsers that never navigate to it (`pdfLink()` in `water-tours-buy.js`) -
 *    which is exactly why the vendor's automatic first hit is replaced with one built from an
 *    allowlist (`sanitizedPageUrl()`, `sanitizedReferrer()`) and outbound-link tracking is off
 *    (`trackLinks: false`) rather than trusted to run after this site's own click handler.
 *
 * The site's own code never calls a vendor API directly: `water-tours-buy.js` and `river.js`
 * dispatch a `wt:analytics` DOM event and this module decides whether anything happens. That is
 * what makes the whole flow testable against a mock sink with no counter in existence - though a
 * mock sink only proves this file's own logic, not what the real vendor script does with a real
 * network request; see `target/METRICA-REVIEW-RESULT.md` for what was additionally checked live.
 *
 * **Second-pass corrections** (after review of `92bd07f`): `product` no longer defaults to
 * `'ticket'` for events that have none; the de-duplication key now includes `product`, so an
 * independent ticket `form_open` and boat `form_open` no longer share one key and silently
 * suppress each other; `sanitizedReferrer()` now strips the query/fragment from every referrer,
 * not only same-origin ones; the manual `hit()` call never falls back to `undefined` for an
 * empty referrer, which would have hit the vendor's own default (the raw, unsanitized
 * `document.referrer`) by omission; and UTM values are now validated against a character
 * pattern, not merely length-capped.
 */
(function () {
  'use strict';

  var config = window.WaterToursAnalytics || {};
  var COUNTER_ID = String(config.counterId || '').trim();
  var SINK = String(config.debugSink || '').trim();
  var PROVIDER = String(config.provider || 'metrica').trim();
  var CONSENT_KEY = config.consentKey || 'wt_analytics_consent';
  // Bump when what is collected changes materially; an old answer then stops counting.
  var CONSENT_VERSION = String(config.consentVersion || '1');
  var SENT_KEY = 'wt_analytics_sent';

  // Nothing is configured: no counter and no mock sink. Do absolutely nothing.
  if (!COUNTER_ID && !SINK) return;

  var EVENTS = {
    form_open: true,
    order_created: true,
    payment_redirect: true,
    payment_confirmed: true,
    pdf_download: true,
    checkout_error: true,
    // Dispatched by river.js, not water-tours-buy.js: a click on the "Написать в Telegram" CTA
    // (footer or the /contacts/ page), and a support question the backend confirmed it accepted.
    // Neither carries the question text, the contact given, the reference number, or which page
    // fired it beyond the short `source` tag payloadFor() already bounds and truncates.
    contact_cta_click: true,
    support_inquiry_sent: true
  };

  var pending = [];
  var loaded = false;

  // ------------------------------------------------------------------ consent storage

  function readConsent() {
    try {
      var raw = localStorage.getItem(CONSENT_KEY);
      if (!raw) return null;
      var value = JSON.parse(raw);
      return (value && value.version === CONSENT_VERSION) ? value.decision : null;
    } catch (e) {
      return null;
    }
  }

  function writeConsent(decision) {
    try {
      localStorage.setItem(CONSENT_KEY, JSON.stringify({
        decision: decision, version: CONSENT_VERSION, at: new Date().toISOString()
      }));
    } catch (e) {
      // A browser that refuses storage simply gets asked again next time, which is the safe
      // direction: it means "no consent recorded", not "consent granted".
    }
  }

  // ------------------------------------------------------------------ de-duplication

  /**
   * One event per order per kind, **within this browser tab's session and no further**. The
   * status endpoint is polled, so "payment confirmed" would otherwise be reported on every poll;
   * a page reload would repeat it again. The key never leaves the browser - it is only how this
   * module remembers what it already sent.
   *
   * What this is not: a claim of exactly-once delivery. A new tab, a different browser, a
   * cleared session, or the same order revisited days later all start with an empty `seen` list
   * and can report the same order again - `sessionStorage` cannot see any of those. The Metrica
   * dashboard for this counter is therefore a rough signal, not an authoritative once-per-order
   * count; see `water-tours-buy.js`'s freshness guard on `payment_confirmed` for the one place
   * this actually matters (revenue/ecommerce is not wired at all - see `target/METRICA-RESULT.md`
   * stage 2).
   */
  function alreadySent(key) {
    try {
      var seen = JSON.parse(sessionStorage.getItem(SENT_KEY) || '[]');
      if (seen.indexOf(key) !== -1) return true;
      seen.push(key);
      // Bounded: a browsing session cannot grow this without limit.
      if (seen.length > 50) seen = seen.slice(-50);
      sessionStorage.setItem(SENT_KEY, JSON.stringify(seen));
      return false;
    } catch (e) {
      return false;
    }
  }

  // ------------------------------------------------------------------ what gets sent

  /**
   * Builds the payload. This is the function to read when asking "what does analytics know about
   * a customer", and the answer is: which product (only when the caller actually knows one),
   * how much, and an opaque order id.
   *
   * `product` used to default to `'ticket'` whenever the caller didn't say - which silently
   * mislabeled `contact_cta_click` and `support_inquiry_sent` (river.js never passes a product;
   * neither event has one) as ticket-flow events. It is now included only when the caller passed
   * exactly `'ticket'` or `'boat'`, and omitted otherwise - a missing field, not a guessed one.
   */
  function payloadFor(detail) {
    var out = {
      event: detail.event,
      // The path only. `location.href` on a provider return carries the order token in the query
      // string, and that must never reach an analytics vendor.
      page: location.pathname
    };
    if (detail.product === 'ticket' || detail.product === 'boat') out.product = detail.product;
    if (detail.orderId) out.orderId = String(detail.orderId);
    if (typeof detail.amount === 'number' && isFinite(detail.amount)) {
      out.amount = detail.amount;
      out.currency = 'RUB';
    }
    // A short, fixed vocabulary - never a server message, which could contain anything.
    if (detail.errorKind) out.errorKind = String(detail.errorKind).slice(0, 40);
    // Same idea for `source`: a caller-chosen UI-location tag ('footer', 'contacts_page'), never
    // free text.
    if (detail.source) out.source = String(detail.source).slice(0, 20);
    return out;
  }

  // ------------------------------------------------------------------ delivery

  var UTM_ALLOWLIST = ['utm_source', 'utm_medium', 'utm_campaign', 'utm_term', 'utm_content'];
  // A UTM value is a label someone put in a link, not free text: letters (including Cyrillic,
  // since a Russian ad network can label a campaign in Russian), digits, and the handful of
  // separators real UTM values actually use. This is a validator, not just a length cap - a
  // value shaped like an e-mail, a UUID-with-dashes-in-odd-places, or containing characters this
  // pattern excludes is dropped whole rather than truncated and forwarded anyway.
  var UTM_VALUE_PATTERN = /^[a-zA-Zа-яА-ЯёЁ0-9_.\-]{1,100}$/;

  /**
   * The page URL Metrica is told about - never the real one. `location.href` can, on this site,
   * carry values this file exists to keep away from a vendor (see property 4 at the top); keeping
   * only the path plus a fixed allowlist of UTM parameters, each checked against
   * `UTM_VALUE_PATTERN`, means any OTHER query parameter - whatever it is, today or in a future
   * change - is dropped by construction, and a UTM parameter whose *value* doesn't look like a
   * UTM value is dropped too, not forwarded because its name matched. This is the "validate UTM
   * values, not just their names/length" boundary, and the "do not assume stripping only
   * event.page is sufficient" one: the vendor's own page-view report is sanitized the same way
   * the event payloads already are.
   */
  function sanitizedPageUrl() {
    var kept = [];
    try {
      var params = new URLSearchParams(location.search);
      for (var i = 0; i < UTM_ALLOWLIST.length; i++) {
        var key = UTM_ALLOWLIST[i];
        var value = params.get(key);
        if (value && UTM_VALUE_PATTERN.test(value)) kept.push(key + '=' + encodeURIComponent(value));
      }
    } catch (e) { /* a malformed query string reports the path alone, not a raw copy of itself */ }
    return location.origin + location.pathname + (kept.length ? '?' + kept.join('&') : '');
  }

  /**
   * `document.referrer` is useful for naming the *site* a visitor arrived from - that is the
   * entire point of tracking one. It is never useful, and is a real risk, for naming the exact
   * *page* on any site, this one included: this site's own URLs have carried a query string that
   * mattered (see `pdfLink()`'s token, and `sanitizedPageUrl()` above), and there is no reason to
   * assume every other site's URLs never do either - a referrer from a forum thread, a webmail
   * client, or an internal company tool can carry a session id, a ticket number, anything its own
   * app put in the address bar. So the query string and fragment are dropped from *every*
   * referrer, same-origin or not, keeping only the origin and path - not only the internal case
   * this file used to special-case.
   */
  function sanitizedReferrer() {
    var raw = document.referrer;
    if (!raw) return '';
    try {
      var url = new URL(raw);
      return url.origin + url.pathname;
    } catch (e) {
      return '';
    }
  }

  function loadProvider() {
    if (loaded) return;
    loaded = true;
    if (SINK) return;                 // the mock sink needs no vendor script
    if (PROVIDER !== 'metrica') return;
    // Yandex Metrica's own loader, unchanged, and only ever reached after consent.
    window.ym = window.ym || function () { (window.ym.a = window.ym.a || []).push(arguments); };
    window.ym.l = Number(new Date());
    var script = document.createElement('script');
    script.async = true;
    script.src = 'https://mc.yandex.ru/metrika/tag.js';
    document.head.appendChild(script);
    window.ym(COUNTER_ID, 'init', {
      // No session recording, no form contents, no click map: this counter exists to count
      // purchases, and every one of those features collects more than that needs.
      clickmap: false,
      // The PDF download link's href carries the order's access token, in every browser, even the
      // ones that never navigate to it - `pdfLink()` in water-tours-buy.js sets it unconditionally
      // and only intercepts the click where the safer fetch-as-blob path is available. Metrica's
      // own outbound-link listener is typically attached at the document level and there is no
      // guarantee it runs after this site's click handler has already prevented the default
      // navigation. Off, not "probably fine".
      trackLinks: false,
      accurateTrackBounce: true,
      webvisor: false,
      trackHash: false,
      // Un-set, the tag sends its own first hit automatically, built from the real location.href
      // and document.referrer - precisely what this file exists to keep sanitized. `defer` turns
      // that off (Yandex's own documented purpose for the flag); the hit below is the sanitized
      // replacement, sent explicitly instead of trusted to the vendor's default.
      defer: true
    });
    // Always the sanitized string, even when it is empty - `referer: sanitizedReferrer() ||
    // undefined` used to fall back to `undefined` for an empty result, and `undefined` means
    // "field not specified" to most JS APIs, including this one: it would have handed the vendor
    // library the exact freedom to fall back to its own default, which is the real, unsanitized
    // `document.referrer` - restoring by omission the thing this function exists to remove.
    window.ym(COUNTER_ID, 'hit', sanitizedPageUrl(), { referer: sanitizedReferrer() });
  }

  function deliver(payload) {
    if (SINK) {
      try {
        var body = JSON.stringify(payload);
        if (navigator.sendBeacon) {
          // text/plain, not application/json. sendBeacon cannot make a preflighted request, and
          // application/json is not a CORS-safelisted content type - the browser drops the beacon
          // silently, which looks exactly like "analytics is broken". The body is still JSON.
          navigator.sendBeacon(SINK, new Blob([body], { type: 'text/plain;charset=UTF-8' }));
        } else {
          fetch(SINK, { method: 'POST', headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
                        body: body, credentials: 'omit', keepalive: true, mode: 'cors' });
        }
      } catch (e) { /* a sink that is down must never break the purchase */ }
      return;
    }
    if (PROVIDER === 'metrica' && typeof window.ym === 'function') {
      try {
        window.ym(COUNTER_ID, 'reachGoal', payload.event, payload);
      } catch (e) { /* same */ }
    }
  }

  /**
   * `product` is part of the key on purpose: without it, a ticket `form_open` and a boat
   * `form_open` on the same page - two genuinely independent interactions, both legitimate - had
   * the exact same key (`form_open:/`) and only the first ever got through in a session. The
   * fallback to `page` when there is no `orderId` is what "de-duplicated" means for a click-shaped
   * event, and it is worth being explicit about: this reports *whether* the interaction happened
   * at least once in this browser tab's session, not *how many times* it was clicked - a repeat
   * click on the same CTA, or opening the same form twice, is one signal, not a running count.
   */
  function keyFor(payload) {
    return payload.event + ':' + (payload.product || '') + ':' + (payload.orderId || payload.page);
  }

  function send(detail) {
    if (!detail || !EVENTS[detail.event]) return;
    var payload = payloadFor(detail);
    if (alreadySent(keyFor(payload))) return;
    deliver(payload);
  }

  function flush() {
    loadProvider();
    var queued = pending;
    pending = [];
    for (var i = 0; i < queued.length; i++) send(queued[i]);
  }

  // ------------------------------------------------------------------ the banner

  function askForConsent() {
    if (document.getElementById('wt-consent')) return;
    var bar = document.createElement('div');
    bar.id = 'wt-consent';
    bar.className = 'wt-consent';
    bar.setAttribute('role', 'region');
    bar.setAttribute('aria-label', 'Согласие на аналитику');

    var text = document.createElement('p');
    text.className = 'wt-consent-text';
    text.textContent = config.text
      || 'Мы хотели бы считать статистику посещений и оформленных заказов, чтобы понимать, '
       + 'что на сайте работает. Это анонимные счётчики: имя, почта, телефон и данные билета '
       + 'в статистику не передаются. Без вашего согласия счётчик не загружается.';

    var accept = document.createElement('button');
    accept.type = 'button';
    accept.className = 'wt-consent-accept';
    accept.textContent = config.acceptLabel || 'Разрешить';

    var decline = document.createElement('button');
    decline.type = 'button';
    decline.className = 'wt-consent-decline';
    decline.textContent = config.declineLabel || 'Не надо';

    accept.addEventListener('click', function () {
      writeConsent('granted');
      bar.remove();
      flush();
    });
    decline.addEventListener('click', function () {
      writeConsent('denied');
      pending = [];
      bar.remove();
    });

    var actions = document.createElement('div');
    actions.className = 'wt-consent-actions';
    actions.appendChild(accept);
    actions.appendChild(decline);
    bar.appendChild(text);
    bar.appendChild(actions);
    document.body.appendChild(bar);
  }

  // ------------------------------------------------------------------ wiring

  var decision = readConsent();

  document.addEventListener('wt:analytics', function (event) {
    var detail = event.detail || {};
    if (readConsent() === 'granted') {
      loadProvider();
      send(detail);
      return;
    }
    if (readConsent() === 'denied') return;
    // Undecided: keep it in memory only, bounded, and never written anywhere.
    if (pending.length < 20) pending.push(detail);
  });

  if (decision === 'granted') {
    loadProvider();
  } else if (decision !== 'denied') {
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', askForConsent);
    } else {
      askForConsent();
    }
  }
})();

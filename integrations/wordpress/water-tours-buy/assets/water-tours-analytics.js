/**
 * Consent-aware analytics for the purchase flow.
 *
 * Three properties this file is built around, in order of importance:
 *
 * 1. **It is off until somebody configures it.** `counterId` is empty by default and there is no
 *    fallback value anywhere in this file. With no counter configured the module registers no
 *    listeners, shows no banner and loads nothing - the site behaves exactly as it does today.
 * 2. **Nothing leaves the browser before consent.** Events that happen before a decision are held
 *    in memory, not sent and not written to storage. The provider's own script is not loaded
 *    either, because loading it *is* the tracking. Declining clears the buffer and the module
 *    goes quiet permanently for that browser.
 * 3. **It carries no way to identify the customer.** The payload is an event name, which of the
 *    two products it was, an order id used purely for de-duplication, and an amount. No e-mail,
 *    no phone, no access token, no ticket code, no QR, and never a URL with a query string -
 *    the order status and PDF URLs carry the access token, so the path is sent without it.
 *
 * The site's own code never calls a vendor API directly: `water-tours-buy.js` dispatches a
 * `wt:analytics` DOM event and this module decides whether anything happens. That is what makes
 * the whole flow testable against a mock sink with no counter in existence.
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
    checkout_error: true
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
   * One event per order per kind. The status endpoint is polled, so "payment confirmed" would
   * otherwise be reported on every poll; a page reload would repeat it again. The key never
   * leaves the browser - it is only how this module remembers what it already sent.
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
   * a customer", and the answer is: which product, how much, and an opaque order id.
   */
  function payloadFor(detail) {
    var out = {
      event: detail.event,
      product: detail.product === 'boat' ? 'boat' : 'ticket',
      // The path only. `location.href` on a provider return carries the order token in the query
      // string, and that must never reach an analytics vendor.
      page: location.pathname
    };
    if (detail.orderId) out.orderId = String(detail.orderId);
    if (typeof detail.amount === 'number' && isFinite(detail.amount)) {
      out.amount = detail.amount;
      out.currency = 'RUB';
    }
    // A short, fixed vocabulary - never a server message, which could contain anything.
    if (detail.errorKind) out.errorKind = String(detail.errorKind).slice(0, 40);
    return out;
  }

  // ------------------------------------------------------------------ delivery

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
      trackLinks: true,
      accurateTrackBounce: true,
      webvisor: false,
      trackHash: false
    });
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

  function send(detail) {
    if (!detail || !EVENTS[detail.event]) return;
    var payload = payloadFor(detail);
    var key = payload.event + ':' + (payload.orderId || payload.page);
    if (alreadySent(key)) return;
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

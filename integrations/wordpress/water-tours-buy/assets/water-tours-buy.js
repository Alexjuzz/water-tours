/**
 * Purchase flow for both products: passenger tickets and a whole-boat rental.
 *
 * The two flows differ only in what the order body contains and what the screens are called, so
 * they share one state machine here. Everything a customer can see after submitting - paid,
 * still processing, not paid, cancelled, refunded, or a failed request - is driven by one
 * token-protected read of GET /api/v1/orders/{id}/status. Guessing "paid" from whether a ticket
 * list happens to be empty reads a cancelled payment as a slow one, which is what this replaces.
 */
(function () {
  'use strict';

  var config = window.WaterToursConfig || {};

  function backendUrl(path) {
    return (config.backendUrl || '').replace(/\/$/, '') + path;
  }

  /**
   * Random values used as credentials: the retry key and the secret that proves this browser owns
   * the order it created. Math.random() was the old fallback here and is not a cryptographic RNG -
   * a predictable key is a key somebody else can present. crypto.getRandomValues is available
   * wherever randomUUID is not (it predates it by years); if neither exists the flow refuses
   * rather than issuing a guessable value.
   */
  function randomHex(byteLength) {
    if (typeof crypto === 'undefined' || !crypto.getRandomValues) {
      throw new Error('no-secure-random');
    }
    var bytes = new Uint8Array(byteLength);
    crypto.getRandomValues(bytes);
    var out = '';
    for (var i = 0; i < bytes.length; i++) {
      out += ('0' + bytes[i].toString(16)).slice(-2);
    }
    return out;
  }

  function generateUUID() {
    if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID();
    var hex = randomHex(16);
    return hex.slice(0, 8) + '-' + hex.slice(8, 12) + '-4' + hex.slice(13, 16) + '-'
      + ((parseInt(hex.slice(16, 17), 16) & 0x3 | 0x8).toString(16)) + hex.slice(17, 20) + '-'
      + hex.slice(20, 32);
  }

  function validateEmail(email) {
    return !!email && typeof email === 'string' && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim());
  }

  /**
   * A rouble amount, grouped by thousands, with kopecks only when there are any.
   *
   * The catalogue is allowed two decimal places (the backend column is scale 2), and three
   * tickets at 1020.33 add up to 3060.9900000000002 in binary floating point. The previous
   * version put that whole string through the grouping expression, which also grouped the
   * digits after the point. Rounding to kopecks first and formatting the two halves separately
   * keeps a whole-rouble price rendering exactly as it did before - `1 500`, not `1 500,00`.
   */
  function formatMoney(value) {
    var amount = Math.round((Number(value) || 0) * 100) / 100;
    var sign = amount < 0 ? '-' : '';
    amount = Math.abs(amount);
    var whole = Math.floor(amount);
    var kopecks = Math.round((amount - whole) * 100);
    if (kopecks === 100) { whole += 1; kopecks = 0; }
    var grouped = String(whole).replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
    if (kopecks === 0) { return sign + grouped; }
    return sign + grouped + ',' + (kopecks < 10 ? '0' + kopecks : String(kopecks));
  }

  function readSession(key) {
    try { return sessionStorage.getItem(key); } catch (e) { return null; }
  }

  function writeSession(key, value) {
    try { sessionStorage.setItem(key, value); } catch (e) {}
  }

  function removeSession(key) {
    try { sessionStorage.removeItem(key); } catch (e) {}
  }

  /** Server error bodies carry a human message; fall back to a generic line when they do not. */
  function readErrorMessage(response, fallback) {
    return response.json()
      .then(function (body) { return (body && body.message) ? body.message : fallback; })
      .catch(function () { return fallback; });
  }

  /** A hung request must not look like a working one; every call is bounded. */
  function fetchWithTimeout(url, init, timeoutMs) {
    var controller = (typeof AbortController !== 'undefined') ? new AbortController() : null;
    var settings = Object.assign({}, init || {});
    if (controller) settings.signal = controller.signal;
    var timer = null;
    var expiry = new Promise(function (resolve, reject) {
      timer = setTimeout(function () {
        if (controller) controller.abort();
        reject(new Error('timeout'));
      }, timeoutMs);
    });
    return Promise.race([fetch(url, settings), expiry]).then(function (response) {
      clearTimeout(timer);
      return response;
    }, function (error) {
      clearTimeout(timer);
      // The aborted fetch rejects a moment after our own timeout did; report one cause, not two.
      throw (error && error.name === 'AbortError') ? new Error('timeout') : error;
    });
  }

  /**
   * Announces a checkout milestone. This file never talks to an analytics vendor: it says what
   * happened and stops there. Whether anything is recorded is decided by water-tours-analytics.js,
   * which is off unless a counter is configured and silent until the visitor agrees.
   *
   * Only non-identifying values are ever passed: which product, the order id (used purely to
   * de-duplicate) and an amount. No e-mail, phone, access token or ticket code.
   */
  function announce(event, detail) {
    try {
      document.dispatchEvent(new CustomEvent('wt:analytics', {
        detail: Object.assign({ event: event }, detail || {})
      }));
    } catch (e) {
      // An old browser without CustomEvent must still be able to buy a ticket.
    }
  }

  /** Animation is optional; the information it carries is not. */
  function prefersReducedMotion() {
    try {
      return !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
    } catch (e) {
      return false;
    }
  }

  var STATUS_TIMEOUT_MS = 10000;
  var PAY_TIMEOUT_MS = 15000;
  var CREATE_TIMEOUT_MS = 20000;
  var POLL_INTERVAL_MS = 4000;
  // ~100s of re-checks after a provider return, which is what a slow confirmation needs.
  var RETURN_ATTEMPTS = 25;
  // How long the manual check stays unavailable after one settles. Long enough that pressing it
  // again cannot turn into a habit, short enough that a customer who really is waiting is not
  // stuck: the automatic checks keep running underneath either way.
  var MANUAL_RECHECK_COOLDOWN_MS = 15000;
  // The ticket is already downloadable at this point; this only waits for the mail server to
  // accept the letter. Low frequency, strictly bounded - ~2 minutes, then manual controls only.
  var EMAIL_POLL_INTERVAL_MS = 15000;
  var EMAIL_POLL_ATTEMPTS = 8;
  // Set only while the browser is away at the payment provider. It is what lets the modal open
  // by itself exactly once on the way back, without an ordinary refresh reopening it forever.
  var RETURN_KEY = 'wt_return_pending';

  function createCheckout(options) {
    var modal = document.getElementById(options.modalId);
    var openBtn = document.getElementById(options.openBtnId);
    var closeBtn = document.getElementById(options.closeBtnId);
    var form = document.getElementById(options.formId);
    var resultEl = document.getElementById(options.resultId);
    var submitBtn = document.getElementById(options.submitId);
    var lastFocusedElement = null;
    var fallbackIdempotencyKey = null;
    var fallbackIdempotencySecret = null;
    var pollTimer = null;
    // Every status check takes a number. A reply whose number is no longer the newest belongs to
    // a check the customer has already superseded, so it must not paint over the current screen.
    var statusGeneration = 0;
    var currentScreenKey = null;
    var resumeEl = null;
    var resumeKey = null;
    // One cooldown deadline for the whole checkout, not one per rendered screen. Every status
    // reply repaints the panel, so a deadline living in a button would be handed back the moment
    // the screen changed - which is exactly the spam this prevents.
    var manualRecheckUntil = 0;
    var cooldownTimer = null;
    var requestInFlight = false;
    var manualCheckPending = false;
    // Bounded budget of automatic "has the letter gone out yet" checks for this order.
    var emailWaitsLeft = EMAIL_POLL_ATTEMPTS;

    // ---------------------------------------------------------------- modal

    function focusableElements() {
      if (!modal) return [];
      return Array.prototype.slice.call(modal.querySelectorAll(
        'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
      )).filter(function (el) { return el.offsetParent !== null; });
    }

    function trapFocus(event) {
      if (event.key === 'Escape') return closeModal();
      if (event.key !== 'Tab') return;
      var focusable = focusableElements();
      if (!focusable.length) return;
      var first = focusable[0];
      var last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    }

    function isOpen() {
      return !!modal && modal.classList.contains('is-open');
    }

    function openModal() {
      if (!modal || isOpen()) return;
      announce('form_open', { product: options.kind });
      lastFocusedElement = document.activeElement;
      modal.classList.add('is-open');
      modal.setAttribute('aria-hidden', 'false');
      document.addEventListener('keydown', trapFocus);
      var focusable = focusableElements();
      if (focusable.length) focusable[0].focus();
    }

    function closeModal() {
      if (!modal) return;
      stopPolling();
      // Drop any reply still in flight: closing is the customer saying they are done for now.
      // The dropped reply will never clear these, and a check that can never settle would leave
      // the manual button disabled for good. The cooldown deadline itself is deliberately kept:
      // closing and reopening must not be a way to press the button again immediately.
      statusGeneration++;
      requestInFlight = false;
      manualCheckPending = false;
      modal.classList.remove('is-open');
      modal.setAttribute('aria-hidden', 'true');
      document.removeEventListener('keydown', trapFocus);
      if (!resumeEl && loadOrder()) {
        showResume('unknown', 'warn', options.labels.resumeUnknown, [openAction('primary')]);
      }
      if (lastFocusedElement && typeof lastFocusedElement.focus === 'function') lastFocusedElement.focus();
    }

    // ------------------------------------------------------------- storage

    function saveOrder(order) {
      writeSession(options.storageKey, JSON.stringify({ id: order.id, accessToken: order.accessToken }));
    }

    function loadOrder() {
      var raw = readSession(options.storageKey);
      if (!raw) return null;
      try {
        var order = JSON.parse(raw);
        return (order && order.id && order.accessToken) ? order : null;
      } catch (e) {
        return null;
      }
    }

    function forgetOrder() {
      removeSession(options.storageKey);
      if (readSession(RETURN_KEY) === options.kind) removeSession(RETURN_KEY);
      hideResume();
    }

    function idempotencyKey() {
      var stored = readSession(options.idempotencyKey);
      if (stored) return stored;
      // Private-mode browsers can refuse sessionStorage; keep the key in memory so a retry in
      // this tab still reuses it and cannot create a second order.
      fallbackIdempotencyKey = fallbackIdempotencyKey || generateUUID();
      writeSession(options.idempotencyKey, fallbackIdempotencyKey);
      return fallbackIdempotencyKey;
    }

    /**
     * Proves to the server that a replay of the retry key above comes from the browser that
     * created the order. The key is a request header and is not treated as a secret anywhere;
     * this value is, and it never goes into a URL. Without it a replay is still honoured, but the
     * reply carries no access token - which is the point: knowing somebody's retry key must not
     * be enough to take over their order.
     */
    function idempotencySecret() {
      var stored = readSession(options.idempotencySecretKey);
      if (stored) return stored;
      fallbackIdempotencySecret = fallbackIdempotencySecret || randomHex(32);
      writeSession(options.idempotencySecretKey, fallbackIdempotencySecret);
      return fallbackIdempotencySecret;
    }

    function clearIdempotencyKey() {
      fallbackIdempotencyKey = null;
      fallbackIdempotencySecret = null;
      removeSession(options.idempotencyKey);
      removeSession(options.idempotencySecretKey);
    }

    /** Keeps the order's access token out of the URL, and therefore out of access logs. */
    function tokenHeaders(order, extra) {
      var headers = extra || {};
      headers['X-Order-Token'] = order.accessToken;
      return headers;
    }

    // --------------------------------------------------------------- screens

    function stopPolling() {
      if (pollTimer) {
        clearTimeout(pollTimer);
        pollTimer = null;
      }
    }

    /** Once the order is paid the form is no longer an option the customer has. */
    function setFormVisible(visible) {
      if (form) form.hidden = !visible;
    }

    function clearResult() {
      if (!resultEl) return;
      stopPolling();
      resultEl.textContent = '';
      resultEl.className = 'wt-result';
      currentScreenKey = null;
    }

    /**
     * One screen shape for every outcome: a title, an explanation, and the actions that apply.
     * `key` identifies what is on screen. Re-rendering the same screen would reset a button the
     * customer just pressed and yank the page back under them on every poll, so it is skipped -
     * and the null return tells the caller nothing was rebuilt.
     */
    function screen(key, tone, title, description, actions) {
      if (!resultEl) return null;
      if (key && key === currentScreenKey) {
        clearBusy();
        syncRecheckButtons();
        return null;
      }
      clearResult();
      currentScreenKey = key;
      resultEl.className = 'wt-result wt-status wt-status-' + tone;
      var panel = document.createElement('div');
      panel.className = 'wt-status-panel';

      var heading = document.createElement('p');
      heading.className = 'wt-status-title';
      heading.textContent = title;
      panel.appendChild(heading);

      if (description) {
        var text = document.createElement('p');
        text.className = 'wt-status-text';
        text.textContent = description;
        panel.appendChild(text);
      }

      if (actions && actions.length) {
        var row = document.createElement('div');
        row.className = 'wt-status-actions';
        actions.forEach(function (action) { if (action) row.appendChild(action); });
        panel.appendChild(row);
      }

      resultEl.appendChild(panel);
      // The panel renders below a long form, so on a phone the outcome would otherwise land
      // off-screen and read as "nothing happened".
      if (typeof panel.scrollIntoView === 'function') {
        panel.scrollIntoView({ block: 'nearest', behavior: prefersReducedMotion() ? 'auto' : 'smooth' });
      }
      syncRecheckButtons();
      return panel;
    }

    /** A pressed button says so and stops accepting presses until its request settles. */
    function markBusy(button, busyLabel) {
      button.setAttribute('data-idle-label', button.textContent);
      button.setAttribute('aria-busy', 'true');
      button.disabled = true;
      button.textContent = busyLabel;
    }

    function clearBusy() {
      if (!resultEl) return;
      Array.prototype.slice.call(resultEl.querySelectorAll('[data-idle-label]')).forEach(function (button) {
        button.textContent = button.getAttribute('data-idle-label');
        button.removeAttribute('data-idle-label');
        button.removeAttribute('aria-busy');
        button.disabled = false;
      });
    }

    function actionButton(label, variant, onClick, busyLabel) {
      var button = document.createElement('button');
      button.type = 'button';
      button.className = 'wt-status-button' + (variant ? ' wt-status-button-' + variant : '');
      button.textContent = label;
      button.addEventListener('click', function () {
        if (button.disabled) return;
        if (busyLabel) markBusy(button, busyLabel);
        onClick(button);
      });
      return button;
    }

    // ------------------------------------------------- manual check cooldown

    /** A check is already under way: a request is out, or the next poll is scheduled. */
    function recheckBusy() {
      return requestInFlight || pollTimer !== null;
    }

    /**
     * Paints every manual check button on screen from the one controller-level state. Buttons are
     * recreated whenever the panel changes, so their label and disabled flag are derived here
     * rather than remembered by the button itself.
     */
    function syncRecheckButtons() {
      if (!resultEl) return;
      var buttons = resultEl.querySelectorAll('[data-wt-recheck]');
      var remaining = Math.ceil((manualRecheckUntil - Date.now()) / 1000);
      for (var i = 0; i < buttons.length; i++) {
        var button = buttons[i];
        var idle = button.getAttribute('data-wt-recheck');
        if (recheckBusy()) {
          button.disabled = true;
          button.setAttribute('aria-busy', 'true');
          button.textContent = button.getAttribute('data-wt-busy') || options.labels.rechecking;
        } else if (remaining > 0) {
          button.disabled = true;
          button.removeAttribute('aria-busy');
          // The remaining seconds are in the label itself: a button that is simply dead tells the
          // customer nothing, and they press it again.
          button.textContent = idle + ' (' + remaining + ' с)';
        } else {
          button.disabled = false;
          button.removeAttribute('aria-busy');
          button.textContent = idle;
        }
      }
    }

    function startCooldownTicker() {
      if (cooldownTimer) return;
      cooldownTimer = setInterval(function () {
        if (Date.now() >= manualRecheckUntil) stopCooldownTicker();
        syncRecheckButtons();
      }, 1000);
    }

    function stopCooldownTicker() {
      if (!cooldownTimer) return;
      clearInterval(cooldownTimer);
      cooldownTimer = null;
    }

    function beginManualCooldown() {
      manualRecheckUntil = Date.now() + MANUAL_RECHECK_COOLDOWN_MS;
      startCooldownTicker();
    }

    /** Settled means the whole chain the press started is done - not just the first reply. */
    function settleManualCheck() {
      if (manualCheckPending && !recheckBusy()) {
        manualCheckPending = false;
        beginManualCooldown();
      }
      syncRecheckButtons();
    }

    function clearManualCooldown() {
      manualRecheckUntil = 0;
      manualCheckPending = false;
      stopCooldownTicker();
    }

    function recheckButton(order, attempts, variant, label, busyLabel) {
      var idle = label || options.labels.recheck;
      var button = document.createElement('button');
      button.type = 'button';
      button.className = 'wt-status-button' + (variant ? ' wt-status-button-' + variant : '');
      button.textContent = idle;
      button.setAttribute('data-wt-recheck', idle);
      button.setAttribute('data-wt-busy', busyLabel || options.labels.rechecking);
      button.addEventListener('click', function () {
        // The deadline is checked here as well as on the button: a stale button from a screen
        // rendered before the cooldown started must not be a way around it.
        if (button.disabled || recheckBusy() || Date.now() < manualRecheckUntil) return;
        manualCheckPending = true;
        refreshStatus(order, attempts);
      });
      return button;
    }

    /** Everything this browser needs to fetch the PDF without the token appearing in a URL. */
    function canDownloadByHeader() {
      return typeof fetch === 'function'
        && typeof Blob !== 'undefined'
        && typeof URL !== 'undefined'
        && typeof URL.createObjectURL === 'function'
        && 'download' in document.createElement('a');
    }

    /**
     * The download button.
     *
     * The href is still the query-string URL and stays that way on purpose: it is the fallback for
     * a browser that cannot do the blob dance, and it is the same shape as the links already sent
     * out by e-mail and into Telegram, which the server must keep honouring either way.
     *
     * What changed is the click. Where the browser can do it, the file is fetched with the token
     * in the `X-Order-Token` header and handed over as a blob, so this browser's own navigation -
     * and therefore the access log, the Referer of whatever the PDF viewer opens next, and the
     * history entry - never carries the token. A failure falls back to following the plain link
     * rather than leaving the customer without a ticket: the exposure it restores is exactly the
     * exposure the already-issued links have anyway.
     */
    function pdfLink(order, snapshot) {
      var link = document.createElement('a');
      link.className = 'wt-status-button wt-status-button-primary';
      link.href = backendUrl(snapshot && snapshot.pdfUrl
        ? snapshot.pdfUrl
        : '/api/v1/orders/' + encodeURIComponent(order.id) + '/tickets/pdf?accessToken=' + encodeURIComponent(order.accessToken));
      link.textContent = options.labels.download;
      link.target = '_blank';
      link.rel = 'noopener noreferrer';
      if (!canDownloadByHeader()) return link;

      var cleanUrl = backendUrl('/api/v1/orders/' + encodeURIComponent(order.id) + '/tickets/pdf');
      var fileName = 'tickets-' + order.id + '.pdf';
      link.addEventListener('click', function (event) {
        if (link.getAttribute('aria-busy') === 'true') return;
        event.preventDefault();
        link.setAttribute('aria-busy', 'true');
        announce('pdf_download', { product: options.kind, orderId: order.id });
        var previous = link.textContent;
        link.textContent = options.labels.downloading;
        fetchWithTimeout(cleanUrl, {
          method: 'GET',
          headers: tokenHeaders(order),
          credentials: 'omit'
        }, STATUS_TIMEOUT_MS)
          .then(function (response) {
            if (!response.ok) throw new Error('pdf');
            return response.blob();
          })
          .then(function (blob) {
            var objectUrl = URL.createObjectURL(blob);
            var saver = document.createElement('a');
            saver.href = objectUrl;
            saver.download = fileName;
            saver.rel = 'noopener';
            document.body.appendChild(saver);
            saver.click();
            document.body.removeChild(saver);
            // Give the browser the tick it needs to start reading the blob before it is dropped.
            setTimeout(function () { URL.revokeObjectURL(objectUrl); }, 20000);
          })
          .catch(function () {
            window.open(link.href, '_blank', 'noopener,noreferrer');
          })
          .finally(function () {
            link.removeAttribute('aria-busy');
            link.textContent = previous;
          });
      });
      return link;
    }

    function message(tone, text) {
      if (!resultEl) return;
      clearResult();
      resultEl.className = 'wt-result wt-status-' + tone;
      resultEl.textContent = text;
    }

    // ------------------------------------------------- order kept on the page

    /**
     * An unfinished order and a ready ticket stay one click away next to the buy button. This is
     * what replaces reopening the modal on load: the order is never lost, but it also never
     * ambushes someone who simply refreshed the page or closed the window.
     */
    function hideResume() {
      if (resumeEl && resumeEl.parentNode) resumeEl.parentNode.removeChild(resumeEl);
      resumeEl = null;
      resumeKey = null;
    }

    function showResume(key, tone, text, actions) {
      if (!openBtn || !openBtn.parentNode) return;
      if (resumeEl && key === resumeKey) return;
      hideResume();
      resumeKey = key;
      resumeEl = document.createElement('div');
      resumeEl.className = 'wt-resume wt-resume-' + tone;
      resumeEl.setAttribute('role', 'status');

      var line = document.createElement('p');
      line.className = 'wt-resume-text';
      line.textContent = text;
      resumeEl.appendChild(line);

      if (actions && actions.length) {
        var row = document.createElement('div');
        row.className = 'wt-resume-actions';
        actions.forEach(function (action) { if (action) row.appendChild(action); });
        resumeEl.appendChild(row);
      }
      openBtn.parentNode.insertBefore(resumeEl, openBtn.nextSibling);
    }

    function openAction(variant) {
      return actionButton(options.labels.resumeOpen, variant || 'ghost', function () { openWithStatus(); });
    }

    function renderResume(order, snapshot) {
      var status = snapshot && snapshot.status;

      if (status === 'CANCELLED' || status === 'EXPIRED' || status === 'REFUNDED') {
        // Nothing left to return to, and keeping it would make the next purchase open on a dead
        // order instead of the form. Only this tab's own bookkeeping is discarded.
        forgetOrder();
        clearIdempotencyKey();
        return;
      }
      if (snapshot && snapshot.refundInProgress) return hideResume();

      if (status === 'PAID' && snapshot.ticketsIssued) {
        return showResume('paid', 'success', options.labels.resumePaid, [pdfLink(order, snapshot), openAction('ghost')]);
      }
      if (status === 'PAID') {
        return showResume('processing', 'success', options.labels.resumeProcessing, [openAction('primary')]);
      }
      showResume('unfinished', 'warn', options.labels.resumeUnfinished, [openAction('primary')]);
    }

    /** Opening the purchase screen always re-verifies first, so a paid order can never land back
     *  on the payment form and no second order is started by mistake. */
    function openWithStatus() {
      var order = loadOrder();
      openModal();
      if (!order) {
        clearResult();
        setFormVisible(true);
        return;
      }
      message('info', options.labels.checking);
      refreshStatus(order, 3);
    }

    // ------------------------------------------------------- status handling

    function fetchStatus(order) {
      return fetchWithTimeout(backendUrl('/api/v1/orders/' + encodeURIComponent(order.id) + '/status'),
        { method: 'GET', credentials: 'omit', headers: tokenHeaders(order) }, STATUS_TIMEOUT_MS)
        .then(function (response) {
          if (response.status === 403 || response.status === 404) throw new Error('gone');
          if (!response.ok) throw new Error('status');
          return response.json();
        });
    }

    /**
     * @param attemptsLeft how many more times to re-check on a non-final state. The ticket
     *        issuance job runs on an interval, and a card payment confirmed at the provider can
     *        take a few seconds to reach us, so a fresh return from checkout is never final yet.
     */
    function refreshStatus(order, attemptsLeft) {
      stopPolling();
      var generation = ++statusGeneration;
      // Set before the request leaves, so the button reads as working in the same tick the
      // customer pressed it rather than after the network answers.
      requestInFlight = true;
      syncRecheckButtons();
      fetchStatus(order)
        .then(function (snapshot) {
          // A superseded reply must not clear the flag either: the check that replaced it owns it.
          if (generation !== statusGeneration) return;
          requestInFlight = false;
          applyStatus(order, snapshot, attemptsLeft);
          settleManualCheck();
        })
        .catch(function (error) {
          if (generation !== statusGeneration) return;
          requestInFlight = false;
          clearBusy();
          if (error && error.message === 'gone') {
            forgetOrder();
            clearIdempotencyKey();
            showCancelled(null, options.labels.orderGone);
          } else if (error && error.message === 'timeout') {
            showTimeout(order);
          } else {
            showConnectionError(order);
          }
          settleManualCheck();
        });
    }

    function applyStatus(order, snapshot, attemptsLeft) {
      clearBusy();
      var status = snapshot && snapshot.status;
      renderResume(order, snapshot);

      if (snapshot && snapshot.refundInProgress) return showRefundPending();
      if (status === 'REFUNDED') return showRefunded();
      if (status === 'CANCELLED' || status === 'EXPIRED') return showCancelled(order, null);

      if (status === 'PAID' && snapshot.ticketsIssued) {
        showPaid(order, snapshot);
        // The ticket itself is ready and downloadable; what is still open is only whether the
        // mail server has taken the letter. Bounded and slow on purpose - it is not a payment
        // check, and it must not keep a finished order polling forever.
        if (!snapshot.ticketsEmailedAt && emailWaitsLeft > 0) {
          emailWaitsLeft -= 1;
          pollTimer = setTimeout(function () {
            pollTimer = null;
            refreshStatus(order, 0);
          }, EMAIL_POLL_INTERVAL_MS);
        }
        return;
      }
      // DRAFT means payment was never started, so there is nothing to wait for.
      if (status === 'DRAFT') return showUnpaid(order);

      // PAID but no ticket yet, or still waiting for the payment to be confirmed.
      if (attemptsLeft > 0) {
        showProcessing(order, status === 'PAID', true);
        pollTimer = setTimeout(function () {
          pollTimer = null;
          refreshStatus(order, attemptsLeft - 1);
        }, POLL_INTERVAL_MS);
        return;
      }
      if (status === 'PAID') return showProcessing(order, true, false);
      // Payment was started, so "not paid" is a claim we cannot make: the provider may simply
      // not have reached us yet. Offer the re-check first and the payment page second.
      return showAwaitingConfirmation(order);
    }

    /**
     * Three distinct states once the money is in and the ticket exists, and each one says only
     * what is actually known: the mail server accepted the letter, the letter is still on its way
     * out, or the send could not be confirmed. The PDF comes first in all three - a ready ticket
     * is never hidden behind the e-mail.
     */
    function showPaid(order, snapshot) {
      setFormVisible(false);
      // De-duplicated downstream: this screen is repainted on every status poll.
      announce('payment_confirmed', {
        product: options.kind, orderId: order.id,
        amount: snapshot && Number(snapshot.totalAmount)
      });
      var emailed = !!(snapshot && snapshot.ticketsEmailedAt);
      var stillSending = !emailed && emailWaitsLeft > 0;
      var state = emailed ? 'emailed' : (stillSending ? 'sending' : 'unconfirmed');

      var actions = [pdfLink(order, snapshot)];
      if (!emailed) {
        actions.push(recheckButton(order, 0, 'ghost', options.labels.emailCheck, options.labels.emailChecking));
      }
      if (snapshot && snapshot.emailResendAvailable) {
        actions.push(actionButton(options.labels.resend, 'ghost', function (button) {
          resendEmail(order, button);
        }));
      }

      var panel = screen('paid:' + state, 'success',
        options.labels.paidTitle, options.labels.paidText, actions);
      if (panel) {
        var note = document.createElement('p');
        note.className = 'wt-status-note';
        note.textContent = state === 'emailed'
          ? options.labels.emailedNote
          : (state === 'sending' ? options.labels.emailSendingNote : options.labels.emailUnconfirmedNote);
        panel.appendChild(note);
      }
    }

    function showProcessing(order, paid, autoChecking) {
      setFormVisible(false);
      var key = 'processing:' + (paid ? 'paid' : 'pending') + ':' + (autoChecking ? 'auto' : 'idle');
      var panel = screen(key, 'info', options.labels.processingTitle,
        paid ? options.labels.processingPaidText : options.labels.processingText,
        [recheckButton(order, 3, 'ghost')]);
      if (panel) {
        var note = document.createElement('p');
        note.className = 'wt-status-note';
        note.textContent = autoChecking ? options.labels.processingAutoNote : options.labels.processingStalledNote;
        panel.appendChild(note);
      }
    }

    function showUnpaid(order) {
      setFormVisible(false);
      screen('unpaid', 'warn', options.labels.unpaidTitle, options.labels.unpaidText, [
        actionButton(config.localTestMode ? options.labels.testPay : options.labels.pay, 'primary', function (button) {
          if (config.localTestMode) testPay(order, button);
          else startPayment(order);
        }, config.localTestMode ? options.labels.testPaying : options.labels.redirecting),
        recheckButton(order, 2, 'ghost'),
        actionButton(options.labels.startOver, 'ghost', function () { startOver(); })
      ]);
    }

    function showAwaitingConfirmation(order) {
      setFormVisible(false);
      screen('awaiting', 'warn', options.labels.awaitingTitle, options.labels.awaitingText, [
        recheckButton(order, 3, 'primary'),
        actionButton(options.labels.pay, 'ghost', function () { startPayment(order); }, options.labels.redirecting),
        actionButton(options.labels.startOver, 'ghost', function () { startOver(); })
      ]);
    }

    function showCancelled(order, customText) {
      setFormVisible(false);
      screen('cancelled:' + (customText || ''), 'warn', options.labels.cancelledTitle,
        customText || options.labels.cancelledText, [
          actionButton(options.labels.startOver, 'primary', function () { startOver(); })
        ]);
    }

    function showRefunded() {
      setFormVisible(false);
      screen('refunded', 'info', options.labels.refundedTitle, options.labels.refundedText, [
        actionButton(options.labels.startOver, 'ghost', function () { startOver(); })
      ]);
    }

    function showRefundPending() {
      setFormVisible(false);
      screen('refund-pending', 'info', options.labels.refundPendingTitle, options.labels.refundPendingText, []);
    }

    function showConnectionError(order) {
      setFormVisible(false);
      screen('error:connection', 'error', options.labels.connectionTitle, options.labels.connectionText, [
        recheckButton(order, 2, 'primary')
      ]);
    }

    function showTimeout(order) {
      setFormVisible(false);
      screen('error:timeout', 'error', options.labels.timeoutTitle, options.labels.timeoutText, [
        recheckButton(order, 2, 'primary')
      ]);
    }

    function startOver() {
      stopPolling();
      statusGeneration++;
      requestInFlight = false;
      clearManualCooldown();
      emailWaitsLeft = EMAIL_POLL_ATTEMPTS;
      forgetOrder();
      clearIdempotencyKey();
      clearResult();
      setFormVisible(true);
      if (form) form.reset();
      if (options.onReset) options.onReset();
    }

    // --------------------------------------------------------------- actions

    function resendEmail(order, button) {
      button.disabled = true;
      var previous = button.textContent;
      button.textContent = options.labels.resending;
      fetchWithTimeout(backendUrl('/api/v1/orders/' + encodeURIComponent(order.id) + '/tickets/email'),
        { method: 'POST', credentials: 'omit', headers: tokenHeaders(order) }, PAY_TIMEOUT_MS)
        .then(function (response) {
          if (response.ok) {
            button.textContent = options.labels.resent;
            // The server only answers OK once the mail server has taken the message, so the
            // order now carries a delivery stamp; let the screen show that rather than keep
            // claiming the letter is still on its way.
            emailWaitsLeft = Math.max(emailWaitsLeft, 1);
            refreshStatus(order, 0);
            return null;
          }
          return readErrorMessage(response, options.labels.resendFailed).then(function (text) {
            button.disabled = false;
            button.textContent = previous;
            var note = document.createElement('p');
            note.className = 'wt-status-note wt-status-note-error';
            note.textContent = text;
            var panel = resultEl.querySelector('.wt-status-panel');
            var existing = panel ? panel.querySelector('.wt-status-note-error') : null;
            if (existing) existing.remove();
            if (panel) panel.appendChild(note);
          });
        })
        .catch(function () {
          button.disabled = false;
          button.textContent = previous;
          message('error', options.labels.resendFailed);
        });
    }

    function startPayment(order) {
      message('info', options.labels.redirecting);
      fetchWithTimeout(backendUrl('/api/v1/orders/' + encodeURIComponent(order.id) + '/pay'),
        { method: 'POST', credentials: 'omit', headers: tokenHeaders(order) }, PAY_TIMEOUT_MS)
        .then(function (response) {
          if (!response.ok) throw new Error('pay');
          return response.json();
        })
        .then(function (payment) {
          if (payment && payment.paymentUrl) {
            // Leaving for the provider. This marker is the only thing that will let the modal
            // reopen on its own, and it is consumed by the first load after the return.
            writeSession(RETURN_KEY, options.kind);
            announce('payment_redirect', { product: options.kind, orderId: order.id });
            window.location.href = payment.paymentUrl;
            return;
          }
          throw new Error('no-payment-url');
        })
        .catch(function (error) {
          var timedOut = error && error.message === 'timeout';
          announce('checkout_error', {
            product: options.kind, orderId: order.id,
            errorKind: timedOut ? 'pay_timeout' : 'pay_failed'
          });
          screen(timedOut ? 'error:pay-timeout' : 'error:pay', 'error',
            options.labels.payFailedTitle,
            timedOut ? options.labels.payTimeoutText : options.labels.payFailedText, [
              actionButton(options.labels.pay, 'primary', function () { startPayment(order); }, options.labels.redirecting),
              recheckButton(order, 2, 'ghost')
            ]);
        });
    }

    function testPay(order, button) {
      fetchWithTimeout(backendUrl('/api/v1/orders/' + encodeURIComponent(order.id)
        + '/test-pay?accessToken=' + encodeURIComponent(order.accessToken)),
        { method: 'POST', credentials: 'omit' }, PAY_TIMEOUT_MS)
        .then(function (response) {
          if (!response.ok) throw new Error('test-pay');
          return response.json();
        })
        .then(function () { refreshStatus(order, 3); })
        .catch(function () {
          if (button) button.disabled = false;
          message('error', options.labels.testPayFailed);
        });
    }

    function submitOrder(event) {
      event.preventDefault();
      var payload = options.collect();
      if (payload.error) return message('error', payload.error);

      // Both values are credentials and both need a real RNG. Resolve them before the button is
      // disabled, so a browser that cannot produce them says so instead of sitting on "creating"
      // for ever - and so no order is created that this browser could never prove it owns.
      var idemKey;
      var idemSecret;
      try {
        idemKey = idempotencyKey();
        idemSecret = idempotencySecret();
      } catch (e) {
        return message('error', options.labels.createFailed);
      }

      if (submitBtn) {
        submitBtn.disabled = true;
        submitBtn.setAttribute('aria-busy', 'true');
      }
      message('info', options.labels.creating);
      fetchWithTimeout(backendUrl(config.ordersPath || '/api/v1/orders'), {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': idemKey,
          'Idempotency-Secret': idemSecret
        },
        body: JSON.stringify(payload.body),
        credentials: 'omit'
      }, CREATE_TIMEOUT_MS)
        .then(function (response) {
          if (response.status === 409) {
            // The retry key already stands for an order this browser cannot claim. Saying so
            // plainly matters: the generic "try again" invites a second attempt, and the honest
            // recovery is a new order or a word with support - never a silent duplicate.
            throw new Error(options.labels.createConflict);
          }
          if (!response.ok) {
            return readErrorMessage(response, options.labels.createFailed).then(function (text) {
              throw new Error(text);
            });
          }
          return response.json();
        })
        .then(function (order) {
          if (!order || !order.id) throw new Error(options.labels.createFailed);
          if (!order.accessToken) {
            // The order exists and the server confirmed it, but the reply carries no credential:
            // the stored record predates caller binding. Nothing can be opened from here, so give
            // the customer the one thing support can act on - the order number - and stop. The
            // retry key is deliberately left in place so pressing the button again resolves to
            // this same order instead of creating a second one.
            return message('warn', options.labels.createUnclaimable.replace('{id}', order.id));
          }
          clearIdempotencyKey();
          saveOrder(order);
          announce('order_created', { product: options.kind, orderId: order.id, amount: Number(order.totalAmount) });
          clearManualCooldown();
          emailWaitsLeft = EMAIL_POLL_ATTEMPTS;
          if (config.localTestMode) return showUnpaid(order);
          startPayment(order);
        })
        .catch(function (error) {
          // The idempotency key survives a failure, so pressing the button again resolves to the
          // same order server-side instead of creating a second one.
          announce('checkout_error', {
            product: options.kind,
            errorKind: (error && error.message === 'timeout') ? 'create_timeout' : 'create_failed'
          });
          if (error && error.message === 'timeout') return message('error', options.labels.createTimeout);
          message('error', (error && error.message) ? error.message : options.labels.createFailed);
        })
        .finally(function () {
          if (submitBtn) {
            submitBtn.disabled = false;
            submitBtn.removeAttribute('aria-busy');
          }
        });
    }

    /**
     * Runs once per page load. Only a genuine return from the payment provider opens the modal;
     * an ordinary refresh - or a reload after the customer closed the window - just puts the
     * order back within reach beside the buy button.
     */
    function restore() {
      var order = loadOrder();
      if (!order) return;

      if (readSession(RETURN_KEY) === options.kind) {
        removeSession(RETURN_KEY);
        openModal();
        message('info', options.labels.checking);
        // Never trust a cached status: the outcome shown here is the one the server confirms.
        refreshStatus(order, RETURN_ATTEMPTS);
        return;
      }

      fetchStatus(order)
        .then(function (snapshot) { renderResume(order, snapshot); })
        .catch(function (error) {
          if (error && error.message === 'gone') {
            forgetOrder();
            clearIdempotencyKey();
            return;
          }
          // Say only what is known: the order exists, its state does not.
          showResume('unknown', 'warn', options.labels.resumeUnknown, [openAction('primary')]);
        });
    }

    function init() {
      if (!form) return;
      if (openBtn) openBtn.addEventListener('click', openWithStatus);
      if (closeBtn) closeBtn.addEventListener('click', closeModal);
      if (modal) modal.addEventListener('click', function (event) { if (event.target === modal) closeModal(); });
      form.addEventListener('submit', submitOrder);
      if (options.onInit) options.onInit();
      restore();
    }

    return { init: init };
  }

  // ------------------------------------------------------------- passenger tickets

  var ticketPrices = Object.assign({}, config.prices || { ADULT: 1500, CHILD: 800, BENEFIT: 1020 });
  var ticketCounts = { ADULT: 0, CHILD: 0, BENEFIT: 0 };
  var ticketTypes = ['ADULT', 'CHILD', 'BENEFIT'];
  var totalSumEl = document.getElementById('wt-total-sum');
  var emailInput = document.getElementById('wt-email');
  var phoneInput = document.getElementById('wt-phone');

  function updateTicketDisplay() {
    var total = 0;
    ticketTypes.forEach(function (type) {
      var countEl = document.getElementById('wt-count-' + type);
      var priceEl = document.getElementById('wt-price-' + type);
      if (countEl) countEl.textContent = String(ticketCounts[type]);
      if (priceEl) priceEl.textContent = formatMoney(ticketPrices[type]) + ' ₽';
      total += ticketCounts[type] * (ticketPrices[type] || 0);
    });
    if (totalSumEl) totalSumEl.textContent = formatMoney(total);
  }

  function fetchCatalog() {
    if (!config.localTestMode || !config.catalogPath) return updateTicketDisplay();
    fetch(backendUrl(config.catalogPath), { method: 'GET', credentials: 'omit' })
      .then(function (response) {
        if (!response.ok) throw new Error('catalog');
        return response.json();
      })
      .then(function (catalog) {
        ticketTypes.forEach(function (type) {
          if (catalog && Number(catalog[type]) >= 0) ticketPrices[type] = Number(catalog[type]);
        });
        updateTicketDisplay();
      })
      .catch(updateTicketDisplay);
  }

  var ticketCheckout = createCheckout({
    kind: 'ticket',
    modalId: 'wt-modal',
    openBtnId: 'wt-open-modal',
    closeBtnId: 'wt-close-modal',
    formId: 'wt-ticket-form',
    submitId: 'wt-submit',
    resultId: 'wt-result',
    storageKey: 'wt_order',
    idempotencyKey: 'wt_idempotency_key',
    idempotencySecretKey: 'wt_idempotency_secret',
    labels: {
      download: 'Скачать PDF билета',
      downloading: 'Готовим PDF...',
      resend: 'Отправить на email ещё раз',
      resending: 'Отправляем...',
      resent: 'Письмо отправлено',
      resendFailed: 'Письмо отправить не удалось. Билет доступен по ссылке выше.',
      paidTitle: 'Оплата прошла. Билеты готовы.',
      paidText: 'Скачайте PDF с QR-кодом и покажите его сотруднику при посадке. Билет действует 72 часа с момента подтверждения оплаты.',
      emailedNote: 'Письмо отправлено на указанную почту: почтовый сервер принял его. Если письма нет во «Входящих», проверьте «Спам» — билет всегда можно скачать по кнопке выше.',
      emailSendingNote: 'Билет готов, письмо ещё отправляется. Мы проверяем это автоматически несколько минут — ждать письма не нужно, билет уже можно скачать.',
      emailUnconfirmedNote: 'Билет готов, но отправку письма подтвердить пока не удалось. Оплата в порядке и билет действителен: скачайте PDF по кнопке выше, проверьте ещё раз или отправьте письмо повторно. Если ничего не помогло — напишите нам, мы отправим билет вручную.',
      emailCheck: 'Проверить письмо',
      emailChecking: 'Проверяем письмо...',
      processingTitle: 'Оплата обрабатывается',
      processingText: 'Проверяем платёж. Это занимает до минуты — не закрывайте страницу.',
      processingPaidText: 'Оплата подтверждена, готовим билеты. Обычно это занимает меньше минуты.',
      recheck: 'Проверить оплату',
      rechecking: 'Проверяем оплату...',
      processingAutoNote: 'Статус обновляется автоматически каждые несколько секунд.',
      processingStalledNote: 'Автоматическая проверка завершена. Нажмите «Проверить оплату», если статус не изменился.',
      timeoutTitle: 'Проверка не уложилась во время',
      timeoutText: 'Сервер не ответил за 10 секунд. Заказ не потерян — попробуйте проверить ещё раз.',
      payTimeoutText: 'Платёжная страница не ответила вовремя. Попробуйте ещё раз — повторный переход не создаёт второй платёж.',
      createTimeout: 'Сервер не ответил вовремя. Нажмите «Оформить заказ» ещё раз — дубликат заказа не создастся.',
      testPaying: 'Оплачиваем...',
      resumeOpen: 'Открыть заказ',
      resumePaid: 'Заказ оплачен, билеты готовы.',
      resumeProcessing: 'Оплата подтверждена, готовим билеты.',
      resumeUnfinished: 'У вас есть незавершённый заказ.',
      resumeUnknown: 'У вас есть незавершённый заказ. Статус пока не удалось проверить.',
      awaitingTitle: 'Оплата пока не подтверждена',
      awaitingText: 'Если вы уже оплатили, подтверждение от банка может идти ещё несколько минут — нажмите «Проверить оплату». Если оплата не завершена, вернитесь к ней: повторный переход не создаёт второй платёж и не списывает деньги дважды.',
      unpaidTitle: 'Оплата не завершена',
      unpaidText: 'Заказ создан, но оплата не подтверждена. Если вы уже платили, подождите минуту и проверьте статус.',
      pay: 'Перейти к оплате',
      testPay: 'Тестовая оплата',
      testPayFailed: 'Не удалось выполнить тестовую оплату. Попробуйте ещё раз.',
      startOver: 'Оформить новый заказ',
      cancelledTitle: 'Оплата отменена',
      cancelledText: 'Заказ не оплачен и больше недействителен. Деньги не списаны. Можно оформить новый заказ.',
      orderGone: 'Заказ больше недоступен. Оформите новый заказ.',
      refundedTitle: 'Заказ возвращён',
      refundedText: 'По этому заказу выполнен возврат, билеты недействительны.',
      refundPendingTitle: 'Выполняется возврат',
      refundPendingText: 'По заказу обрабатывается возврат. Билеты временно недоступны для прохода.',
      connectionTitle: 'Не удалось проверить статус',
      connectionText: 'Сервер не ответил. Проверьте соединение и попробуйте ещё раз — заказ не потерян.',
      payFailedTitle: 'Не удалось перейти к оплате',
      payFailedText: 'Платёжная страница сейчас недоступна. Попробуйте ещё раз или напишите нам.',
      creating: 'Оформляем заказ...',
      createFailed: 'Не удалось создать заказ. Проверьте данные и попробуйте ещё раз.',
      createConflict: 'Этот заказ уже оформлен в другом окне или на другом устройстве, и открыть его здесь не получится. Оформите новый заказ — второй платёж не спишется, — либо напишите нам, и мы найдём оплаченный заказ.',
      createUnclaimable: 'Заказ №{id} уже создан, но подтвердить доступ к нему из этого браузера не удалось. Повторное нажатие не создаст второй заказ и не спишет деньги. Напишите нам и назовите этот номер — мы вышлем билет вручную.',
      checking: 'Проверяем статус заказа...',
      redirecting: 'Переходим к оплате...'
    },
    collect: function () {
      var email = emailInput ? emailInput.value.trim() : '';
      if (!validateEmail(email)) return { error: 'Укажите корректный email.' };
      var tickets = {};
      ticketTypes.forEach(function (type) { if (ticketCounts[type] > 0) tickets[type] = ticketCounts[type]; });
      if (!Object.keys(tickets).length) return { error: 'Выберите хотя бы один билет.' };
      return {
        body: {
          email: email,
          phoneNumber: phoneInput ? phoneInput.value.trim() : '',
          tickets: tickets
        }
      };
    },
    onReset: function () {
      ticketTypes.forEach(function (type) { ticketCounts[type] = 0; });
      updateTicketDisplay();
    },
    onInit: function () {
      document.addEventListener('click', function (event) {
        var button = event.target.closest ? event.target.closest('[data-type][data-delta]') : null;
        if (!button || !Object.prototype.hasOwnProperty.call(ticketCounts, button.dataset.type)) return;
        ticketCounts[button.dataset.type] = Math.max(0, ticketCounts[button.dataset.type] + Number(button.dataset.delta));
        updateTicketDisplay();
      });
      updateTicketDisplay();
      fetchCatalog();
    }
  });

  // ------------------------------------------------------------------ boat rental

  var boatPrices = Object.assign({ 30: 3500, 60: 6000, 90: 9000, 120: 11000 }, config.boatPrices || {});
  var boatEmailInput = document.getElementById('wt-boat-email');
  var boatPhoneInput = document.getElementById('wt-boat-phone');
  var boatGuestsInput = document.getElementById('wt-boat-guests');
  var boatDurationInput = document.getElementById('wt-boat-duration');
  var boatRouteNoteInput = document.getElementById('wt-boat-route-note');
  var boatForm = document.getElementById('wt-boat-form');
  var boatTotalEl = document.getElementById('wt-boat-total-sum');

  function updateBoatTotal() {
    var duration = Number(boatDurationInput ? boatDurationInput.value : 30);
    if (boatTotalEl) boatTotalEl.textContent = formatMoney(boatPrices[duration] || 0);
  }

  function selectedBoatRouteType() {
    var selected = boatForm ? boatForm.querySelector('input[name="wt-boat-route"]:checked') : null;
    return selected ? selected.value : '';
  }

  var boatCheckout = createCheckout({
    kind: 'boat',
    modalId: 'wt-boat-modal',
    openBtnId: 'wt-boat-open-modal',
    closeBtnId: 'wt-boat-close-modal',
    formId: 'wt-boat-form',
    submitId: 'wt-boat-submit',
    resultId: 'wt-boat-result',
    storageKey: 'wt_boat_order',
    idempotencyKey: 'wt_boat_idempotency_key',
    idempotencySecretKey: 'wt_boat_idempotency_secret',
    labels: {
      download: 'Скачать PDF билета',
      downloading: 'Готовим PDF...',
      resend: 'Отправить на email ещё раз',
      resending: 'Отправляем...',
      resent: 'Письмо отправлено',
      resendFailed: 'Письмо отправить не удалось. Билет доступен по ссылке выше.',
      paidTitle: 'Аренда оплачена. Билет готов.',
      paidText: 'Скачайте PDF с QR-кодом — он один на всю компанию. Билет действует 72 часа с момента подтверждения оплаты, время выхода согласуется отдельно.',
      emailedNote: 'Письмо отправлено на указанную почту: почтовый сервер принял его. Если письма нет во «Входящих», проверьте «Спам» — билет всегда можно скачать по кнопке выше.',
      emailSendingNote: 'Билет готов, письмо ещё отправляется. Мы проверяем это автоматически несколько минут — ждать письма не нужно, билет уже можно скачать.',
      emailUnconfirmedNote: 'Билет готов, но отправку письма подтвердить пока не удалось. Оплата в порядке и билет действителен: скачайте PDF по кнопке выше, проверьте ещё раз или отправьте письмо повторно. Если ничего не помогло — напишите нам, мы отправим билет вручную.',
      emailCheck: 'Проверить письмо',
      emailChecking: 'Проверяем письмо...',
      processingTitle: 'Оплата обрабатывается',
      processingText: 'Проверяем платёж. Это занимает до минуты — не закрывайте страницу.',
      processingPaidText: 'Оплата подтверждена, готовим билет. Обычно это занимает меньше минуты.',
      recheck: 'Проверить оплату',
      rechecking: 'Проверяем оплату...',
      processingAutoNote: 'Статус обновляется автоматически каждые несколько секунд.',
      processingStalledNote: 'Автоматическая проверка завершена. Нажмите «Проверить оплату», если статус не изменился.',
      timeoutTitle: 'Проверка не уложилась во время',
      timeoutText: 'Сервер не ответил за 10 секунд. Заявка не потеряна — попробуйте проверить ещё раз.',
      payTimeoutText: 'Платёжная страница не ответила вовремя. Попробуйте ещё раз — повторный переход не создаёт второй платёж.',
      createTimeout: 'Сервер не ответил вовремя. Нажмите «Оформить аренду» ещё раз — дубликат заявки не создастся.',
      testPaying: 'Оплачиваем...',
      resumeOpen: 'Открыть заявку',
      resumePaid: 'Аренда оплачена, билет готов.',
      resumeProcessing: 'Оплата подтверждена, готовим билет.',
      resumeUnfinished: 'У вас есть незавершённая заявка на аренду.',
      resumeUnknown: 'У вас есть незавершённая заявка на аренду. Статус пока не удалось проверить.',
      awaitingTitle: 'Оплата пока не подтверждена',
      awaitingText: 'Если вы уже оплатили, подтверждение от банка может идти ещё несколько минут — нажмите «Проверить оплату». Если оплата не завершена, вернитесь к ней: повторный переход не создаёт второй платёж и не списывает деньги дважды.',
      unpaidTitle: 'Оплата не завершена',
      unpaidText: 'Заявка на аренду создана, но оплата не подтверждена. Если вы уже платили, подождите минуту и проверьте статус.',
      pay: 'Перейти к оплате',
      testPay: 'Тестовая оплата',
      testPayFailed: 'Не удалось выполнить тестовую оплату. Попробуйте ещё раз.',
      startOver: 'Оформить аренду заново',
      cancelledTitle: 'Оплата отменена',
      cancelledText: 'Аренда не оплачена и больше недействительна. Деньги не списаны. Можно оформить заново.',
      orderGone: 'Заявка больше недоступна. Оформите аренду заново.',
      refundedTitle: 'Аренда возвращена',
      refundedText: 'По этой аренде выполнен возврат, билет недействителен.',
      refundPendingTitle: 'Выполняется возврат',
      refundPendingText: 'По аренде обрабатывается возврат. Билет временно недоступен для прохода.',
      connectionTitle: 'Не удалось проверить статус',
      connectionText: 'Сервер не ответил. Проверьте соединение и попробуйте ещё раз — заявка не потеряна.',
      payFailedTitle: 'Не удалось перейти к оплате',
      payFailedText: 'Платёжная страница сейчас недоступна. Попробуйте ещё раз или напишите нам.',
      creating: 'Оформляем аренду...',
      createFailed: 'Не удалось оформить аренду. Проверьте данные и попробуйте ещё раз.',
      createConflict: 'Эта заявка уже оформлена в другом окне или на другом устройстве, и открыть её здесь не получится. Оформите новую заявку — второй платёж не спишется, — либо напишите нам, и мы найдём оплаченную заявку.',
      createUnclaimable: 'Заявка №{id} уже создана, но подтвердить доступ к ней из этого браузера не удалось. Повторное нажатие не создаст вторую заявку и не спишет деньги. Напишите нам и назовите этот номер — мы подтвердим аренду вручную.',
      checking: 'Проверяем статус аренды...',
      redirecting: 'Переходим к оплате...'
    },
    collect: function () {
      var email = boatEmailInput ? boatEmailInput.value.trim() : '';
      var phone = boatPhoneInput ? boatPhoneInput.value.trim() : '';
      var guests = Number(boatGuestsInput ? boatGuestsInput.value : 0);
      var duration = Number(boatDurationInput ? boatDurationInput.value : 0);
      var routeType = selectedBoatRouteType();
      var routeNote = boatRouteNoteInput ? boatRouteNoteInput.value.trim() : '';

      if (!validateEmail(email)) return { error: 'Укажите корректный email.' };
      if (!phone) return { error: 'Укажите телефон.' };
      if (!Number.isInteger(guests) || guests < 1 || guests > 6) return { error: 'Выберите от 1 до 6 гостей.' };
      if (!Object.prototype.hasOwnProperty.call(boatPrices, duration)) return { error: 'Выберите доступную продолжительность.' };
      if (routeType !== 'CUSTOM' && routeType !== 'ASSISTED') return { error: 'Выберите вариант маршрута.' };
      if (routeNote.length > 300) return { error: 'Пожелания к маршруту должны быть короче 300 символов.' };

      return {
        body: {
          email: email,
          phoneNumber: phone,
          boatRental: {
            durationMinutes: duration,
            guestCount: guests,
            routeType: routeType,
            routeNote: routeNote
          }
        }
      };
    },
    onReset: updateBoatTotal,
    onInit: function () {
      if (boatDurationInput) boatDurationInput.addEventListener('change', updateBoatTotal);
      updateBoatTotal();
    }
  });

  function init() {
    ticketCheckout.init();
    boatCheckout.init();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();

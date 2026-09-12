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

  function generateUUID() {
    if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID();
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
      var r = (Math.random() * 16) | 0;
      return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
    });
  }

  function validateEmail(email) {
    return !!email && typeof email === 'string' && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim());
  }

  function formatMoney(value) {
    return String(value).replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
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

  var STATUS_TIMEOUT_MS = 10000;
  var PAY_TIMEOUT_MS = 15000;
  var CREATE_TIMEOUT_MS = 20000;
  var POLL_INTERVAL_MS = 4000;
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
    var pollTimer = null;
    // Every status check takes a number. A reply whose number is no longer the newest belongs to
    // a check the customer has already superseded, so it must not paint over the current screen.
    var statusGeneration = 0;
    var currentScreenKey = null;
    var resumeEl = null;
    var resumeKey = null;

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
      statusGeneration++;
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

    function clearIdempotencyKey() {
      fallbackIdempotencyKey = null;
      removeSession(options.idempotencyKey);
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
        panel.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
      }
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

    function recheckButton(order, attempts, variant) {
      return actionButton(options.labels.recheck, variant || 'ghost', function () {
        refreshStatus(order, attempts);
      }, options.labels.rechecking);
    }

    function pdfLink(order, snapshot) {
      var link = document.createElement('a');
      link.className = 'wt-status-button wt-status-button-primary';
      link.href = backendUrl(snapshot && snapshot.pdfUrl
        ? snapshot.pdfUrl
        : '/api/v1/orders/' + encodeURIComponent(order.id) + '/tickets/pdf?accessToken=' + encodeURIComponent(order.accessToken));
      link.textContent = options.labels.download;
      link.target = '_blank';
      link.rel = 'noopener noreferrer';
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
      return fetchWithTimeout(backendUrl('/api/v1/orders/' + encodeURIComponent(order.id)
        + '/status?accessToken=' + encodeURIComponent(order.accessToken)),
        { method: 'GET', credentials: 'omit' }, STATUS_TIMEOUT_MS)
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
      fetchStatus(order)
        .then(function (snapshot) {
          if (generation !== statusGeneration) return;
          applyStatus(order, snapshot, attemptsLeft);
        })
        .catch(function (error) {
          if (generation !== statusGeneration) return;
          clearBusy();
          if (error && error.message === 'gone') {
            forgetOrder();
            clearIdempotencyKey();
            return showCancelled(null, options.labels.orderGone);
          }
          if (error && error.message === 'timeout') return showTimeout(order);
          showConnectionError(order);
        });
    }

    function applyStatus(order, snapshot, attemptsLeft) {
      clearBusy();
      var status = snapshot && snapshot.status;
      renderResume(order, snapshot);

      if (snapshot && snapshot.refundInProgress) return showRefundPending();
      if (status === 'REFUNDED') return showRefunded();
      if (status === 'CANCELLED' || status === 'EXPIRED') return showCancelled(order, null);

      if (status === 'PAID' && snapshot.ticketsIssued) return showPaid(order, snapshot);
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
      return showUnpaid(order);
    }

    function showPaid(order, snapshot) {
      setFormVisible(false);
      var emailed = !!(snapshot && snapshot.ticketsEmailedAt);
      var actions = [pdfLink(order, snapshot)];
      if (snapshot && snapshot.emailResendAvailable) {
        actions.push(actionButton(options.labels.resend, 'ghost', function (button) {
          resendEmail(order, button);
        }));
      }
      var panel = screen('paid:' + (emailed ? '1' : '0'), 'success',
        options.labels.paidTitle, options.labels.paidText, actions);
      if (panel && emailed) {
        var note = document.createElement('p');
        note.className = 'wt-status-note';
        note.textContent = options.labels.emailedNote;
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
      fetchWithTimeout(backendUrl('/api/v1/orders/' + encodeURIComponent(order.id)
        + '/tickets/email?accessToken=' + encodeURIComponent(order.accessToken)),
        { method: 'POST', credentials: 'omit' }, PAY_TIMEOUT_MS)
        .then(function (response) {
          if (response.ok) {
            button.textContent = options.labels.resent;
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
      fetchWithTimeout(backendUrl('/api/v1/orders/' + encodeURIComponent(order.id)
        + '/pay?accessToken=' + encodeURIComponent(order.accessToken)),
        { method: 'POST', credentials: 'omit' }, PAY_TIMEOUT_MS)
        .then(function (response) {
          if (!response.ok) throw new Error('pay');
          return response.json();
        })
        .then(function (payment) {
          if (payment && payment.paymentUrl) {
            // Leaving for the provider. This marker is the only thing that will let the modal
            // reopen on its own, and it is consumed by the first load after the return.
            writeSession(RETURN_KEY, options.kind);
            window.location.href = payment.paymentUrl;
            return;
          }
          throw new Error('no-payment-url');
        })
        .catch(function (error) {
          var timedOut = error && error.message === 'timeout';
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

      if (submitBtn) {
        submitBtn.disabled = true;
        submitBtn.setAttribute('aria-busy', 'true');
      }
      message('info', options.labels.creating);
      fetchWithTimeout(backendUrl(config.ordersPath || '/api/v1/orders'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey() },
        body: JSON.stringify(payload.body),
        credentials: 'omit'
      }, CREATE_TIMEOUT_MS)
        .then(function (response) {
          if (!response.ok) {
            return readErrorMessage(response, options.labels.createFailed).then(function (text) {
              throw new Error(text);
            });
          }
          return response.json();
        })
        .then(function (order) {
          if (!order || !order.id || !order.accessToken) throw new Error(options.labels.createFailed);
          clearIdempotencyKey();
          saveOrder(order);
          if (config.localTestMode) return showUnpaid(order);
          startPayment(order);
        })
        .catch(function (error) {
          // The idempotency key survives a failure, so pressing the button again resolves to the
          // same order server-side instead of creating a second one.
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
        refreshStatus(order, 6);
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
    labels: {
      download: 'Скачать PDF билета',
      resend: 'Отправить на email ещё раз',
      resending: 'Отправляем...',
      resent: 'Письмо отправлено',
      resendFailed: 'Письмо отправить не удалось. Билет доступен по ссылке выше.',
      paidTitle: 'Оплата прошла. Билеты готовы.',
      paidText: 'Скачайте PDF с QR-кодом и покажите его сотруднику при посадке. Билет действует 72 часа с момента подтверждения оплаты.',
      emailedNote: 'Письмо с билетами уже отправлено на указанный email.',
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
    labels: {
      download: 'Скачать PDF билета',
      resend: 'Отправить на email ещё раз',
      resending: 'Отправляем...',
      resent: 'Письмо отправлено',
      resendFailed: 'Письмо отправить не удалось. Билет доступен по ссылке выше.',
      paidTitle: 'Аренда оплачена. Билет готов.',
      paidText: 'Скачайте PDF с QR-кодом — он один на всю компанию. Билет действует 72 часа с момента подтверждения оплаты, время выхода согласуется отдельно.',
      emailedNote: 'Письмо с билетом уже отправлено на указанный email.',
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

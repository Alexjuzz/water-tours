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

    function openModal() {
      if (!modal) return;
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
      modal.classList.remove('is-open');
      modal.setAttribute('aria-hidden', 'true');
      document.removeEventListener('keydown', trapFocus);
      if (lastFocusedElement && typeof lastFocusedElement.focus === 'function') lastFocusedElement.focus();
    }

    // ------------------------------------------------------------- storage

    function saveOrder(order) {
      writeSession(options.storageKey, JSON.stringify({ id: order.id, accessToken: order.accessToken }));
      writeSession('wt_last_order_kind', options.kind);
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
      if (readSession('wt_last_order_kind') === options.kind) removeSession('wt_last_order_kind');
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

    function clearResult() {
      if (!resultEl) return;
      stopPolling();
      resultEl.textContent = '';
      resultEl.className = 'wt-result';
    }

    /** One screen shape for every outcome: a title, an explanation, and the actions that apply. */
    function screen(tone, title, description, actions) {
      if (!resultEl) return null;
      clearResult();
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

    function actionButton(label, variant, onClick) {
      var button = document.createElement('button');
      button.type = 'button';
      button.className = 'wt-status-button' + (variant ? ' wt-status-button-' + variant : '');
      button.textContent = label;
      button.addEventListener('click', function () { onClick(button); });
      return button;
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

    // ------------------------------------------------------- status handling

    function fetchStatus(order) {
      return fetch(backendUrl('/api/v1/orders/' + encodeURIComponent(order.id)
        + '/status?accessToken=' + encodeURIComponent(order.accessToken)), { method: 'GET', credentials: 'omit' })
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
      fetchStatus(order)
        .then(function (snapshot) { applyStatus(order, snapshot, attemptsLeft); })
        .catch(function (error) {
          if (error && error.message === 'gone') {
            forgetOrder();
            return showCancelled(null, options.labels.orderGone);
          }
          showConnectionError(order);
        });
    }

    function applyStatus(order, snapshot, attemptsLeft) {
      var status = snapshot && snapshot.status;

      if (snapshot && snapshot.refundInProgress) return showRefundPending();
      if (status === 'REFUNDED') return showRefunded();
      if (status === 'CANCELLED' || status === 'EXPIRED') return showCancelled(order, null);

      if (status === 'PAID' && snapshot.ticketsIssued) return showPaid(order, snapshot);
      // DRAFT means payment was never started, so there is nothing to wait for.
      if (status === 'DRAFT') return showUnpaid(order);

      // PAID but no ticket yet, or still waiting for the payment to be confirmed.
      if (attemptsLeft > 0) {
        showProcessing(order, status === 'PAID');
        pollTimer = setTimeout(function () { refreshStatus(order, attemptsLeft - 1); }, 4000);
        return;
      }
      if (status === 'PAID') return showProcessing(order, true);
      return showUnpaid(order);
    }

    function showPaid(order, snapshot) {
      var actions = [pdfLink(order, snapshot)];
      if (snapshot && snapshot.emailResendAvailable) {
        actions.push(actionButton(options.labels.resend, 'ghost', function (button) {
          resendEmail(order, button);
        }));
      }
      var panel = screen('success', options.labels.paidTitle, options.labels.paidText, actions);
      if (panel && snapshot && snapshot.ticketsEmailedAt) {
        var note = document.createElement('p');
        note.className = 'wt-status-note';
        note.textContent = options.labels.emailedNote;
        panel.appendChild(note);
      }
    }

    function showProcessing(order, paid) {
      screen('info', options.labels.processingTitle,
        paid ? options.labels.processingPaidText : options.labels.processingText,
        [actionButton(options.labels.recheck, 'ghost', function () { refreshStatus(order, 3); })]);
    }

    function showUnpaid(order) {
      screen('warn', options.labels.unpaidTitle, options.labels.unpaidText, [
        actionButton(config.localTestMode ? options.labels.testPay : options.labels.pay, 'primary', function (button) {
          button.disabled = true;
          if (config.localTestMode) testPay(order, button);
          else startPayment(order);
        }),
        actionButton(options.labels.startOver, 'ghost', function () { startOver(); })
      ]);
    }

    function showCancelled(order, customText) {
      screen('warn', options.labels.cancelledTitle, customText || options.labels.cancelledText, [
        actionButton(options.labels.startOver, 'primary', function () { startOver(); })
      ]);
    }

    function showRefunded() {
      screen('info', options.labels.refundedTitle, options.labels.refundedText, [
        actionButton(options.labels.startOver, 'ghost', function () { startOver(); })
      ]);
    }

    function showRefundPending() {
      screen('info', options.labels.refundPendingTitle, options.labels.refundPendingText, []);
    }

    function showConnectionError(order) {
      screen('error', options.labels.connectionTitle, options.labels.connectionText, [
        actionButton(options.labels.recheck, 'primary', function () { refreshStatus(order, 2); })
      ]);
    }

    function startOver() {
      forgetOrder();
      clearIdempotencyKey();
      clearResult();
      if (form) form.reset();
      if (options.onReset) options.onReset();
    }

    // --------------------------------------------------------------- actions

    function resendEmail(order, button) {
      button.disabled = true;
      var previous = button.textContent;
      button.textContent = options.labels.resending;
      fetch(backendUrl('/api/v1/orders/' + encodeURIComponent(order.id)
        + '/tickets/email?accessToken=' + encodeURIComponent(order.accessToken)), { method: 'POST', credentials: 'omit' })
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
      fetch(backendUrl('/api/v1/orders/' + encodeURIComponent(order.id)
        + '/pay?accessToken=' + encodeURIComponent(order.accessToken)), { method: 'POST', credentials: 'omit' })
        .then(function (response) {
          if (!response.ok) throw new Error('pay');
          return response.json();
        })
        .then(function (payment) {
          if (payment && payment.paymentUrl) {
            window.location.href = payment.paymentUrl;
            return;
          }
          throw new Error('no-payment-url');
        })
        .catch(function () {
          screen('error', options.labels.payFailedTitle, options.labels.payFailedText, [
            actionButton(options.labels.pay, 'primary', function () { startPayment(order); })
          ]);
        });
    }

    function testPay(order, button) {
      fetch(backendUrl('/api/v1/orders/' + encodeURIComponent(order.id)
        + '/test-pay?accessToken=' + encodeURIComponent(order.accessToken)), { method: 'POST', credentials: 'omit' })
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

      if (submitBtn) submitBtn.disabled = true;
      message('info', options.labels.creating);
      fetch(backendUrl(config.ordersPath || '/api/v1/orders'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey() },
        body: JSON.stringify(payload.body),
        credentials: 'omit'
      })
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
          message('error', (error && error.message) ? error.message : options.labels.createFailed);
        })
        .finally(function () { if (submitBtn) submitBtn.disabled = false; });
    }

    function restore() {
      if (readSession('wt_last_order_kind') !== options.kind) return;
      var order = loadOrder();
      if (!order) return;
      openModal();
      message('info', options.labels.checking);
      // A reload or a return from the payment page lands here: never trust a cached status.
      refreshStatus(order, 6);
    }

    function init() {
      if (!form) return;
      if (openBtn) openBtn.addEventListener('click', openModal);
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
      recheck: 'Проверить ещё раз',
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
      recheck: 'Проверить ещё раз',
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

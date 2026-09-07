(function () {
  'use strict';

  var config = window.WaterToursConfig || {};
  var prices = Object.assign({}, config.prices || { ADULT: 1500, CHILD: 800, BENEFIT: 1020 });
  var counts = { ADULT: 0, CHILD: 0, BENEFIT: 0 };
  var idempotencyKey = null;
  var orderStorageKey = 'wt_order';
  var idempotencyStorageKey = 'wt_idempotency_key';

  var modal = document.getElementById('wt-modal');
  var openBtn = document.getElementById('wt-open-modal');
  var closeBtn = document.getElementById('wt-close-modal');
  var form = document.getElementById('wt-ticket-form');
  var emailInput = document.getElementById('wt-email');
  var phoneInput = document.getElementById('wt-phone');
  var submitBtn = document.getElementById('wt-submit');
  var resultEl = document.getElementById('wt-result');
  var totalSumEl = document.getElementById('wt-total-sum');

  function getCountEl(type) {
    return document.getElementById('wt-count-' + type);
  }

  function getPriceEl(type) {
    return document.getElementById('wt-price-' + type);
  }

  function updateDisplay() {
    var types = ['ADULT', 'CHILD', 'BENEFIT'];
    var total = 0;
    for (var i = 0; i < types.length; i++) {
      var t = types[i];
      var countEl = getCountEl(t);
      var priceEl = getPriceEl(t);
      if (countEl) countEl.textContent = String(counts[t]);
      if (priceEl) priceEl.textContent = String(prices[t]);
      total += counts[t] * (prices[t] || 0);
    }
    if (totalSumEl) totalSumEl.textContent = String(total);
  }

  function setResult(text, isError) {
    if (!resultEl) return;
    resultEl.textContent = text || '';
    resultEl.style.color = isError ? '#c00' : '';
  }

  function setSubmitEnabled(enabled) {
    if (submitBtn) submitBtn.disabled = !enabled;
  }

  var lastFocusedElement = null;

  function getFocusableElements() {
    if (!modal) return [];
    return Array.prototype.slice.call(
      modal.querySelectorAll('button:not([disabled]), [href], input:not([disabled]), select, textarea, [tabindex]:not([tabindex="-1"])')
    ).filter(function (el) { return el.offsetParent !== null; });
  }

  function trapFocus(event) {
    if (event.key === 'Escape') {
      closeModal();
      return;
    }
    if (event.key !== 'Tab') return;
    var focusable = getFocusableElements();
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
    document.addEventListener('keydown', trapFocus);
    var focusable = getFocusableElements();
    if (focusable.length) focusable[0].focus();
  }

  function closeModal() {
    if (!modal) return;
    modal.classList.remove('is-open');
    document.removeEventListener('keydown', trapFocus);
    if (lastFocusedElement && typeof lastFocusedElement.focus === 'function') lastFocusedElement.focus();
  }

  function validateEmail(email) {
    return !!email && typeof email === 'string' && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim());
  }

  function getTicketCounts() {
    var tickets = {};
    var types = ['ADULT', 'CHILD', 'BENEFIT'];
    for (var i = 0; i < types.length; i++) {
      if (counts[types[i]] > 0) tickets[types[i]] = counts[types[i]];
    }
    return Object.keys(tickets).length ? tickets : null;
  }

  function generateUUID() {
    if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID();
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
      var r = (Math.random() * 16) | 0;
      return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
    });
  }

  function getOrCreateIdempotencyKey() {
    try {
      var stored = sessionStorage.getItem(idempotencyStorageKey);
      if (stored) return stored;
      var key = generateUUID();
      sessionStorage.setItem(idempotencyStorageKey, key);
      return key;
    } catch (e) {
      idempotencyKey = idempotencyKey || generateUUID();
      return idempotencyKey;
    }
  }

  function clearIdempotencyKey() {
    idempotencyKey = null;
    try { sessionStorage.removeItem(idempotencyStorageKey); } catch (e) {}
  }

  function saveOrder(order) {
    try {
      sessionStorage.setItem(orderStorageKey, JSON.stringify({ id: order.id, accessToken: order.accessToken, status: order.status }));
    } catch (e) {}
  }

  function loadOrder() {
    try {
      var raw = sessionStorage.getItem(orderStorageKey);
      return raw ? JSON.parse(raw) : null;
    } catch (e) {
      return null;
    }
  }

  function backendUrl(path) {
    return (config.backendUrl || '').replace(/\/$/, '') + path;
  }

  function showPaidPdfLink(order) {
    if (!resultEl || !order || !order.id || !order.accessToken) return;
    resultEl.textContent = '';
    var text = document.createElement('p');
    text.textContent = 'Заказ оплачен. Скачать билет:';
    var link = document.createElement('a');
    link.href = backendUrl('/api/v1/orders/' + encodeURIComponent(order.id) + '/tickets/pdf?accessToken=' + encodeURIComponent(order.accessToken));
    link.textContent = 'PDF билета';
    link.target = '_blank';
    link.rel = 'noopener noreferrer';
    resultEl.append(text, link);
  }

  function showTestPayButton(order) {
    if (!resultEl || !order || !order.id || !order.accessToken) return;
    resultEl.textContent = '';
    var text = document.createElement('p');
    text.textContent = 'Заказ создан. Оплата ещё не подтверждена.';
    var button = document.createElement('button');
    button.type = 'button';
    button.className = 'wt-test-pay';
    button.textContent = 'Тестовая оплата';
    button.addEventListener('click', function () { testPay(order, button); });
    resultEl.append(text, button);
  }

  function handleTestPaymentResponse(order, payment) {
    var updated = { id: order.id, accessToken: order.accessToken, status: payment && payment.status };
    saveOrder(updated);
    if (payment && (payment.testPaid || payment.status === 'PAID')) showPaidPdfLink(updated);
    else showTestPayButton(updated);
  }

  function testPayUrl(order) {
    return backendUrl('/api/v1/orders/' + encodeURIComponent(order.id) + '/test-pay?accessToken=' + encodeURIComponent(order.accessToken));
  }

  function testPay(order, button) {
    if (button) button.disabled = true;
    fetch(testPayUrl(order), { method: 'POST', credentials: 'omit' })
      .then(function (response) {
        if (!response.ok) throw new Error('test-pay');
        return response.json();
      })
      .then(function (payment) { handleTestPaymentResponse(order, payment); })
      .catch(function () { setResult('Не удалось выполнить тестовую оплату. Попробуйте ещё раз.', true); })
      .finally(function () { if (button) button.disabled = false; });
  }

  function fetchTestPayStatus(order) {
    fetch(testPayUrl(order), { method: 'GET', credentials: 'omit' })
      .then(function (response) {
        if (!response.ok) throw new Error('test-pay-status');
        return response.json();
      })
      .then(function (payment) { handleTestPaymentResponse(order, payment); })
      .catch(function () { showTestPayButton(order); });
  }

  function payUrl(order) {
    return backendUrl('/api/v1/orders/' + encodeURIComponent(order.id) + '/pay?accessToken=' + encodeURIComponent(order.accessToken));
  }

  function startRealPayment(order) {
    setResult('Переходим к оплате...', false);
    fetch(payUrl(order), { method: 'POST', credentials: 'omit' })
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
        setResult('Не удалось перейти к оплате. Попробуйте ещё раз или напишите нам.', true);
      });
  }

  // The ticket-issuance job runs on an interval (up to ~30s after payment), so a customer
  // freshly back from YooKassa may not have a ticket yet even though payment succeeded.
  // Poll a few times before telling them to check back rather than reporting a false negative.
  function pollForIssuedTicket(order, attemptsLeft) {
    fetch(backendUrl('/api/v1/orders/' + encodeURIComponent(order.id) + '/tickets?accessToken=' + encodeURIComponent(order.accessToken)),
      { method: 'GET', credentials: 'omit' })
      .then(function (response) {
        if (!response.ok) throw new Error('tickets');
        return response.json();
      })
      .then(function (tickets) {
        if (tickets && tickets.length) {
          saveOrder({ id: order.id, accessToken: order.accessToken, status: 'PAID' });
          showPaidPdfLink(order);
          return;
        }
        if (attemptsLeft > 0) {
          setResult('Оплата обрабатывается, подождите...', false);
          setTimeout(function () { pollForIssuedTicket(order, attemptsLeft - 1); }, 5000);
        } else {
          showPaymentPendingWithRetry(order);
        }
      })
      .catch(function () {
        setResult('Не удалось проверить статус оплаты. Обновите страницу.', true);
      });
  }

  function showPaymentPendingWithRetry(order) {
    if (!resultEl) return;
    resultEl.textContent = '';
    var text = document.createElement('p');
    text.textContent = 'Оплата ещё не подтверждена. Если вы уже платили, обновите страницу через минуту. Если платёж не начинали или он не прошёл — попробуйте снова:';
    var button = document.createElement('button');
    button.type = 'button';
    button.className = 'wt-test-pay';
    button.textContent = 'Оплатить';
    button.addEventListener('click', function () { startRealPayment(order); });
    resultEl.append(text, button);
  }

  function restoreOrderIfAny() {
    var order = loadOrder();
    if (!order) return;
    openModal();
    if (order.status === 'PAID') return showPaidPdfLink(order);
    if (config.localTestMode) return fetchTestPayStatus(order);
    // Real payment: the customer may be returning from YooKassa right now, so always
    // re-check with the backend instead of trusting the pre-payment status we cached.
    pollForIssuedTicket(order, 6);
  }

  function fetchCatalog() {
    if (!config.localTestMode || !config.catalogPath) return updateDisplay();
    fetch(backendUrl(config.catalogPath), { method: 'GET', credentials: 'omit' })
      .then(function (response) {
        if (!response.ok) throw new Error('catalog');
        return response.json();
      })
      .then(function (catalog) {
        ['ADULT', 'CHILD', 'BENEFIT'].forEach(function (type) {
          if (catalog && Number(catalog[type]) >= 0) prices[type] = Number(catalog[type]);
        });
        updateDisplay();
      })
      .catch(updateDisplay);
  }

  function submitOrder(event) {
    event.preventDefault();
    var email = emailInput ? emailInput.value.trim() : '';
    if (!validateEmail(email)) return setResult('Укажите корректный email.', true);
    var tickets = getTicketCounts();
    if (!tickets) return setResult('Выберите хотя бы один билет.', true);
    setSubmitEnabled(false);
    fetch(backendUrl(config.ordersPath || '/api/v1/orders'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': getOrCreateIdempotencyKey() },
      body: JSON.stringify({ email: email, phoneNumber: phoneInput ? phoneInput.value.trim() : '', tickets: tickets }),
      credentials: 'omit'
    })
      .then(function (response) {
        if (!response.ok) throw new Error('order');
        return response.json();
      })
      .then(function (order) {
        if (!order || !order.id || !order.accessToken) throw new Error('order');
        clearIdempotencyKey();
        saveOrder(order);
        if (order.status === 'PAID') { showPaidPdfLink(order); return; }
        if (config.localTestMode) { showTestPayButton(order); return; }
        startRealPayment(order);
      })
      .catch(function () { setResult('Не удалось создать заказ. Попробуйте ещё раз.', true); })
      .finally(function () { setSubmitEnabled(true); });
  }

  function init() {
    if (!form) return;
    if (openBtn) openBtn.addEventListener('click', openModal);
    if (closeBtn) closeBtn.addEventListener('click', closeModal);
    if (modal) modal.addEventListener('click', function (event) { if (event.target === modal) closeModal(); });
    document.addEventListener('click', function (event) {
      var button = event.target.closest('[data-type][data-delta]');
      if (!button || !counts.hasOwnProperty(button.dataset.type)) return;
      counts[button.dataset.type] = Math.max(0, counts[button.dataset.type] + Number(button.dataset.delta));
      updateDisplay();
    });
    form.addEventListener('submit', submitOrder);
    updateDisplay();
    fetchCatalog();
    restoreOrderIfAny();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();

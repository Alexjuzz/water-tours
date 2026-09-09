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
      sessionStorage.setItem('wt_last_order_kind', 'ticket');
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
    try {
      if (sessionStorage.getItem('wt_last_order_kind') === 'boat') return;
    } catch (e) {}
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

  var boatPrices = { 30: 3500, 60: 6000, 90: 9000, 120: 11000 };
  var boatOrderStorageKey = 'wt_boat_order';
  var boatIdempotencyStorageKey = 'wt_boat_idempotency_key';
  var boatIdempotencyKey = null;
  var boatModal = document.getElementById('wt-boat-modal');
  var boatOpenBtn = document.getElementById('wt-boat-open-modal');
  var boatCloseBtn = document.getElementById('wt-boat-close-modal');
  var boatForm = document.getElementById('wt-boat-form');
  var boatEmailInput = document.getElementById('wt-boat-email');
  var boatPhoneInput = document.getElementById('wt-boat-phone');
  var boatGuestsInput = document.getElementById('wt-boat-guests');
  var boatDurationInput = document.getElementById('wt-boat-duration');
  var boatRouteNoteInput = document.getElementById('wt-boat-route-note');
  var boatSubmitBtn = document.getElementById('wt-boat-submit');
  var boatResultEl = document.getElementById('wt-boat-result');
  var boatTotalEl = document.getElementById('wt-boat-total-sum');
  var boatLastFocusedElement = null;

  function setBoatResult(text, isError) {
    if (!boatResultEl) return;
    boatResultEl.textContent = text || '';
    boatResultEl.style.color = isError ? '#c00' : '';
  }

  function getBoatFocusableElements() {
    if (!boatModal) return [];
    return Array.prototype.slice.call(
      boatModal.querySelectorAll('button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])')
    ).filter(function (el) { return el.offsetParent !== null; });
  }

  function trapBoatFocus(event) {
    if (event.key === 'Escape') {
      closeBoatModal();
      return;
    }
    if (event.key !== 'Tab') return;
    var focusable = getBoatFocusableElements();
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

  function openBoatModal() {
    if (!boatModal) return;
    boatLastFocusedElement = document.activeElement;
    boatModal.classList.add('is-open');
    boatModal.setAttribute('aria-hidden', 'false');
    document.addEventListener('keydown', trapBoatFocus);
    var focusable = getBoatFocusableElements();
    if (focusable.length) focusable[0].focus();
  }

  function closeBoatModal() {
    if (!boatModal) return;
    boatModal.classList.remove('is-open');
    boatModal.setAttribute('aria-hidden', 'true');
    document.removeEventListener('keydown', trapBoatFocus);
    if (boatLastFocusedElement && typeof boatLastFocusedElement.focus === 'function') boatLastFocusedElement.focus();
  }

  function formatBoatPrice(value) {
    return String(value).replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
  }

  function updateBoatTotal() {
    var duration = Number(boatDurationInput ? boatDurationInput.value : 30);
    if (boatTotalEl) boatTotalEl.textContent = formatBoatPrice(boatPrices[duration] || 0);
  }

  function getOrCreateBoatIdempotencyKey() {
    try {
      var stored = sessionStorage.getItem(boatIdempotencyStorageKey);
      if (stored) return stored;
      var key = generateUUID();
      sessionStorage.setItem(boatIdempotencyStorageKey, key);
      return key;
    } catch (e) {
      boatIdempotencyKey = boatIdempotencyKey || generateUUID();
      return boatIdempotencyKey;
    }
  }

  function clearBoatIdempotencyKey() {
    boatIdempotencyKey = null;
    try { sessionStorage.removeItem(boatIdempotencyStorageKey); } catch (e) {}
  }

  function saveBoatOrder(order) {
    try {
      sessionStorage.setItem(boatOrderStorageKey, JSON.stringify({ id: order.id, accessToken: order.accessToken, status: order.status }));
      sessionStorage.setItem('wt_last_order_kind', 'boat');
    } catch (e) {}
  }

  function loadBoatOrder() {
    try {
      var raw = sessionStorage.getItem(boatOrderStorageKey);
      return raw ? JSON.parse(raw) : null;
    } catch (e) {
      return null;
    }
  }

  function showBoatPaidPdfLink(order) {
    if (!boatResultEl || !order || !order.id || !order.accessToken) return;
    boatResultEl.textContent = '';
    var text = document.createElement('p');
    text.textContent = 'Аренда оплачена. Скачать билет:';
    var link = document.createElement('a');
    link.href = backendUrl('/api/v1/orders/' + encodeURIComponent(order.id) + '/tickets/pdf?accessToken=' + encodeURIComponent(order.accessToken));
    link.textContent = 'PDF билета';
    link.target = '_blank';
    link.rel = 'noopener noreferrer';
    boatResultEl.append(text, link);
  }

  function showBoatTestPayButton(order) {
    if (!boatResultEl || !order || !order.id || !order.accessToken) return;
    boatResultEl.textContent = '';
    var text = document.createElement('p');
    text.textContent = 'Заказ создан. Оплата ещё не подтверждена.';
    var button = document.createElement('button');
    button.type = 'button';
    button.className = 'wt-test-pay';
    button.textContent = 'Тестовая оплата';
    button.addEventListener('click', function () { testPayBoat(order, button); });
    boatResultEl.append(text, button);
  }

  function handleBoatTestPaymentResponse(order, payment) {
    var updated = { id: order.id, accessToken: order.accessToken, status: payment && payment.status };
    saveBoatOrder(updated);
    if (payment && (payment.testPaid || payment.status === 'PAID')) showBoatPaidPdfLink(updated);
    else showBoatTestPayButton(updated);
  }

  function testPayBoat(order, button) {
    if (button) button.disabled = true;
    fetch(testPayUrl(order), { method: 'POST', credentials: 'omit' })
      .then(function (response) {
        if (!response.ok) throw new Error('test-pay');
        return response.json();
      })
      .then(function (payment) { handleBoatTestPaymentResponse(order, payment); })
      .catch(function () { setBoatResult('Не удалось выполнить тестовую оплату. Попробуйте ещё раз.', true); })
      .finally(function () { if (button) button.disabled = false; });
  }

  function fetchBoatTestPayStatus(order) {
    fetch(testPayUrl(order), { method: 'GET', credentials: 'omit' })
      .then(function (response) {
        if (!response.ok) throw new Error('test-pay-status');
        return response.json();
      })
      .then(function (payment) { handleBoatTestPaymentResponse(order, payment); })
      .catch(function () { showBoatTestPayButton(order); });
  }

  function startBoatRealPayment(order) {
    setBoatResult('Переходим к оплате...', false);
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
      .catch(function () { setBoatResult('Не удалось перейти к оплате. Попробуйте ещё раз или напишите нам.', true); });
  }

  function showBoatPaymentPendingWithRetry(order) {
    if (!boatResultEl) return;
    boatResultEl.textContent = '';
    var text = document.createElement('p');
    text.textContent = 'Оплата ещё не подтверждена. Если вы уже платили, обновите страницу через минуту.';
    var button = document.createElement('button');
    button.type = 'button';
    button.className = 'wt-test-pay';
    button.textContent = 'Оплатить';
    button.addEventListener('click', function () { startBoatRealPayment(order); });
    boatResultEl.append(text, button);
  }

  function pollForBoatTicket(order, attemptsLeft) {
    fetch(backendUrl('/api/v1/orders/' + encodeURIComponent(order.id) + '/tickets?accessToken=' + encodeURIComponent(order.accessToken)),
      { method: 'GET', credentials: 'omit' })
      .then(function (response) {
        if (!response.ok) throw new Error('tickets');
        return response.json();
      })
      .then(function (tickets) {
        if (tickets && tickets.length) {
          saveBoatOrder({ id: order.id, accessToken: order.accessToken, status: 'PAID' });
          showBoatPaidPdfLink(order);
          return;
        }
        if (attemptsLeft > 0) {
          setBoatResult('Оплата обрабатывается, подождите...', false);
          setTimeout(function () { pollForBoatTicket(order, attemptsLeft - 1); }, 5000);
        } else {
          showBoatPaymentPendingWithRetry(order);
        }
      })
      .catch(function () { setBoatResult('Не удалось проверить статус оплаты. Обновите страницу.', true); });
  }

  function restoreBoatOrderIfAny() {
    try {
      if (sessionStorage.getItem('wt_last_order_kind') !== 'boat') return;
    } catch (e) { return; }
    var order = loadBoatOrder();
    if (!order) return;
    openBoatModal();
    if (order.status === 'PAID') return showBoatPaidPdfLink(order);
    if (config.localTestMode) return fetchBoatTestPayStatus(order);
    pollForBoatTicket(order, 6);
  }

  function selectedBoatRouteType() {
    var selected = boatForm ? boatForm.querySelector('input[name="wt-boat-route"]:checked') : null;
    return selected ? selected.value : '';
  }

  function submitBoatOrder(event) {
    event.preventDefault();
    var email = boatEmailInput ? boatEmailInput.value.trim() : '';
    var phone = boatPhoneInput ? boatPhoneInput.value.trim() : '';
    var guests = Number(boatGuestsInput ? boatGuestsInput.value : 0);
    var duration = Number(boatDurationInput ? boatDurationInput.value : 0);
    var routeType = selectedBoatRouteType();
    var routeNote = boatRouteNoteInput ? boatRouteNoteInput.value.trim() : '';

    if (!validateEmail(email)) return setBoatResult('Укажите корректный email.', true);
    if (!phone) return setBoatResult('Укажите телефон.', true);
    if (!Number.isInteger(guests) || guests < 1 || guests > 6) return setBoatResult('Выберите от 1 до 6 гостей.', true);
    if (!Object.prototype.hasOwnProperty.call(boatPrices, duration)) return setBoatResult('Выберите доступную продолжительность.', true);
    if (routeType !== 'CUSTOM' && routeType !== 'ASSISTED') return setBoatResult('Выберите вариант маршрута.', true);
    if (routeNote.length > 300) return setBoatResult('Пожелания к маршруту должны быть короче 300 символов.', true);

    if (boatSubmitBtn) boatSubmitBtn.disabled = true;
    fetch(backendUrl(config.ordersPath || '/api/v1/orders'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': getOrCreateBoatIdempotencyKey() },
      body: JSON.stringify({
        email: email,
        phoneNumber: phone,
        boatRental: {
          durationMinutes: duration,
          guestCount: guests,
          routeType: routeType,
          routeNote: routeNote
        }
      }),
      credentials: 'omit'
    })
      .then(function (response) {
        if (!response.ok) throw new Error('order');
        return response.json();
      })
      .then(function (order) {
        if (!order || !order.id || !order.accessToken) throw new Error('order');
        clearBoatIdempotencyKey();
        saveBoatOrder(order);
        if (order.status === 'PAID') { showBoatPaidPdfLink(order); return; }
        if (config.localTestMode) { showBoatTestPayButton(order); return; }
        startBoatRealPayment(order);
      })
      .catch(function () { setBoatResult('Не удалось создать заказ. Проверьте данные и попробуйте ещё раз.', true); })
      .finally(function () { if (boatSubmitBtn) boatSubmitBtn.disabled = false; });
  }

  function initBoat() {
    if (!boatForm) return;
    if (boatOpenBtn) boatOpenBtn.addEventListener('click', openBoatModal);
    if (boatCloseBtn) boatCloseBtn.addEventListener('click', closeBoatModal);
    if (boatModal) boatModal.addEventListener('click', function (event) { if (event.target === boatModal) closeBoatModal(); });
    if (boatDurationInput) boatDurationInput.addEventListener('change', updateBoatTotal);
    boatForm.addEventListener('submit', submitBoatOrder);
    updateBoatTotal();
    restoreBoatOrderIfAny();
  }

  function init() {
    if (form) {
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
    initBoat();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();

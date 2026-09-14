(function () {
  'use strict';
  var toggle = document.querySelector('.menu-toggle');
  var nav = document.getElementById('river-nav');
  if (!toggle || !nav) return;
  function closeMenu() { nav.classList.remove('open'); toggle.setAttribute('aria-expanded', 'false'); }
  toggle.addEventListener('click', function () { var open = nav.classList.toggle('open'); toggle.setAttribute('aria-expanded', String(open)); });
  nav.addEventListener('click', function (event) { if (event.target.closest('a')) closeMenu(); });
  document.addEventListener('keydown', function (event) { if (event.key === 'Escape' && nav.classList.contains('open')) { closeMenu(); toggle.focus(); } });
  window.matchMedia('(min-width:901px)').addEventListener('change', closeMenu);
})();

/**
 * "Задать вопрос" dialog.
 *
 * The form posts to the backend, which is the only thing that talks to Telegram - no bot token,
 * chat id or any other credential reaches the browser. The button stays hidden until the backend
 * confirms a support recipient is configured, so a visitor is never offered a form whose answer
 * could not be delivered.
 *
 * The success message says the question was received, never that anyone has read it.
 */
(function () {
  'use strict';

  var openButton = document.getElementById('wt-ask-open');
  var line = document.getElementById('wt-ask-line');
  var modal = document.getElementById('wt-ask-modal');
  var panel = modal && modal.querySelector('.wt-ask-panel');
  var form = document.getElementById('wt-ask-form');
  var status = document.getElementById('wt-ask-status');
  var submit = document.getElementById('wt-ask-submit');
  var closeButton = document.getElementById('wt-ask-close');
  var message = document.getElementById('wt-ask-message');
  var contact = document.getElementById('wt-ask-contact');
  if (!openButton || !line || !modal || !panel || !form || !status || !submit) return;

  var MIN_MESSAGE = 10;
  var lastFocused = null;
  var sending = false;
  var accepted = false;

  function backendUrl(path) {
    var config = window.WaterToursConfig || {};
    return String(config.backendUrl || '').replace(/\/$/, '') + path;
  }

  function fetchWithTimeout(url, options, ms) {
    if (typeof AbortController === 'undefined') return fetch(url, options);
    var controller = new AbortController();
    var timer = setTimeout(function () { controller.abort(); }, ms);
    options = options || {};
    options.signal = controller.signal;
    return fetch(url, options).finally(function () { clearTimeout(timer); });
  }

  // Reveal the entry point only for a configured, reachable support channel.
  fetchWithTimeout(backendUrl('/api/v1/support/status'), { method: 'GET' }, 5000)
    .then(function (response) { return response.ok ? response.json() : null; })
    .then(function (body) { if (body && body.enabled === true) line.hidden = false; })
    .catch(function () { /* leave the button hidden */ });

  function focusable() {
    return Array.prototype.filter.call(
      panel.querySelectorAll('button, [href], input, textarea, select'),
      function (el) { return !el.disabled && el.offsetParent !== null; }
    );
  }

  function openModal() {
    lastFocused = document.activeElement;
    modal.hidden = false;
    document.body.style.overflow = 'hidden';
    (message || panel).focus();
  }

  function closeModal() {
    modal.hidden = true;
    document.body.style.overflow = '';
    if (lastFocused && lastFocused.focus) lastFocused.focus();
  }

  function setStatus(text, state) {
    status.textContent = text;
    if (state) status.setAttribute('data-state', state);
    else status.removeAttribute('data-state');
  }

  function markInvalid(field, invalid) {
    if (field) field.setAttribute('aria-invalid', invalid ? 'true' : 'false');
  }

  openButton.addEventListener('click', openModal);
  if (closeButton) closeButton.addEventListener('click', closeModal);
  modal.addEventListener('click', function (event) {
    if (event.target.hasAttribute('data-wt-ask-close')) closeModal();
  });

  document.addEventListener('keydown', function (event) {
    if (modal.hidden) return;
    if (event.key === 'Escape') { closeModal(); return; }
    if (event.key !== 'Tab') return;
    // Keep keyboard focus inside the dialog while it is open.
    var items = focusable();
    if (!items.length) return;
    var first = items[0];
    var last = items[items.length - 1];
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
  });

  form.addEventListener('submit', function (event) {
    event.preventDefault();
    if (sending || accepted) return;

    var messageText = (message.value || '').trim();
    var contactText = (contact.value || '').trim();
    markInvalid(message, false);
    markInvalid(contact, false);

    if (messageText.length < MIN_MESSAGE) {
      markInvalid(message, true);
      setStatus('Опишите вопрос подробнее — не менее ' + MIN_MESSAGE + ' символов.', 'error');
      message.focus();
      return;
    }
    if (!contactText) {
      markInvalid(contact, true);
      setStatus('Укажите email или телефон — иначе ответить будет некуда.', 'error');
      contact.focus();
      return;
    }

    sending = true;
    submit.disabled = true;
    setStatus('Отправляем…', null);

    fetchWithTimeout(backendUrl('/api/v1/support/questions'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        message: messageText,
        contact: contactText,
        orderReference: (document.getElementById('wt-ask-order').value || '').trim(),
        website: document.getElementById('wt-ask-website').value || ''
      })
    }, 15000).then(function (response) {
      return response.json().catch(function () { return {}; }).then(function (body) {
        return { ok: response.ok, status: response.status, body: body };
      });
    }).then(function (result) {
      if (result.ok && result.body && result.body.accepted) {
        accepted = true;
        form.hidden = true;
        // Received and stored. Deliberately not "прочитали" - nothing here knows that.
        setStatus('Вопрос получен, номер обращения ' + result.body.reference
          + '. Мы передали его в поддержку и ответим на указанный контакт.', 'ok');
        return;
      }
      var text = (result.body && result.body.error)
        || (result.status === 429
          ? 'Слишком много обращений подряд. Попробуйте позже.'
          : 'Не удалось отправить вопрос. Попробуйте ещё раз позже.');
      setStatus(text, 'error');
    }).catch(function () {
      setStatus('Не удалось отправить вопрос: нет связи с сервером. Попробуйте ещё раз позже.', 'error');
    }).finally(function () {
      sending = false;
      submit.disabled = accepted;
    });
  });
})();

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

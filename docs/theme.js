// Light or dark site theme. Follows the device until the visitor picks one.
(function () {
  var root = document.documentElement;
  var media = window.matchMedia ? matchMedia('(prefers-color-scheme: light)') : null;
  var saved = null;
  try { saved = localStorage.getItem('theme'); } catch (e) {}
  if (saved === 'light' || saved === 'dark') root.dataset.theme = saved;

  function current() {
    return root.dataset.theme || (media && media.matches ? 'light' : 'dark');
  }
  function announce() {
    var theme = current();
    document.querySelectorAll('.theme-toggle').forEach(function (b) {
      b.setAttribute('aria-label', theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode');
    });
    document.dispatchEvent(new CustomEvent('themechange', { detail: theme }));
  }
  window.plumeTheme = current;

  if (media && media.addEventListener) media.addEventListener('change', function () { if (!root.dataset.theme) announce(); });
  document.addEventListener('DOMContentLoaded', function () {
    document.querySelectorAll('.theme-toggle').forEach(function (b) {
      b.addEventListener('click', function () {
        root.dataset.theme = current() === 'dark' ? 'light' : 'dark';
        try { localStorage.setItem('theme', root.dataset.theme); } catch (e) {}
        announce();
      });
    });
    announce();
  });
})();

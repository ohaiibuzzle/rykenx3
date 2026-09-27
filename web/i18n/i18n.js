// Translation core. Each language lives in its own file next to this one and calls
// I18n.register(code, name, strings); load it with a <script> tag after this file.
// English ('en') is the fallback for keys a language does not define.
'use strict';

const I18n = (() => {
  const languages = {}; // code -> {name, strings}
  const KEY = 'ryken.lang';
  let lang = null; // resolved on first use, once every language file has registered

  function register(code, name, strings) {
    languages[code] = { name, strings };
  }

  function detect() {
    try {
      const saved = localStorage.getItem(KEY);
      if (saved in languages) return saved;
    } catch (_) { /* storage unavailable */ }
    for (const l of navigator.languages || [navigator.language]) {
      const base = String(l).split('-')[0].toLowerCase();
      if (base in languages) return base;
    }
    return 'en';
  }

  function current() {
    if (!lang) lang = detect();
    return lang;
  }

  function t(key, params = {}) {
    const s = languages[current()]?.strings[key] ?? languages.en?.strings[key] ?? key;
    return s.replace(/\{(\w+)\}/g, (m, k) => (k in params ? params[k] : m));
  }

  // Fill [data-i18n] text and [data-i18n-title] / [data-i18n-aria-label] attributes.
  function apply(root = document) {
    for (const el of root.querySelectorAll('[data-i18n]')) el.textContent = t(el.dataset.i18n);
    for (const el of root.querySelectorAll('[data-i18n-title]')) el.title = t(el.dataset.i18nTitle);
    for (const el of root.querySelectorAll('[data-i18n-aria-label]')) {
      el.setAttribute('aria-label', t(el.dataset.i18nAriaLabel));
    }
    document.documentElement.lang = current();
    document.title = t('app.title');
    document.querySelector('meta[name=description]')?.setAttribute('content', t('app.description'));
  }

  function setLang(code) {
    if (!(code in languages)) return;
    lang = code;
    try { localStorage.setItem(KEY, code); } catch (_) { /* storage unavailable */ }
    apply();
  }

  // -> [{code, name}] in registration order
  function list() {
    return Object.entries(languages).map(([code, { name }]) => ({ code, name }));
  }

  return { register, t, apply, setLang, list, get lang() { return current(); } };
})();

'use strict';
/* Smart Service Hub — core: state, helpers, API client, modals, notifications, router, application shell. */

/* ================= Storage / state ================= */
const STORE = {
  get(k, d) { try { const v = localStorage.getItem(k); return v === null ? d : v; } catch (e) { return d; } },
  set(k, v) { try { localStorage.setItem(k, v); } catch (e) { /* storage unavailable */ } },
};
const state = {
  user: null, categories: [], districts: [],
  unread: 0, notifItems: [], notifSig: '', notifReady: false,
  lang: STORE.get('lang', 'kk') === 'ru' ? 'ru' : 'kk',
  theme: ['light', 'dark'].includes(STORE.get('theme', 'system')) ? STORE.get('theme') : 'system',
  collapsed: STORE.get('sidebar', '0') === '1',
  returnTo: null, flash: null,
};
/** Per-page UI state (tabs, filters). Lives in memory so re-renders and language switches keep it. */
const ui = {};

/* ================= Helpers ================= */
const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const t = (k, vars) => {
  let s = (I18N[state.lang] && I18N[state.lang][k]);
  if (s === undefined) s = I18N.kk[k];
  if (s === undefined) s = k;
  return vars ? s.replace(/\{(\w+)\}/g, (_, n) => (vars[n] ?? '')) : s;
};
const loc = (o, base) => (o ? o[base + (state.lang === 'kk' ? 'Kk' : 'Ru')] || '' : '');
const locale = () => (state.lang === 'kk' ? 'kk-KZ' : 'ru-RU');
const money = (n) => new Intl.NumberFormat(locale(), { maximumFractionDigits: 0 }).format(Number(n) || 0) + ' ₸';
const fmtDate = (s) => (s ? new Date(s).toLocaleString(locale(), { dateStyle: 'medium', timeStyle: 'short' }) : '—');
const fmtDay = (s) => (s ? new Date(s).toLocaleDateString(locale(), { day: 'numeric', month: 'short', year: 'numeric' }) : '—');
const fmtShortDay = (s) => new Date(s).toLocaleDateString(locale(), { day: '2-digit', month: '2-digit' });
const initials = (name) => String(name || '?').trim().split(/\s+/).slice(0, 2).map((p) => p[0]).join('').toUpperCase();
const debounce = (fn, ms = 300) => { let id; return (...a) => { clearTimeout(id); id = setTimeout(() => fn(...a), ms); }; };
const sameDay = (a, b) => a.toDateString() === b.toDateString();

function relTime(s) {
  if (!s) return '';
  const d = new Date(s);
  const diff = (d.getTime() - Date.now()) / 1000;
  const abs = Math.abs(diff);
  const rtf = new Intl.RelativeTimeFormat(locale(), { numeric: 'auto' });
  if (abs < 60) return rtf.format(0, 'second');
  if (abs < 3600) return rtf.format(Math.round(diff / 60), 'minute');
  if (abs < 86400) return rtf.format(Math.round(diff / 3600), 'hour');
  if (abs < 7 * 86400) return rtf.format(Math.round(diff / 86400), 'day');
  return fmtDay(s);
}

/** Server messages arrive as "қазақша||русский". */
function msg(m) {
  if (!m) return t('err_generic');
  const parts = String(m).split('||');
  return parts.length > 1 ? (state.lang === 'kk' ? parts[0] : parts[1]) : parts[0];
}

/** History notes are stored as "қазақша / русский[: name]". Pick the current language. */
function splitNote(note) {
  if (!note) return '';
  const m = String(note).match(/^(.+?) \/ ([^:]+?)(: (.+))?$/);
  if (!m) return note;
  const tail = m[3] || '';
  return (state.lang === 'kk' ? m[1] : m[2]) + tail;
}

class ApiError extends Error {
  constructor(message, status) { super(message); this.status = status; }
}
class Stale extends Error {}

function getCookie(name) {
  const m = document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]*)'));
  return m ? decodeURIComponent(m[1]) : null;
}

/* ================= API client ================= */
async function api(path, { method = 'GET', body, auth = true } = {}) {
  const headers = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (method !== 'GET') {
    const xsrf = getCookie('XSRF-TOKEN');
    if (xsrf) headers['X-XSRF-TOKEN'] = xsrf;
  }
  let res;
  try {
    res = await fetch('/api' + path, { method, headers, body: body !== undefined ? JSON.stringify(body) : undefined, credentials: 'same-origin' });
  } catch (e) {
    throw new ApiError(t('err_network'), 0);
  }
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch (e) { /* empty or non-JSON body */ }
  if (res.status === 401 && auth) {
    sessionExpired();
    const err = new ApiError(t('session_expired'), 401);
    err.silent = true;
    throw err;
  }
  if (!res.ok) throw new ApiError(msg(data && data.message), res.status);
  return data;
}

/** The server session is gone: drop stale user state and send the user to the login screen. */
function sessionExpired() {
  if (!state.user) return;
  state.user = null;
  stopNotifPolling();
  closeAllModals();
  state.flash = t('session_expired');
  state.returnTo = null;
  go('#/login');
}

/* ================= Toasts ================= */
function toast(text, kind = 'ok', ms = 4200) {
  const box = $('#toasts');
  if (!box) return;
  const el = document.createElement('div');
  el.className = 'toast ' + kind;
  el.innerHTML = icon(kind === 'err' ? 'alert' : kind === 'info' ? 'info' : 'check-circle', 18) + `<span>${esc(text)}</span>`;
  box.appendChild(el);
  while (box.children.length > 4) box.firstElementChild.remove();
  setTimeout(() => el.remove(), ms);
}

/* ================= Modal manager (focus trap, Esc, focus return) ================= */
const modals = [];
let modalSeq = 0;
const FOCUSABLE = 'a[href],button:not([disabled]),input:not([disabled]):not([type=hidden]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])';
const focusables = (root) => $$(FOCUSABLE, root).filter((el) => el.offsetParent !== null || el === document.activeElement);

function syncModalState() {
  document.documentElement.classList.toggle('modal-open', modals.length > 0);
  const root = $('#root');
  if (root) root.inert = modals.length > 0;
}

function openModal({ title = '', size = 'md', html = '', onClose } = {}) {
  const id = 'modal-' + (++modalSeq);
  const back = document.createElement('div');
  back.className = 'modal-backdrop';
  back.innerHTML = `<div class="modal ${size}" role="dialog" aria-modal="true" aria-labelledby="${id}-title" tabindex="-1">
    <div class="modal-head"><h2 id="${id}-title">${esc(title)}</h2><button type="button" class="icon-btn" data-close aria-label="${esc(t('close'))}">${icon('x')}</button></div>
    <div class="modal-body">${html}</div></div>`;
  const opener = document.activeElement;
  $('#modal-root').appendChild(back);
  const el = back.firstElementChild;
  const m = {
    id, el, back, body: $('.modal-body', el), opener, closed: false, onClose,
    setTitle(s) { $('h2', el).textContent = s; },
    close() { closeModalObj(m); },
  };
  modals.push(m);
  syncModalState();
  back.addEventListener('mousedown', (e) => { if (e.target === back) m.close(); });
  $('[data-close]', el).addEventListener('click', () => m.close());
  requestAnimationFrame(() => { const f = focusables(m.body)[0] || $('[data-close]', el); if (f) f.focus({ preventScroll: true }); });
  return m;
}

function closeModalObj(m) {
  if (m.closed) return;
  m.closed = true;
  const i = modals.indexOf(m);
  if (i >= 0) modals.splice(i, 1);
  m.back.remove();
  syncModalState();
  if (m.onClose) { try { m.onClose(); } catch (e) { console.error(e); } }
  const target = m.opener && document.contains(m.opener) && m.opener.focus ? m.opener : null;
  if (!modals.length) (target || $('#main') || document.body).focus({ preventScroll: true });
  else if (target) target.focus({ preventScroll: true });
}
function closeAllModals() { [...modals].reverse().forEach((m) => closeModalObj(m)); }

function confirmDialog({ title, message, confirmLabel, cancelLabel, danger = false } = {}) {
  return new Promise((resolve) => {
    let done = false;
    const finish = (v) => { if (!done) { done = true; resolve(v); } };
    const m = openModal({
      title: title || t('confirm_title'), size: 'sm',
      html: `<p class="secondary">${esc(message || '')}</p>
        <div class="modal-actions"><button type="button" class="btn secondary" data-no>${esc(cancelLabel || t('cancel'))}</button>
        <button type="button" class="btn ${danger ? 'danger' : ''}" data-yes>${esc(confirmLabel || t('confirm'))}</button></div>`,
      onClose: () => finish(false),
    });
    $('[data-no]', m.body).addEventListener('click', () => m.close());
    $('[data-yes]', m.body).addEventListener('click', () => { finish(true); m.close(); });
    if (danger) requestAnimationFrame(() => $('[data-no]', m.body).focus());
  });
}

document.addEventListener('keydown', (e) => {
  const top = modals[modals.length - 1];
  if (e.key === 'Escape') {
    if (top) { e.preventDefault(); top.close(); return; }
    if (closePopover()) { e.preventDefault(); return; }
    if (closeDrawer()) { e.preventDefault(); }
    return;
  }
  if (e.key === 'Tab' && top) {
    const f = focusables(top.el);
    if (!f.length) { e.preventDefault(); top.el.focus(); return; }
    const first = f[0], last = f[f.length - 1];
    if (e.shiftKey && (document.activeElement === first || document.activeElement === top.el)) { e.preventDefault(); last.focus(); }
    else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
  }
});

/* ================= Form helpers ================= */
function setFieldError(input, text) {
  if (!input) return;
  const field = input.closest('.field') || input.parentElement;
  let err = $('.field-error', field);
  if (!text) {
    input.removeAttribute('aria-invalid');
    input.removeAttribute('aria-describedby');
    if (err) err.remove();
    return;
  }
  input.setAttribute('aria-invalid', 'true');
  if (!err) { err = document.createElement('span'); err.className = 'field-error'; err.id = 'err-' + (input.name || input.id) + '-' + (++modalSeq); field.appendChild(err); }
  err.textContent = text;
  input.setAttribute('aria-describedby', err.id);
}
function clearFormErrors(form) {
  $$('[aria-invalid]', form).forEach((i) => setFieldError(i, ''));
  const a = $('.form-alert', form);
  if (a) a.remove();
}
function formAlert(form, text, kind = 'danger') {
  let a = $('.form-alert', form);
  if (!a) { a = document.createElement('div'); a.className = 'form-alert'; form.prepend(a); }
  a.innerHTML = `<div class="alert ${kind}" role="alert">${icon(kind === 'danger' ? 'alert' : 'info', 18)}<span>${esc(text)}</span></div>`;
}
/** Runs an async action while the button shows a busy state and ignores repeated clicks. */
async function withBusy(btn, fn) {
  if (btn) { btn.disabled = true; btn.setAttribute('aria-busy', 'true'); }
  try { return await fn(); } finally { if (btn && btn.isConnected) { btn.disabled = false; btn.removeAttribute('aria-busy'); } }
}
const districtOptions = (selected) =>
  `<option value="">${esc(t('choose'))}</option>` + state.districts.map((d) => `<option value="${esc(d)}" ${d === selected ? 'selected' : ''}>${esc(t('d_' + d))}</option>`).join('');

/* ================= Theme ================= */
const darkQuery = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;
function applyTheme() {
  const dark = state.theme === 'dark' || (state.theme === 'system' && darkQuery && darkQuery.matches);
  document.documentElement.dataset.theme = dark ? 'dark' : 'light';
  const meta = $('meta[name="theme-color"]');
  if (meta) meta.setAttribute('content', dark ? '#0b161d' : '#0b5c85');
}
if (darkQuery && darkQuery.addEventListener) darkQuery.addEventListener('change', applyTheme);
function setTheme(v) { state.theme = v; STORE.set('theme', v); applyTheme(); }

/* ================= Notifications ================= */
const notif = { timer: null, listeners: new Set(), busy: false };

function notifSignature(items) { return items.map((i) => i.id + (i.seen ? 's' : 'u')).join(','); }

async function pollNotifications(force = false) {
  if (!state.user || notif.busy || (document.hidden && !force)) return;
  notif.busy = true;
  try {
    const r = await api('/notifications');
    const sig = notifSignature(r.items);
    const changed = sig !== state.notifSig;
    if (state.notifReady && r.unread > state.unread) {
      const first = r.items.find((i) => !i.seen);
      if (first) toast(loc(first, 'message'), 'info');
    }
    state.unread = r.unread;
    state.notifReady = true;
    if (changed) {
      state.notifSig = sig;
      state.notifItems = r.items;
      fillNotifPopover();
      notif.listeners.forEach((fn) => { try { fn(); } catch (e) { console.error(e); } });
    }
    updateBell();
  } catch (e) { /* session errors are handled in api(); network blips are retried on the next tick */ }
  finally { notif.busy = false; }
}
function startNotifPolling() {
  stopNotifPolling();
  state.notifReady = false; state.notifSig = ''; state.notifItems = []; state.unread = 0;
  pollNotifications(true);
  notif.timer = setInterval(() => pollNotifications(false), 10000);
}
function stopNotifPolling() { clearInterval(notif.timer); notif.timer = null; }
document.addEventListener('visibilitychange', () => { if (!document.hidden) pollNotifications(true); });

function updateBell() {
  const c = $('#bell-count');
  if (c) { c.textContent = state.unread > 99 ? '99+' : state.unread; c.hidden = !state.unread; }
  const bell = $('#bell');
  if (bell) bell.setAttribute('aria-label', t('notifications') + (state.unread ? ` (${state.unread})` : ''));
  const nb = $('#nav-notif-badge');
  if (nb) { nb.textContent = state.unread > 99 ? '99+' : state.unread; nb.hidden = !state.unread; }
  setDocumentTitle();
}

function notifItemHtml(n) {
  const inner = `<span class="n-ico">${icon(n.orderId ? 'clipboard' : 'bell', 16)}</span>
    <span><span class="n-text">${esc(loc(n, 'message'))}</span><span class="n-time" style="display:block">${esc(relTime(n.createdAt))}</span></span>`;
  const cls = 'notif' + (n.seen ? '' : ' unseen');
  return n.orderId
    ? `<a class="${cls}" href="#/orders/${n.orderId}" data-notif-link>${inner}</a>`
    : `<div class="${cls}">${inner}</div>`;
}

/** Re-renders the popover list while keeping the scroll position. Only called when the data changed. */
function fillNotifPopover() {
  const body = $('#np-body');
  if (!body) return;
  const top = body.scrollTop;
  const items = state.notifItems.slice(0, 8);
  body.innerHTML = items.length ? items.map(notifItemHtml).join('') : `<p class="muted center" style="padding:1.5rem 1rem">${esc(t('no_notifications'))}</p>`;
  body.scrollTop = top;
  const ra = $('#np-readall');
  if (ra) ra.hidden = !state.unread;
}

async function markAllRead() {
  try {
    await api('/notifications/read-all', { method: 'POST' });
    state.unread = 0;
    state.notifItems = state.notifItems.map((n) => ({ ...n, seen: true }));
    state.notifSig = notifSignature(state.notifItems);
    fillNotifPopover(); updateBell();
    notif.listeners.forEach((fn) => fn());
  } catch (e) { if (!e.silent) toast(e.message, 'err'); }
}

function closePopover() {
  const p = $('#notif-pop');
  if (!p) return false;
  p.remove();
  const bell = $('#bell');
  if (bell) { bell.setAttribute('aria-expanded', 'false'); bell.focus({ preventScroll: true }); }
  return true;
}
function togglePopover() {
  if ($('#notif-pop')) { closePopover(); return; }
  const host = $('.topbar-right');
  if (!host) return;
  const pop = document.createElement('div');
  pop.className = 'popover'; pop.id = 'notif-pop'; pop.setAttribute('role', 'dialog'); pop.setAttribute('aria-label', t('notifications')); pop.tabIndex = -1;
  pop.innerHTML = `<div class="popover-head"><strong>${esc(t('notifications'))}</strong><button type="button" class="link-btn" id="np-readall">${esc(t('mark_read'))}</button></div>
    <div class="popover-body" id="np-body"></div>
    <div class="popover-foot"><a href="#/notifications" data-notif-link>${esc(t('view_all'))}</a></div>`;
  host.appendChild(pop);
  $('#bell').setAttribute('aria-expanded', 'true');
  fillNotifPopover();
  $('#np-readall').addEventListener('click', markAllRead);
  pop.focus({ preventScroll: true });
}
document.addEventListener('click', (e) => {
  if (e.target.closest('[data-notif-link]')) { const p = $('#notif-pop'); if (p) { p.remove(); const b = $('#bell'); if (b) b.setAttribute('aria-expanded', 'false'); } return; }
  const p = $('#notif-pop');
  if (p && !e.target.closest('#notif-pop') && !e.target.closest('#bell')) { p.remove(); const b = $('#bell'); if (b) b.setAttribute('aria-expanded', 'false'); }
});

/* ================= Navigation / shell ================= */
const go = (hash) => { if (location.hash === hash) route(); else location.hash = hash; };

const NAV = {
  CLIENT: [
    ['main', [['#/', 'nav_dashboard', 'dashboard'], ['#/services', 'nav_services', 'briefcase'], ['#/orders', 'nav_orders', 'clipboard'], ['#/payments', 'nav_payments', 'card'], ['#/reviews', 'nav_reviews', 'star']]],
    ['account', [['#/notifications', 'nav_notifications', 'bell'], ['#/profile', 'nav_profile', 'user'], ['#/settings', 'nav_settings', 'settings']]],
  ],
  WORKER: [
    ['main', [['#/', 'nav_dashboard', 'dashboard'], ['#/orders', 'nav_worker_orders', 'clipboard'], ['#/payments', 'nav_earnings', 'wallet'], ['#/reviews', 'nav_reviews', 'star']]],
    ['account', [['#/notifications', 'nav_notifications', 'bell'], ['#/profile', 'nav_profile', 'user'], ['#/settings', 'nav_settings', 'settings']]],
  ],
  ADMIN: [
    ['main', [['#/', 'nav_dashboard', 'dashboard'], ['#/orders', 'nav_worker_orders', 'clipboard'], ['#/services', 'nav_catalog', 'briefcase'], ['#/users', 'nav_users', 'users']]],
    ['account', [['#/notifications', 'nav_notifications', 'bell'], ['#/profile', 'nav_profile', 'user'], ['#/settings', 'nav_settings', 'settings']]],
  ],
};

const langSwitchHtml = (extra = '') => `<div class="segmented lang-switch ${extra}" role="group" aria-label="${esc(t('language'))}">
  <button type="button" data-lang="kk" aria-pressed="${state.lang === 'kk'}" lang="kk">ҚАЗ</button>
  <button type="button" data-lang="ru" aria-pressed="${state.lang === 'ru'}" lang="ru">РУС</button></div>`;

const brandHtml = (href) => `<a class="brand" href="${href}"><span class="brand-mark">${BRAND_MARK}</span><span class="brand-text">Smart Service Hub</span></a>`;

let shellKey = '';
function ensureShell(kind) {
  const u = state.user;
  const key = [kind, u ? u.id + u.role : 0, state.lang].join('|');
  if (key === shellKey && $('#main')) return;
  shellKey = key;
  const root = $('#root');
  const skip = `<a class="skip-link" href="#main" data-skip>${esc(t('skip_to_content'))}</a>`;
  if (kind === 'auth') {
    root.innerHTML = `${skip}<div class="auth-layout">
      <aside class="auth-aside">${brandHtml('#/services')}
        <div><h2>${esc(t('app_tagline'))}</h2><p>${esc(t('auth_aside_text'))}</p>
        <ul class="auth-points"><li>${icon('check-circle', 20)}${esc(t('auth_point_1'))}</li><li>${icon('check-circle', 20)}${esc(t('auth_point_2'))}</li><li>${icon('check-circle', 20)}${esc(t('auth_point_3'))}</li></ul></div>
        <span class="caption" style="color:rgba(255,255,255,.7)">${esc(t('app_city'))}</span></aside>
      <div class="auth-main"><div class="auth-top">${brandHtml('#/services')}${langSwitchHtml()}</div>
        <main id="main" class="auth-card" tabindex="-1"></main></div></div>`;
  } else if (kind === 'public') {
    root.innerHTML = `${skip}<header class="public-header"><div class="public-header-inner">${brandHtml('#/services')}
      <div class="public-actions">${langSwitchHtml()}<a class="btn secondary sm" href="#/login">${esc(t('login'))}</a><a class="btn sm" href="#/register">${esc(t('register'))}</a></div></div></header>
      <main id="main" class="content" tabindex="-1"></main>`;
  } else {
    const groups = NAV[u.role] || NAV.CLIENT;
    const link = ([h, k, ic]) => `<li><a class="nav-link" href="${h}" data-nav="${h}">${icon(ic, 20)}<span class="nav-text">${esc(t(k))}</span>${k === 'nav_notifications' ? `<span class="nav-badge" id="nav-notif-badge" hidden>0</span>` : ''}</a></li>`;
    root.innerHTML = `${skip}<div class="app" id="app" data-collapsed="${state.collapsed}" data-drawer="closed">
      <aside class="sidebar" id="sidebar" aria-label="${esc(t('main_navigation'))}">
        ${brandHtml('#/')}
        <nav aria-label="${esc(t('main_navigation'))}">
          ${groups.map(([g, items], i) => `${i ? `<div class="nav-group-label" aria-hidden="true">${esc(t('nav_group_' + g))}</div>` : ''}<ul class="nav-list">${items.map(link).join('')}</ul>`).join('')}
        </nav>
        <div class="sidebar-footer">
          ${langSwitchHtml('mobile-only')}
          <button type="button" class="nav-link collapse-toggle" id="collapse" aria-pressed="${state.collapsed}">${icon('panel-left', 20)}<span class="nav-text">${esc(t('collapse_menu'))}</span></button>
          <a class="user-chip" href="#/profile" data-nav-chip><span class="avatar">${esc(initials(u.fullName))}</span><span class="meta"><span class="name truncate" style="display:block">${esc(u.fullName)}</span><span class="role">${esc(t('role_' + u.role))}</span></span></a>
          <button type="button" class="nav-link" id="logout">${icon('logout', 20)}<span class="nav-text">${esc(t('logout'))}</span></button>
        </div>
      </aside>
      <div class="scrim" id="scrim"></div>
      <div class="main">
        <header class="topbar">
          <div class="topbar-left">
            <button type="button" class="icon-btn menu-btn bordered" id="menu-btn" aria-label="${esc(t('open_menu'))}" aria-controls="sidebar" aria-expanded="false">${icon('menu')}</button>
            <div class="topbar-title"><ol class="crumbs" id="crumbs"></ol><h1 id="page-title"></h1></div>
          </div>
          <div class="topbar-right">
            <button type="button" class="icon-btn bordered" id="bell" aria-haspopup="dialog" aria-expanded="false" aria-label="${esc(t('notifications'))}">${icon('bell')}<span class="bell-count" id="bell-count" hidden>0</span></button>
            ${langSwitchHtml()}
            <a class="avatar" href="#/profile" aria-label="${esc(t('nav_profile'))}" title="${esc(u.fullName)}">${esc(initials(u.fullName))}</a>
          </div>
        </header>
        <main id="main" class="content" tabindex="-1"></main>
      </div></div>`;
    $('#bell').addEventListener('click', togglePopover);
    $('#logout').addEventListener('click', logout);
    $('#collapse').addEventListener('click', toggleCollapsed);
    $('#menu-btn').addEventListener('click', () => (isDrawerOpen() ? closeDrawer() : openDrawer()));
    $('#scrim').addEventListener('click', closeDrawer);
    applyCollapsedTitles();
    updateBell();
  }
}

function isDrawerOpen() { const a = $('#app'); return !!a && a.dataset.drawer === 'open'; }
function openDrawer() {
  const a = $('#app'); if (!a) return;
  a.dataset.drawer = 'open'; document.documentElement.classList.add('drawer-open');
  $('#menu-btn').setAttribute('aria-expanded', 'true');
  const first = $('.nav-link', $('#sidebar')); if (first) first.focus({ preventScroll: true });
}
function closeDrawer() {
  const a = $('#app'); if (!a || a.dataset.drawer !== 'open') return false;
  a.dataset.drawer = 'closed'; document.documentElement.classList.remove('drawer-open');
  const mb = $('#menu-btn'); if (mb) { mb.setAttribute('aria-expanded', 'false'); mb.focus({ preventScroll: true }); }
  return true;
}
function toggleCollapsed() {
  state.collapsed = !state.collapsed; STORE.set('sidebar', state.collapsed ? '1' : '0');
  const a = $('#app'); if (a) a.dataset.collapsed = String(state.collapsed);
  const c = $('#collapse'); if (c) c.setAttribute('aria-pressed', String(state.collapsed));
  applyCollapsedTitles();
}
function applyCollapsedTitles() {
  $$('.nav-link[data-nav]').forEach((a) => { if (state.collapsed) a.title = $('.nav-text', a).textContent; else a.removeAttribute('title'); });
}

async function logout() {
  try { await api('/auth/logout', { method: 'POST', auth: false }); } catch (e) { /* session may already be gone */ }
  state.user = null; stopNotifPolling(); closeAllModals(); closeDrawer();
  state.flash = null; state.returnTo = null;
  Object.keys(ui).forEach((k) => delete ui[k]);
  go('#/services');
}

function setLang(l) {
  if (l === state.lang) return;
  state.lang = l; STORE.set('lang', l); document.documentElement.lang = l;
  closeAllModals();
  shellKey = '';
  route(true);
}

document.addEventListener('click', (e) => {
  const lb = e.target.closest('[data-lang]');
  if (lb) { setLang(lb.dataset.lang); return; }
  if (e.target.closest('[data-skip]')) { e.preventDefault(); const m = $('#main'); if (m) m.focus(); return; }
  if (isDrawerOpen() && e.target.closest('.sidebar a.nav-link, .sidebar a.user-chip, .sidebar .brand')) closeDrawer();
});

/* ================= Page chrome (title, breadcrumbs, active nav) ================= */
let pageTitleText = '';
function setDocumentTitle() {
  document.title = (state.unread ? `(${state.unread}) ` : '') + (pageTitleText ? pageTitleText + ' · ' : '') + 'Smart Service Hub';
}
/** Pages may call this to refine the header (e.g. add the order number as a breadcrumb). */
function setPageMeta({ title, crumb } = {}) {
  if (title) { pageTitleText = title; const h = $('#page-title'); if (h) h.textContent = title; }
  const c = $('#crumbs');
  if (c && crumb !== undefined) renderCrumbs(crumb);
  setDocumentTitle();
}
let currentRouteDef = null;
function renderCrumbs(extra) {
  const c = $('#crumbs'); if (!c || !currentRouteDef) return;
  const def = currentRouteDef;
  if (def.home) { c.innerHTML = ''; return; }
  const parts = [`<li><a href="#/">${esc(t('nav_dashboard'))}</a></li>`];
  if (extra) {
    parts.push(`<li>${icon('chevron-right', 12)}<a href="${def.path || '#/'}">${esc(t(def.title))}</a></li>`, `<li>${icon('chevron-right', 12)}<span aria-current="page">${esc(extra)}</span></li>`);
  } else {
    parts.push(`<li>${icon('chevron-right', 12)}<span aria-current="page">${esc(t(def.title))}</span></li>`);
  }
  c.innerHTML = parts.join('');
}
function updateChrome(def, hash) {
  currentRouteDef = def;
  pageTitleText = t(def.title);
  const h = $('#page-title'); if (h) h.textContent = pageTitleText;
  renderCrumbs(null);
  $$('.nav-link[data-nav]').forEach((a) => {
    const target = a.dataset.nav;
    const on = target === '#/' ? hash === '#/' : hash === target || hash.startsWith(target + '/');
    if (on) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current');
  });
  closeDrawer();
  setDocumentTitle();
}

/* ================= Router ================= */
const routes = [];
const addRoute = (re, def) => routes.push({ re, ...def });
let routeSeq = 0;
let mounted = null; // { def, api, hash }

const LEGACY = [
  [/^#\/catalog$/, '#/services'], [/^#\/worker$/, '#/orders'], [/^#\/admin$/, '#/'], [/^#\/admin\/orders$/, '#/orders'],
  [/^#\/admin\/catalog$/, '#/services'], [/^#\/admin\/users$/, '#/users'], [/^#\/order\/(\d+)$/, '#/orders/$1'],
];
function normalizeHash() {
  let h = location.hash;
  if (!h || h === '#') h = '#/';
  for (const [re, to] of LEGACY) {
    if (re.test(h)) { const n = h.replace(re, to); history.replaceState(null, '', n); return n; }
  }
  return h;
}

const skeleton = () => `<div class="stack" aria-busy="true" aria-label="${esc(t('loading'))}"><div class="skeleton sk-block"></div>
  <div class="grid-skel" style="display:grid;gap:1rem;grid-template-columns:repeat(auto-fill,minmax(240px,1fr))"><div class="skeleton sk-card"></div><div class="skeleton sk-card"></div><div class="skeleton sk-card"></div></div></div>`;

function errorState(e, retry = true) {
  return `<div class="card"><div class="empty-state"><span class="tile-icon warn">${icon('alert', 22)}</span><h3>${esc(t('err_title'))}</h3>
    <p>${esc(e && e.message ? e.message : t('err_generic'))}</p>${retry ? `<button type="button" class="btn secondary" data-retry>${icon('refresh', 16)}${esc(t('retry'))}</button>` : ''}</div></div>`;
}

async function route(force = false) {
  const my = ++routeSeq;
  const hash = normalizeHash();
  const u = state.user;
  if (hash === '#/' && !u) { go('#/services'); return; }
  let def = null, m = null;
  for (const r of routes) { m = hash.match(r.re); if (m) { def = r; break; } }
  if (!def) { go('#/'); return; }
  if (def.roles === 'guest' && u) { go('#/'); return; }
  if (def.roles !== 'public' && def.roles !== 'guest') {
    if (!u) { state.returnTo = hash; go('#/login'); return; }
    if (Array.isArray(def.roles) && !def.roles.includes(u.role)) { go('#/'); return; }
  }
  const params = m.slice(1);

  // Same page, only the parameters changed (e.g. opening an order from the list): update in place.
  if (!force && mounted && mounted.def === def && mounted.api && mounted.api.update) {
    mounted.hash = hash;
    updateChrome(def, hash);
    mounted.api.update(...params);
    return;
  }
  if (mounted && mounted.api && mounted.api.destroy) { try { mounted.api.destroy(); } catch (e) { console.error(e); } }
  mounted = null;
  // Leaving a page: no dialog opened from it (payment, order form, confirmation ...) may outlive it.
  closeAllModals();

  const layout = def.layout || (u ? 'app' : 'public');
  ensureShell(layout);
  if (layout === 'app') updateChrome(def, hash); else { currentRouteDef = def; pageTitleText = t(def.title); setDocumentTitle(); }
  // A fresh <main> per page: drops every listener the previous page attached to it.
  const oldMain = $('#main');
  const view = oldMain.cloneNode(false);
  oldMain.replaceWith(view);
  view.innerHTML = skeleton();
  const ctx = {
    view, layout, hash,
    alive: () => my === routeSeq,
    wait: async (p) => { const r = await p; if (my !== routeSeq) throw new Stale(); return r; },
  };
  try {
    const pageApi = await def.handler(ctx, ...params);
    if (my !== routeSeq) { if (pageApi && pageApi.destroy) pageApi.destroy(); return; }
    mounted = { def, api: pageApi, hash };
  } catch (e) {
    if (e instanceof Stale || e.silent || my !== routeSeq) return;
    console.error(e);
    view.innerHTML = errorState(e);
    const rb = $('[data-retry]', view); if (rb) rb.addEventListener('click', () => route(true));
  }
  if (!force && my === routeSeq) { window.scrollTo(0, 0); view.focus({ preventScroll: true }); }
}

window.addEventListener('hashchange', () => route());

/* ================= Boot ================= */
async function boot() {
  applyTheme();
  document.documentElement.lang = state.lang;
  try {
    const [d, c] = await Promise.all([api('/catalog/districts', { auth: false }), api('/catalog/categories', { auth: false })]);
    state.districts = d; state.categories = c;
  } catch (e) { toast(e.message, 'err'); }
  try { state.user = await api('/auth/me', { auth: false }); startNotifPolling(); } catch (e) { state.user = null; }
  route();
}

/* ================= Voice search (Web Speech API, UC-04) ================= */
function startVoiceSearch(button, input, onDone) {
  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!SR) { toast(t('voice_unsupported'), 'err'); return; }
  if (button._rec) { button._rec.stop(); return; }
  const rec = new SR();
  button._rec = rec;
  rec.lang = state.lang === 'kk' ? 'kk-KZ' : 'ru-RU';
  rec.interimResults = false; rec.maxAlternatives = 1;
  button.setAttribute('aria-pressed', 'true');
  toast(t('voice_listening'), 'info', 3000);
  const end = () => { button._rec = null; button.setAttribute('aria-pressed', 'false'); };
  rec.onresult = (e) => {
    const text = ((e.results[0] && e.results[0][0] && e.results[0][0].transcript) || '').trim();
    if (!text) { toast(t('voice_retry'), 'err'); return; }
    input.value = text; onDone();
  };
  rec.onerror = () => { toast(t('voice_retry'), 'err'); end(); };
  rec.onnomatch = () => toast(t('voice_retry'), 'err');
  rec.onend = end;
  try { rec.start(); } catch (e) { end(); }
}

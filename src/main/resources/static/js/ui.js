'use strict';
/* Smart Service Hub — shared UI components (pure functions returning HTML strings). */

const STEPS = ['NEW', 'ACCEPTED', 'IN_PROGRESS', 'COMPLETED'];
const ACTIVE_STATUSES = ['NEW', 'ACCEPTED', 'IN_PROGRESS'];

const statusBadge = (s) => `<span class="badge st-${esc(s)}">${esc(t('st_' + s))}</span>`;
const avatar = (name, cls = '') => `<span class="avatar ${cls}" aria-hidden="true">${esc(initials(name))}</span>`;

/** Accessible rating: stars are decorative, the wrapper carries the text alternative. */
function rating(value, count, { size = 16, showValue = true } = {}) {
  const v = Math.max(0, Math.min(5, Number(value) || 0));
  const label = t('rating_label', { v: v.toFixed(1) }) + (count !== undefined && count !== null ? ', ' + t('reviews_count', { n: count }) : '');
  const five = starSvg(size).repeat(5);
  return `<span class="rating" role="img" aria-label="${esc(label)}"><span class="stars" aria-hidden="true"><span class="base">${five}</span><span class="fill" style="width:${(v / 5) * 100}%">${five}</span></span>${
    showValue ? `<span class="val" aria-hidden="true">${v.toFixed(1)}</span>` : ''}${count !== undefined && count !== null ? `<span class="cnt" aria-hidden="true">(${count})</span>` : ''}</span>`;
}

/** Keyboard- and screen-reader-friendly star picker built from radio inputs. */
function starPicker(name = 'rating') {
  return `<fieldset class="star-input"><legend class="sr-only">${esc(t('your_rating'))}</legend>${[5, 4, 3, 2, 1].map((n) =>
    `<input type="radio" id="${name}-${n}" name="${name}" value="${n}"><label for="${name}-${n}" title="${esc(t('rating_n', { n }))}"><span class="sr-only">${esc(t('rating_n', { n }))}</span>${starSvg(30)}</label>`).join('')}</fieldset>`;
}

function categoryIcon(c) {
  const name = (c && (c.categoryNameRu || c.nameRu || '') + ' ' + (c.categoryNameKk || c.nameKk || '')).toLowerCase();
  if (/ремонт|жөндеу/.test(name)) return 'wrench';
  if (/уборк|тазалау/.test(name)) return 'sparkles';
  if (/установ|орнату/.test(name)) return 'zap';
  return 'briefcase';
}
const CAT_TONES = ['', 'ok', 'violet', 'info', 'warn'];
const categoryTone = (id) => CAT_TONES[(Number(id) || 0) % CAT_TONES.length];

function emptyState({ iconName = 'inbox', title, text, action = '' }) {
  return `<div class="empty-state"><span class="tile-icon">${icon(iconName, 24)}</span><h3>${esc(title)}</h3>${text ? `<p>${esc(text)}</p>` : ''}${action}</div>`;
}

function statCard({ iconName, tone = '', label, value, sub = '', alert = false }) {
  return `<div class="stat ${alert ? 'alert-stat' : ''}"><span class="tile-icon ${tone}">${icon(iconName, 20)}</span><div><div class="label">${esc(label)}</div><div class="value">${value}</div>${sub ? `<div class="sub">${esc(sub)}</div>` : ''}</div></div>`;
}

/** Row actions for an order, depending on the viewer's role and the order's state. */
function orderActionButtons(o) {
  const u = state.user;
  if (!u) return '';
  const b = (act, label, cls = '') => `<button type="button" class="btn sm ${cls}" data-act="${act}" data-id="${o.id}">${esc(label)}</button>`;
  if (u.role === 'WORKER' && o.workerId === u.id) {
    if (o.status === 'NEW') return b('accept', t('w_accept')) + b('reject', t('w_reject'), 'danger-outline');
    if (o.status === 'ACCEPTED') return b('IN_PROGRESS', t('w_start'));
    if (o.status === 'IN_PROGRESS') return b('COMPLETED', t('w_finish'), 'success');
  }
  if (u.role === 'CLIENT') {
    if (o.status === 'COMPLETED' && !o.paid) return b('pay', t('pay_btn'));
    if (o.status === 'COMPLETED' && !o.reviewed) return b('review', t('leave_review'), 'secondary');
  }
  if (u.role === 'ADMIN' && o.status === 'NEW') return b('assign', o.workerId ? t('a_reassign_btn') : t('a_assign_btn'), 'secondary');
  return '';
}

function orderRow(o, { counterpart = 'worker' } = {}) {
  const clientLine = `<span class="who-line">${avatar(o.clientName, 'sm')}<span class="truncate">${esc(o.clientName)}</span></span>`;
  const workerLine = o.workerName
    ? `<span class="who-line">${avatar(o.workerName, 'sm')}<span class="truncate">${esc(o.workerName)}</span></span>`
    : `<span class="who-line"><span class="unassigned">${esc(t('unassigned'))}</span></span>`;
  const who = counterpart === 'client' ? clientLine : counterpart === 'both' ? `<div class="stack sm">${clientLine}${workerLine}</div>` : workerLine;
  const needs = (state.user.role === 'WORKER' && o.status === 'NEW' && o.workerId === state.user.id) || (state.user.role === 'ADMIN' && o.status === 'NEW' && !o.workerId);
  return `<li class="order-row ${needs ? 'needs-action' : ''}" data-id="${o.id}">
    <div class="c-id"><a class="order-link truncate" href="#/orders/${o.id}">${esc(loc(o, 'serviceName'))}</a><span class="order-id">${esc(t('order_no'))}${o.id}${o.status === 'COMPLETED' ? ' · ' + esc(o.paid ? t('paid') : t('unpaid')) : ''}</span></div>
    <div class="c-who">${who}</div>
    <div class="c-date">${esc(fmtDate(o.scheduledTime))}</div>
    <div class="c-price">${money(o.price)}</div>
    <div class="c-status">${statusBadge(o.status)}</div>
    <div class="c-act">${orderActionButtons(o)}</div></li>`;
}

const orderListHead = (counterpartKey) => `<div class="order-head" aria-hidden="true"><span>${esc(t('service'))}</span><span>${esc(t(counterpartKey))}</span><span>${esc(t('date'))}</span><span class="r">${esc(t('price'))}</span><span>${esc(t('status'))}</span><span></span></div>`;

/** Compact row used on dashboards (no actions column). */
function miniOrder(o, who) {
  return `<li class="list-item"><span class="tile-icon info">${icon(categoryIcon({ nameRu: o.serviceNameRu, nameKk: o.serviceNameKk }), 18)}</span>
    <div class="grow"><a class="t truncate" style="display:block;color:var(--text)" href="#/orders/${o.id}">${esc(loc(o, 'serviceName'))}</a><div class="s truncate">${esc(t('order_no'))}${o.id} · ${esc(who || fmtDate(o.scheduledTime))}</div></div>
    <div class="right"><div class="strong nowrap">${money(o.price)}</div>${statusBadge(o.status)}</div></li>`;
}

function field({ name, label, type = 'text', value = '', required = false, placeholder = '', autocomplete = '', inputmode = '', maxlength = '', hint = '', icon: ic = '', extra = '' }) {
  const id = 'f-' + name;
  return `<div class="field"><label class="label" for="${id}">${esc(label)}${required ? `<span class="req" aria-hidden="true">*</span>` : ''}</label>
    <div class="control ${ic ? 'has-icon' : ''}">${ic ? icon(ic, 18) : ''}<input id="${id}" name="${name}" type="${type}" value="${esc(value)}" ${required ? 'required aria-required="true"' : ''} ${placeholder ? `placeholder="${esc(placeholder)}"` : ''} ${autocomplete ? `autocomplete="${autocomplete}"` : ''} ${inputmode ? `inputmode="${inputmode}"` : ''} ${maxlength ? `maxlength="${maxlength}"` : ''} ${extra}></div>
    ${hint ? `<span class="field-hint">${esc(hint)}</span>` : ''}</div>`;
}

function passwordField({ name = 'password', label, autocomplete = 'current-password', required = true, hint = '' }) {
  const id = 'f-' + name;
  return `<div class="field"><label class="label" for="${id}">${esc(label)}${required ? `<span class="req" aria-hidden="true">*</span>` : ''}</label>
    <div class="control has-icon has-trail">${icon('lock', 18)}<input id="${id}" name="${name}" type="password" autocomplete="${autocomplete}" ${required ? 'required aria-required="true"' : ''}>
    <button type="button" class="icon-btn trail" data-toggle-password aria-label="${esc(t('show_password'))}" aria-pressed="false">${icon('eye', 18)}</button></div>
    ${hint ? `<span class="field-hint">${esc(hint)}</span>` : ''}</div>`;
}
/** Delegated handler for the show/hide password buttons. */
document.addEventListener('click', (e) => {
  const b = e.target.closest('[data-toggle-password]');
  if (!b) return;
  const input = $('input', b.closest('.control'));
  const show = input.type === 'password';
  input.type = show ? 'text' : 'password';
  b.setAttribute('aria-pressed', String(show));
  b.setAttribute('aria-label', t(show ? 'hide_password' : 'show_password'));
  b.innerHTML = icon(show ? 'eye-off' : 'eye', 18);
});

const tabsHtml = (tabs, current, idPrefix = 'tab') => `<div class="tabs" role="tablist">${tabs.map(([key, label, count]) =>
  `<button type="button" role="tab" class="tab" id="${idPrefix}-${key || 'all'}" data-tab="${key}" aria-selected="${key === current}" tabindex="${key === current ? 0 : -1}">${esc(label)}${count !== undefined ? `<span class="count">${count}</span>` : ''}</button>`).join('')}</div>`;

/** Arrow-key navigation between tabs (WAI-ARIA tabs pattern). */
function bindTabKeys(container) {
  container.addEventListener('keydown', (e) => {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(e.key)) return;
    const tabs = $$('[role=tab]', container);
    const i = tabs.indexOf(document.activeElement);
    if (i < 0) return;
    e.preventDefault();
    const n = e.key === 'Home' ? 0 : e.key === 'End' ? tabs.length - 1 : (i + (e.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length;
    tabs[n].focus(); tabs[n].click();
  });
}

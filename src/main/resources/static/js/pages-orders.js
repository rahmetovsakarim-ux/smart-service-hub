'use strict';
/* Smart Service Hub — orders: list, detail dialog, payment dialog, specialist actions. Shared by all three roles. */

/** Specialist transitions. Returns the updated order, or null if the user cancelled. Throws ApiError on failure. */
async function workerAction(act, order) {
  if (act === 'reject') {
    const ok = await confirmDialog({ title: t('reject_title'), message: t('confirm_reject'), confirmLabel: t('w_reject'), danger: true });
    if (!ok) return null;
    const r = await api(`/worker/orders/${order.id}/reject`, { method: 'POST' });
    toast(t('rejected_ok'), 'info'); pollNotifications(true);
    return r;
  }
  const r = act === 'accept'
    ? await api(`/worker/orders/${order.id}/accept`, { method: 'POST' })
    : await api(`/worker/orders/${order.id}/status`, { method: 'POST', body: { status: act } });
  toast(t('status_updated')); pollNotifications(true);
  return r;
}

/* ---------------- Payment dialog (UC-07). Only the card number reaches the API; nothing is stored client-side. ---------------- */
function openPaymentModal(order, onDone) {
  const m = openModal({
    title: t('pay_title'), size: 'lg',
    html: `<div class="pay-grid">
      <div class="pay-summary">
        <div class="card-visual" aria-hidden="true"><div class="cv-row"><span>Smart Service Hub</span><span>${icon('card', 18)}</span></div>
          <div class="num" id="cv-num">•••• •••• •••• ••••</div>
          <div class="cv-row"><span id="cv-name">${esc(t('card_holder'))}</span><span id="cv-exp">MM/YY</span></div></div>
        <div><div class="caption muted">${esc(t('pay_amount'))}</div><div class="pay-amount">${money(order.price)}</div></div>
        <div class="caption secondary">${esc(loc(order, 'serviceName'))} · ${esc(t('order_no'))}${order.id}</div>
        <div class="secure-note">${icon('shield', 18)}<span>${esc(t('secure_note'))}</span></div>
      </div>
      <form id="pay-form" novalidate>
        ${field({ name: 'card', label: t('card_number'), required: true, autocomplete: 'cc-number', inputmode: 'numeric', placeholder: '0000 0000 0000 0000', maxlength: 19, icon: 'card' })}
        ${field({ name: 'holder', label: t('card_holder'), required: true, autocomplete: 'cc-name', placeholder: 'AIDAR NURLAN', maxlength: 40 })}
        <div class="form-row two">
          ${field({ name: 'exp', label: t('card_exp'), required: true, autocomplete: 'cc-exp', inputmode: 'numeric', placeholder: 'MM/YY', maxlength: 5 })}
          ${field({ name: 'cvv', label: t('card_cvv'), required: true, autocomplete: 'cc-csc', inputmode: 'numeric', placeholder: '123', maxlength: 4, type: 'password' })}
        </div>
        <p class="field-hint" style="margin-bottom:1rem">${esc(t('pay_hint'))}</p>
        <button class="btn lg block" type="submit">${icon('lock', 18)}${esc(t('pay_btn'))} · ${money(order.price)}</button>
      </form></div>`,
  });
  const form = $('#pay-form', m.body);
  const num = $('#cv-num', m.body), nm = $('#cv-name', m.body), ex = $('#cv-exp', m.body);
  form.card.addEventListener('input', () => {
    const d = form.card.value.replace(/\D/g, '').slice(0, 16);
    form.card.value = d.replace(/(.{4})/g, '$1 ').trim();
    const padded = (d + '•'.repeat(16)).slice(0, 16);
    num.textContent = padded.replace(/(.{4})/g, '$1 ').trim();
  });
  form.holder.addEventListener('input', () => { nm.textContent = form.holder.value.toUpperCase() || t('card_holder'); });
  form.exp.addEventListener('input', () => {
    const d = form.exp.value.replace(/\D/g, '').slice(0, 4);
    form.exp.value = d.length > 2 ? d.slice(0, 2) + '/' + d.slice(2) : d;
    ex.textContent = form.exp.value || 'MM/YY';
  });
  form.cvv.addEventListener('input', () => { form.cvv.value = form.cvv.value.replace(/\D/g, '').slice(0, 4); });

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    clearFormErrors(form);
    const digits = form.card.value.replace(/\D/g, '');
    const errs = [];
    if (digits.length !== 16) errs.push([form.card, t('v_card')]);
    if (form.holder.value.trim().length < 2) errs.push([form.holder, t('v_required')]);
    const em = form.exp.value.match(/^(\d{2})\/(\d{2})$/);
    const now = new Date();
    if (!em || Number(em[1]) < 1 || Number(em[1]) > 12 || new Date(2000 + Number(em[2]), Number(em[1]), 1) <= new Date(now.getFullYear(), now.getMonth(), 1)) errs.push([form.exp, t('v_exp')]);
    if (!/^\d{3,4}$/.test(form.cvv.value)) errs.push([form.cvv, t('v_cvv')]);
    if (errs.length) { errs.forEach(([i, x]) => setFieldError(i, x)); errs[0][0].focus(); return; }
    await withBusy($('button[type=submit]', form), async () => {
      try {
        const r = await api(`/client/orders/${order.id}/pay`, { method: 'POST', body: { cardNumber: digits } });
        if (r.status === 'PAID') {
          toast(t('pay_ok') + (r.transactionRef ? ' · ' + r.transactionRef : ''));
          pollNotifications(true);
          m.close();
          if (onDone) onDone();
        } else {
          formAlert(form, msg(r.message) || t('pay_failed'));
          form.card.focus();
        }
      } catch (err) { if (!err.silent) formAlert(form, err.message); }
    });
  });
}

/* ---------------- Orders page ---------------- */
(function () {
  const TAB_KEYS = ['', 'NEW', 'ACCEPTED', 'IN_PROGRESS', 'COMPLETED'];

  async function ordersPage(ctx, idParam) {
    const me = state.user;
    const role = me.role;
    const S = ui.orders = Object.assign({ tab: '', q: '', workerId: '', clientId: '', from: '', to: '' }, ui.orders);
    let items = [];
    let workers = [], clients = [];
    let detail = null; // { id, modal, data }
    let lastSig = '';

    const counterpart = role === 'CLIENT' ? 'worker' : role === 'WORKER' ? 'client' : 'both';
    const counterpartKey = role === 'CLIENT' ? 'specialist' : role === 'WORKER' ? 'client' : 'client_specialist';

    const fetchOrders = () => {
      if (role === 'CLIENT') return api('/client/orders');
      if (role === 'WORKER') return api('/worker/orders');
      const q = new URLSearchParams();
      if (S.workerId) q.set('workerId', S.workerId);
      if (S.clientId) q.set('clientId', S.clientId);
      return api('/admin/orders' + (q.toString() ? '?' + q : ''));
    };
    const reqs = [fetchOrders()];
    if (role === 'ADMIN') reqs.push(api('/admin/users?role=WORKER'), api('/admin/users?role=CLIENT'));
    const res = await ctx.wait(Promise.all(reqs));
    items = res[0]; workers = res[1] || []; clients = res[2] || [];
    lastSig = JSON.stringify(items.map((o) => [o.id, o.status, o.workerId, o.paid, o.reviewed]));

    const dateInputs = `<label class="sr-only" for="o-from">${esc(t('date_from'))}</label><input type="date" id="o-from" value="${esc(S.from)}" style="width:auto">
      <label class="sr-only" for="o-to">${esc(t('date_to'))}</label><input type="date" id="o-to" value="${esc(S.to)}" style="width:auto">`;
    const adminFilters = role === 'ADMIN' ? `
      <label class="sr-only" for="o-worker">${esc(t('all_workers'))}</label><select id="o-worker"><option value="">${esc(t('all_workers'))}</option>${workers.map((w) => `<option value="${w.id}" ${String(w.id) === S.workerId ? 'selected' : ''}>${esc(w.fullName)}</option>`).join('')}</select>
      <label class="sr-only" for="o-client">${esc(t('all_clients'))}</label><select id="o-client"><option value="">${esc(t('all_clients'))}</option>${clients.map((c) => `<option value="${c.id}" ${String(c.id) === S.clientId ? 'selected' : ''}>${esc(c.fullName)}</option>`).join('')}</select>` : '';

    ctx.view.innerHTML = `
      <div class="page-head"><p>${esc(t('orders_sub_' + role))}</p>
        <div class="page-actions">${role === 'CLIENT' ? `<a class="btn" href="#/services">${icon('plus', 18)}${esc(t('new_order'))}</a>` : ''}</div></div>
      <div class="toolbar">
        ${tabsHtml(TAB_KEYS.map((k) => [k, k ? t('st_' + k) : t('st_all_short'), 0]), S.tab, 'otab')}
        <div class="toolbar-main">
          <div class="control has-icon">${icon('search', 18)}<input id="o-q" type="search" value="${esc(S.q)}" placeholder="${esc(t('orders_search'))}" aria-label="${esc(t('orders_search'))}" autocomplete="off"></div>
          <button type="button" class="btn secondary filter-toggle" id="o-filter-toggle" aria-expanded="false" aria-controls="o-filters">${icon('filter', 18)}<span>${esc(t('filters'))}</span></button>
        </div>
        <div class="filter-panel" id="o-filters">${adminFilters}${dateInputs}<button type="button" class="btn ghost sm" id="o-clear">${esc(t('clear_filters'))}</button></div>
      </div>
      <p class="caption muted" id="o-count" aria-live="polite"></p>
      <div id="o-list"></div>`;

    const tabsEl = $('.tabs', ctx.view);
    bindTabKeys(tabsEl);

    function baseList() {
      const q = S.q.trim().toLowerCase();
      const from = S.from ? new Date(S.from + 'T00:00:00') : null;
      const to = S.to ? new Date(S.to + 'T23:59:59') : null;
      return items.filter((o) => {
        if (q) {
          const hay = [o.id, o.serviceNameKk, o.serviceNameRu, o.workerName, o.clientName, o.address].join(' ').toLowerCase();
          if (!hay.includes(q)) return false;
        }
        const d = new Date(o.scheduledTime);
        return !(from && d < from) && !(to && d > to);
      });
    }
    function syncTabs(base) {
      $$('.tab', tabsEl).forEach((b) => {
        const k = b.dataset.tab;
        const on = k === S.tab;
        b.setAttribute('aria-selected', String(on)); b.tabIndex = on ? 0 : -1;
        $('.count', b).textContent = k ? base.filter((o) => o.status === k).length : base.length;
      });
    }
    function renderList() {
      const base = baseList();
      syncTabs(base);
      const list = S.tab ? base.filter((o) => o.status === S.tab) : base;
      $('#o-count').textContent = t('n_orders', { n: list.length });
      const box = $('#o-list');
      if (!list.length) {
        const filtered = S.q || S.from || S.to || S.tab || S.workerId || S.clientId;
        box.innerHTML = `<div class="card">${emptyState({
          iconName: 'clipboard', title: t(filtered ? 'orders_none_filtered' : 'orders_none'), text: t(filtered ? 'orders_none_filtered_text' : 'orders_none_text_' + role),
          action: !filtered && role === 'CLIENT' ? `<a class="btn" href="#/services">${esc(t('go_catalog'))}</a>` : '',
        })}</div>`;
        return;
      }
      box.innerHTML = `${orderListHead(counterpartKey)}<ul class="order-list">${list.map((o) => orderRow(o, { counterpart })).join('')}</ul>`;
    }

    /* ---- Updating the list in place (no full re-render, scroll and filters are kept) ---- */
    function applyOrder(o) {
      const i = items.findIndex((x) => x.id === o.id);
      const gone = role === 'WORKER' && o.workerId !== me.id;
      if (gone) { if (i >= 0) items.splice(i, 1); }
      else if (i >= 0) items[i] = o;
      else items.unshift(o);
      renderList();
    }
    async function reload() {
      try {
        const fresh = await fetchOrders();
        const sig = JSON.stringify(fresh.map((o) => [o.id, o.status, o.workerId, o.paid, o.reviewed]));
        if (sig === lastSig) return;
        lastSig = sig; items = fresh; renderList();
        if (detail) refreshDetail();
      } catch (e) { /* silent: the next poll will retry */ }
    }

    /* ---- Row actions ---- */
    $('#o-list').addEventListener('click', async (e) => {
      const btn = e.target.closest('[data-act]');
      if (!btn) return;
      const act = btn.dataset.act;
      const o = items.find((x) => x.id === Number(btn.dataset.id));
      if (!o) return;
      if (act === 'pay') { openPaymentModal(o, () => reload2(o.id)); return; }
      if (act === 'review' || act === 'assign') { go('#/orders/' + o.id); return; }
      await withBusy(btn, async () => {
        try {
          const upd = await workerAction(act, o);
          if (upd) applyOrder(upd);
        } catch (err) { if (!err.silent) toast(err.message, 'err'); }
      });
    });
    async function reload2(id) {
      try { applyOrder(await api('/orders/' + id)); } catch (e) { if (!e.silent) toast(e.message, 'err'); }
      if (detail && detail.id === id) refreshDetail();
    }

    /* ---- Tabs, search, filters ---- */
    tabsEl.addEventListener('click', (e) => { const b = e.target.closest('[data-tab]'); if (!b) return; S.tab = b.dataset.tab; renderList(); });
    $('#o-q').addEventListener('input', debounce((e) => { S.q = e.target.value; renderList(); }, 150));
    $('#o-from').addEventListener('change', (e) => { S.from = e.target.value; renderList(); });
    $('#o-to').addEventListener('change', (e) => { S.to = e.target.value; renderList(); });
    const ow = $('#o-worker'), oc = $('#o-client');
    const refetch = async () => { try { items = await fetchOrders(); lastSig = ''; renderList(); } catch (err) { if (!err.silent) toast(err.message, 'err'); } };
    if (ow) ow.addEventListener('change', (e) => { S.workerId = e.target.value; refetch(); });
    if (oc) oc.addEventListener('change', (e) => { S.clientId = e.target.value; refetch(); });
    $('#o-clear').addEventListener('click', () => {
      const needFetch = S.workerId || S.clientId;
      Object.assign(S, { tab: '', q: '', workerId: '', clientId: '', from: '', to: '' });
      $('#o-q').value = ''; $('#o-from').value = ''; $('#o-to').value = '';
      if (ow) ow.value = ''; if (oc) oc.value = '';
      if (needFetch) refetch(); else renderList();
    });
    const tog = $('#o-filter-toggle'), panel = $('#o-filters');
    tog.addEventListener('click', () => { const open = panel.classList.toggle('open'); tog.setAttribute('aria-expanded', String(open)); });

    /* ---- Detail dialog ---- */
    function closeDetail(fromRoute) {
      if (!detail) return;
      detail.fromRoute = !!fromRoute;
      detail.modal.close();
    }
    async function openDetail(id) {
      if (detail && detail.id === id && !detail.modal.closed) return;
      if (detail) closeDetail(true);
      const m = openModal({
        title: t('order_no') + id, size: 'lg',
        html: `<div class="stack" aria-busy="true"><div class="skeleton sk-block"></div><div class="skeleton sk-block"></div></div>`,
        onClose: () => {
          const d = detail;
          if (!d || d.modal !== m) return;
          detail = null;
          if (!d.fromRoute && /^#\/orders\/\d+$/.test(location.hash)) history.replaceState(null, '', '#/orders');
          setPageMeta({ crumb: null });
        },
      });
      detail = { id, modal: m };
      setPageMeta({ crumb: t('order_no') + id });
      await refreshDetail();
    }
    async function refreshDetail() {
      const d = detail;
      if (!d) return;
      try {
        const [o, cands] = await Promise.all([
          api('/orders/' + d.id),
          role === 'ADMIN' ? api(`/admin/orders/${d.id}/candidates`).catch(() => []) : Promise.resolve([]),
        ]);
        if (detail !== d || d.modal.closed) return;
        d.data = o;
        renderDetail(d, o, cands);
        const i = items.findIndex((x) => x.id === o.id);
        if (i >= 0) { items[i] = o; renderList(); }
      } catch (e) {
        if (e.silent || detail !== d) return;
        d.modal.body.innerHTML = errorState(e, false);
      }
    }

    function renderDetail(d, o, cands) {
      const body = d.modal.body;
      d.modal.setTitle(t('order_no') + o.id);
      const cur = STEPS.indexOf(o.status);
      const hist = o.history || [];
      const stageTime = (s) => { const h = hist.find((x) => x.newStatus === s && x.oldStatus !== s); return h ? fmtDate(h.changedAt) : ''; };
      const stages = STEPS.map((s, i) => {
        const cls = cur === 3 ? 'done' : i < cur ? 'done' : i === cur ? 'current' : 'upcoming';
        return `<li class="${cls}" ${cls === 'current' ? 'aria-current="step"' : ''}><span class="t-dot">${cls === 'done' ? icon('check', 14) : ''}</span><div><div class="t-title">${esc(t('st_' + s))}</div><div class="t-sub">${esc(stageTime(s) || (cls === 'upcoming' ? t('step_pending') : ''))}</div></div></li>`;
      }).join('');
      const activity = hist.slice().reverse().map((h) => {
        const text = h.note ? splitNote(h.note) : (h.oldStatus && h.oldStatus !== h.newStatus ? t('st_' + h.oldStatus) + ' → ' + t('st_' + h.newStatus) : t('st_' + h.newStatus));
        return `<li><strong>${esc(text)}</strong><br><span class="muted">${esc(fmtDate(h.changedAt))} · ${esc(h.changedBy || t('system'))}</span></li>`;
      }).join('');

      const isStaff = role !== 'CLIENT';
      const dd = (ic, label, value) => `<div>${icon(ic, 18)}<div><dt>${esc(label)}</dt><dd>${value}</dd></div></div>`;
      const left = `<div class="stack">
        <div class="sub-card"><div class="row" style="flex-wrap:nowrap"><span class="tile-icon ${categoryTone(o.serviceId)}">${icon(categoryIcon({ nameRu: o.serviceNameRu, nameKk: o.serviceNameKk }), 22)}</span>
          <div class="grow"><div class="strong">${esc(loc(o, 'serviceName'))}</div><div class="caption muted">${statusBadge(o.status)}</div></div><div class="price">${money(o.price)}</div></div></div>
        <dl class="info-list">
          ${dd('calendar', t('scheduled_time'), esc(fmtDate(o.scheduledTime)))}
          ${dd('map-pin', t('address'), `${esc(t('d_' + o.district))}, ${esc(o.address)}`)}
          ${o.note ? dd('clipboard', t('note'), esc(o.note)) : ''}
          ${dd('user-check', t('specialist'), o.workerName ? `${esc(o.workerName)}<br><span class="caption secondary">${esc(o.workerPhone || '')}</span>${o.workerRating ? '<br>' + rating(o.workerRating, undefined, { size: 14 }) : ''}` : `<span class="unassigned" style="color:var(--warning)">${esc(t('unassigned'))}</span>`)}
          ${isStaff ? dd('user', t('client'), `${esc(o.clientName)}<br><span class="caption secondary">${esc(o.clientPhone || '')}</span>`) : ''}
          ${dd('card', t('payment'), o.status === 'COMPLETED' ? `<span class="badge ${o.paid ? 'ok' : 'warn'}">${esc(o.paid ? t('paid') : t('unpaid'))}</span>` : `<span class="muted">${esc(t('payment_after_completion'))}</span>`)}
        </dl></div>`;

      let actions = '';
      if (role === 'CLIENT') {
        if (o.status === 'COMPLETED' && !o.paid) actions += `<div class="stack sm"><p class="caption secondary">${esc(t('pay_due'))}</p><button type="button" class="btn" data-act="pay" data-id="${o.id}">${icon('card', 18)}${esc(t('pay_btn'))} · ${money(o.price)}</button></div>`;
        if (o.status === 'COMPLETED' && !o.reviewed) actions += `<form id="review-form" novalidate><h3 style="margin-bottom:.5rem">${esc(t('review_title'))}</h3>${starPicker('rating')}
          <div class="field" style="margin-top:.5rem"><label class="label" for="r-comment">${esc(t('comment'))}</label><textarea id="r-comment" name="comment" rows="3" maxlength="500"></textarea></div>
          <button class="btn" type="submit">${esc(t('send_review'))}</button></form>`;
        if (o.reviewed) actions += `<div class="stack sm"><h3>${esc(t('your_rating'))}</h3>${rating(o.reviewRating, undefined, { size: 20 })}</div>`;
      } else if (role === 'WORKER') {
        const a = orderActionButtons(o);
        if (a) actions += `<div class="action-bar">${a}</div>`;
      } else if (role === 'ADMIN' && o.status === 'NEW') {
        actions += `<form id="assign-form" class="stack sm" novalidate><h3>${esc(t('a_assign'))}</h3>${cands.length
          ? `<label class="sr-only" for="a-worker">${esc(t('specialist'))}</label><select id="a-worker" name="workerId">${cands.map((w) => `<option value="${w.id}" ${w.id === o.workerId ? 'selected' : ''}>${esc(w.fullName)} · ${esc(t('d_' + w.district))} · ${w.ratingCount ? '★ ' + w.ratingAvg.toFixed(1) : '—'}${w.available ? '' : ' · ' + esc(t('unavailable'))}</option>`).join('')}</select><button class="btn" type="submit">${esc(t('a_assign_btn'))}</button>`
          : `<p class="muted">${esc(t('a_no_candidates'))}</p>`}</form>`;
      }

      body.innerHTML = `<div class="detail-grid">${left}<div class="stack">
        <section aria-labelledby="tl-h"><h3 id="tl-h" style="margin-bottom:.9rem">${esc(t('lifecycle'))}</h3><ol class="timeline">${stages}</ol></section>
        ${actions ? `<section class="sub-card" aria-label="${esc(t('actions'))}">${actions}</section>` : ''}
        <section aria-labelledby="hist-h"><h3 id="hist-h" style="margin-bottom:.6rem">${esc(t('history'))}</h3><ul class="activity">${activity}</ul></section></div></div>`;

      /* actions */
      $$('[data-act]', body).forEach((btn) => btn.addEventListener('click', async () => {
        const act = btn.dataset.act;
        if (act === 'pay') { openPaymentModal(o, () => refreshDetail()); return; }
        await withBusy(btn, async () => {
          try {
            const upd = await workerAction(act, o);
            if (!upd) return;
            if (act === 'reject') { applyOrder(upd); closeDetail(false); return; }
            await refreshDetail();
          } catch (err) { if (!err.silent) toast(err.message, 'err'); }
        });
      }));
      const rf = $('#review-form', body);
      if (rf) rf.addEventListener('submit', async (e) => {
        e.preventDefault(); clearFormErrors(rf);
        const rt = Number((new FormData(rf)).get('rating'));
        if (!rt) { formAlert(rf, t('v_rating')); return; }
        await withBusy($('button[type=submit]', rf), async () => {
          try {
            await api(`/client/orders/${o.id}/review`, { method: 'POST', body: { rating: rt, comment: rf.comment.value } });
            toast(t('review_ok')); await refreshDetail();
          } catch (err) { if (!err.silent) formAlert(rf, err.message); }
        });
      });
      const af = $('#assign-form', body);
      if (af && af.workerId) af.addEventListener('submit', async (e) => {
        e.preventDefault();
        await withBusy($('button[type=submit]', af), async () => {
          try {
            await api(`/admin/orders/${o.id}/assign`, { method: 'POST', body: { workerId: Number(af.workerId.value) } });
            toast(t('assigned_ok')); await refreshDetail();
          } catch (err) { if (!err.silent) toast(err.message, 'err'); }
        });
      });
    }

    notif.listeners.add(reload);
    renderList();
    if (idParam) openDetail(Number(idParam));

    return {
      update(id) { if (id) openDetail(Number(id)); else closeDetail(true); },
      destroy() { notif.listeners.delete(reload); closeDetail(true); },
    };
  }

  addRoute(/^#\/orders(?:\/(\d+))?$/, { roles: 'auth', title: 'nav_orders', path: '#/orders', handler: ordersPage });
})();

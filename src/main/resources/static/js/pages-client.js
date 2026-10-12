'use strict';
/* Smart Service Hub — services catalog, order dialog, client dashboard, payments / earnings, reviews. */
(function () {
  /* ================= Order dialog (UC-05) ================= */
  function openOrderModal(service) {
    const min = new Date(Date.now() + 30 * 60000 - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 16);
    const pref = (ui.services && ui.services.district) || '';
    const m = openModal({
      title: t('order_title'), size: 'md',
      html: `<div class="sub-card row between" style="margin-bottom:1rem;flex-wrap:nowrap"><div class="row" style="flex-wrap:nowrap"><span class="tile-icon ${categoryTone(service.categoryId)}">${icon(categoryIcon(service), 20)}</span>
          <div><div class="strong">${esc(loc(service, 'name'))}</div><div class="caption muted">${esc(loc(service, 'categoryName'))}</div></div></div><div class="price">${money(service.price)}</div></div>
        <form id="order-form" novalidate>
          ${field({ name: 'address', label: t('address'), required: true, placeholder: t('address_ph'), maxlength: 255, autocomplete: 'street-address', icon: 'map-pin' })}
          <div class="form-row two">
            <div class="field"><label class="label" for="f-district">${esc(t('district'))}<span class="req" aria-hidden="true">*</span></label><select id="f-district" name="district">${districtOptions(pref)}</select></div>
            ${field({ name: 'scheduledTime', label: t('scheduled_time'), type: 'datetime-local', required: true, extra: `min="${min}"` })}
          </div>
          <div class="field"><label class="label" for="f-note">${esc(t('note'))}</label><textarea id="f-note" name="note" rows="2" maxlength="500"></textarea></div>
          <p class="caption muted">${icon('info', 14)} ${esc(t('order_auto_note'))}</p>
          <div class="modal-actions"><button type="button" class="btn secondary" data-cancel>${esc(t('cancel'))}</button><button class="btn" type="submit">${esc(t('send_order'))}</button></div>
        </form>`,
    });
    const form = $('#order-form', m.body);
    $('[data-cancel]', m.body).addEventListener('click', () => m.close());
    form.addEventListener('submit', async (e) => {
      e.preventDefault(); clearFormErrors(form);
      const errs = [];
      if (form.address.value.trim().length < 4) errs.push([form.address, t('v_address')]);
      if (!form.district.value) errs.push([form.district, t('v_required')]);
      if (!form.scheduledTime.value) errs.push([form.scheduledTime, t('v_required')]);
      else if (new Date(form.scheduledTime.value) <= new Date()) errs.push([form.scheduledTime, t('v_future')]);
      if (errs.length) { errs.forEach(([i, x]) => setFieldError(i, x)); errs[0][0].focus(); return; }
      await withBusy($('button[type=submit]', form), async () => {
        try {
          const o = await api('/client/orders', { method: 'POST', body: { serviceId: service.id, address: form.address.value.trim(), district: form.district.value, scheduledTime: form.scheduledTime.value, note: form.note.value.trim() || null } });
          ui.services = Object.assign(ui.services || {}, { district: form.district.value });
          toast(t('order_created') + ' №' + o.id); pollNotifications(true);
          m.close(); go('#/orders/' + o.id);
        } catch (err) { if (!err.silent) formAlert(form, err.message); }
      });
    });
  }

  /* ================= Services catalog (UC-03/04) ================= */
  function serviceCard(s, canOrder) {
    const avail = s.availableSpecialists;
    const meta = [];
    if (s.durationMinutes) meta.push(`<span>${icon('clock', 16)}${esc(t('minutes', { n: s.durationMinutes }))}</span>`);
    meta.push(s.ratingCount > 0 ? `<span>${rating(s.ratingAvg, s.ratingCount, { size: 14 })}</span>` : `<span class="muted">${icon('star', 16)}${esc(t('no_ratings'))}</span>`);
    const availBadge = avail === undefined ? '' : avail > 0
      ? `<span class="badge ok">${esc(t('specialists_available', { n: avail }))}</span>`
      : `<span class="badge warn">${esc(t('no_specialists'))}</span>`;
    const action = canOrder
      ? `<button type="button" class="btn sm" data-order="${s.id}">${esc(state.user ? t('order_now') : t('login_to_order'))}</button>` : '';
    return `<article class="svc-card" aria-labelledby="svc-${s.id}">
      <div class="svc-top"><span class="tile-icon ${categoryTone(s.categoryId)}">${icon(categoryIcon(s), 22)}</span><span class="svc-cat">${esc(loc(s, 'categoryName'))}</span></div>
      <h3 id="svc-${s.id}">${esc(loc(s, 'name'))}</h3>
      <p class="svc-desc">${esc(loc(s, 'description'))}</p>
      <div class="svc-meta">${meta.join('')}</div>${availBadge ? `<div>${availBadge}</div>` : ''}
      <div class="svc-foot"><div class="price"><small>${esc(t('from_price'))}</small>${money(s.price)}</div>${action}</div></article>`;
  }

  async function servicesPage(ctx) {
    if (state.user && state.user.role === 'ADMIN') return window.adminCatalogPage(ctx);
    const S = ui.services = Object.assign({ q: '', categoryId: null, sort: 'popular', district: '' }, ui.services);
    const canOrder = !state.user || state.user.role === 'CLIENT';
    let services = [];
    let reqId = 0;

    ctx.view.innerHTML = `
      ${ctx.layout === 'public'
        ? `<div class="hero-mini"><div><h1>${esc(t('hero_title'))}</h1><p>${esc(t('hero_sub'))}</p></div></div>`
        : `<div class="page-head"><p>${esc(t('services_sub'))}</p></div>`}
      <div class="toolbar">
        <div class="toolbar-main">
          <div class="control has-icon">${icon('search', 18)}<input id="s-q" type="search" value="${esc(S.q)}" placeholder="${esc(t('search_placeholder'))}" aria-label="${esc(t('search_placeholder'))}" autocomplete="off"></div>
          <button type="button" class="btn secondary icon-only" id="voice" aria-pressed="false" aria-label="${esc(t('search_voice'))}" title="${esc(t('search_voice'))}">${icon('mic', 18)}</button>
          <button type="button" class="btn secondary filter-toggle" id="s-filter-toggle" aria-expanded="false" aria-controls="s-filters">${icon('filter', 18)}<span>${esc(t('filters'))}</span></button>
        </div>
        <div class="chips" id="s-chips" role="group" aria-label="${esc(t('category'))}"></div>
        <div class="filter-panel" id="s-filters">
          <label class="sr-only" for="s-district">${esc(t('my_district'))}</label>
          <select id="s-district" title="${esc(t('my_district_hint'))}">${`<option value="">${esc(t('my_district'))}</option>` + state.districts.map((d) => `<option value="${esc(d)}" ${d === S.district ? 'selected' : ''}>${esc(t('d_' + d))}</option>`).join('')}</select>
          <label class="sr-only" for="s-sort">${esc(t('sort_by'))}</label>
          <select id="s-sort">${['popular', 'price_asc', 'price_desc', 'name'].map((k) => `<option value="${k}" ${k === S.sort ? 'selected' : ''}>${esc(t('sort_' + k))}</option>`).join('')}</select>
        </div>
      </div>
      <p class="caption muted" id="s-count" aria-live="polite"></p>
      <div class="svc-grid" id="s-grid"></div>`;

    const renderChips = () => {
      $('#s-chips').innerHTML = `<button type="button" class="chip" data-cat="" aria-pressed="${S.categoryId === null}">${esc(t('all_categories'))}</button>` +
        state.categories.map((c) => `<button type="button" class="chip" data-cat="${c.id}" aria-pressed="${S.categoryId === c.id}">${icon(categoryIcon(c), 16)}${esc(loc(c, 'name'))}</button>`).join('');
    };
    const sorted = () => {
      const l = services.slice();
      if (S.sort === 'price_asc') l.sort((a, b) => a.price - b.price);
      else if (S.sort === 'price_desc') l.sort((a, b) => b.price - a.price);
      else if (S.sort === 'name') l.sort((a, b) => loc(a, 'name').localeCompare(loc(b, 'name'), locale()));
      return l;
    };
    const renderGrid = () => {
      const list = sorted();
      $('#s-count').textContent = t('n_services', { n: list.length });
      $('#s-grid').innerHTML = list.length ? list.map((s) => serviceCard(s, canOrder)).join('')
        : `<div class="card" style="grid-column:1/-1">${emptyState({ iconName: 'search', title: t('no_services_title'), text: t('no_services') })}</div>`;
    };
    async function load() {
      const my = ++reqId;
      const p = new URLSearchParams();
      if (S.categoryId) p.set('categoryId', S.categoryId);
      if (S.q.trim()) p.set('q', S.q.trim());
      try {
        const data = await api('/catalog/services' + (p.toString() ? '?' + p : ''), { auth: false });
        if (my !== reqId || !ctx.alive()) return; // a newer search is in flight: drop this response
        services = data; renderGrid();
      } catch (e) { if (my === reqId && ctx.alive() && !e.silent) $('#s-grid').innerHTML = `<div style="grid-column:1/-1">${errorState(e, false)}</div>`; }
    }

    $('#s-chips').addEventListener('click', (e) => {
      const b = e.target.closest('[data-cat]'); if (!b) return;
      S.categoryId = b.dataset.cat ? Number(b.dataset.cat) : null; renderChips(); load();
    });
    $('#s-q').addEventListener('input', debounce((e) => { S.q = e.target.value; load(); }, 300));
    $('#s-district').addEventListener('change', (e) => { S.district = e.target.value; });
    $('#s-sort').addEventListener('change', (e) => { S.sort = e.target.value; renderGrid(); });
    $('#voice').addEventListener('click', (e) => startVoiceSearch(e.currentTarget, $('#s-q'), () => { S.q = $('#s-q').value; load(); }));
    const tog = $('#s-filter-toggle'), panel = $('#s-filters');
    tog.addEventListener('click', () => { const open = panel.classList.toggle('open'); tog.setAttribute('aria-expanded', String(open)); });
    $('#s-grid').addEventListener('click', (e) => {
      const b = e.target.closest('[data-order]'); if (!b) return;
      if (!state.user) { state.returnTo = '#/services'; go('#/login'); return; }
      const s = services.find((x) => x.id === Number(b.dataset.order));
      if (s) openOrderModal(s);
    });
    renderChips();
    await ctx.wait(load());
  }
  addRoute(/^#\/services$/, { roles: 'public', title: 'nav_services', handler: servicesPage });

  /* ================= Dashboards (role dispatch) ================= */
  addRoute(/^#\/$/, {
    roles: 'auth', title: 'nav_dashboard', home: true,
    handler: (ctx) => {
      const r = state.user.role;
      if (r === 'WORKER') return window.workerDashboard(ctx);
      if (r === 'ADMIN') return window.adminDashboard(ctx);
      return clientDashboard(ctx);
    },
  });

  const firstName = () => String(state.user.fullName).split(/\s+/)[0];

  async function clientDashboard(ctx) {
    let orders, services;
    const load = async () => { [orders, services] = await Promise.all([api('/client/orders'), api('/catalog/services')]); };
    await ctx.wait(load());
    let lastSig = '';

    function render() {
      const active = orders.filter((o) => ACTIVE_STATUSES.includes(o.status));
      const done = orders.filter((o) => o.status === 'COMPLETED');
      const spent = orders.filter((o) => o.paid).reduce((a, o) => a + Number(o.price), 0);
      const due = done.filter((o) => !o.paid);
      lastSig = JSON.stringify(orders.map((o) => [o.id, o.status, o.paid]));
      const msgText = due.length ? t('ctx_pay', { n: due.length }) : active.length ? t('ctx_active', { n: active.length }) : t('ctx_idle');
      const recommended = services.slice().sort((a, b) => (b.ratingAvg || 0) - (a.ratingAvg || 0) || a.price - b.price).slice(0, 3);
      const notifs = state.notifItems.slice(0, 4);
      ctx.view.innerHTML = `
        <section class="welcome" aria-labelledby="wl"><div><h2 id="wl">${esc(t('welcome_back', { name: firstName() }))}</h2><p>${esc(msgText)}</p></div>
          <a class="btn lg" href="#/services">${icon('plus', 18)}${esc(t('order_a_service'))}</a></section>
        ${due.length ? `<div class="alert warning" role="status">${icon('card', 18)}<span class="grow">${esc(t('pay_banner', { n: due.length }))}</span><a class="link-btn" href="#/payments">${esc(t('go_payments'))}</a></div>` : ''}
        <div class="stats">
          ${statCard({ iconName: 'clipboard', label: t('stat_total_orders'), value: orders.length })}
          ${statCard({ iconName: 'clock', tone: 'warn', label: t('stat_active'), value: active.length })}
          ${statCard({ iconName: 'check-circle', tone: 'ok', label: t('stat_completed'), value: done.length })}
          ${statCard({ iconName: 'wallet', tone: 'info', label: t('stat_spent'), value: money(spent) })}
        </div>
        <div class="dash-grid">
          <div class="dash-col">
            <section class="card" aria-labelledby="ro"><div class="card-head"><h2 id="ro">${esc(t('recent_orders'))}</h2><a class="link-btn" href="#/orders">${esc(t('view_all'))}</a></div>
              ${orders.length ? `<ul class="list">${orders.slice(0, 5).map((o) => miniOrder(o, o.workerName || t('unassigned'))).join('')}</ul>`
                : emptyState({ iconName: 'clipboard', title: t('orders_none'), text: t('orders_none_text_CLIENT'), action: `<a class="btn" href="#/services">${esc(t('go_catalog'))}</a>` })}</section>
            <section class="card" aria-labelledby="rs"><div class="card-head"><h2 id="rs">${esc(t('recommended'))}</h2><a class="link-btn" href="#/services">${esc(t('view_all'))}</a></div>
              <ul class="list">${recommended.map((s) => `<li class="list-item"><span class="tile-icon ${categoryTone(s.categoryId)}">${icon(categoryIcon(s), 18)}</span>
                <div class="grow"><div class="t">${esc(loc(s, 'name'))}</div><div class="s">${esc(loc(s, 'categoryName'))} · ${money(s.price)}</div></div>
                <button type="button" class="btn secondary sm" data-order="${s.id}">${esc(t('order_now'))}</button></li>`).join('')}</ul></section>
          </div>
          <div class="dash-col">
            <section class="card" aria-labelledby="qa"><div class="card-head"><h2 id="qa">${esc(t('quick_actions'))}</h2></div>
              <div class="quick-actions">
                <a class="quick-action" href="#/services"><span class="tile-icon">${icon('search', 18)}</span><span><span class="qa-title">${esc(t('qa_find'))}</span><br><span class="qa-sub">${esc(t('qa_find_sub'))}</span></span>${icon('chevron-right', 16)}</a>
                <a class="quick-action" href="#/orders"><span class="tile-icon info">${icon('clipboard', 18)}</span><span><span class="qa-title">${esc(t('qa_track'))}</span><br><span class="qa-sub">${esc(t('qa_track_sub'))}</span></span>${icon('chevron-right', 16)}</a>
                <a class="quick-action" href="#/payments"><span class="tile-icon ok">${icon('card', 18)}</span><span><span class="qa-title">${esc(t('qa_pay'))}</span><br><span class="qa-sub">${esc(due.length ? t('qa_pay_due', { n: due.length }) : t('qa_pay_sub'))}</span></span>${icon('chevron-right', 16)}</a>
              </div></section>
            <section class="card flush" aria-labelledby="rn"><div class="card-head" style="padding:1rem 1.25rem 0;margin:0 0 .5rem"><h2 id="rn">${esc(t('recent_notifications'))}</h2><a class="link-btn" href="#/notifications">${esc(t('view_all'))}</a></div>
              ${notifs.length ? notifs.map(notifItemHtml).join('') : `<p class="muted" style="padding:0 1.25rem 1.25rem">${esc(t('no_notifications'))}</p>`}</section>
          </div>
        </div>`;
    }
    render();
    ctx.view.addEventListener('click', (e) => {
      const b = e.target.closest('[data-order]'); if (!b) return;
      const s = services.find((x) => x.id === Number(b.dataset.order)); if (s) openOrderModal(s);
    });
    const refresh = async () => {
      try { await load(); } catch (e) { return; }
      render();
    };
    notif.listeners.add(refresh);
    return { destroy() { notif.listeners.delete(refresh); } };
  }

  /* ================= Payments (client) / Earnings (specialist) ================= */
  async function paymentsPage(ctx) {
    const worker = state.user.role === 'WORKER';
    let orders = await ctx.wait(api(worker ? '/worker/orders' : '/client/orders'));
    const render = () => {
      const done = orders.filter((o) => o.status === 'COMPLETED');
      const paid = done.filter((o) => o.paid), due = done.filter((o) => !o.paid);
      const sum = (l) => l.reduce((a, o) => a + Number(o.price), 0);
      const row = (o, canPay) => `<li class="list-item"><span class="tile-icon ${o.paid ? 'ok' : 'warn'}">${icon(o.paid ? 'check-circle' : 'receipt', 18)}</span>
        <div class="grow"><a class="t truncate" style="display:block;color:var(--text)" href="#/orders/${o.id}">${esc(loc(o, 'serviceName'))}</a><div class="s">${esc(t('order_no'))}${o.id} · ${esc(fmtDay(o.scheduledTime))} · ${esc(worker ? o.clientName : (o.workerName || '—'))}</div></div>
        <div class="right"><div class="strong nowrap">${money(o.price)}</div></div>
        ${canPay ? `<button type="button" class="btn sm" data-pay="${o.id}">${esc(t('pay_btn'))}</button>` : `<span class="badge ok">${esc(t('paid'))}</span>`}</li>`;
      ctx.view.innerHTML = `
        <div class="page-head"><p>${esc(t(worker ? 'earnings_sub' : 'payments_sub'))}</p></div>
        <div class="stats" style="grid-template-columns:repeat(3,minmax(0,1fr))">
          ${statCard({ iconName: 'wallet', tone: 'ok', label: t(worker ? 'stat_earned' : 'stat_spent'), value: money(sum(paid)) })}
          ${statCard({ iconName: 'clock', tone: 'warn', label: t(worker ? 'stat_pending_payout' : 'stat_awaiting'), value: money(sum(due)), sub: t('n_orders', { n: due.length }) })}
          ${statCard({ iconName: 'receipt', tone: 'info', label: t('stat_paid_orders'), value: paid.length })}
        </div>
        <div class="stack">
          <section class="card" aria-labelledby="pd"><div class="card-head"><h2 id="pd">${esc(t(worker ? 'awaiting_client' : 'awaiting_payment'))}</h2></div>
            ${due.length ? `<ul class="list">${due.map((o) => row(o, !worker)).join('')}</ul>` : emptyState({ iconName: 'check-circle', title: t('nothing_due'), text: t('nothing_due_text') })}</section>
          <section class="card" aria-labelledby="ph"><div class="card-head"><h2 id="ph">${esc(t('payment_history'))}</h2></div>
            ${paid.length ? `<ul class="list">${paid.map((o) => row(o, false)).join('')}</ul>` : emptyState({ iconName: 'receipt', title: t('no_payments'), text: t('no_payments_text') })}</section>
        </div>`;
    };
    render();
    ctx.view.addEventListener('click', (e) => {
      const b = e.target.closest('[data-pay]'); if (!b) return;
      const o = orders.find((x) => x.id === Number(b.dataset.pay)); if (!o) return;
      openPaymentModal(o, async () => { try { orders = await api('/client/orders'); render(); } catch (err) { /* keep old view */ } });
    });
    const refresh = async () => { try { orders = await api(worker ? '/worker/orders' : '/client/orders'); render(); } catch (e) { /* ignore */ } };
    notif.listeners.add(refresh);
    return { destroy() { notif.listeners.delete(refresh); } };
  }
  addRoute(/^#\/payments$/, { roles: ['CLIENT', 'WORKER'], title: 'nav_payments', handler: paymentsPage });

  /* ================= Reviews ================= */
  async function reviewsPage(ctx) {
    if (state.user.role === 'WORKER') return workerReviews(ctx);
    const orders = await ctx.wait(api('/client/orders'));
    const pending = orders.filter((o) => o.status === 'COMPLETED' && !o.reviewed);
    const given = orders.filter((o) => o.reviewed);
    ctx.view.innerHTML = `
      <div class="page-head"><p>${esc(t('reviews_sub_client'))}</p></div>
      <div class="stack">
        <section class="card" aria-labelledby="rp"><div class="card-head"><h2 id="rp">${esc(t('awaiting_review'))}</h2>${pending.length ? `<span class="pill-count">${pending.length}</span>` : ''}</div>
          ${pending.length ? `<ul class="list">${pending.map((o) => `<li class="list-item"><span class="tile-icon warn">${icon('star', 18)}</span>
            <div class="grow"><div class="t">${esc(loc(o, 'serviceName'))}</div><div class="s">${esc(o.workerName || '—')} · ${esc(fmtDay(o.scheduledTime))}</div></div>
            <a class="btn sm" href="#/orders/${o.id}">${esc(t('leave_review'))}</a></li>`).join('')}</ul>`
            : emptyState({ iconName: 'check-circle', title: t('no_pending_reviews'), text: t('no_pending_reviews_text') })}</section>
        <section class="card" aria-labelledby="rg"><div class="card-head"><h2 id="rg">${esc(t('your_reviews'))}</h2></div>
          ${given.length ? `<ul class="list">${given.map((o) => `<li class="list-item"><span class="tile-icon ok">${icon('check-circle', 18)}</span>
            <div class="grow"><a class="t" style="display:block;color:var(--text)" href="#/orders/${o.id}">${esc(loc(o, 'serviceName'))}</a><div class="s">${esc(o.workerName || '—')} · ${esc(fmtDay(o.scheduledTime))}</div></div>${rating(o.reviewRating, undefined, { size: 16 })}</li>`).join('')}</ul>`
            : emptyState({ iconName: 'star', title: t('no_reviews_given'), text: t('no_reviews_given_text') })}</section>
      </div>`;
  }

  async function workerReviews(ctx) {
    const [list, profile] = await ctx.wait(Promise.all([api('/worker/reviews'), api('/worker/profile')]));
    const dist = [5, 4, 3, 2, 1].map((n) => [n, list.filter((r) => r.rating === n).length]);
    const max = Math.max(1, ...dist.map((d) => d[1]));
    ctx.view.innerHTML = `
      <div class="page-head"><p>${esc(t('reviews_sub_worker'))}</p></div>
      <div class="dash-grid" style="grid-template-columns:minmax(0,1fr) minmax(0,2fr)">
        <section class="card" aria-labelledby="rs"><h2 id="rs" class="sr-only">${esc(t('rating'))}</h2>
          <div class="stat-big" style="font-size:2.6rem;font-weight:750;letter-spacing:-.03em">${profile.ratingCount ? profile.ratingAvg.toFixed(1) : '—'}</div>
          ${rating(profile.ratingAvg, profile.ratingCount, { size: 20, showValue: false })}
          <p class="caption muted mt-1">${esc(t('reviews_count', { n: profile.ratingCount }))}</p>
          <div class="stack sm mt-2" role="list" aria-label="${esc(t('rating_distribution'))}">${dist.map(([n, c]) => `<div class="row" role="listitem" style="flex-wrap:nowrap;gap:.6rem"><span class="caption nowrap" style="width:2.2rem">${n} ★</span><div class="progress grow" aria-hidden="true"><span style="width:${(c / max) * 100}%;background:var(--accent)"></span></div><span class="caption" style="width:1.5rem;text-align:right">${c}</span></div>`).join('')}</div></section>
        <section class="card" aria-labelledby="rl"><div class="card-head"><h2 id="rl">${esc(t('client_reviews'))}</h2></div>
          ${list.length ? list.map((r) => `<article class="review"><div class="row between"><div class="row"><span class="avatar sm" aria-hidden="true">${esc(initials(r.clientName))}</span><strong>${esc(r.clientName)}</strong></div>${rating(r.rating, undefined, { size: 16 })}</div>
            ${r.text ? `<p>${esc(r.text)}</p>` : ''}<div class="caption muted">${esc(fmtDate(r.createdAt))} · <a href="#/orders/${r.orderId}">${esc(t('order_no'))}${r.orderId}</a></div></article>`).join('')
            : emptyState({ iconName: 'star', title: t('no_reviews'), text: t('no_reviews_worker_text') })}</section>
      </div>`;
  }
  addRoute(/^#\/reviews$/, { roles: ['CLIENT', 'WORKER'], title: 'nav_reviews', handler: reviewsPage });

  window.openOrderModal = openOrderModal;
})();

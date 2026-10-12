'use strict';
/* Smart Service Hub — specialist dashboard: actions that need attention come first. */
(function () {
  async function workerDashboard(ctx) {
    let orders, profile, reviews;
    const loadOrders = async () => { orders = await api('/worker/orders'); };
    const res = await ctx.wait(Promise.all([api('/worker/orders'), api('/worker/profile'), api('/worker/reviews')]));
    [orders, profile, reviews] = res;

    function render() {
      const pending = orders.filter((o) => o.status === 'NEW');
      const active = orders.filter((o) => o.status === 'ACCEPTED' || o.status === 'IN_PROGRESS');
      const done = orders.filter((o) => o.status === 'COMPLETED');
      const earned = done.filter((o) => o.paid).reduce((a, o) => a + Number(o.price), 0);
      const notifs = state.notifItems.slice(0, 5);
      const first = String(state.user.fullName).split(/\s+/)[0];
      const sub = pending.length ? t('w_ctx_pending', { n: pending.length }) : active.length ? t('w_ctx_active', { n: active.length }) : t('w_ctx_idle');

      ctx.view.innerHTML = `
        <section class="welcome" aria-labelledby="wl"><div><h2 id="wl">${esc(t('welcome_back', { name: first }))}</h2><p>${esc(sub)}</p></div>
          <a class="btn lg" href="#/orders">${icon('clipboard', 18)}${esc(t('all_my_orders'))}</a></section>

        <section aria-labelledby="pa" class="mt-1">
          <div class="section-title"><h2 id="pa">${esc(t('pending_action'))}</h2>${pending.length ? `<span class="pill-count" aria-label="${esc(t('n_orders', { n: pending.length }))}">${pending.length}</span>` : ''}</div>
          ${pending.length ? `<div class="stack">${pending.map((o) => `
            <article class="pending-card" aria-label="${esc(t('order_no') + o.id)}">
              <div class="p-top"><div><div class="strong">${esc(loc(o, 'serviceName'))}</div><div class="order-id">${esc(t('order_no'))}${o.id}</div></div><div class="right"><div class="price">${money(o.price)}</div>${statusBadge(o.status)}</div></div>
              <div class="meta"><span>${icon('calendar', 14)}${esc(fmtDate(o.scheduledTime))}</span><span>${icon('map-pin', 14)}${esc(t('d_' + o.district))}, ${esc(o.address)}</span><span>${icon('user', 14)}${esc(o.clientName)} · ${esc(o.clientPhone || '')}</span>${o.note ? `<span>${icon('clipboard', 14)}${esc(o.note)}</span>` : ''}</div>
              <div class="p-actions"><button type="button" class="btn" data-act="accept" data-id="${o.id}">${icon('check', 18)}${esc(t('w_accept'))}</button><button type="button" class="btn danger-outline" data-act="reject" data-id="${o.id}">${esc(t('w_reject'))}</button><a class="btn ghost" href="#/orders/${o.id}">${esc(t('details'))}</a></div></article>`).join('')}</div>`
            : `<div class="card">${emptyState({ iconName: 'check-circle', title: t('caught_up'), text: t('caught_up_text') })}</div>`}
        </section>

        <div class="stats mt-2">
          ${statCard({ iconName: 'bell', tone: 'warn', label: t('stat_pending'), value: pending.length, alert: pending.length > 0 })}
          ${statCard({ iconName: 'clock', tone: 'violet', label: t('stat_active'), value: active.length })}
          ${statCard({ iconName: 'check-circle', tone: 'ok', label: t('stat_completed'), value: done.length })}
          ${statCard({ iconName: 'wallet', tone: 'info', label: t('stat_earned'), value: money(earned) })}
        </div>

        <div class="dash-grid">
          <div class="dash-col">
            <section class="card" aria-labelledby="ao"><div class="card-head"><h2 id="ao">${esc(t('active_orders'))}</h2><a class="link-btn" href="#/orders">${esc(t('view_all'))}</a></div>
              ${active.length ? `<ul class="list">${active.map((o) => `<li class="list-item wrap-sm"><span class="tile-icon violet">${icon(categoryIcon({ nameRu: o.serviceNameRu, nameKk: o.serviceNameKk }), 18)}</span>
                <div class="grow"><a class="t truncate" style="display:block;color:var(--text)" href="#/orders/${o.id}">${esc(loc(o, 'serviceName'))}</a><div class="s truncate">${esc(o.clientName)} · ${esc(fmtDate(o.scheduledTime))}</div></div>
                <div class="row end">${statusBadge(o.status)}${orderActionButtons(o)}</div></li>`).join('')}</ul>`
                : emptyState({ iconName: 'clipboard', title: t('no_active'), text: t('no_active_text') })}</section>
            <section class="card" aria-labelledby="rr"><div class="card-head"><h2 id="rr">${esc(t('recent_reviews'))}</h2><a class="link-btn" href="#/reviews">${esc(t('view_all'))}</a></div>
              ${reviews.length ? reviews.slice(0, 3).map((r) => `<article class="review"><div class="row between"><strong>${esc(r.clientName)}</strong>${rating(r.rating, undefined, { size: 15 })}</div>${r.text ? `<p>${esc(r.text)}</p>` : ''}<div class="caption muted">${esc(fmtDay(r.createdAt))}</div></article>`).join('')
                : `<p class="muted">${esc(t('no_reviews'))}</p>`}</section>
          </div>
          <div class="dash-col">
            <section class="card" aria-labelledby="rt"><div class="card-head"><h2 id="rt">${esc(t('your_rating'))}</h2></div>
              <div style="font-size:2.2rem;font-weight:750;letter-spacing:-.03em;line-height:1.1">${profile.ratingCount ? profile.ratingAvg.toFixed(1) : '—'}</div>
              <div class="mt-1">${rating(profile.ratingAvg, profile.ratingCount, { size: 20, showValue: false })}</div>
              <p class="caption muted mt-1">${esc(t('reviews_count', { n: profile.ratingCount }))}</p></section>
            <section class="card" aria-labelledby="av"><div class="card-head"><h2 id="av">${esc(t('availability'))}</h2></div>
              <label class="check"><input type="checkbox" id="avail" role="switch" ${profile.available ? 'checked' : ''}><span>${esc(t('available'))}</span></label>
              <p class="caption muted">${esc(t('availability_hint'))}</p></section>
            <section class="card" aria-labelledby="ra"><div class="card-head"><h2 id="ra">${esc(t('recent_activity'))}</h2><a class="link-btn" href="#/notifications">${esc(t('view_all'))}</a></div>
              ${notifs.length ? `<ul class="activity">${notifs.map((n) => `<li>${esc(loc(n, 'message'))}<br><span class="muted">${esc(relTime(n.createdAt))}</span></li>`).join('')}</ul>` : `<p class="muted">${esc(t('no_notifications'))}</p>`}</section>
          </div>
        </div>`;
    }
    render();

    ctx.view.addEventListener('click', async (e) => {
      const btn = e.target.closest('[data-act]');
      if (!btn) return;
      const o = orders.find((x) => x.id === Number(btn.dataset.id));
      if (!o) return;
      await withBusy(btn, async () => {
        try {
          const upd = await workerAction(btn.dataset.act, o);
          if (upd) { await loadOrders(); render(); }
        } catch (err) { if (!err.silent) toast(err.message, 'err'); }
      });
    });
    ctx.view.addEventListener('change', async (e) => {
      if (e.target.id !== 'avail') return;
      const box = e.target;
      try {
        state.user = await api('/worker/profile', { method: 'PUT', body: { available: box.checked } });
        profile = state.user;
        toast(t('saved'));
      } catch (err) { box.checked = !box.checked; if (!err.silent) toast(err.message, 'err'); }
    });
    const refresh = async () => { try { await loadOrders(); render(); } catch (e) { /* ignore */ } };
    notif.listeners.add(refresh);
    return { destroy() { notif.listeners.delete(refresh); } };
  }
  window.workerDashboard = workerDashboard;
})();

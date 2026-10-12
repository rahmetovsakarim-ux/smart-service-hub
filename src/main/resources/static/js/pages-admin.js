'use strict';
/* Smart Service Hub — administrator pages: dashboard, users, catalog management. */
(function () {
  /* ================= Dashboard (UC-13) ================= */
  async function adminDashboard(ctx) {
    const S = ui.adminDash = Object.assign({ days: 14 }, ui.adminDash);
    let stats, orders;
    const load = async () => { [stats, orders] = await Promise.all([api('/admin/stats?days=' + S.days), api('/admin/orders')]); };
    await ctx.wait(load());

    function chart() {
      const max = Math.max(1, ...stats.daily.map((d) => d.count));
      const every = stats.daily.length > 14 ? 3 : 1;
      const cols = stats.daily.map((d, i) => {
        const h = d.count ? Math.max(4, (d.count / max) * 80) : 0;
        return `<div class="col" title="${esc(fmtShortDay(d.date) + ': ' + d.count)}">${stats.daily.length <= 14 ? `<span class="v">${d.count || ''}</span>` : ''}<div class="bar" style="height:${h}%"></div></div>`;
      }).join('');
      const axis = stats.daily.map((d, i) => `<span>${i % every === 0 ? esc(fmtShortDay(d.date)) : ''}</span>`).join('');
      const table = `<table class="sr-only"><caption>${esc(t('a_by_day'))}</caption><thead><tr><th>${esc(t('date'))}</th><th>${esc(t('nav_orders'))}</th></tr></thead><tbody>${stats.daily.map((d) => `<tr><td>${esc(fmtDay(d.date))}</td><td>${d.count}</td></tr>`).join('')}</tbody></table>`;
      return `<div class="bars-chart" aria-hidden="true">${cols}</div><div class="bars-chart-axis" aria-hidden="true">${axis}</div>${table}`;
    }

    function render() {
      const s = stats;
      const users = Object.values(s.usersByRole).reduce((a, b) => a + b, 0);
      const statusTotal = Math.max(1, STEPS.reduce((a, k) => a + (s.byStatus[k] || 0), 0));
      const needAssign = orders.filter((o) => o.status === 'NEW' && !o.workerId).slice(0, 5);
      const workers = s.workers.slice().sort((a, b) => b.total - a.total).slice(0, 6);
      ctx.view.innerHTML = `
        <div class="page-head"><p>${esc(t('admin_sub'))}</p></div>
        ${s.unassigned ? `<div class="alert warning" role="status">${icon('alert', 18)}<span class="grow">${esc(t('unassigned_banner', { n: s.unassigned }))}</span><a class="link-btn" href="#/orders">${esc(t('review_orders'))}</a></div>` : ''}
        <div class="stats">
          ${statCard({ iconName: 'users', label: t('stat_users'), value: users, sub: `${s.usersByRole.CLIENT || 0} ${t('role_CLIENT_pl').toLowerCase()}` })}
          ${statCard({ iconName: 'clipboard', tone: 'info', label: t('stat_orders'), value: s.totalOrders, sub: t('stat_period', { n: s.periodOrders, d: s.days }) })}
          ${statCard({ iconName: 'wallet', tone: 'ok', label: t('a_revenue'), value: money(s.revenue) })}
          ${statCard({ iconName: 'briefcase', tone: 'violet', label: t('stat_specialists'), value: s.usersByRole.WORKER || 0, sub: s.unassigned ? t('n_unassigned', { n: s.unassigned }) : '' })}
        </div>
        <div class="dash-grid">
          <div class="dash-col">
            <section class="card" aria-labelledby="ov"><div class="card-head"><h2 id="ov">${esc(t('orders_overview'))}</h2><span class="caption muted">${esc(t('n_orders', { n: s.totalOrders }))}</span></div>
              <div class="stackbar" role="img" aria-label="${esc(STEPS.map((k) => t('st_' + k) + ': ' + (s.byStatus[k] || 0)).join(', '))}">${STEPS.map((k) => (s.byStatus[k] ? `<span class="seg-${k}" style="flex:${s.byStatus[k] / statusTotal}"></span>` : '')).join('')}</div>
              <ul class="legend">${STEPS.map((k) => `<li><span class="sw seg-${k}"></span>${esc(t('st_' + k))}<span class="n">${s.byStatus[k] || 0}</span></li>`).join('')}</ul></section>
            <section class="card" aria-labelledby="pd"><div class="card-head"><h2 id="pd">${esc(t('a_by_day'))}</h2>
              <label class="sr-only" for="days">${esc(t('days'))}</label><select id="days" style="width:auto">${[7, 14, 30].map((d) => `<option value="${d}" ${d === S.days ? 'selected' : ''}>${esc(t('last_n_days', { n: d }))}</option>`).join('')}</select></div>${chart()}</section>
            <section class="card" aria-labelledby="rc"><div class="card-head"><h2 id="rc">${esc(t('recent_orders'))}</h2><a class="link-btn" href="#/orders">${esc(t('view_all'))}</a></div>
              ${orders.length ? `<ul class="list">${orders.slice(0, 6).map((o) => miniOrder(o, o.clientName + ' → ' + (o.workerName || t('unassigned')))).join('')}</ul>` : emptyState({ iconName: 'clipboard', title: t('orders_none'), text: '' })}</section>
          </div>
          <div class="dash-col">
            <section class="card" aria-labelledby="na"><div class="card-head"><h2 id="na">${esc(t('needs_attention'))}</h2>${needAssign.length ? `<span class="pill-count">${needAssign.length}</span>` : ''}</div>
              ${needAssign.length ? `<ul class="list">${needAssign.map((o) => `<li class="list-item"><div class="grow"><div class="t truncate">${esc(loc(o, 'serviceName'))}</div><div class="s">${esc(t('order_no'))}${o.id} · ${esc(t('d_' + o.district))}</div></div><a class="btn secondary sm" href="#/orders/${o.id}">${esc(t('a_assign_btn'))}</a></li>`).join('')}</ul>`
                : emptyState({ iconName: 'check-circle', title: t('all_assigned'), text: '' })}</section>
            <section class="card" aria-labelledby="sp"><div class="card-head"><h2 id="sp">${esc(t('a_workers'))}</h2></div>
              ${workers.length ? `<ul class="list">${workers.map((w) => `<li class="list-item">${avatar(w.fullName)}<div class="grow"><div class="t truncate">${esc(w.fullName)}</div>
                <div class="progress" style="margin-top:.35rem" role="img" aria-label="${esc(t('completed_of', { c: w.completed, n: w.total }))}"><span style="width:${w.total ? (w.completed / w.total) * 100 : 0}%"></span></div>
                <div class="s">${esc(t('completed_of', { c: w.completed, n: w.total }))}</div></div><div class="right">${w.ratingCount ? rating(w.ratingAvg, undefined, { size: 14 }) : '<span class="muted">—</span>'}</div></li>`).join('')}</ul>` : `<p class="muted">${esc(t('no_workers'))}</p>`}</section>
          </div>
        </div>`;
    }
    render();
    ctx.view.addEventListener('change', async (e) => {
      if (e.target.id !== 'days') return;
      S.days = Number(e.target.value);
      try { await load(); render(); $('#days').focus(); } catch (err) { if (!err.silent) toast(err.message, 'err'); }
    });
    const refresh = async () => { try { await load(); const sel = document.activeElement && document.activeElement.id === 'days'; render(); if (sel) $('#days').focus(); } catch (e) { /* ignore */ } };
    notif.listeners.add(refresh);
    return { destroy() { notif.listeners.delete(refresh); } };
  }
  window.adminDashboard = adminDashboard;

  /* ================= Users (FR-12) ================= */
  async function usersPage(ctx) {
    const S = ui.users = Object.assign({ tab: '', q: '' }, ui.users);
    let users = await ctx.wait(api('/admin/users'));
    const TABS = [['', t('st_all_short')], ['CLIENT', t('role_CLIENT_pl')], ['WORKER', t('role_WORKER_pl')], ['ADMIN', t('role_ADMIN_pl')]];
    ctx.view.innerHTML = `
      <div class="page-head"><p>${esc(t('users_sub'))}</p></div>
      <div class="toolbar">${tabsHtml(TABS.map(([k, l]) => [k, l, 0]), S.tab, 'utab')}
        <div class="toolbar-main"><div class="control has-icon">${icon('search', 18)}<input id="u-q" type="search" value="${esc(S.q)}" placeholder="${esc(t('users_search'))}" aria-label="${esc(t('users_search'))}" autocomplete="off"></div></div></div>
      <p class="caption muted" id="u-count" aria-live="polite"></p>
      <section class="card flush"><div class="table-wrap cards" id="u-table"></div></section>`;
    const tabsEl = $('.tabs', ctx.view);
    bindTabKeys(tabsEl);

    function base() {
      const q = S.q.trim().toLowerCase();
      return users.filter((u) => !q || [u.fullName, u.phone, u.email].join(' ').toLowerCase().includes(q));
    }
    function render() {
      const b = base();
      $$('.tab', tabsEl).forEach((el) => {
        const k = el.dataset.tab; const on = k === S.tab;
        el.setAttribute('aria-selected', String(on)); el.tabIndex = on ? 0 : -1;
        $('.count', el).textContent = k ? b.filter((u) => u.role === k).length : b.length;
      });
      const list = S.tab ? b.filter((u) => u.role === S.tab) : b;
      $('#u-count').textContent = t('n_users', { n: list.length });
      $('#u-table').innerHTML = list.length ? `<table><thead><tr><th>${esc(t('user'))}</th><th>${esc(t('phone'))}</th><th>${esc(t('role'))}</th><th>${esc(t('specialization'))}</th><th>${esc(t('status'))}</th><th><span class="sr-only">${esc(t('actions'))}</span></th></tr></thead><tbody>
        ${list.map((u) => `<tr class="${u.active ? '' : 'inactive'}">
          <td data-label="${esc(t('user'))}"><div class="row" style="flex-wrap:nowrap">${avatar(u.fullName)}<div><div class="strong">${esc(u.fullName)}</div><div class="caption muted">${esc(u.email || '')}</div></div></div></td>
          <td data-label="${esc(t('phone'))}" class="nowrap">${esc(u.phone)}</td>
          <td data-label="${esc(t('role'))}"><span class="badge plain info">${esc(t('role_' + u.role))}</span></td>
          <td data-label="${esc(t('specialization'))}">${u.role === 'WORKER' ? `${esc(loc(u, 'specialization'))}${u.ratingCount ? ' · ' + rating(u.ratingAvg, undefined, { size: 13 }) : ''}` : '<span class="muted">—</span>'}</td>
          <td data-label="${esc(t('status'))}"><span class="badge ${u.active ? 'ok' : 'bad'}">${esc(u.active ? t('active') : t('blocked'))}</span></td>
          <td data-label="" class="actions">${u.role === 'ADMIN' ? '' : `<button type="button" class="btn sm ${u.active ? 'danger-outline' : 'secondary'}" data-toggle="${u.id}">${icon(u.active ? 'ban' : 'check', 16)}${esc(u.active ? t('block') : t('unblock'))}</button>`}</td></tr>`).join('')}
        </tbody></table>` : emptyState({ iconName: 'users', title: t('users_none'), text: t('orders_none_filtered_text') });
    }
    render();
    tabsEl.addEventListener('click', (e) => { const b = e.target.closest('[data-tab]'); if (b) { S.tab = b.dataset.tab; render(); } });
    $('#u-q').addEventListener('input', debounce((e) => { S.q = e.target.value; render(); }, 150));
    $('#u-table').addEventListener('click', async (e) => {
      const btn = e.target.closest('[data-toggle]'); if (!btn) return;
      const u = users.find((x) => x.id === Number(btn.dataset.toggle)); if (!u) return;
      if (u.active) {
        const ok = await confirmDialog({ title: t('block_title'), message: t('block_confirm', { name: u.fullName }), confirmLabel: t('block'), danger: true });
        if (!ok) return;
      }
      await withBusy(btn, async () => {
        try {
          const upd = await api(`/admin/users/${u.id}/active`, { method: 'POST', body: { active: !u.active } });
          users = users.map((x) => (x.id === upd.id ? upd : x));
          toast(t('user_updated')); render();
        } catch (err) { if (!err.silent) toast(err.message, 'err'); }
      });
    });
  }
  addRoute(/^#\/users$/, { roles: ['ADMIN'], title: 'nav_users', handler: usersPage });

  /* ================= Catalog management (UC-12) ================= */
  async function adminCatalogPage(ctx) {
    let categories, services;
    const load = async () => { [categories, services] = await Promise.all([api('/catalog/categories'), api('/admin/services')]); state.categories = categories; };
    await ctx.wait(load());
    // The admin sees this route as the catalog manager, not the public services list.
    currentRouteDef = Object.assign({}, currentRouteDef, { title: 'nav_catalog' });
    setPageMeta({ title: t('nav_catalog'), crumb: null });

    function render() {
      ctx.view.innerHTML = `
        <div class="page-head"><p>${esc(t('catalog_sub'))}</p>
          <div class="page-actions"><button type="button" class="btn secondary" id="add-cat">${icon('plus', 18)}${esc(t('add_category'))}</button><button type="button" class="btn" id="add-svc">${icon('plus', 18)}${esc(t('add_service'))}</button></div></div>
        <div class="stack">
          <section class="card" aria-labelledby="cc"><div class="card-head"><h2 id="cc">${esc(t('a_categories'))}</h2><span class="caption muted">${categories.length}</span></div>
            ${categories.length ? `<ul class="list">${categories.map((c) => `<li class="list-item"><span class="tile-icon ${categoryTone(c.id)}">${icon(categoryIcon(c), 18)}</span>
              <div class="grow"><div class="t">${esc(c.nameKk)}</div><div class="s">${esc(c.nameRu)}</div></div>
              <button type="button" class="icon-btn" data-ecat="${c.id}" aria-label="${esc(t('edit') + ': ' + loc(c, 'name'))}">${icon('edit', 18)}</button>
              <button type="button" class="icon-btn" data-dcat="${c.id}" aria-label="${esc(t('delete') + ': ' + loc(c, 'name'))}">${icon('trash', 18)}</button></li>`).join('')}</ul>` : emptyState({ iconName: 'grid', title: t('no_categories'), text: '' })}</section>
          <section class="card flush" aria-labelledby="cs"><div class="card-head" style="padding:1.25rem 1.25rem 0;margin-bottom:.5rem"><h2 id="cs">${esc(t('a_services'))}</h2><span class="caption muted">${services.length}</span></div>
            <div class="table-wrap cards">${services.length ? `<table><thead><tr><th>${esc(t('service'))}</th><th>${esc(t('category'))}</th><th>${esc(t('price'))}</th><th>${esc(t('duration'))}</th><th>${esc(t('status'))}</th><th><span class="sr-only">${esc(t('actions'))}</span></th></tr></thead><tbody>
              ${services.map((s) => `<tr class="${s.active ? '' : 'inactive'}"><td data-label="${esc(t('service'))}"><div class="strong">${esc(loc(s, 'name'))}</div><div class="caption muted truncate" style="max-width:34ch">${esc(loc(s, 'description'))}</div></td>
                <td data-label="${esc(t('category'))}">${esc(loc(s, 'categoryName'))}</td><td data-label="${esc(t('price'))}" class="nowrap strong">${money(s.price)}</td>
                <td data-label="${esc(t('duration'))}">${s.durationMinutes ? esc(t('minutes', { n: s.durationMinutes })) : '<span class="muted">—</span>'}</td>
                <td data-label="${esc(t('status'))}"><span class="badge ${s.active ? 'ok' : ''}">${esc(s.active ? t('active') : t('hidden'))}</span></td>
                <td data-label="" class="actions"><button type="button" class="btn secondary sm" data-esvc="${s.id}">${icon('edit', 16)}${esc(t('edit'))}</button>
                  ${s.active ? `<button type="button" class="btn danger-outline sm" data-dsvc="${s.id}">${esc(t('hide'))}</button>` : `<button type="button" class="btn secondary sm" data-rsvc="${s.id}">${esc(t('restore'))}</button>`}</td></tr>`).join('')}
              </tbody></table>` : emptyState({ iconName: 'grid', title: t('no_services_admin'), text: '' })}</div></section>
        </div>`;
    }
    render();
    const reload = async () => { await load(); render(); };

    ctx.view.addEventListener('click', async (e) => {
      const q = (a) => e.target.closest(`[${a}]`);
      let b;
      if (e.target.closest('#add-cat')) { categoryForm(null, reload); return; }
      if (e.target.closest('#add-svc')) { serviceForm(null, categories, reload); return; }
      if ((b = q('data-ecat'))) { categoryForm(categories.find((c) => c.id === Number(b.dataset.ecat)), reload); return; }
      if ((b = q('data-esvc'))) { serviceForm(services.find((s) => s.id === Number(b.dataset.esvc)), categories, reload); return; }
      if ((b = q('data-dcat'))) {
        const c = categories.find((x) => x.id === Number(b.dataset.dcat));
        if (!(await confirmDialog({ title: t('delete_category'), message: t('confirm_delete_cat', { name: loc(c, 'name') }), confirmLabel: t('delete'), danger: true }))) return;
        try { await api('/admin/categories/' + c.id, { method: 'DELETE' }); toast(t('deleted')); await reload(); } catch (err) { if (!err.silent) toast(err.message, 'err'); }
        return;
      }
      if ((b = q('data-dsvc'))) {
        const s = services.find((x) => x.id === Number(b.dataset.dsvc));
        if (!(await confirmDialog({ title: t('hide_service'), message: t('confirm_hide', { name: loc(s, 'name') }), confirmLabel: t('hide'), danger: true }))) return;
        try { await api('/admin/services/' + s.id, { method: 'DELETE' }); toast(t('hidden_ok')); await reload(); } catch (err) { if (!err.silent) toast(err.message, 'err'); }
        return;
      }
      if ((b = q('data-rsvc'))) {
        const s = services.find((x) => x.id === Number(b.dataset.rsvc));
        await withBusy(b, async () => {
          try { await api('/admin/services/' + s.id, { method: 'PUT', body: serviceBody(s, { active: true }) }); toast(t('restored_ok')); await reload(); } catch (err) { if (!err.silent) toast(err.message, 'err'); }
        });
      }
    });
  }
  window.adminCatalogPage = adminCatalogPage;

  const serviceBody = (s, over = {}) => ({
    categoryId: s.categoryId, nameKk: s.nameKk, nameRu: s.nameRu, descriptionKk: s.descriptionKk, descriptionRu: s.descriptionRu,
    price: s.price, durationMinutes: s.durationMinutes ?? null, active: s.active, ...over,
  });

  function categoryForm(c, done) {
    const m = openModal({
      title: t(c ? 'edit_category' : 'add_category'), size: 'sm',
      html: `<form id="cat-form" novalidate>${field({ name: 'nameKk', label: t('name_kk'), required: true, value: c ? c.nameKk : '', maxlength: 100 })}${field({ name: 'nameRu', label: t('name_ru'), required: true, value: c ? c.nameRu : '', maxlength: 100 })}
        <div class="modal-actions"><button type="button" class="btn secondary" data-cancel>${esc(t('cancel'))}</button><button class="btn" type="submit">${esc(t('save'))}</button></div></form>`,
    });
    const f = $('#cat-form', m.body);
    $('[data-cancel]', m.body).addEventListener('click', () => m.close());
    f.addEventListener('submit', async (e) => {
      e.preventDefault(); clearFormErrors(f);
      const errs = [];
      if (!f.nameKk.value.trim()) errs.push(f.nameKk);
      if (!f.nameRu.value.trim()) errs.push(f.nameRu);
      if (errs.length) { errs.forEach((i) => setFieldError(i, t('v_required'))); errs[0].focus(); return; }
      await withBusy($('button[type=submit]', f), async () => {
        try {
          await api(c ? '/admin/categories/' + c.id : '/admin/categories', { method: c ? 'PUT' : 'POST', body: { nameKk: f.nameKk.value.trim(), nameRu: f.nameRu.value.trim() } });
          m.close(); toast(t('saved')); await done();
        } catch (err) { if (!err.silent) formAlert(f, err.message); }
      });
    });
  }

  function serviceForm(s, categories, done) {
    const m = openModal({
      title: t(s ? 'edit_service' : 'add_service'), size: 'md',
      html: `<form id="svc-form" novalidate>
        <div class="field"><label class="label" for="f-categoryId">${esc(t('category'))}<span class="req" aria-hidden="true">*</span></label><select id="f-categoryId" name="categoryId">${categories.map((c) => `<option value="${c.id}" ${s && s.categoryId === c.id ? 'selected' : ''}>${esc(loc(c, 'name'))}</option>`).join('')}</select></div>
        <div class="form-row two">${field({ name: 'nameKk', label: t('name_kk'), required: true, value: s ? s.nameKk : '', maxlength: 150 })}${field({ name: 'nameRu', label: t('name_ru'), required: true, value: s ? s.nameRu : '', maxlength: 150 })}</div>
        <div class="form-row two">
          <div class="field"><label class="label" for="f-dkk">${esc(t('desc_kk'))}</label><textarea id="f-dkk" name="descriptionKk" rows="3" maxlength="1000">${esc(s ? s.descriptionKk || '' : '')}</textarea></div>
          <div class="field"><label class="label" for="f-dru">${esc(t('desc_ru'))}</label><textarea id="f-dru" name="descriptionRu" rows="3" maxlength="1000">${esc(s ? s.descriptionRu || '' : '')}</textarea></div></div>
        <div class="form-row two">${field({ name: 'price', label: t('price') + ' (₸)', type: 'number', required: true, value: s ? s.price : '', extra: 'min="0" step="100"' })}
          ${field({ name: 'durationMinutes', label: t('duration_min'), type: 'number', value: s && s.durationMinutes ? s.durationMinutes : '', extra: 'min="5" max="1440" step="5"' })}</div>
        <div class="modal-actions"><button type="button" class="btn secondary" data-cancel>${esc(t('cancel'))}</button><button class="btn" type="submit">${esc(t('save'))}</button></div></form>`,
    });
    const f = $('#svc-form', m.body);
    $('[data-cancel]', m.body).addEventListener('click', () => m.close());
    f.addEventListener('submit', async (e) => {
      e.preventDefault(); clearFormErrors(f);
      const errs = [];
      if (!f.nameKk.value.trim()) errs.push(f.nameKk);
      if (!f.nameRu.value.trim()) errs.push(f.nameRu);
      if (f.price.value === '' || Number(f.price.value) < 0) errs.push(f.price);
      if (errs.length) { errs.forEach((i) => setFieldError(i, t('v_required'))); errs[0].focus(); return; }
      const body = {
        categoryId: Number(f.categoryId.value), nameKk: f.nameKk.value.trim(), nameRu: f.nameRu.value.trim(),
        descriptionKk: f.descriptionKk.value.trim(), descriptionRu: f.descriptionRu.value.trim(),
        price: Number(f.price.value), durationMinutes: f.durationMinutes.value ? Number(f.durationMinutes.value) : null, active: s ? s.active : true,
      };
      await withBusy($('button[type=submit]', f), async () => {
        try {
          await api(s ? '/admin/services/' + s.id : '/admin/services', { method: s ? 'PUT' : 'POST', body });
          m.close(); toast(t('saved')); await done();
        } catch (err) { if (!err.silent) formAlert(f, err.message); }
      });
    });
  }
})();

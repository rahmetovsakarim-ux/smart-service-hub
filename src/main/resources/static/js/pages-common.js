'use strict';
/* Smart Service Hub — pages shared by every role: notifications, profile, settings. */
(function () {
  /* ================= Notification center ================= */
  async function notificationsPage(ctx) {
    const S = ui.notifications = Object.assign({ unreadOnly: false }, ui.notifications);
    await ctx.wait(pollNotifications(true));
    ctx.view.innerHTML = `
      <div class="page-head"><p>${esc(t('notifications_sub'))}</p>
        <div class="page-actions"><div class="segmented" role="group" aria-label="${esc(t('filters'))}">
          <button type="button" data-f="all" aria-pressed="${!S.unreadOnly}">${esc(t('st_all_short'))}</button>
          <button type="button" data-f="unread" aria-pressed="${S.unreadOnly}">${esc(t('unread'))}</button></div>
          <button type="button" class="btn secondary" id="read-all">${icon('check', 18)}${esc(t('mark_read'))}</button></div></div>
      <section class="card flush" aria-label="${esc(t('notifications'))}"><div id="n-list"></div></section>`;
    const list = $('#n-list', ctx.view);
    const render = () => {
      const items = S.unreadOnly ? state.notifItems.filter((n) => !n.seen) : state.notifItems;
      const top = window.scrollY;
      list.innerHTML = items.length ? items.map(notifItemHtml).join('') : emptyState({ iconName: 'bell', title: t(S.unreadOnly ? 'no_unread' : 'no_notifications'), text: t('no_notifications_text') });
      $('#read-all', ctx.view).disabled = !state.unread;
      window.scrollTo(0, top);
    };
    render();
    $$('[data-f]', ctx.view).forEach((b) => b.addEventListener('click', () => {
      S.unreadOnly = b.dataset.f === 'unread';
      $$('[data-f]', ctx.view).forEach((x) => x.setAttribute('aria-pressed', String(x === b)));
      render();
    }));
    $('#read-all', ctx.view).addEventListener('click', markAllRead);
    notif.listeners.add(render);
    return { destroy() { notif.listeners.delete(render); } };
  }
  addRoute(/^#\/notifications$/, { roles: 'auth', title: 'nav_notifications', handler: notificationsPage });

  /* ================= Profile ================= */
  async function profilePage(ctx) {
    const role = state.user.role;
    const u = await ctx.wait(api(role === 'WORKER' ? '/worker/profile' : '/auth/me'));
    const row = (ic, label, value) => (value || value === 0 ? `<div>${icon(ic, 18)}<div><dt>${esc(label)}</dt><dd>${value}</dd></div></div>` : '');
    ctx.view.innerHTML = `
      <div class="dash-grid" style="grid-template-columns:minmax(0,1fr) minmax(0,1.4fr)">
        <section class="card" aria-labelledby="pi">
          <div class="row" style="flex-wrap:nowrap;margin-bottom:1.25rem"><span class="avatar lg" aria-hidden="true">${esc(initials(u.fullName))}</span>
            <div><h2 id="pi">${esc(u.fullName)}</h2><span class="badge plain info">${esc(t('role_' + u.role))}</span></div></div>
          <dl class="info-list">
            ${row('phone', t('phone'), esc(u.phone))}
            ${row('mail', t('email'), u.email ? esc(u.email) : '')}
            ${role === 'WORKER' ? row('briefcase', t('specialization'), esc(loc(u, 'specialization'))) : ''}
            ${row('calendar', t('member_since'), esc(fmtDay(u.createdAt)))}
          </dl>
          ${role === 'WORKER' ? `<hr class="divider"><div class="row between"><span class="secondary">${esc(t('rating'))}</span>${u.ratingCount ? rating(u.ratingAvg, u.ratingCount) : '<span class="muted">—</span>'}</div>` : ''}
        </section>
        ${role === 'WORKER' ? `<section class="card" aria-labelledby="pe"><div class="card-head"><h2 id="pe">${esc(t('profile_edit'))}</h2></div>
          <form id="profile-form" novalidate>
            <div class="form-row two">
              <div class="field"><label class="label" for="f-district">${esc(t('district'))}</label><select id="f-district" name="district">${districtOptions(u.district)}</select></div>
              ${field({ name: 'experienceYears', label: t('experience'), type: 'number', value: String(u.experienceYears ?? 0), extra: 'min="0" max="60"' })}
            </div>
            <div class="field"><label class="label" for="f-bio">${esc(t('bio'))}</label><textarea id="f-bio" name="bio" rows="4" maxlength="500">${esc(u.bio || '')}</textarea></div>
            <label class="check"><input type="checkbox" name="available" ${u.available ? 'checked' : ''}><span>${esc(t('available'))}</span></label>
            <div class="modal-actions" style="justify-content:flex-start"><button class="btn" type="submit">${esc(t('save'))}</button></div>
          </form></section>`
          : `<section class="card" aria-labelledby="pn"><div class="card-head"><h2 id="pn">${esc(t('account'))}</h2></div>
            <div class="alert info">${icon('info', 18)}<span>${esc(t('profile_readonly'))}</span></div>
            <a class="btn secondary" href="#/settings">${icon('settings', 18)}${esc(t('nav_settings'))}</a></section>`}
      </div>`;
    const f = $('#profile-form', ctx.view);
    if (f) f.addEventListener('submit', async (e) => {
      e.preventDefault(); clearFormErrors(f);
      const years = Number(f.experienceYears.value || 0);
      if (years < 0 || years > 60) { setFieldError(f.experienceYears, t('v_experience')); f.experienceYears.focus(); return; }
      await withBusy($('button[type=submit]', f), async () => {
        try {
          state.user = await api('/worker/profile', { method: 'PUT', body: { district: f.district.value || null, experienceYears: years, bio: f.bio.value, available: f.available.checked } });
          toast(t('saved'));
        } catch (err) { if (!err.silent) formAlert(f, err.message); }
      });
    });
  }
  addRoute(/^#\/profile$/, { roles: 'auth', title: 'nav_profile', handler: profilePage });

  /* ================= Settings ================= */
  async function settingsPage(ctx) {
    const themes = [['system', 'monitor_system', 'settings'], ['light', 'theme_light', 'sun'], ['dark', 'theme_dark', 'moon']];
    ctx.view.innerHTML = `
      <div class="page-head"><p>${esc(t('settings_sub'))}</p></div>
      <div class="stack" style="max-width:720px">
        <section class="card" aria-labelledby="sl"><div class="card-head"><h2 id="sl">${icon('globe', 20)} ${esc(t('language'))}</h2></div>
          <p class="caption secondary" style="margin-bottom:.75rem">${esc(t('language_hint'))}</p>
          <div class="segmented" role="group" aria-label="${esc(t('language'))}"><button type="button" data-lang="kk" aria-pressed="${state.lang === 'kk'}" lang="kk">Қазақша</button><button type="button" data-lang="ru" aria-pressed="${state.lang === 'ru'}" lang="ru">Русский</button></div></section>
        <section class="card" aria-labelledby="st"><div class="card-head"><h2 id="st">${icon('sun', 20)} ${esc(t('appearance'))}</h2></div>
          <div class="segmented" role="group" aria-label="${esc(t('appearance'))}">${themes.map(([k, lbl, ic]) => `<button type="button" data-theme="${k}" aria-pressed="${state.theme === k}">${icon(ic, 16)}${esc(t(lbl === 'monitor_system' ? 'theme_system' : lbl))}</button>`).join('')}</div></section>
        <section class="card" aria-labelledby="sn"><div class="card-head"><h2 id="sn">${icon('panel-left', 20)} ${esc(t('navigation'))}</h2></div>
          <label class="check"><input type="checkbox" id="set-collapse" role="switch" ${state.collapsed ? 'checked' : ''}><span>${esc(t('collapse_menu'))}</span></label>
          <p class="caption muted">${esc(t('collapse_hint'))}</p></section>
        <section class="card" aria-labelledby="sa"><div class="card-head"><h2 id="sa">${icon('user', 20)} ${esc(t('account'))}</h2></div>
          <div class="row between"><div><div class="strong">${esc(state.user.fullName)}</div><div class="caption muted">${esc(state.user.phone)}</div></div>
            <button type="button" class="btn danger-outline" id="set-logout">${icon('logout', 18)}${esc(t('logout'))}</button></div></section>
      </div>`;
    ctx.view.addEventListener('click', (e) => {
      const th = e.target.closest('[data-theme]');
      if (th) { setTheme(th.dataset.theme); $$('[data-theme]', ctx.view).forEach((b) => b.setAttribute('aria-pressed', String(b === th))); return; }
      if (e.target.closest('#set-logout')) logout();
    });
    $('#set-collapse', ctx.view).addEventListener('change', (e) => { if (e.target.checked !== state.collapsed) toggleCollapsed(); });
  }
  addRoute(/^#\/settings$/, { roles: 'auth', title: 'nav_settings', handler: settingsPage });
})();

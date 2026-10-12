'use strict';
/* Smart Service Hub — authentication pages: login and registration. */
(function () {
  const IS_LOCAL = ['localhost', '127.0.0.1', '[::1]'].includes(location.hostname);
  // Convenience for local demo mode only; never rendered on a deployed host.
  const DEMO = [['admin@hub.kz', 'Admin123!', 'role_ADMIN'], ['client@hub.kz', 'Client123!', 'role_CLIENT'], ['worker1@hub.kz', 'Worker123!', 'role_WORKER']];

  async function afterLogin(user) {
    state.user = user;
    state.flash = null;
    startNotifPolling();
    const target = state.returnTo && state.returnTo !== '#/login' && state.returnTo !== '#/register' ? state.returnTo : '#/';
    state.returnTo = null;
    go(target);
  }

  addRoute(/^#\/login$/, {
    roles: 'guest', layout: 'auth', title: 'login',
    handler: async (ctx) => {
      const flash = state.flash; state.flash = null;
      ctx.view.innerHTML = `
        <h1>${esc(t('login_title'))}</h1>
        <p class="lead">${esc(t('login_lead'))}</p>
        ${flash ? `<div class="alert warning" role="status">${icon('info', 18)}<span>${esc(flash)}</span></div>` : ''}
        <form id="login-form" novalidate>
          ${field({ name: 'login', label: t('login_field'), required: true, autocomplete: 'username', icon: 'user', placeholder: t('login_ph') })}
          ${passwordField({ label: t('password') })}
          <button class="btn lg block" type="submit">${esc(t('login'))}</button>
        </form>
        <p class="auth-foot">${esc(t('no_account'))} <a href="#/register">${esc(t('register'))}</a></p>
        <p class="auth-foot"><a href="#/services">${icon('arrow-left', 14)} ${esc(t('browse_services'))}</a></p>
        ${IS_LOCAL ? `<details class="demo-box"><summary>${esc(t('demo_accounts'))}</summary>${DEMO.map(([l, p, r]) => `<button type="button" class="btn secondary sm" data-l="${esc(l)}" data-p="${esc(p)}">${esc(t(r))}</button>`).join('')}</details>` : ''}`;

      const form = $('#login-form', ctx.view);
      $$('[data-l]', ctx.view).forEach((b) => b.addEventListener('click', () => { form.login.value = b.dataset.l; form.password.value = b.dataset.p; }));
      form.addEventListener('submit', async (e) => {
        e.preventDefault();
        clearFormErrors(form);
        let bad = null;
        if (!form.login.value.trim()) { setFieldError(form.login, t('v_required')); bad = bad || form.login; }
        if (!form.password.value) { setFieldError(form.password, t('v_required')); bad = bad || form.password; }
        if (bad) { bad.focus(); return; }
        const btn = $('button[type=submit]', form);
        await withBusy(btn, async () => {
          try {
            const user = await api('/auth/login', { method: 'POST', body: { login: form.login.value.trim(), password: form.password.value }, auth: false });
            await afterLogin(user);
          } catch (err) {
            formAlert(form, err.message);
            form.password.value = ''; form.password.focus();
          }
        });
      });
      if (!flash) form.login.focus();
    },
  });

  addRoute(/^#\/register$/, {
    roles: 'guest', layout: 'auth', title: 'register',
    handler: async (ctx) => {
      const saved = ui.register || { role: 'CLIENT' };
      ctx.view.innerHTML = `
        <h1>${esc(t('register_title'))}</h1>
        <p class="lead">${esc(t('register_lead'))}</p>
        <form id="reg-form" novalidate>
          <div class="field"><span class="label" id="role-label">${esc(t('i_am'))}</span>
            <div class="segmented block" role="radiogroup" aria-labelledby="role-label">
              <label><input type="radio" name="role" value="CLIENT" ${saved.role === 'CLIENT' ? 'checked' : ''}><span>${esc(t('as_client'))}</span></label>
              <label><input type="radio" name="role" value="WORKER" ${saved.role === 'WORKER' ? 'checked' : ''}><span>${esc(t('as_worker'))}</span></label>
            </div></div>
          ${field({ name: 'fullName', label: t('full_name'), required: true, autocomplete: 'name', maxlength: 100, icon: 'user' })}
          <div class="form-row two">
            ${field({ name: 'phone', label: t('phone'), required: true, autocomplete: 'tel', inputmode: 'tel', placeholder: '+77001234567', icon: 'phone' })}
            ${field({ name: 'email', label: t('email_opt'), type: 'email', autocomplete: 'email', icon: 'mail' })}
          </div>
          ${passwordField({ label: t('password'), autocomplete: 'new-password', hint: t('password_hint') })}
          <div id="worker-fields" ${saved.role === 'WORKER' ? '' : 'hidden'}>
            <div class="form-row two">
              <div class="field"><label class="label" for="f-specializationId">${esc(t('specialization'))}<span class="req" aria-hidden="true">*</span></label>
                <select id="f-specializationId" name="specializationId"><option value="">${esc(t('choose'))}</option>${state.categories.map((c) => `<option value="${c.id}">${esc(loc(c, 'name'))}</option>`).join('')}</select></div>
              <div class="field"><label class="label" for="f-district">${esc(t('district'))}<span class="req" aria-hidden="true">*</span></label>
                <select id="f-district" name="district">${districtOptions('')}</select></div>
            </div>
            ${field({ name: 'experienceYears', label: t('experience'), type: 'number', value: '0', extra: 'min="0" max="60"' })}
            <div class="field"><label class="label" for="f-bio">${esc(t('bio'))}</label><textarea id="f-bio" name="bio" rows="3" maxlength="500"></textarea></div>
          </div>
          <button class="btn lg block" type="submit">${esc(t('register'))}</button>
        </form>
        <p class="auth-foot">${esc(t('has_account'))} <a href="#/login">${esc(t('login'))}</a></p>`;

      const form = $('#reg-form', ctx.view);
      const wf = $('#worker-fields', ctx.view);
      $$('[name=role]', form).forEach((r) => r.addEventListener('change', () => { ui.register = { role: form.role.value }; wf.hidden = form.role.value !== 'WORKER'; }));

      form.addEventListener('submit', async (e) => {
        e.preventDefault();
        clearFormErrors(form);
        const role = form.role.value;
        const phone = form.phone.value.replace(/[\s()-]/g, '');
        const email = form.email.value.trim();
        const errs = [];
        const need = (input, cond, text) => { if (!cond) errs.push([input, text]); };
        need(form.fullName, form.fullName.value.trim().length >= 2, t('v_name'));
        need(form.phone, /^\+?[0-9]{10,15}$/.test(phone), t('v_phone'));
        need(form.email, !email || /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email), t('v_email'));
        need(form.password, form.password.value.length >= 6, t('v_password'));
        if (role === 'WORKER') {
          need(form.specializationId, !!form.specializationId.value, t('v_required'));
          need(form.district, !!form.district.value, t('v_required'));
        }
        if (errs.length) { errs.forEach(([i, m]) => setFieldError(i, m)); errs[0][0].focus(); return; }

        const body = { fullName: form.fullName.value.trim(), phone, email: email || null, password: form.password.value, role };
        if (role === 'WORKER') Object.assign(body, { specializationId: Number(form.specializationId.value), district: form.district.value, experienceYears: Number(form.experienceYears.value || 0), bio: form.bio.value.trim() || null });
        await withBusy($('button[type=submit]', form), async () => {
          try {
            await api('/auth/register', { method: 'POST', body, auth: false });
          } catch (err) { formAlert(form, err.message); return; }
          try {
            const user = await api('/auth/login', { method: 'POST', body: { login: phone, password: body.password }, auth: false });
            toast(t('registered_ok'));
            await afterLogin(user);
          } catch (err) {
            toast(t('registered_login'), 'ok'); go('#/login');
          }
        });
      });
      form.fullName.focus();
    },
  });
})();

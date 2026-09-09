(function () {
    'use strict';

    var API = '/api/online';
    var CACHE = 'strike:' + API;
    var control = document.getElementById('tunnelControl');
    var toggle = Strike.core.el('button', 'switch');
    toggle.id = 'tunnelEnabled';
    toggle.type = 'button';
    toggle.setAttribute('aria-label', 'Remote access');
    toggle.appendChild(Strike.core.el('span', 'switch__knob'));
    var hostname = document.getElementById('tunnelHostname');
    var token = document.getElementById('tunnelToken');
    var mode = document.getElementById('tunnelMode');
    var save = document.getElementById('tunnelSave');
    var forget = document.getElementById('tunnelForget');
    var retry = document.getElementById('tunnelRetry');
    var settings = document.getElementById('tunnelSettings');
    var settingsOpen = document.getElementById('tunnelSettingsOpen');
    var settingsClose = document.getElementById('tunnelSettingsClose');
    var accessCode = document.getElementById('accessCode');
    var accessShow = document.getElementById('accessShow');
    var accessCopy = document.getElementById('accessCopy');
    var regenerate = document.getElementById('accessRegenerate');
    var loaded = false;
    var fresh = false;
    var pending = false;
    var polling = false;
    var state = null;
    var selected = 'off';
    var shownAddresses = '';
    var timer = null;
    var revision = 0;
    var confirmTimer = null;

    function remember(payload) {
        var saved = {};
        ['enabled', 'mode', 'hostname', 'hasToken', 'state', 'status', 'running',
            'canRetry', 'accessReady', 'addresses'].forEach(function (key) { saved[key] = payload[key]; });
        saved.browserAccess = { canManage: payload.browserAccess.canManage };
        try { sessionStorage.setItem(CACHE, JSON.stringify(saved)); } catch (e) { return; }
    }

    function restore() {
        try {
            var saved = JSON.parse(sessionStorage.getItem(CACHE));
            if (saved) paint(saved, true);
        } catch (e) { return; }
    }

    function addressReady(id) {
        var row = document.getElementById(id);
        row.removeAttribute('data-loading');
        var skeleton = row.querySelector('.sk');
        if (skeleton) row.removeChild(skeleton);
    }

    function payload(xhr) {
        if (xhr.status !== 200) return null;
        try { return JSON.parse(xhr.responseText); } catch (e) { return null; }
    }

    function say(text, failed) {
        if (text) Strike.toast(text, failed);
    }

    function copy(input, button) {
        var masked = input.type === 'password';
        if (masked) input.type = 'text';
        input.focus();
        input.select();
        input.setSelectionRange(0, input.value.length);
        var copied = false;
        try { copied = document.execCommand('copy'); } catch (e) { copied = false; }
        if (masked && copied) input.type = 'password';
        if (masked && !copied) {
            accessShow.textContent = 'Hide';
            accessShow.setAttribute('aria-pressed', 'true');
        }
        if (copied) button.focus();
        clearTimeout(button.copyTimer);
        button.textContent = copied ? 'Copied' : 'Select';
        button.setAttribute('data-copied', copied ? 'true' : 'false');
        button.copyTimer = setTimeout(function () {
            button.textContent = 'Copy';
            button.removeAttribute('data-copied');
        }, 1500);
    }

    function addresses(values) {
        var joined = values.join('\n');
        if (joined === shownAddresses && loaded) return;
        shownAddresses = joined;
        var host = document.getElementById('localAddresses');
        host.textContent = '';
        if (!values.length) {
            host.appendChild(Strike.core.el('div', 'online__address', Strike.core.dash));
        }
        values.forEach(function (address) {
            var row = Strike.core.el('div', 'online__address');
            var input = Strike.core.el('input', 'input');
            input.type = 'text';
            input.value = address;
            input.readOnly = true;
            input.setAttribute('aria-label', 'Local address');
            var button = Strike.core.el('button', 'btn online__copy', 'Copy');
            button.type = 'button';
            button.setAttribute('aria-live', 'polite');
            button.onclick = function () { copy(input, button); };
            row.appendChild(input);
            row.appendChild(button);
            host.appendChild(row);
        });
    }

    function controls() {
        var canManage = state && state.browserAccess.canManage;
        var active = state && (state.enabled || state.running || state.state === 'stopping');
        toggle.disabled = !canManage || pending || !fresh || !state || state.state === 'stopping' ||
            (!state.enabled && (!state.accessReady || !state.hasToken));
        hostname.disabled = token.disabled = save.disabled = !canManage || pending || !fresh || !state || active;
        settingsOpen.disabled = !canManage || pending || !fresh || !state || active;
        settingsClose.disabled = pending;
        forget.disabled = !canManage || pending || !fresh || active;
        retry.disabled = !canManage || pending || !fresh;
        var buttons = mode.getElementsByTagName('button');
        for (var i = 0; i < buttons.length; i++) buttons[i].disabled = !canManage || pending || !fresh || !state || active;
        var access = state && state.browserAccess;
        accessShow.disabled = accessCopy.disabled = pending || !fresh || !access || !access.canManage || !access.code;
        regenerate.disabled = pending || !fresh || !access || !access.canManage;
    }

    function paint(payload, cached) {
        state = payload;
        document.getElementById('tunnelInCar').hidden = payload.browserAccess.canManage;
        addresses(payload.addresses);
        if (!loaded) {
            hostname.value = payload.hostname;
            selected = payload.mode;
            Strike.core.press(mode, selected);
        }
        loaded = !cached;
        fresh = !cached;
        token.placeholder = payload.hasToken ? 'Saved. Leave blank to keep it' : 'Paste from Cloudflare';
        toggle.setAttribute('aria-pressed', payload.enabled ? 'true' : 'false');
        document.getElementById('tunnelStatus').textContent = payload.status;
        document.getElementById('tunnelSchedule').textContent = !payload.hasToken ? 'Not set up yet' :
            payload.mode === 'always' ? 'Always on' :
            payload.mode === 'lock' ? 'Starts when the car locks' : 'Starts when the car is off';
        var dot = document.getElementById('tunnelDot');
        dot.setAttribute('data-state', payload.state === 'running' ? 'ok' :
            payload.state === 'broken' ? 'bad' :
            payload.state === 'starting' || payload.state === 'waiting' || payload.state === 'stopping' ? 'warn' : '');
        addressReady('remoteAddress');
        var url = payload.hostname ? 'https://' + payload.hostname + '/' : '';
        document.getElementById('remoteUrl').value = url;
        var remoteCopy = document.getElementById('remoteCopy');
        remoteCopy.hidden = !url;
        remoteCopy.disabled = !url;
        forget.hidden = !payload.hasToken;
        retry.hidden = !payload.canRetry;
        document.getElementById('accessContent').setAttribute('data-manage', payload.browserAccess.canManage ? 'true' : 'false');
        document.getElementById('accessManage').setAttribute('aria-hidden', payload.browserAccess.canManage ? 'false' : 'true');
        document.getElementById('accessInCar').hidden = payload.browserAccess.canManage;
        regenerate.hidden = !payload.browserAccess.canManage;
        accessCode.value = payload.browserAccess.code || '';
        accessCode.placeholder = Strike.core.dash;
        if (!cached) addressReady('accessAddress');
        controls();
        if (!toggle.parentNode) {
            control.textContent = '';
            control.appendChild(toggle);
        }
        if (!cached) remember(payload);
    }

    function schedule() {
        clearTimeout(timer);
        if (!document.hidden) timer = setTimeout(load, 5000);
    }

    function load() {
        if (pending || polling) { schedule(); return; }
        polling = true;
        var before = revision;
        var xhr = new XMLHttpRequest();
        xhr.open('GET', API, true);
        xhr.timeout = 8000;
        xhr.onloadend = function () {
            polling = false;
            if (xhr.status === 401) { Strike.session.signIn(); return; }
            if (xhr.status === 403) { location.replace('/lock'); return; }
            if (!pending && before === revision) {
                var found = payload(xhr);
                if (found) {
                    paint(found);
                } else {
                    fresh = false;
                    document.getElementById('tunnelStatus').textContent = 'Cannot reach Strike';
                    document.getElementById('tunnelSchedule').textContent = Strike.core.dash;
                    document.getElementById('tunnelDot').removeAttribute('data-state');
                    controls();
                    if (!toggle.parentNode) control.textContent = Strike.core.dash;
                    addressReady('remoteAddress');
                    addressReady('accessAddress');
                    if (!state) {
                        addresses([]);
                        document.getElementById('remoteCopy').hidden = true;
                    }
                }
            }
            schedule();
        };
        xhr.send();
    }

    function post(body, saved, stopping) {
        if (pending) return;
        pending = true;
        revision++;
        controls();
        say(stopping ? 'Remote browsers will disconnect when the tunnel stops.' : '', false);
        var xhr = new XMLHttpRequest();
        xhr.open('POST', API, true);
        xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
        xhr.timeout = 8000;
        xhr.onloadend = function () {
            pending = false;
            if (xhr.status === 401) { Strike.session.signIn(); return; }
            if (xhr.status === 403) {
                closeSettings();
                say('Edit tunnel settings from the car', true);
                load();
                return;
            }
            var found = payload(xhr);
            if (found) {
                if (saved) { loaded = false; token.value = ''; }
                paint(found);
                if (saved) {
                    closeSettings();
                    say(body === 'action=forget' ? 'Setup removed.' : 'Setup saved.', false);
                }
                if (body === 'action=regenerate') say('Access code regenerated', false);
            } else {
                var plain = (xhr.getResponseHeader('Content-Type') || '').indexOf('text/plain') === 0;
                say(xhr.status === 0 && stopping ? 'The connection closed. Use the car or its local address to reconnect.' :
                    plain && xhr.responseText ? xhr.responseText : 'Cannot reach Strike.', true);
                controls();
            }
            schedule();
        };
        xhr.send(body);
    }

    toggle.onclick = function () { post('action=toggle&enabled=' + !state.enabled, false, state.enabled); };
    retry.onclick = function () { post('action=retry', false, false); };
    forget.onclick = function () { post('action=forget', true, false); };
    function closeSettings() {
        if (pending) return;
        settings.hidden = true;
        token.value = '';
        settingsOpen.focus();
    }
    settingsOpen.onclick = function () {
        hostname.value = state.hostname;
        selected = state.mode;
        Strike.core.press(mode, selected);
        settings.hidden = false;
        say('', false);
        settingsClose.focus();
    };
    settingsClose.onclick = closeSettings;
    settings.onclick = function (event) { if (event.target === settings) closeSettings(); };
    settings.onkeydown = function (event) {
        if (event.key === 'Escape') { closeSettings(); return; }
        if (event.key !== 'Tab') return;
        var enabled = settings.querySelectorAll('button:not([disabled]):not([hidden]), input:not([disabled])');
        var first = enabled[0], last = enabled[enabled.length - 1];
        if (event.shiftKey && document.activeElement === first) {
            event.preventDefault();
            last.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
            event.preventDefault();
            first.focus();
        }
    };
    document.getElementById('remoteCopy').onclick = function () { copy(document.getElementById('remoteUrl'), this); };
    accessCopy.onclick = function () { copy(accessCode, this); };
    accessShow.onclick = function () {
        var show = accessCode.type === 'password';
        accessCode.type = show ? 'text' : 'password';
        accessShow.textContent = show ? 'Hide' : 'Show';
        accessShow.setAttribute('aria-pressed', show ? 'true' : 'false');
    };
    function cancelRegeneration() {
        clearTimeout(confirmTimer);
        regenerate.textContent = 'Regenerate';
        regenerate.removeAttribute('data-confirm');
    }
    regenerate.onclick = function () {
        if (regenerate.getAttribute('data-confirm') === 'true') {
            cancelRegeneration();
            accessCode.type = 'text';
            accessShow.textContent = 'Hide';
            accessShow.setAttribute('aria-pressed', 'true');
            post('action=regenerate', false, false);
        } else {
            regenerate.textContent = 'Confirm';
            regenerate.setAttribute('data-confirm', 'true');
            confirmTimer = setTimeout(cancelRegeneration, 5000);
        }
    };
    mode.onclick = function (event) {
        var button = Strike.core.buttonIn(event, mode);
        if (!button) return;
        selected = button.getAttribute('data-value');
        Strike.core.press(mode, selected);
    };
    document.getElementById('tunnelForm').onsubmit = function (event) {
        event.preventDefault();
        if (save.disabled) return;
        post('action=save&hostname=' + encodeURIComponent(hostname.value) +
            '&token=' + encodeURIComponent(token.value) + '&mode=' + selected, true, false);
    };
    document.addEventListener('visibilitychange', function () {
        clearTimeout(timer);
        if (document.hidden) {
            cancelRegeneration();
            accessCode.type = 'password';
            accessShow.textContent = 'Show';
            accessShow.setAttribute('aria-pressed', 'false');
        }
        if (!document.hidden) load();
    });
    restore();
    controls();
    load();
}());

(function () {
    'use strict';

    var API = '/api/online';
    var CACHE = 'strike:' + API;
    var SERVICES = {
        cloudflare: {
            nameLabel: 'Hostname',
            namePlaceholder: 'car.example.com',
            nameLength: 253,
            secretLabel: 'Tunnel token',
            secretPlaceholder: 'Paste from Cloudflare',
            hint: 'Create a tunnel in Cloudflare Zero Trust, point its public hostname at http://127.0.0.1:8090, then paste the tunnel token here.'
        },
        zrok: {
            nameLabel: 'Share name',
            namePlaceholder: 'strikecar',
            nameLength: 32,
            secretLabel: 'Account token',
            secretPlaceholder: 'Paste from zrok',
            hint: 'Create a free zrok account and copy its account token. The car answers at the share name you choose on share.zrok.io.'
        }
    };
    var control = document.getElementById('tunnelControl');
    var toggle = Strike.core.el('button', 'switch');
    toggle.id = 'tunnelEnabled';
    toggle.type = 'button';
    toggle.setAttribute('aria-label', 'Remote access');
    toggle.appendChild(Strike.core.el('span', 'switch__knob'));
    var services = document.getElementById('tunnelMethod');
    var name = document.getElementById('tunnelName');
    var nameRow = document.getElementById('tunnelNameRow');
    var nameLabel = document.getElementById('tunnelNameLabel');
    var secret = document.getElementById('tunnelSecret');
    var secretLabel = document.getElementById('tunnelSecretLabel');
    var hint = document.getElementById('tunnelHint');
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
    var selectedMode = 'off';
    var shownAddresses = '';
    var timer = null;
    var revision = 0;
    var confirmTimer = null;

    function remember(payload) {
        var saved = {};
        ['enabled', 'mode', 'method', 'methods', 'name', 'hasSecret', 'configured', 'address',
            'state', 'status', 'running', 'canRetry', 'accessReady',
            'addresses'].forEach(function (key) { saved[key] = payload[key]; });
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

    function fields() {
        var service = SERVICES[state.method] || SERVICES.cloudflare;
        nameRow.hidden = !service.nameLabel;
        nameLabel.textContent = service.nameLabel;
        name.placeholder = service.namePlaceholder;
        name.maxLength = service.nameLength || 253;
        secretLabel.textContent = service.secretLabel;
        secret.placeholder = state.hasSecret ? 'Saved. Leave blank to keep it' : service.secretPlaceholder;
        hint.textContent = service.hint;
    }

    function controls() {
        var canManage = state && state.browserAccess.canManage;
        var active = state && (state.enabled || state.running || state.state === 'stopping');
        var locked = !canManage || pending || !fresh || !state || active;
        toggle.disabled = !canManage || pending || !fresh || !state || state.state === 'stopping' ||
            (!state.enabled && (!state.accessReady || !state.configured));
        name.disabled = secret.disabled = save.disabled = locked;
        settingsOpen.disabled = locked;
        settingsClose.disabled = pending;
        forget.disabled = !canManage || pending || !fresh || active;
        retry.disabled = !canManage || pending || !fresh;
        var chooser = services.getElementsByTagName('button');
        for (var s = 0; s < chooser.length; s++) chooser[s].disabled = locked;
        var buttons = mode.getElementsByTagName('button');
        for (var i = 0; i < buttons.length; i++) buttons[i].disabled = locked;
        var access = state && state.browserAccess;
        accessShow.disabled = accessCopy.disabled = pending || !fresh || !access || !access.canManage || !access.code;
        regenerate.disabled = pending || !fresh || !access || !access.canManage;
    }

    function paint(payload, cached) {
        state = payload;
        document.getElementById('tunnelInCar').hidden = payload.browserAccess.canManage;
        addresses(payload.addresses);
        if (!loaded) {
            name.value = payload.name;
            selectedMode = payload.mode;
            Strike.core.press(mode, selectedMode);
        }
        loaded = !cached;
        fresh = !cached;
        Strike.core.press(services, payload.method);
        fields();
        toggle.setAttribute('aria-pressed', payload.enabled ? 'true' : 'false');
        document.getElementById('tunnelStatus').textContent = payload.status;
        document.getElementById('tunnelSchedule').textContent = !payload.configured ? 'Not set up yet' :
            payload.mode === 'always' ? 'Always on' :
            payload.mode === 'lock' ? 'Starts when the car locks' : 'Starts when the car is off';
        var dot = document.getElementById('tunnelDot');
        dot.setAttribute('data-state', payload.state === 'running' ? 'ok' :
            payload.state === 'broken' ? 'bad' :
            payload.state === 'starting' || payload.state === 'waiting' || payload.state === 'stopping' ? 'warn' : '');
        addressReady('remoteAddress');
        var url = payload.address || '';
        document.getElementById('remoteUrl').value = url;
        var remoteCopy = document.getElementById('remoteCopy');
        remoteCopy.hidden = !url;
        remoteCopy.disabled = !url;
        forget.hidden = !payload.hasSecret;
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
        say(stopping ? 'Remote browsers will disconnect when remote access stops.' : '', false);
        var xhr = new XMLHttpRequest();
        xhr.open('POST', API, true);
        xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
        xhr.timeout = 8000;
        xhr.onloadend = function () {
            pending = false;
            if (xhr.status === 401) { Strike.session.signIn(); return; }
            if (xhr.status === 403) {
                closeSettings();
                say('Edit remote access settings from the car', true);
                load();
                return;
            }
            var found = payload(xhr);
            if (found) {
                if (saved) { loaded = false; secret.value = ''; }
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
        secret.value = '';
        settingsOpen.focus();
    }
    settingsOpen.onclick = function () {
        name.value = state.name;
        selectedMode = state.mode;
        Strike.core.press(mode, selectedMode);
        fields();
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
    services.onclick = function (event) {
        var button = Strike.core.buttonIn(event, services);
        if (!button) return;
        var chosen = button.getAttribute('data-value');
        if (chosen === state.method) return;
        loaded = false;
        post('action=method&method=' + chosen, false, false);
    };
    mode.onclick = function (event) {
        var button = Strike.core.buttonIn(event, mode);
        if (!button) return;
        selectedMode = button.getAttribute('data-value');
        Strike.core.press(mode, selectedMode);
    };
    document.getElementById('tunnelForm').onsubmit = function (event) {
        event.preventDefault();
        if (save.disabled) return;
        post('action=save&method=' + state.method + '&name=' + encodeURIComponent(name.value) +
            '&secret=' + encodeURIComponent(secret.value) + '&mode=' + selectedMode, true, false);
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

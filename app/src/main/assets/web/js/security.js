(function () {
    'use strict';

    var SECURITY = '/api/security';
    var MIN = 4;
    var MAX = 8;
    var TITLES = { set: 'Set PIN', change: 'Change PIN', clear: 'Turn off PIN' };
    var ASKS = {
        current: 'Enter your current PIN.',
        pin: 'Enter a new PIN.',
        confirm: 'Enter it again.'
    };

    var flow = null;
    var at = null;
    var current = '';
    var chosen = '';
    var pad = null;
    var isSet = null;
    var open = document.getElementById('securityOpen');

    function pinState(set) {
        isSet = set;
        document.getElementById('pinState').textContent = set ? 'PIN set' : 'Off';
        open.textContent = set ? 'Change PIN' : 'Set PIN';
        open.disabled = false;
    }

    function load() {
        open.disabled = true;
        var xhr = new XMLHttpRequest();
        xhr.open('GET', SECURITY, true);
        xhr.timeout = 8000;
        xhr.onloadend = function () {
            if (xhr.status === 401) { location.replace('/access'); return; }
            if (xhr.status === 403) {
                document.getElementById('securityCard').hidden = true;
                return;
            }
            var payload = null;
            if (xhr.status === 200) {
                try { payload = JSON.parse(xhr.responseText); } catch (e) { payload = null; }
            }
            document.getElementById('securityCard').hidden = false;
            if (payload && typeof payload.set === 'boolean') {
                pinState(payload.set);
            } else {
                isSet = null;
                document.getElementById('pinState').textContent = 'Cannot load security';
                open.textContent = 'Retry';
                open.disabled = false;
            }
        };
        xhr.send();
    }

    function say(text) {
        document.getElementById('securitySub').textContent = text;
    }

    function closed(set) {
        document.getElementById('security').hidden = true;
        pinState(set);
    }

    function ask(step) {
        at = step;
        pad.clear();
        say(ASKS[step]);
    }

    function start(next) {
        if (!pad) pad = Strike.keypad(document.getElementById('securityPad'), MIN, MAX, submitted);
        flow = next;
        current = '';
        chosen = '';
        document.getElementById('securityTitle').textContent = TITLES[next];
        document.getElementById('pinClear').hidden = next !== 'change';
        document.getElementById('security').hidden = false;
        ask(next === 'set' ? 'pin' : 'current');
    }

    function body() {
        if (flow === 'clear') {
            return 'action=clear&current=' + encodeURIComponent(current);
        }
        var pins = 'pin=' + encodeURIComponent(chosen) + '&confirm=' + encodeURIComponent(chosen);
        if (flow === 'set') {
            return 'action=set&' + pins;
        }
        return 'action=change&current=' + encodeURIComponent(current) + '&' + pins;
    }

    function refused(xhr) {
        var payload = null;
        try {
            payload = JSON.parse(xhr.responseText);
        } catch (e) {
            payload = null;
        }
        var reason = payload ? payload.error : '';
        if (reason === 'wrong' || reason === 'locked') {
            ask('current');
            say(reason === 'wrong' ? 'Wrong PIN.' : 'Too many tries. Wait a moment.');
            return;
        }
        ask(flow === 'set' ? 'pin' : 'current');
        say('Strike could not save that.');
    }

    function send() {
        pad.busy(true);
        var xhr = new XMLHttpRequest();
        xhr.open('POST', SECURITY, true);
        xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
        xhr.onreadystatechange = function () {
            if (xhr.readyState !== 4) {
                return;
            }
            if (xhr.status === 200) {
                closed(flow !== 'clear');
                return;
            }
            refused(xhr);
        };
        xhr.send(body());
    }

    function submitted(digits) {
        if (at === 'current') {
            current = digits;
            if (flow === 'clear') {
                send();
                return;
            }
            ask('pin');
            return;
        }
        if (at === 'pin') {
            chosen = digits;
            ask('confirm');
            return;
        }
        if (digits !== chosen) {
            chosen = '';
            ask('pin');
            say('Those PINs do not match. Start again.');
            return;
        }
        send();
    }

    open.onclick = function () {
        if (isSet === null) { load(); return; }
        start(isSet ? 'change' : 'set');
    };

    document.getElementById('securityClose').onclick = function () {
        document.getElementById('security').hidden = true;
    };

    document.getElementById('pinClear').onclick = function () {
        start('clear');
    };

    load();
}());

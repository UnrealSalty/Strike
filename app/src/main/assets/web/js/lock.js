(function () {
    'use strict';

    var MIN = 4;
    var MAX = 8;
    var ASK = 'Unlock Strike to continue.';

    var sub = document.getElementById('lockSub');
    var pad = Strike.keypad(document.getElementById('lockPad'), MIN, MAX, unlock);

    function say(text) {
        sub.textContent = text;
    }

    function waitFor(ms) {
        if (ms <= 0) {
            say(ASK);
            return;
        }
        var seconds = Math.ceil(ms / 1000);
        say('Too many tries. Wait ' + seconds + ' s.');
    }

    function refused(xhr) {
        var payload = null;
        try {
            payload = JSON.parse(xhr.responseText);
        } catch (e) {
            payload = null;
        }
        if (payload && payload.error === 'locked') {
            waitFor(payload.lockoutMs);
            return;
        }
        say('Wrong PIN.');
    }

    function unlock(digits) {
        pad.busy(true);
        var xhr = new XMLHttpRequest();
        xhr.open('POST', '/api/security/unlock', true);
        xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
        xhr.onreadystatechange = function () {
            if (xhr.readyState !== 4) {
                return;
            }
            if (xhr.status === 200) {
                location.replace('/');
                return;
            }
            pad.clear();
            refused(xhr);
        };
        xhr.send('pin=' + encodeURIComponent(digits));
    }

    Strike.core.get('/api/status', function () {
        location.replace('/');
    }, function () {
        Strike.core.get('/api/security', function (payload) {
            if (payload.set !== true) {
                location.replace('/');
                return;
            }
            waitFor(payload.lockoutMs);
        }, function () {});
    });
}());

(function () {
    'use strict';

    window.Strike = window.Strike || {};

    var SETTINGS = '/api/recording/settings';
    var REFRESH_DELAY_MS = 500;
    var modal = document.getElementById('settings');
    var busy = false;
    var refreshTimer = null;

    function disableControls(disabled) {
        var controls = modal.querySelectorAll('button, input');
        for (var i = 0; i < controls.length; i++) {
            if (controls[i].id !== 'settingsClose') controls[i].disabled = disabled;
        }
    }

    function pending(active) {
        busy = active;
        modal.setAttribute('aria-busy', active ? 'true' : 'false');
        if (active) disableControls(true);
    }

    var storage = Strike.budget({
        location: 'storage.location',
        budget: 'storage.budgetMb',
        other: 'Surveillance',
        save: function (key, value) {
            save(key, value);
        }
    });

    function paintSwitch(toggle, on) {
        toggle.setAttribute('aria-pressed', on ? 'true' : 'false');
    }

    function paintApp(name, state, writable) {
        var cell = document.querySelector('[data-app="' + name + '"]');
        if (state === 'notInstalled') {
            cell.textContent = 'Not installed';
            return;
        }
        var toggle = cell.getElementsByTagName('button')[0];
        toggle.disabled = !writable;
        paintSwitch(toggle, state === 'enabled');
    }

    function paint(payload) {
        var segs = document.querySelectorAll('.seg[data-key]');
        for (var i = 0; i < segs.length; i++) {
            Strike.core.press(segs[i], payload.values[segs[i].getAttribute('data-key')]);
        }
        paintSwitch(document.querySelector('.switch[data-key="recording.audio"]'), payload.values['recording.audio']);
        storage.paint(payload);
        paintApp('dashcam', payload.bydApps.dashcam, payload.bydAppsWritable);
        paintApp('trafficMonitor', payload.bydApps.trafficMonitor, payload.bydAppsWritable);
    }

    function load() {
        if (modal.hidden || busy || refreshTimer !== null) return;
        pending(true);
        Strike.core.get(SETTINGS, function (payload) {
            pending(false);
            if (payload.pending === true) {
                if (!modal.hidden) {
                    modal.setAttribute('aria-busy', 'true');
                    refreshTimer = window.setTimeout(function () {
                        refreshTimer = null;
                        load();
                    }, REFRESH_DELAY_MS);
                }
                return;
            }
            disableControls(false);
            paint(payload);
        }, function () {
            pending(false);
            if (!modal.hidden) Strike.toast('Could not load recording settings', true);
        });
    }

    function post(path, body, success, failure) {
        if (busy) return;
        pending(true);
        Strike.core.post(path, body, function () {
            pending(false);
            load();
            if (success) Strike.toast(success);
        }, function () {
            pending(false);
            load();
            Strike.toast(failure, true);
        });
    }

    function save(key, value) {
        var body = 'key=' + encodeURIComponent(key) + '&value=' + encodeURIComponent(value);
        post(SETTINGS, body, 'Recording settings saved', 'Could not save recording settings');
    }

    function show(open) {
        modal.hidden = !open;
        if (open) {
            load();
        } else {
            window.clearTimeout(refreshTimer);
            refreshTimer = null;
            Strike.list.load(true);
        }
    }

    function wire() {
        var segs = document.querySelectorAll('.seg[data-key]');
        for (var i = 0; i < segs.length; i++) {
            segs[i].onclick = function (event) {
                var button = Strike.core.buttonIn(event, this);
                if (button) {
                    save(this.getAttribute('data-key'), button.getAttribute('data-value'));
                }
            };
        }

        var audio = document.querySelector('.switch[data-key="recording.audio"]');
        audio.onclick = function () {
            save('recording.audio', this.getAttribute('aria-pressed') === 'true' ? 'false' : 'true');
        };

        var apps = document.querySelectorAll('[data-app]');
        for (var a = 0; a < apps.length; a++) {
            apps[a].onclick = function (event) {
                var toggle = Strike.core.buttonIn(event, this);
                if (!toggle) {
                    return;
                }
                var on = toggle.getAttribute('aria-pressed') === 'true';
                var body = 'enabled=' + (on ? 'false' : 'true');
                post('/api/byd/' + this.getAttribute('data-app'), body, null, 'Could not change the factory app');
            };
        }

        document.getElementById('settingsOpen').onclick = function () {
            show(true);
        };
        document.getElementById('settingsClose').onclick = function () {
            show(false);
        };
        document.getElementById('settings').onclick = function (event) {
            if (event.target === this) {
                show(false);
            }
        };
    }

    Strike.settings = {
        load: load,
        close: function () {
            show(false);
        }
    };

    wire();
}());

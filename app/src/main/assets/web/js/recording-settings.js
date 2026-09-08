(function () {
    'use strict';

    window.Strike = window.Strike || {};

    var SETTINGS = '/api/recording/settings';

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
        Strike.core.get(SETTINGS, paint, function () {});
    }

    function save(key, value) {
        var body = 'key=' + encodeURIComponent(key) + '&value=' + encodeURIComponent(value);
        Strike.core.post(SETTINGS, body, function () {
            load();
            Strike.toast('Recording settings saved');
        }, function () { load(); Strike.toast('Could not save recording settings', true); });
    }

    function show(open) {
        document.getElementById('settings').hidden = !open;
        if (!open) {
            Strike.list.load();
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
                Strike.core.post('/api/byd/' + this.getAttribute('data-app'), body, load,
                    function () { load(); Strike.toast('Could not change the factory app', true); });
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
    load();
}());

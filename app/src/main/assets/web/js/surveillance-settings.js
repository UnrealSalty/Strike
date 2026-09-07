(function () {
    'use strict';

    window.Strike = window.Strike || {};

    var SETTINGS = '/api/surveillance/settings';
    var PREVIEW = '/api/surveillance/preview';
    var PREVIEW_NOTE = 'The car has to be on for a preview. Parked, the daemon draws it over a dark screen.';

    /** Overdrive's wording for the same five steps. */
    var NEAR = {
        1: 'Touching the car',
        2: 'Within about a metre',
        3: 'Within about 3 m',
        4: 'Within about 6 m',
        5: 'Anything in view'
    };

    var storage = Strike.budget({
        location: 'surveillance.location',
        budget: 'surveillance.budgetMb',
        other: 'Recordings',
        save: function (key, value) {
            save(key, value);
        }
    });

    function paintSwitch(key, on) {
        document.querySelector('.switch[data-key="' + key + '"]')
            .setAttribute('aria-pressed', on ? 'true' : 'false');
    }

    function paintProximity(value) {
        document.querySelector('.slider[data-key="surveillance.proximity"]').value = value;
        document.getElementById('proximity').textContent = NEAR[value];
    }

    /**
     * Continuous has nothing to detect, so proximity and the whole screen
     * message have nothing to act on either.
     */
    function paintMode(mode) {
        var smart = mode === 'smart';
        document.getElementById('proximityRow').hidden = !smart;
        document.getElementById('screenHead').hidden = !smart;
        document.getElementById('screenList').hidden = !smart;
        document.getElementById('previewNote').hidden = !smart;
    }

    function paint(payload) {
        var segs = document.querySelectorAll('.seg[data-key]');
        for (var i = 0; i < segs.length; i++) {
            Strike.core.press(segs[i], payload.values[segs[i].getAttribute('data-key')]);
        }
        paintSwitch('surveillance.enabled', payload.values['surveillance.enabled']);
        paintSwitch('surveillance.screen', payload.values['surveillance.screen']);
        paintProximity(payload.values['surveillance.proximity']);
        paintMode(payload.values['surveillance.mode']);
        var message = document.getElementById('message');
        if (document.activeElement !== message) {
            message.value = payload.values['surveillance.message'];
        }
        storage.paint(payload);
    }

    function load() {
        Strike.core.get(SETTINGS, paint, function () {});
    }

    // A rejected save must not leave the control showing a value the server did not take.
    function save(key, value) {
        var body = 'key=' + encodeURIComponent(key) + '&value=' + encodeURIComponent(value);
        Strike.core.post(SETTINGS, body, load, load);
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

        var switches = document.querySelectorAll('.switch[data-key]');
        for (var s = 0; s < switches.length; s++) {
            switches[s].onclick = function () {
                var on = this.getAttribute('aria-pressed') === 'true';
                save(this.getAttribute('data-key'), on ? 'false' : 'true');
            };
        }

        var proximity = document.querySelector('.slider[data-key="surveillance.proximity"]');
        proximity.oninput = function () {
            document.getElementById('proximity').textContent = NEAR[this.value];
        };
        proximity.onchange = function () {
            save('surveillance.proximity', this.value);
        };

        var message = document.getElementById('message');
        message.onchange = function () {
            if (this.value.replace(/^\s+|\s+$/g, '') === '') {
                load();
                return;
            }
            save('surveillance.message', this.value);
        };

        document.getElementById('preview').onclick = function () {
            var button = this;
            var note = document.getElementById('previewNote');
            button.disabled = true;
            Strike.core.post(PREVIEW, '', function () {
                button.disabled = false;
                note.textContent = PREVIEW_NOTE;
            }, function () {
                button.disabled = false;
                note.textContent = 'The camera daemon is not running, so there is nothing to draw the screen.';
            });
        };

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

window.Strike = window.Strike || {};

(function () {
    'use strict';

    var KEYS = ['1', '2', '3', '4', '5', '6', '7', '8', '9', 'del', '0', 'ok'];
    var LABELS = { del: 'Del', ok: 'OK' };

    // Use digit buttons to avoid opening the head unit's software keyboard.
    Strike.keypad = function (host, min, max, onSubmit) {
        var entered = '';
        var busy = false;
        var dots = Strike.core.el('div', 'dots');
        var pad = Strike.core.el('div', 'pad');

        dots.setAttribute('aria-hidden', 'true');
        for (var i = 0; i < KEYS.length; i++) {
            var key = Strike.core.el('button', 'pad__key', LABELS[KEYS[i]] || KEYS[i]);
            key.type = 'button';
            key.setAttribute('data-key', KEYS[i]);
            pad.appendChild(key);
        }
        host.appendChild(dots);
        host.appendChild(pad);

        function paint() {
            dots.innerHTML = '';
            var shown = min;
            if (entered.length >= max) {
                shown = max;
            } else if (entered.length >= min) {
                shown = entered.length + 1;
            }
            for (var j = 0; j < shown; j++) {
                var dot = Strike.core.el('span', 'dots__dot');
                if (j < entered.length) {
                    dot.setAttribute('data-on', 'true');
                }
                dots.appendChild(dot);
            }
        }

        pad.onclick = function (event) {
            var key = Strike.core.buttonIn(event, this);
            if (!key || busy) {
                return;
            }
            var value = key.getAttribute('data-key');
            if (value === 'ok') {
                if (entered.length >= min) {
                    onSubmit(entered);
                }
                return;
            }
            if (value === 'del') {
                entered = entered.slice(0, -1);
            } else if (entered.length < max) {
                entered += value;
            }
            paint();
        };

        paint();

        return {
            clear: function () {
                entered = '';
                busy = false;
                paint();
            },
            busy: function (on) {
                busy = on;
            }
        };
    };
}());

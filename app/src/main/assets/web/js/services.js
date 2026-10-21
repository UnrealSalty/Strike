(function () {
    'use strict';

    var STATES = '/api/byd';
    var WRITE_TIMEOUT_MS = 8000;
    var modal = document.getElementById('servicesModal');
    var open = document.getElementById('servicesOpen');
    var close = document.getElementById('servicesClose');
    var cells = modal.querySelectorAll('[data-app]');

    function toggles(disabled) {
        for (var i = 0; i < cells.length; i++) {
            var toggle = cells[i].getElementsByTagName('button')[0];
            if (toggle) toggle.disabled = disabled;
        }
    }

    function paint(payload) {
        for (var i = 0; i < cells.length; i++) {
            var state = payload.apps[cells[i].getAttribute('data-app')];
            if (state === 'notInstalled') {
                cells[i].textContent = 'Not installed';
                continue;
            }
            var toggle = cells[i].getElementsByTagName('button')[0];
            toggle.disabled = !payload.writable || state === 'unknown';
            toggle.setAttribute('aria-pressed', state === 'enabled' ? 'true' : 'false');
        }
    }

    function load() {
        toggles(true);
        Strike.core.get(STATES, paint, function () {
            Strike.toast('Could not load services', true);
        });
    }

    function hide() {
        modal.hidden = true;
        open.focus();
    }

    for (var i = 0; i < cells.length; i++) {
        cells[i].onclick = function (event) {
            var toggle = Strike.core.buttonIn(event, this);
            if (!toggle || toggle.disabled) return;
            var body = 'enabled=' + (toggle.getAttribute('aria-pressed') === 'true' ? 'false' : 'true');
            toggles(true);
            Strike.core.post('/api/byd/' + this.getAttribute('data-app'), body, load, function () {
                load();
                Strike.toast('Could not change that service', true);
            }, WRITE_TIMEOUT_MS);
        };
    }

    open.onclick = function () {
        modal.hidden = false;
        close.focus();
        load();
    };
    close.onclick = hide;
    modal.onclick = function (event) { if (event.target === modal) hide(); };
    modal.onkeydown = function (event) { if (event.key === 'Escape') hide(); };
}());

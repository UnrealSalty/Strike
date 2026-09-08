(function () {
    'use strict';

    var API = '/api/updates';
    var modal = document.getElementById('updates');
    var open = document.getElementById('updatesOpen');
    var close = document.getElementById('updatesClose');
    var check = document.getElementById('updateCheck');
    var apply = document.getElementById('updateApply');
    var message = document.getElementById('updateMessage');
    var state = null;
    var fresh = false;
    var pending = false;
    var polling = false;
    var confirming = false;
    var installing = false;
    var timer = null;
    var confirmTimer = null;
    var revision = 0;

    function version(value) { return value ? 'v' + value.replace(/^v/, '') : Strike.core.dash; }

    function checked(atMs) {
        if (!atMs) return null;
        var date = new Date(atMs);
        var months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
        return date.getDate() + ' ' + months[date.getMonth()] + ', ' +
            ('0' + date.getHours()).slice(-2) + ':' + ('0' + date.getMinutes()).slice(-2);
    }

    function status(payload) {
        if (payload.phase === 'checking') return 'Checking GitHub';
        if (payload.phase === 'downloading') return 'Downloading update';
        if (payload.phase === 'installing') return payload.message || 'Installing update. Strike will reconnect';
        if (payload.message) return payload.message;
        if (payload.ready) return 'Ready to install';
        if (payload.available) return 'Update available';
        return payload.latest ? 'Up to date' : Strike.core.dash;
    }

    function fingerprint(payload) {
        return [payload.phase, payload.checkedAtMs, payload.latest, payload.ready, payload.message].join('|');
    }

    function controls() {
        check.disabled = pending || !fresh || !state || state.busy || state.checkAfterMs > 0;
        check.textContent = state && state.phase === 'installing' && !state.busy ? 'Retry' : 'Check now';
        apply.disabled = pending || !fresh || !state || state.busy || state.phase === 'installing';
        apply.hidden = !state || !state.available;
        apply.textContent = confirming ? 'Confirm install' : state && state.ready ? 'Install update' : 'Download';
    }

    function paint(payload) {
        state = payload;
        installing = payload.phase === 'installing';
        fresh = true;
        Strike.core.value('updateCurrent', version(payload.current));
        Strike.core.value('updateLatest', version(payload.latest));
        Strike.core.value('updateChecked', checked(payload.checkedAtMs));
        document.getElementById('updateChecked').title = payload.checkedAtMs ? new Date(payload.checkedAtMs).toLocaleString() : '';
        document.getElementById('updateNotes').textContent = payload.notes || '';
        document.getElementById('updateNotesBox').hidden = !payload.notes;
        message.textContent = confirming ? 'Recording pauses during installation. Strike restarts afterward.' : status(payload);
        message.setAttribute('data-error', payload.failed ? 'true' : 'false');
        var downloading = payload.phase === 'downloading';
        document.getElementById('updateProgress').hidden = !downloading;
        var share = payload.totalBytes ? Math.min(1, payload.receivedBytes / payload.totalBytes) : 0;
        Strike.core.meter('updateFill', share * 100);
        document.getElementById('updateMeter').setAttribute('aria-valuenow', Math.floor(share * 100));
        document.getElementById('updateBytes').textContent = Strike.core.size(payload.receivedBytes) + ' / ' + Strike.core.size(payload.totalBytes);
        controls();
        summary(payload);
    }

    function summary(payload) {
        if (!payload) return;
        document.getElementById('updateSummary').textContent = payload.available ?
            (payload.ready ? 'Ready to install' : 'Update available') : version(payload.current);
        if (!modal.hidden && !pending && !polling && state && fingerprint(payload) !== fingerprint(state)) load();
    }

    function schedule() {
        clearTimeout(timer);
        if (modal.hidden || document.hidden) return;
        if (!fresh || state && state.busy) timer = setTimeout(load, 1000);
        else if (state && state.checkAfterMs > 0) timer = setTimeout(load, state.checkAfterMs + 100);
    }

    function reply(xhr) {
        if (xhr.status === 401) { location.replace('/access'); return null; }
        if (xhr.status === 403) { location.replace('/lock'); return null; }
        if (xhr.status !== 200) return null;
        try { return JSON.parse(xhr.responseText); } catch (e) { return null; }
    }

    function failed() {
        fresh = false;
        message.textContent = installing ?
            'Waiting for Strike to restart' : 'Cannot reach Strike';
        message.setAttribute('data-error', installing ? 'false' : 'true');
        if (!state) {
            Strike.core.value('updateCurrent', null);
            Strike.core.value('updateLatest', null);
            Strike.core.value('updateChecked', null);
        }
        controls();
    }

    function load() {
        if (polling || pending || modal.hidden || document.hidden) return;
        polling = true;
        var before = revision;
        var xhr = new XMLHttpRequest();
        xhr.open('GET', API, true);
        xhr.timeout = 8000;
        xhr.onloadend = function () {
            polling = false;
            if (before === revision && !pending) {
                var payload = reply(xhr);
                if (payload) paint(payload); else failed();
            }
            schedule();
        };
        xhr.send();
    }

    function post(action) {
        pending = true;
        if (action === 'install') installing = true;
        revision++;
        controls();
        var xhr = new XMLHttpRequest();
        xhr.open('POST', API, true);
        xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
        xhr.timeout = 8000;
        xhr.onloadend = function () {
            pending = false;
            var payload = reply(xhr);
            if (payload) paint(payload); else failed();
            schedule();
        };
        xhr.send('action=' + action + (action === 'install' ? '&confirmed=true' : ''));
    }

    function cancelConfirmation() {
        clearTimeout(confirmTimer);
        confirming = false;
        if (state) message.textContent = status(state);
        controls();
    }

    function hide() {
        modal.hidden = true;
        clearTimeout(timer);
        cancelConfirmation();
        open.focus();
    }

    open.onclick = function () {
        modal.hidden = false;
        fresh = false;
        controls();
        close.focus();
        load();
    };
    close.onclick = hide;
    modal.onclick = function (event) { if (event.target === modal) hide(); };
    document.addEventListener('keydown', function (event) {
        if (modal.hidden) return;
        if (event.key === 'Escape') { hide(); return; }
        if (event.key !== 'Tab') return;
        var buttons = modal.querySelectorAll('button:not([disabled]):not([hidden])');
        var first = buttons[0], last = buttons[buttons.length - 1];
        if (!modal.contains(document.activeElement)) { event.preventDefault(); first.focus(); }
        else if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    });
    check.onclick = function () { cancelConfirmation(); post('check'); };
    apply.onclick = function () {
        if (!state.ready) { post('download'); return; }
        if (confirming) { cancelConfirmation(); post('install'); return; }
        confirming = true;
        message.textContent = 'Recording pauses during installation. Strike restarts afterward.';
        controls();
        confirmTimer = setTimeout(cancelConfirmation, 10000);
    };
    document.addEventListener('visibilitychange', function () {
        clearTimeout(timer);
        cancelConfirmation();
        if (!document.hidden) load();
    });
    Strike.updates = { summary: summary };
}());

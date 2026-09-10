(function () {
    'use strict';

    window.Strike = window.Strike || {};

    var VALUE_IDS = ['soc', 'fuel', 'range', 'kwh'];
    var TEXT_IDS = ['vehicleState', 'usedSub', 'clipCount', 'eventCount', 'daemonCount', 'clipsToday'];
    var LOCATION = { internal: 'Internal storage', sd: 'SD card', usb: 'USB storage' };
    var EVENTS = { person: 'Person', vehicle: 'Vehicle', watch: 'Continuous', event: 'Movement' };
    var shownEvent = null;
    var requestedPreview = null;
    var image = document.getElementById('lastEventImage');
    var picture = document.getElementById('lastEventPicture');
    var empty = document.getElementById('lastEventEmpty');
    var updateModal = document.getElementById('updateNotice');
    var updateClose = document.getElementById('updateNoticeClose');
    var updateAccept = document.getElementById('updateNoticeAccept');
    var offeredUpdate = null;
    var requestingUpdate = false;
    var returnFocus = null;
    var announced = '';
    try { announced = sessionStorage.getItem('strike:update-notice') || ''; }
    catch (e) { announced = ''; }

    function hideUpdateNotice() {
        if (updateModal.hidden) return;
        updateModal.hidden = true;
        if (returnFocus) returnFocus.focus();
    }

    function updateNotice(update, cached) {
        if (cached) return;
        offeredUpdate = update;
        if (!update || !update.available || !update.latest || update.busy || update.phase === 'installing') {
            if (!requestingUpdate) hideUpdateNotice();
            return;
        }
        var key = update.current + ':' + update.latest;
        if (announced === key || document.hidden || Strike.session.leaving) return;
        announced = key;
        try { sessionStorage.setItem('strike:update-notice', key); }
        catch (e) { /* The notice still stays dismissed for this page. */ }
        document.getElementById('updateNoticeVersion').textContent =
            'Strike v' + update.latest.replace(/^v/, '') + ' is available.';
        document.getElementById('updateNoticeHint').textContent = update.ready ?
            'Downloaded and ready to install from Settings.' : 'Download now, then choose when to install.';
        returnFocus = document.activeElement;
        updateModal.hidden = false;
        updateClose.focus();
    }

    updateClose.onclick = hideUpdateNotice;
    updateModal.onclick = function (event) { if (event.target === updateModal) hideUpdateNotice(); };
    updateModal.onkeydown = function (event) {
        if (event.key === 'Escape') { event.preventDefault(); hideUpdateNotice(); }
        if (event.key === 'Tab') {
            event.preventDefault();
            if (updateAccept.disabled || document.activeElement !== updateClose) updateClose.focus();
            else updateAccept.focus();
        }
    };
    updateAccept.onclick = function () {
        if (requestingUpdate || !offeredUpdate || !offeredUpdate.available) return;
        if (offeredUpdate.ready) { location.assign('/settings#updates'); return; }
        requestingUpdate = true;
        updateAccept.disabled = true;
        var xhr = new XMLHttpRequest();
        xhr.open('POST', '/api/updates', true);
        xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
        xhr.timeout = 8000;
        xhr.onloadend = function () {
            requestingUpdate = false;
            updateAccept.disabled = false;
            if (xhr.status === 200) location.assign('/settings#updates');
            else if (xhr.status === 401) Strike.session.signIn();
            else if (xhr.status === 403) location.replace('/lock');
            else Strike.toast('Could not start the update download. Try again.', true);
        };
        xhr.send('action=download');
    };

    image.onload = function () {
        picture.removeAttribute('data-loading');
        image.hidden = false;
        empty.hidden = true;
    };
    image.onerror = function () {
        picture.removeAttribute('data-loading');
        image.hidden = true;
        empty.hidden = false;
    };

    function size(mb) {
        return mb < 1024 ? Math.round(mb) + ' MB' : (mb / 1024).toFixed(1) + ' GB';
    }

    function say(id, text) {
        document.getElementById(id).textContent = text === null ? Strike.core.dash : text;
    }

    function clips(count) {
        return count === 1 ? '1 clip' : count + ' clips';
    }

    function number(vehicle, key) {
        return vehicle && typeof vehicle[key] === 'number' ? vehicle[key] : null;
    }

    function socState(soc) {
        if (soc > 50) {
            return null;
        }
        return soc <= 20 ? 'bad' : 'warn';
    }

    // A car that burns fuel shows both levels, so the low battery colour would read as a fuel state.
    function gauge(soc, fuel) {
        var hybrid = fuel !== null;
        document.getElementById('fuelBox').hidden = !hybrid;
        document.getElementById('socSwatch').hidden = !hybrid;
        document.getElementById('vehicleMeter').className =
            hybrid ? 'meter card__meter meter--mix' : 'meter card__meter';
        Strike.core.meter('socFill', soc, (hybrid || soc === null) ? null : socState(soc));
        Strike.core.meter('fuelFill', hybrid ? fuel : null);
    }

    function range(vehicle) {
        var ev = number(vehicle, 'rangeKm');
        var fuelKm = number(vehicle, 'fuelRangeKm');
        if (ev === null && fuelKm === null) {
            return null;
        }
        return ((ev || 0) + (fuelKm || 0)) + ' km';
    }

    function battery(vehicle) {
        var soc = number(vehicle, 'soc');
        var kwh = number(vehicle, 'batteryKwh');
        var fuel = number(vehicle, 'fuelPercent');
        Strike.core.value('soc', soc === null ? null : soc + ' %');
        Strike.core.value('fuel', fuel === null ? null : fuel + ' %');
        Strike.core.value('range', range(vehicle));
        Strike.core.value('kwh', kwh === null ? null : kwh.toFixed(1) + ' kWh');
        gauge(soc, fuel);
        say('vehicleState', vehicle ? 'Vehicle connected' : 'Vehicle data unavailable');
    }

    function footage(storage) {
        if (!storage) {
            Strike.core.meter('usedFill', null);
            say('usedSub', null);
            say('clipCount', null);
            say('clipsToday', null);
            return;
        }
        var share = storage.budgetMb > 0 ? Math.round(storage.usedMb * 100 / storage.budgetMb) : 0;
        Strike.core.meter('usedFill', share, share >= 100 ? 'warn' : null);
        say('usedSub', size(storage.usedMb) + ' of ' + size(storage.budgetMb) + ' on ' + LOCATION[storage.location]);
        say('clipCount', clips(storage.clips));
        say('clipsToday', clips(storage.clipsToday));
    }

    function daemons(count) {
        var dot = document.getElementById('daemonDot');
        if (!count) {
            say('daemonCount', null);
            dot.removeAttribute('data-state');
            return;
        }
        say('daemonCount', count.running + ' of ' + count.total + ' running');
        dot.setAttribute('data-state', count.health);
    }

    function activity(event, recording, unknown) {
        var link = document.getElementById('lastEventLink');
        var preview = event && event.id + ':' + event.seen + ':' +
            !!(recording && recording.on && recording.clip === event.id);
        link.href = event ? '/surveillance#' + encodeURIComponent(event.id) : '/surveillance';
        picture.hidden = !event;
        if (!event) {
            shownEvent = null;
            image.removeAttribute('src');
            image.hidden = true;
            empty.hidden = true;
            picture.removeAttribute('data-loading');
        } else if (shownEvent !== event.id || (image.hidden && requestedPreview !== preview)) {
            shownEvent = event.id;
            requestedPreview = preview;
            image.hidden = true;
            empty.hidden = true;
            picture.setAttribute('data-loading', 'true');
            image.src = '/heroes/' + encodeURIComponent(event.id);
        }
        if (event) {
            say('lastEvent', (EVENTS[event.seen] || EVENTS[event.kind]) + ' \u00b7 ' + event.date + ' ' + event.time);
        } else {
            say('lastEvent', unknown ? null : 'Nothing seen while parked yet');
        }
    }

    Strike.dashboard = {
        render: function (status, cached) {
            var update = status.updates;
            updateNotice(update, cached);
            document.getElementById('updateLink').hidden = !update || !update.available;
            say('updateSummary', update && update.latest ? 'v' + update.latest.replace(/^v/, '') : null);
            battery(status.vehicle);
            footage(status.storage);
            say('eventCount', typeof status.eventCount === 'number' ? clips(status.eventCount) : null);
            daemons(status.daemons);
            activity(status.lastEvent, status.recording);
        },

        clear: function () {
            offeredUpdate = null;
            if (!requestingUpdate) hideUpdateNotice();
            for (var i = 0; i < VALUE_IDS.length; i++) {
                Strike.core.value(VALUE_IDS[i], null);
            }
            for (var j = 0; j < TEXT_IDS.length; j++) {
                say(TEXT_IDS[j], null);
            }
            gauge(null, null);
            Strike.core.meter('usedFill', null);
            document.getElementById('daemonDot').removeAttribute('data-state');
            activity(null, null, true);
        }
    };

    Strike.shell.start(Strike.dashboard.render, Strike.dashboard.clear);
}());

(function () {
    'use strict';

    window.Strike = window.Strike || {};

    var VALUE_IDS = ['soc', 'range', 'kwh'];
    var TEXT_IDS = ['vehicleState', 'usedSub', 'clipCount', 'eventCount', 'daemonCount', 'clipsToday'];
    var LOCATION = { internal: 'Internal storage', sd: 'SD card', usb: 'USB storage' };
    var EVENTS = { person: 'Person', vehicle: 'Vehicle', watch: 'Continuous', event: 'Movement' };
    var shownEvent = null;
    var requestedPreview = null;
    var image = document.getElementById('lastEventImage');
    var picture = document.getElementById('lastEventPicture');
    var empty = document.getElementById('lastEventEmpty');

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

    function battery(vehicle) {
        var soc = number(vehicle, 'soc');
        var rangeKm = number(vehicle, 'rangeKm');
        var kwh = number(vehicle, 'batteryKwh');
        Strike.core.value('soc', soc === null ? null : soc + ' %');
        Strike.core.value('range', rangeKm === null ? null : rangeKm + ' km');
        Strike.core.value('kwh', kwh === null ? null : kwh.toFixed(1) + ' kWh');
        Strike.core.meter('socFill', soc, soc === null ? null : socState(soc));
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
        render: function (status) {
            var update = status.updates;
            document.getElementById('updateLink').hidden = !update || !update.available;
            say('updateSummary', update && update.latest ? 'v' + update.latest.replace(/^v/, '') : null);
            battery(status.vehicle);
            footage(status.storage);
            say('eventCount', typeof status.eventCount === 'number' ? clips(status.eventCount) : null);
            daemons(status.daemons);
            activity(status.lastEvent, status.recording);
        },

        clear: function () {
            for (var i = 0; i < VALUE_IDS.length; i++) {
                Strike.core.value(VALUE_IDS[i], null);
            }
            for (var j = 0; j < TEXT_IDS.length; j++) {
                say(TEXT_IDS[j], null);
            }
            Strike.core.meter('socFill', null);
            Strike.core.meter('usedFill', null);
            document.getElementById('daemonDot').removeAttribute('data-state');
            activity(null, null, true);
        }
    };

    Strike.shell.start(Strike.dashboard.render, Strike.dashboard.clear);
}());

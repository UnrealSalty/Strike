(function () {
    'use strict';

    window.Strike = window.Strike || {};

    var VALUE_IDS = ['soc', 'range', 'kwh'];
    var TEXT_IDS = ['vehicleState', 'usedSub', 'clipCount', 'daemonCount', 'clipsToday', 'pinState'];
    var LOCATION = { internal: 'Internal storage', sd: 'SD card', usb: 'USB storage' };
    var EVENTS = { person: 'Person', vehicle: 'Vehicle', watch: 'Continuous', event: 'Movement' };

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

    function pin(set) {
        say('pinState', set ? 'PIN set' : 'Off');
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

    function activity(event) {
        var link = document.getElementById('lastEventLink');
        link.href = event ? 'surveillance.html#' + encodeURIComponent(event.id) : 'surveillance.html';
        link.setAttribute('data-empty', event ? 'false' : 'true');
        say('lastEvent', event
            ? (EVENTS[event.seen] || EVENTS[event.kind]) + ' \u00b7 ' + event.date + ' ' + event.time
            : null);
    }

    Strike.dashboard = {
        render: function (status) {
            battery(status.vehicle);
            footage(status.storage);
            daemons(status.daemons);
            pin(status.pinSet === true);
            activity(status.lastEvent);
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
            activity(null);
        }
    };

    Strike.shell.start(Strike.dashboard.render, Strike.dashboard.clear);
}());

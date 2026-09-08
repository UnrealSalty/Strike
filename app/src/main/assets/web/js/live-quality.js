window.Strike = window.Strike || {};

(function () {
    'use strict';

    var STORE = 'strike:live-quality';
    var OPTIONS = [
        { value: 'low', label: 'Low data' },
        { value: 'balanced', label: 'Balanced' },
        { value: 'high', label: 'High' }
    ];

    function remembered() {
        try {
            var saved = localStorage.getItem(STORE);
            return saved === 'low' || saved === 'balanced' ? saved : 'high';
        } catch (e) {
            return 'high';
        }
    }

    Strike.liveQuality = function (changed) {
        var host = document.getElementById('liveQuality');
        var selected = remembered();
        var canChange = false;
        var pick = Strike.pick('liveQuality', function (chosen) {
            if (chosen === selected) return;
            selected = chosen;
            try { localStorage.setItem(STORE, selected); } catch (e) { /* Selection still applies to this view. */ }
            changed();
        });

        pick.fill(OPTIONS);
        pick.select(selected);

        return {
            value: function () { return canChange ? selected : 'high'; },
            inCar: function (inCar) {
                var was = canChange;
                canChange = inCar === false;
                host.hidden = !canChange;
                if (!canChange) pick.close();
                if (was !== canChange && selected !== 'high') changed();
            }
        };
    };
}());

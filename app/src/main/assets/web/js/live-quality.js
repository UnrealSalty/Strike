window.Strike = window.Strike || {};

Strike.liveQuality = function (changed) {
    var host = document.getElementById('liveQuality');
    var selected = 'high';
    var canChange = false;
    try {
        var saved = localStorage.getItem('strike:live-quality');
        if (saved === 'low' || saved === 'balanced' || saved === 'high') selected = saved;
    } catch (e) { selected = 'high'; }

    Strike.core.press(host, selected);
    host.onclick = function (event) {
        var button = Strike.core.buttonIn(event, host);
        if (!canChange || !button) return;
        var quality = button.getAttribute('data-value');
        if (quality === selected) return;
        selected = quality;
        Strike.core.press(host, selected);
        try { localStorage.setItem('strike:live-quality', selected); } catch (e) { /* Selection still applies to this view. */ }
        changed();
    };
    return {
        value: function () { return canChange ? selected : 'high'; },
        inCar: function (inCar) {
            var was = canChange;
            canChange = inCar === false;
            document.getElementById('liveQualityRow').hidden = !canChange;
            if (was !== canChange && selected !== 'high') changed();
        }
    };
};

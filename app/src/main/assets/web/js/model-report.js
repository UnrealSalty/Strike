(function () {
    'use strict';

    var API = '/api/report';
    var POLL_MS = 500;
    var create = document.getElementById('reportCreate');
    var progress = document.getElementById('reportProgress');
    var step = document.getElementById('reportStep');
    var inCar = false;

    function show(done, of, label) {
        var share = of ? done / of * 100 : 0;
        Strike.core.meter('reportFill', share);
        document.getElementById('reportMeter').setAttribute('aria-valuenow', Math.floor(share));
        step.textContent = label;
    }

    function finish() {
        create.disabled = false;
        progress.hidden = true;
    }

    function failed() {
        finish();
        Strike.toast('Could not create the model report', true);
    }

    function deliver() {
        if (!inCar) {
            finish();
            location.href = API + '/file';
            return;
        }
        Strike.core.post(API + '/save', '', function (text) {
            var place = { internal: 'internal storage', sd: 'the SD card', usb: 'USB storage' }[JSON.parse(text).location];
            finish();
            Strike.toast('Model report saved to Strike/logs on ' + place);
        }, function () {
            finish();
            Strike.toast('Could not save the model report', true);
        });
    }

    function paint(payload) {
        if (payload.running) {
            create.disabled = true;
            progress.hidden = false;
            show(payload.step, payload.of, 'Step ' + (payload.step + 1) + ' of ' + payload.of + ': ' + payload.label);
            setTimeout(poll, POLL_MS);
            return;
        }
        if (create.disabled && payload.name) {
            show(payload.of, payload.of, 'Done');
            deliver();
        } else if (create.disabled) {
            failed();
        }
    }

    function poll() {
        Strike.core.get(API, paint, failed);
    }

    create.onclick = function () {
        create.disabled = true;
        Strike.core.post(API, '', function (text) { paint(JSON.parse(text)); }, failed, 8000);
    };

    Strike.core.get('/api/status', function (status) { inCar = status.inCar === true; }, function () {});
    Strike.core.get(API, function (payload) { if (payload.running) paint(payload); }, function () {});
}());

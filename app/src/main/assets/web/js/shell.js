(function () {
    'use strict';

    window.Strike = window.Strike || {};

    Strike.shell = {
        start: function (onStatus, onLost, cacheable) {
            Strike.core.poll('/api/status', 2000, function (status, cached) {
                if (onStatus) {
                    onStatus(status, cached);
                }
            }, function () {
                if (onLost) {
                    onLost();
                }
            }, cacheable);
        }
    };
}());

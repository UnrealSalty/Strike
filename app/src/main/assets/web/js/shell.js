(function () {
    'use strict';

    window.Strike = window.Strike || {};

    Strike.shell = {
        start: function (onStatus, onLost) {
            Strike.core.poll('/api/status', 2000, function (status) {
                if (onStatus) {
                    onStatus(status);
                }
            }, function () {
                if (onLost) {
                    onLost();
                }
            });
        }
    };
}());

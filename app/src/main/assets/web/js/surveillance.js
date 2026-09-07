(function () {
    'use strict';

    window.Strike = window.Strike || {};

    Strike.list = Strike.library({
        rows: '/api/surveillance/events',
        list: 'events',
        thumbs: '/heroes/',
        media: '/events/',
        one: 'event',
        many: 'events',
        tags: {
            person: 'Person',
            vehicle: 'Vehicle',
            watch: 'Continuous',
            event: 'Movement'
        },
        tag: function (row) {
            return row.seen || row.kind;
        },
        elsewhere: 'Choose another place to keep events in settings.',
        nothing: function (anyEvents, payload) {
            if (anyEvents) {
                return { head: 'No events match', note: 'Clear the search or pick another day.' };
            }
            if (payload && !payload.enabled) {
                return { head: 'Surveillance is off', note: 'Turn it on in settings and the car is watched once you switch it off.' };
            }
            if (payload && payload.mode === 'continuous') {
                return { head: 'No events yet', note: 'The tape starts the next time the car is parked.' };
            }
            return { head: 'No events yet', note: 'A person or a vehicle coming close is recorded here.' };
        }
    });

    Strike.list.load();
    Strike.shell.start();
}());

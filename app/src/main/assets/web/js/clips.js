(function () {
    'use strict';

    window.Strike = window.Strike || {};

    Strike.list = Strike.library({
        rows: '/api/recording/clips',
        list: 'clips',
        thumbs: '/thumbs/',
        media: '/clips/',
        one: 'clip',
        many: 'clips',
        // Parked has no chip. The name still reads if an old clip is sitting there.
        tags: { drive: 'Drive', manual: 'Manual', parked: 'Parked' },
        elsewhere: 'Choose another place to record to in settings.',
        nothing: function (anyClips, payload) {
            if (anyClips) {
                return { head: 'No clips match', note: 'Clear the search or pick another day.' };
            }
            if (payload && payload.mode === 'off') {
                return { head: 'Recording is off', note: 'Turn it on in settings and clips will land here.' };
            }
            return { head: 'No clips yet', note: 'Clips appear here as they are recorded.' };
        }
    });

    Strike.list.load();
    Strike.shell.start();
}());

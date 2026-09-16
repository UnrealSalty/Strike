'use strict';

var assert = require('node:assert/strict');
var harness = require('./library-harness');

function ready(times) {
    return { mounted: true, location: 'sd', mode: 'continuous', clips: times.map(function (time, index) {
        return { id: 'clip-' + index, date: '2026-09-16', time: time, kind: 'drive', bytes: 1048576 };
    }) };
}
var checks = 0;
function check(name, run) { run(); checks++; console.log('PASS ' + name); }

check('first-load failure recovers without navigating or reloading the page', function () {
    var app = harness();
    app.gets()[0].respond(503);
    assert.match(app.nodes.clips.textContent, /Cannot reach Strike/);
    assert.equal(Object.keys(app.timers).length, 1);
    app.retry(1000);
    app.gets()[1].respond(200, ready(['12:00']));
    assert.match(app.nodes.clips.textContent, /12:00/);
    assert.doesNotMatch(app.nodes.clips.textContent, /Cannot reach Strike/);
    assert.equal(Object.keys(app.timers).length, 0);
});

check('initial pending load keeps its spinner and coalesces requests until ready', function () {
    var app = harness();
    app.load();
    app.load();
    assert.equal(app.gets().length, 1);
    app.gets()[0].respond(200, { pending: true });
    assert.match(app.nodes.clips.textContent, /Loading recordings/);
    app.load();
    assert.equal(app.gets().length, 2);
    assert.equal(Object.keys(app.timers).length, 0);
    app.load();
    assert.equal(app.gets().length, 2);
    app.gets()[1].respond(200, { pending: true });
    app.retry();
    app.gets()[2].respond(200, ready(['12:00']));
    assert.match(app.nodes.clips.textContent, /12:00/);
    assert.doesNotMatch(app.nodes.clips.textContent, /Loading recordings/);
    assert.equal(app.nodes.count.textContent, '1 clip');
    assert.equal(Object.keys(app.timers).length, 0);
});

check('warm rows remain visible during refresh and removal is shown when ready', function () {
    var app = harness();
    app.gets()[0].respond(200, ready(['12:00', '12:02']));
    var before = app.nodes.clips.children.slice();
    app.load();
    app.gets()[1].respond(200, { pending: true });
    assert.deepEqual(app.nodes.clips.children, before);
    assert.equal(app.nodes.count.textContent, '2 clips');
    app.retry();
    app.gets()[2].respond(200, { mounted: false, location: 'sd', clips: [] });
    assert.match(app.nodes.clips.textContent, /No SD card/);
    assert.doesNotMatch(app.nodes.clips.textContent, /12:00/);
});

check('hidden pages cancel pending polling and resume only pending work on visibility', function () {
    var app = harness();
    app.gets()[0].respond(200, { pending: true });
    app.visibility(true);
    assert.equal(Object.keys(app.timers).length, 0);
    app.load();
    assert.equal(app.gets().length, 1);
    app.visibility(false);
    assert.equal(app.gets().length, 2);
    app.visibility(false);
    assert.equal(app.gets().length, 2);
    app.visibility(true);
    app.gets()[1].respond(200, { pending: true });
    assert.equal(Object.keys(app.timers).length, 0);
    app.visibility(false);
    app.gets()[2].respond(200, ready(['12:04']));
    app.visibility(true);
    app.visibility(false);
    assert.equal(app.gets().length, 3);
});

check('pagehide cancels a pending retry and pageshow resumes it once', function () {
    var app = harness();
    app.gets()[0].respond(200, { pending: true });
    app.event('pagehide');
    assert.equal(Object.keys(app.timers).length, 0);
    app.event('pageshow');
    assert.equal(app.gets().length, 2);
    app.event('pageshow');
    assert.equal(app.gets().length, 2);
    app.gets()[1].respond(200, ready([]));
    assert.match(app.nodes.clips.textContent, /No clips yet/);
});

check('a scan failure backs off and then resumes pending scan handling', function () {
    var app = harness();
    app.gets()[0].respond(200, { pending: true });
    app.retry();
    app.gets()[1].respond(500);
    assert.match(app.nodes.clips.textContent, /Cannot reach Strike/);
    app.retry(1000);
    app.gets()[2].respond(200, { pending: true });
    app.retry();
    app.gets()[3].respond(200, ready(['12:00']));
    assert.match(app.nodes.clips.textContent, /12:00/);
    assert.equal(Object.keys(app.timers).length, 0);
});

check('deleting a row stays removed while its post-delete refresh is pending', function () {
    var app = harness();
    app.gets()[0].respond(200, ready(['12:00', '12:02']));
    var bin = app.nodes.clips.getElementsByTagName('button').filter(function (node) { return node.getAttribute('aria-label') === 'Delete'; })[0];
    bin.onclick();
    bin.onclick();
    assert.doesNotMatch(app.nodes.clips.textContent, /12:00/);
    assert.match(app.nodes.clips.textContent, /12:02/);
    var deletion = app.requests.filter(function (request) { return request.method === 'DELETE'; })[0];
    assert.equal(deletion.url, '/api/recording/clips/clip-0');
    deletion.respond(200, {});
    app.gets()[1].respond(200, { pending: true });
    assert.doesNotMatch(app.nodes.clips.textContent, /12:00/);
    app.retry();
    app.gets()[2].respond(200, ready(['12:02']));
    assert.equal(app.nodes.count.textContent, '1 clip');
});

check('a deletion during an in-flight list read cannot restore the deleted row', function () {
    var app = harness();
    app.gets()[0].respond(200, ready(['12:00', '12:02']));
    app.load();
    var bin = app.nodes.clips.getElementsByTagName('button').filter(function (node) { return node.getAttribute('aria-label') === 'Delete'; })[0];
    bin.onclick();
    bin.onclick();
    app.requests.filter(function (request) { return request.method === 'DELETE'; })[0].respond(200, {});
    app.gets()[1].respond(200, ready(['12:00', '12:02']));
    assert.equal(app.gets().length, 3, 'Post-delete refresh must not be lost behind an earlier read');
    app.gets()[2].respond(200, ready(['12:02']));
    assert.doesNotMatch(app.nodes.clips.textContent, /12:00/);
    assert.equal(app.nodes.count.textContent, '1 clip');
});

check('repeated forced refreshes replace an obsolete failed read with one fresh request', function () {
    var app = harness();
    app.gets()[0].respond(200, ready(['12:00']));
    app.load();
    app.load(true);
    app.load(true);
    assert.equal(app.gets().length, 2);
    app.gets()[1].respond(500);
    assert.equal(app.gets().length, 3);
    assert.match(app.nodes.clips.textContent, /12:00/);
    assert.doesNotMatch(app.nodes.clips.textContent, /Cannot reach Strike/);
    app.load();
    assert.equal(app.gets().length, 3);
    app.gets()[2].respond(200, ready(['12:02']));
    assert.match(app.nodes.clips.textContent, /12:02/);
    assert.doesNotMatch(app.nodes.clips.textContent, /12:00/);
    assert.equal(Object.keys(app.timers).length, 0);
});

check('repainted rows retain their thumbnail request and receive duration and codec', function () {
    var app = harness();
    app.gets()[0].respond(200, ready(['12:00']));
    var old = app.nodes.clips.getElementsByClassName('clip__shot')[0];
    app.nodes.search.value = '';
    app.nodes.search.oninput();
    var thumbnails = app.requests.filter(function (xhr) { return xhr.url.indexOf('/thumbs/') === 0; });
    assert.equal(thumbnails.length, 1);
    thumbnails[0].headers = { 'X-Clip-Duration-Ms': '125000', 'X-Clip-Codec': 'h265' };
    thumbnails[0].respond(200, {});
    var current = app.nodes.clips.getElementsByClassName('clip__shot')[0];
    assert.notEqual(current, old);
    assert.equal(old.getElementsByTagName('img').length, 0);
    assert.equal(current.getElementsByTagName('img')[0].src, 'blob:1');
    assert.match(current.textContent, /2:05/);
    assert.match(app.nodes.clips.textContent, /H\.265/);
    app.visibility(true);
    app.visibility(false);
    assert.equal(current.getElementsByTagName('img').length, 1);
});

check('paging and filtering abort obsolete thumbnails while keeping the request bound', function () {
    var app = harness(), times = [];
    for (var i = 0; i < 25; i++) times.push('12:' + (i < 10 ? '0' : '') + i);
    app.gets()[0].respond(200, ready(times));
    var thumbs = app.requests.filter(function (xhr) { return xhr.url.indexOf('/thumbs/') === 0; });
    assert.equal(thumbs.length, 2);
    assert.equal(app.nodes.clips.getElementsByClassName('clip').length, 20);
    app.nodes.clips.getElementsByTagName('button').filter(function (node) { return node.textContent === 'Next'; })[0].onclick();
    assert.ok(thumbs[0].aborted && thumbs[1].aborted);
    assert.equal(app.nodes.clips.getElementsByClassName('clip').length, 5);
    var active = app.requests.filter(function (xhr) { return xhr.url.indexOf('/thumbs/') === 0 && !xhr.aborted; });
    assert.equal(active.length, 2);
    assert.match(active[0].url, /clip-20$/);
    app.nodes.search.value = 'nothing matches';
    app.nodes.search.oninput();
    assert.ok(active[0].aborted && active[1].aborted);
    active[0].respond(200, {});
    assert.equal(app.nodes.clips.getElementsByTagName('img').length, 0);
});

check('storage revision and location changes revoke same-id cached thumbnails', function () {
    var app = harness(), payload = ready(['12:00']);
    payload.storageRevision = 1;
    app.gets()[0].respond(200, payload);
    app.requests.filter(function (xhr) { return xhr.url.indexOf('/thumbs/') === 0; })[0].respond(200, {});
    app.load();
    payload.storageRevision = 2;
    app.gets()[1].respond(200, payload);
    assert.deepEqual(app.revoked, ['blob:1']);
    var thumbs = app.requests.filter(function (xhr) { return xhr.url.indexOf('/thumbs/') === 0; });
    assert.equal(thumbs.length, 2);
    thumbs[1].respond(200, {});
    app.load();
    payload.location = 'usb';
    app.gets()[2].respond(200, payload);
    assert.deepEqual(app.revoked, ['blob:1', 'blob:2']);
    assert.match(app.nodes.clips.textContent, /USB/);
    app.load();
    app.gets()[3].respond(200, { mounted: false, location: 'usb', clips: [] });
    assert.equal(app.requests.filter(function (xhr) { return xhr.url.indexOf('/thumbs/') === 0; })[2].aborted, true);
    assert.match(app.nodes.clips.textContent, /No USB storage/);
});

check('pagehide cancels an in-flight list read and stale replies cannot repaint', function () {
    var app = harness();
    var obsolete = app.gets()[0].onreadystatechange;
    app.event('pagehide');
    assert.equal(app.gets()[0].aborted, true);
    app.gets()[0].respond(200, ready(['12:00']));
    obsolete();
    assert.equal(app.requests.length, 1);
    assert.doesNotMatch(app.nodes.clips.textContent, /12:00/);
    app.event('pageshow');
    app.gets()[1].respond(200, ready(['12:02']));
    assert.match(app.nodes.clips.textContent, /12:02/);
    app.requests[2].respond(200, {});
    assert.equal(app.nodes.clips.getElementsByTagName('img').length, 1);
});

check('temporary refresh failure preserves the current rows, count and thumbnails', function () {
    var app = harness();
    app.gets()[0].respond(200, ready(['12:00', '12:02']));
    var before = app.nodes.clips.children.slice();
    app.load();
    app.gets()[1].respond(500);
    assert.deepEqual(app.nodes.clips.children, before);
    assert.equal(app.nodes.count.textContent, '2 clips');
    assert.equal(app.retryButton().hidden, false);
    app.retry(1000);
    app.gets()[2].respond(200, ready(['12:04']));
    assert.match(app.nodes.clips.textContent, /12:04/);
    assert.doesNotMatch(app.nodes.clips.textContent, /12:00/);
    assert.equal(app.retryButton().hidden, true);
});

check('timeout and malformed JSON both recover through the existing request timeout', function () {
    [0, 200].forEach(function (status) {
        var app = harness();
        assert.equal(app.gets()[0].timeout, 8000);
        app.gets()[0].respond(status);
        assert.match(app.nodes.clips.textContent, /Cannot reach Strike/);
        app.retry(1000);
        app.gets()[1].respond(200, ready(['12:00']));
        assert.match(app.nodes.clips.textContent, /12:00/);
    });
});

check('repeated failures stop after five retries and manual Retry starts a new attempt', function () {
    var app = harness();
    app.gets()[0].respond(500);
    [1000, 2000, 4000, 8000, 8000].forEach(function (delay, index) {
        app.retry(delay);
        assert.equal(app.retryButton().disabled, true);
        app.gets()[index + 1].respond(500);
    });
    assert.equal(Object.keys(app.timers).length, 0);
    assert.equal(app.gets().length, 6);
    app.visibility(true);
    app.visibility(false);
    assert.equal(app.gets().length, 6);
    assert.equal(app.retryButton().hidden, false);
    assert.equal(app.retryButton().disabled, false);
    app.retryButton().onclick();
    app.gets()[6].respond(500);
    app.retry(1000);
    app.gets()[7].respond(200, ready([]));
    assert.match(app.nodes.clips.textContent, /No clips yet/);
    assert.equal(app.retryButton().hidden, true);
    app.load();
    app.gets()[8].respond(500);
    app.retry(1000);
    app.gets()[9].respond(200, ready(['12:10']));
    assert.match(app.nodes.clips.textContent, /12:10/);
});

check('hidden recovery pauses timers, aborts requests and resumes without duplicates', function () {
    var app = harness();
    app.gets()[0].respond(503);
    app.visibility(true);
    assert.equal(Object.keys(app.timers).length, 0);
    app.load();
    assert.equal(app.gets().length, 1);
    app.visibility(false);
    app.visibility(false);
    assert.equal(app.gets().length, 2);
    app.visibility(true);
    assert.equal(app.gets()[1].aborted, true);
    app.gets()[1].respond(500);
    assert.equal(Object.keys(app.timers).length, 0);
    app.visibility(false);
    app.gets()[2].respond(200, ready(['12:00']));
    assert.match(app.nodes.clips.textContent, /12:00/);
    app.visibility(true);
    app.visibility(false);
    assert.equal(app.gets().length, 3);
});

check('authentication expiry ends recovery without more requests or retries', function () {
    var app = harness();
    app.gets()[0].respond(500);
    app.retry(1000);
    app.gets()[1].respond(401);
    assert.equal(app.redirects(), 1);
    assert.equal(Object.keys(app.timers).length, 0);
    app.load(true);
    app.visibility(true);
    app.visibility(false);
    app.event('pageshow');
    assert.equal(app.gets().length, 2);
});

check('an explicit settings refresh cancels backoff and resets the retry delay', function () {
    var app = harness();
    app.gets()[0].respond(500);
    app.retry(1000);
    app.gets()[1].respond(500);
    app.load(true);
    assert.equal(Object.keys(app.timers).length, 0);
    app.gets()[2].respond(500);
    app.retry(1000);
    app.gets()[3].respond(200, ready(['12:00']));
    assert.match(app.nodes.clips.textContent, /12:00/);
});

console.log(checks + ' library checks passed');

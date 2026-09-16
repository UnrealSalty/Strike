'use strict';

var assert = require('node:assert/strict');
var fs = require('node:fs');
var path = require('node:path');
var vm = require('node:vm');
var web = path.join(process.argv[2] || path.join(__dirname, '..'), 'app/src/main/assets/web/js');
var source = fs.readFileSync(path.join(web, 'thumbnail-loader.js'), 'utf8');

function harness(base) {
    var now = 0, nextTimer = 0, nextBlob = 0, peak = 0, redirects = 0;
    var timers = {}, requests = [], painted = [], created = [], revoked = [], nodes = {};
    var events = { window: {}, document: {} };
    function later(callback, delay) {
        var id = ++nextTimer;
        timers[id] = { callback: callback, at: now + delay };
        return id;
    }
    function clear(id) { delete timers[id]; }
    function node(id) {
        if (!nodes[id]) nodes[id] = {
            hidden: true, attributes: {}, focus: function () {},
            setAttribute: function (key, value) { this.attributes[key] = value; },
            removeAttribute: function (key) { delete this.attributes[key]; if (key === 'src') this.src = ''; }
        };
        return nodes[id];
    }
    function listen(owner, name, callback) {
        var list = events[owner][name] || (events[owner][name] = []);
        list.push(callback);
    }
    function fire(owner, name) {
        (events[owner][name] || []).forEach(function (callback) { callback({ persisted: true }); });
    }
    function active() { return requests.filter(function (xhr) { return xhr.sent && !xhr.done && !xhr.aborted; }); }
    function Xhr() { requests.push(this); }
    Xhr.prototype.open = function (method, url) { this.method = method; this.url = url; };
    Xhr.prototype.getResponseHeader = function (name) { return this.headers && this.headers[name] || null; };
    Xhr.prototype.send = function () {
        var xhr = this;
        this.sent = true;
        peak = Math.max(peak, active().length);
        assert.ok(peak <= 2, 'Thumbnail requests must leave server workers available');
        this.expiry = later(function () { xhr.respond(0); }, this.timeout);
    };
    Xhr.prototype.abort = function () { this.aborted = true; clear(this.expiry); };
    Xhr.prototype.respond = function (status, headers) {
        this.done = true;
        clear(this.expiry);
        this.status = status;
        this.headers = headers || {};
        this.response = { image: this.url };
        this.readyState = 4;
        if (this.onreadystatechange) this.onreadystatechange();
    };
    var context = {
        Date: { now: function () { return now; } },
        XMLHttpRequest: Xhr, setTimeout: later, clearTimeout: clear,
        sessionStorage: { getItem: function () { return null; }, setItem: function () {} },
        addEventListener: function (name, callback) { listen('window', name, callback); },
        URL: {
            createObjectURL: function () { var url = 'blob:' + (++nextBlob); created.push(url); return url; },
            revokeObjectURL: function (url) { revoked.push(url); }
        },
        document: {
            hidden: false, getElementById: node,
            addEventListener: function (name, callback) { listen('document', name, callback); }
        },
        Strike: {
            core: { dash: '\u2014', value: function () {}, meter: function () {} },
            shell: { start: function () {} },
            session: { signIn: function () { redirects++; } }
        }
    };
    context.window = context;
    vm.createContext(context);
    vm.runInContext(source, context);
    var loader = context.Strike.thumbnails(base || '/thumbs/', function (shot, target) {
        target.shot = shot;
        painted.push({ shot: shot, target: target });
    });
    return {
        loader: loader, requests: requests, painted: painted, created: created, revoked: revoked,
        context: context, nodes: nodes, active: active,
        redirects: function () { return redirects; },
        peak: function () { return peak; },
        tick: function (ms) {
            var until = now + ms, loops = 0;
            while (true) {
                var ids = Object.keys(timers).filter(function (id) { return timers[id].at <= until; });
                ids.sort(function (a, b) { return timers[a].at - timers[b].at; });
                if (!ids.length) break;
                assert.ok(++loops < 1000, 'Timer loop must remain bounded');
                var timer = timers[ids[0]];
                delete timers[ids[0]];
                now = timer.at;
                timer.callback();
            }
            now = until;
        },
        visibility: function (hidden) { context.document.hidden = hidden; fire('document', 'visibilitychange'); },
        event: function (name) { fire('window', name); }
    };
}

function entry(id, identity) { return { id: id, identity: identity || '1', nodes: { id: id } }; }
function entries(from, count) {
    var list = [];
    for (var i = from; i < from + count; i++) list.push(entry('clip-' + i));
    return list;
}
function drain(app) { while (app.active().length) app.active()[0].respond(200); }
function pendingFor(app, ms, status) {
    for (var elapsed = 0; elapsed < ms; elapsed += 1000) {
        while (app.active().length) app.active()[0].respond(status, { 'Retry-After': '1' });
        app.tick(1000);
    }
}
var checks = 0;
function check(name, run) { run(); checks++; console.log('PASS ' + name); }

check('a full page fills all posters with at most two requests in flight', function () {
    var app = harness(), page = entries(0, 20);
    app.loader.show(page);
    assert.equal(app.requests.length, 2);
    drain(app);
    assert.equal(app.requests.length, 20);
    assert.equal(app.peak(), 2);
    page.forEach(function (row) { assert.ok(row.nodes.shot.url); });
});

check('repainting the same row shares its request and paints only its newest nodes', function () {
    var app = harness(), first = entry('a'), latest = entry('a');
    app.loader.show([first]);
    app.loader.show([latest, latest]);
    assert.equal(app.requests.length, 1);
    app.requests[0].respond(200, { 'X-Clip-Duration-Ms': '123456', 'X-Clip-Codec': 'h265' });
    assert.equal(first.nodes.shot, undefined);
    assert.equal(latest.nodes.shot.durationMs, 123456);
    assert.equal(latest.nodes.shot.codec, 'h265');
    app.loader.show([first]);
    assert.equal(first.nodes.shot.url, latest.nodes.shot.url);
    assert.equal(app.requests.length, 1);
});

check('pending heroes fill on retry without a page reload or placeholder flash', function () {
    var app = harness('/heroes/'), row = entry('event with spaces');
    app.loader.show([row]);
    assert.equal(app.requests[0].url, '/heroes/event%20with%20spaces');
    app.requests[0].respond(202, { 'Retry-After': '1' });
    assert.equal(row.nodes.shot, undefined);
    app.tick(999);
    assert.equal(app.requests.length, 1);
    app.tick(1);
    app.requests[1].respond(200);
    assert.ok(row.nodes.shot.url);
});

check('busy and transport failures back off while honoring Retry-After', function () {
    var app = harness(), row = entry('a');
    app.loader.show([row]);
    app.requests[0].respond(503, { 'Retry-After': '3' });
    app.tick(2999);
    assert.equal(app.requests.length, 1);
    app.tick(1);
    app.requests[1].respond(0);
    app.tick(1999);
    assert.equal(app.requests.length, 2);
    app.tick(1);
    app.requests[2].respond(200);
    assert.ok(row.nodes.shot.url);
});

check('unreadable clips settle without polling and may retry on a later paint', function () {
    var app = harness(), row = entry('a');
    app.loader.show([row]);
    app.requests[0].respond(404);
    assert.equal(row.nodes.shot.url, null);
    assert.equal(app.loader.peek('a', '1'), null);
    app.tick(60000);
    assert.equal(app.requests.length, 1);
    app.loader.show([row]);
    app.requests[1].respond(200);
    assert.ok(row.nodes.shot.url);
});

check('expired authentication aborts other work and redirects once', function () {
    var app = harness();
    app.loader.show(entries(0, 20));
    app.requests[0].respond(401);
    assert.equal(app.requests[1].aborted, true);
    app.requests[1].respond(401);
    app.loader.show(entries(20, 20));
    app.event('pageshow');
    app.tick(60000);
    assert.equal(app.redirects(), 1);
    assert.equal(app.requests.length, 2);
    assert.equal(app.painted.length, 0);
});

check('rapid paging drops queued and active old rows and ignores late replies', function () {
    var app = harness(), old = entries(0, 20), next = entries(20, 20);
    app.loader.show(old);
    app.requests[0].respond(202);
    app.loader.show(next);
    assert.equal(app.requests[1].aborted, true);
    app.requests[1].respond(200);
    drain(app);
    assert.equal(old[1].nodes.shot, undefined);
    assert.equal(app.painted.length, 20);
    assert.ok(next[19].nodes.shot.url);
});

check('deletion cancels an active poster and revokes any cached blob', function () {
    var app = harness(), rows = entries(0, 3);
    app.loader.show(rows);
    app.requests[0].respond(200);
    var url = rows[0].nodes.shot.url;
    app.loader.forget('clip-0');
    app.loader.forget('clip-1');
    app.requests[1].respond(200);
    drain(app);
    assert.ok(app.revoked.indexOf(url) >= 0);
    assert.equal(rows[1].nodes.shot, undefined);
    assert.equal(app.loader.peek('clip-0', '1'), null);
});

check('hidden pages cancel jobs, retain ready blobs and resume only the latest rows', function () {
    var app = harness(), rows = entries(0, 4);
    app.loader.show(rows);
    app.requests[0].respond(200);
    app.visibility(true);
    var count = app.requests.length;
    app.loader.show([rows[0], rows[3]]);
    app.event('pageshow');
    app.tick(60000);
    assert.equal(app.requests.length, count);
    assert.equal(app.revoked.length, 0);
    app.visibility(false);
    assert.equal(app.requests.length, count + 1);
    assert.match(app.active()[0].url, /clip-3$/);
    app.active()[0].respond(200);
    assert.ok(rows[3].nodes.shot.url);
});

check('pagehide releases blobs and prevents late repaint requests until pageshow', function () {
    var app = harness(), row = entry('a');
    app.loader.show([row]);
    app.requests[0].respond(200);
    app.event('pagehide');
    assert.deepEqual(app.revoked, app.created);
    app.loader.show([entry('b')]);
    app.tick(60000);
    assert.equal(app.requests.length, 1);
    app.event('pageshow');
    assert.match(app.active()[0].url, /\/b$/);
    app.active()[0].respond(200);
    assert.equal(app.painted[1].target.id, 'b');
});

check('pending and busy show a placeholder at thirty seconds and stop polling at two minutes', function () {
    [202, 503].forEach(function (status) {
        var app = harness(), row = entry('a');
        app.loader.show([row]);
        pendingFor(app, 30000, status);
        assert.equal(row.nodes.shot.url, null);
        var latest = entry('a');
        app.loader.show([latest]);
        assert.equal(latest.nodes.shot.url, null);
        var count = app.requests.length;
        app.tick(4999);
        assert.equal(app.requests.length, count);
        app.tick(1);
        assert.equal(app.requests.length, count + 1);
        pendingFor(app, 85000, status);
        assert.equal(app.active().length, 0);
        count = app.requests.length;
        app.tick(60000);
        assert.equal(app.requests.length, count);
        app.loader.show([row]);
        app.active()[0].respond(200);
        assert.ok(row.nodes.shot.url);
    });
});

check('twenty serial two-second cold thumbnails all fill after the initial deadline without repaint', function () {
    var app = harness(), rows = entries(0, 20);
    app.loader.show(rows);
    for (var elapsed = 0; elapsed <= 45000; elapsed += 1000) {
        while (app.active().length) {
            var xhr = app.active()[0];
            var readyAt = (Number(xhr.url.split('clip-')[1]) + 1) * 2000;
            xhr.respond(elapsed >= readyAt ? 200 : readyAt > elapsed + 18000 ? 503 : 202);
        }
        app.tick(1000);
    }
    rows.forEach(function (row) { assert.ok(row.nodes.shot.url); });
    assert.equal(app.created.length, 20);
});

check('slow pending work is canceled while hidden and obsolete rows never resume', function () {
    var app = harness(), row = entry('a');
    app.loader.show([row]);
    pendingFor(app, 30000, 202);
    app.visibility(true);
    var count = app.requests.length;
    app.loader.show([entry('b')]);
    app.tick(120000);
    assert.equal(app.requests.length, count);
    app.visibility(false);
    assert.match(app.active()[0].url, /\/b$/);
    app.active()[0].respond(200);
    assert.equal(app.painted[app.painted.length - 1].target.id, 'b');
});

check('unreadable and transport failures terminate a slow pending attempt', function () {
    [404, 0, 500].forEach(function (status) {
        var app = harness(), row = entry('a');
        app.loader.show([row]);
        pendingFor(app, 30000, 202);
        app.tick(5000);
        app.active()[0].respond(status);
        var count = app.requests.length;
        app.tick(120000);
        assert.equal(app.requests.length, count);
        assert.equal(row.nodes.shot.url, null);
    });
});

check('hanging requests release their slots and settle by the same deadline', function () {
    var app = harness(), rows = entries(0, 20);
    app.loader.show(rows);
    app.tick(30000);
    assert.equal(app.active().length, 0);
    rows.forEach(function (row) { assert.equal(row.nodes.shot.url, null); });
    assert.ok(app.requests.length < 20, 'A stalled page must not keep flooding unreadable clips');
});

check('successful blob cache is bounded and retains recently visible posters', function () {
    var app = harness();
    for (var i = 0; i < 40; i++) { app.loader.show([entry('clip-' + i)]); drain(app); }
    app.loader.show([entry('clip-0')]);
    app.loader.show([entry('clip-40')]);
    drain(app);
    assert.equal(app.created.length - app.revoked.length, 40);
    assert.ok(app.loader.peek('clip-0', '1'));
    assert.equal(app.loader.peek('clip-1', '1'), null);
    app.loader.clear();
    assert.equal(app.created.length, app.revoked.length);
    app.event('pageshow');
    assert.equal(app.active().length, 0);
});

check('changed file identity invalidates ready and in-flight images for a reused id', function () {
    var app = harness(), original = entry('same', '1'), replacement = entry('same', '2');
    app.loader.show([original]);
    app.loader.show([replacement]);
    assert.equal(app.requests[0].aborted, true);
    app.requests[0].respond(200);
    app.requests[1].respond(200);
    assert.equal(original.nodes.shot, undefined);
    assert.equal(app.loader.peek('same', '1'), null);
    app.loader.show([entry('same', '3')]);
    assert.equal(app.revoked.length, 1);
    app.active()[0].respond(200);
    assert.ok(app.loader.peek('same', '3'));
});

check('dashboard unknown counts stay unknown and pending latest-event previews recover', function () {
    var app = harness();
    vm.runInContext(fs.readFileSync(path.join(web, 'dashboard.js'), 'utf8'), app.context);
    var render = app.context.Strike.dashboard.render;
    render({ eventCount: null });
    assert.equal(app.nodes.lastEvent.textContent, '\u2014');
    render({ eventCount: 0 });
    assert.equal(app.nodes.lastEvent.textContent, 'Nothing seen while parked yet');
    var status = { eventCount: 1, lastEvent: { id: 'event-1', seen: 'person', date: '2026-09-16', time: '12:00' } };
    render(status);
    render(status);
    assert.equal(app.requests.length, 1);
    app.requests[0].respond(202);
    app.tick(1000);
    app.requests[1].respond(200);
    app.nodes.lastEventImage.onload();
    assert.equal(app.nodes.lastEventImage.hidden, false);
    assert.match(app.nodes.lastEventImage.src, /^blob:/);
    status.recording = { on: true, clip: 'event-1' };
    render(status);
    assert.equal(app.requests.length, 3);
    app.context.Strike.dashboard.clear();
    assert.equal(app.requests[2].aborted, true);
    app.requests[2].respond(200);
    assert.equal(app.nodes.lastEventPicture.hidden, true);
    assert.equal(app.nodes.lastEvent.textContent, '\u2014');
});

console.log(checks + ' thumbnail checks passed');

'use strict';

var assert = require('node:assert/strict');
var fs = require('node:fs');
var path = require('node:path');
var vm = require('node:vm');
var web = path.join(__dirname, '../app/src/main/assets/web/js');
var liveSource = fs.readFileSync(path.join(web, 'live.js'), 'utf8');
var fmp4Source = fs.readFileSync(path.join(web, 'fmp4.js'), 'utf8');
var sps = [
    0x67, 0x64, 0x00, 0x28, 0xAC, 0xD9, 0x40, 0x78,
    0x02, 0x27, 0xE5, 0x84, 0x00, 0x00, 0x03, 0x00,
    0x04, 0x00, 0x00, 0x03, 0x00, 0xCA, 0x3C, 0x60,
    0xC6, 0x58
];
var pps = [0x68, 0xCE, 0x06, 0xE2];
var key = [0x65, 11, 22];
var delta = [0x41, 33, 44];

function annex(nals) {
    var bytes = [];
    nals.forEach(function (nal) { bytes = bytes.concat([0, 0, 0, 1], nal); });
    return bytes;
}

function packet(flags, timeUs, nals) {
    var body = annex(nals);
    var bytes = new Uint8Array(9 + body.length);
    var header = new DataView(bytes.buffer);
    bytes[0] = flags;
    header.setUint32(1, Math.floor(timeUs / 4294967296));
    header.setUint32(5, timeUs >>> 0);
    bytes.set(body, 9);
    return bytes.buffer;
}

function Element(tag) {
    this.tagName = tag;
    this.childNodes = [];
    this.attributes = {};
    this.style = {};
    this.clientWidth = 1280;
    this.clientHeight = 720;
    this.readyState = 0;
    this.pauses = 0;
    this.loads = 0;
}
Element.prototype.appendChild = function (node) { this.childNodes.push(node); return node; };
Element.prototype.setAttribute = function (key, value) { this.attributes[key] = value; };
Element.prototype.getAttribute = function (key) { return this.attributes[key]; };
Element.prototype.removeAttribute = function (key) { delete this.attributes[key]; };
Element.prototype.pause = function () { this.pauses++; };
Element.prototype.load = function () { this.loads++; };
Object.defineProperty(Element.prototype, 'innerHTML', {
    set: function () { this.childNodes = []; }
});

function harness(webcodecs, deferCameras) {
    var nodes = {};
    var cameraRequests = [];
    var events = {};
    var sockets = [];
    var sources = [];
    var sinks = [];
    var revoked = [];
    var timers = new Map();
    var nextTimer = 0;
    var qualityChanged;
    function node(id) {
        if (!nodes[id]) nodes[id] = new Element(id);
        return nodes[id];
    }
    function Socket(url) {
        this.url = url;
        this.closes = 0;
        sockets.push(this);
    }
    Socket.prototype.close = function () {
        this.closes++;
        if (this.onclose) this.onclose();
    };
    function Source() {
        this.readyState = 'closed';
        this.events = {};
        sources.push(this);
    }
    Source.prototype.addEventListener = function (name, callback) { this.events[name] = callback; };
    Source.prototype.open = function () {
        this.readyState = 'open';
        this.events.sourceopen();
    };
    function sink(branch, size, onFail) {
        var fresh = {
            branch: branch,
            size: size,
            frames: [],
            closes: 0,
            fail: onFail,
            push: function (nals, keyFrame, timeUs) {
                this.frames.push({
                    nals: Array.from(nals, function (nal) { return Array.from(nal); }),
                    keyFrame: keyFrame,
                    timeUs: timeUs
                });
            },
            close: function () { this.closes++; }
        };
        sinks.push(fresh);
        return fresh;
    }
    var context = {
        Uint8Array: Uint8Array,
        ArrayBuffer: ArrayBuffer,
        DataView: DataView,
        WebSocket: Socket,
        MediaSource: Source,
        location: { protocol: 'https:', host: 'strike.test' },
        document: {
            getElementById: node,
            createElementNS: function (_, tag) { return new Element(tag); }
        },
        URL: {
            createObjectURL: function () { return 'blob:camera-' + sources.length; },
            revokeObjectURL: function (url) { revoked.push(url); }
        },
        setTimeout: function (callback, delay) {
            var id = ++nextTimer;
            timers.set(id, { callback: callback, delay: delay });
            return id;
        },
        clearTimeout: function (id) { timers.delete(id); },
        requestAnimationFrame: function () { throw new Error('unexpected camera-picker animation'); },
        cancelAnimationFrame: function () {},
        addEventListener: function (name, callback) {
            if (!events[name]) events[name] = [];
            events[name].push(callback);
        },
        Strike: {
            core: {
                get: function (url, success, failure) {
                    assert.equal(url, '/api/live/cameras');
                    cameraRequests.push({ success: success, failure: failure });
                    if (!deferCameras) success({ cameras: [{ tag: 'all', width: 5120, height: 960 }] });
                },
                el: function (tag, className, text) {
                    var element = new Element(tag);
                    element.className = className;
                    element.textContent = text;
                    return element;
                },
                value: function () {},
                meter: function () {}
            },
            liveQuality: function (onChange) {
                qualityChanged = onChange;
                return { value: function () { return 'balanced'; }, inCar: function () {} };
            },
            shell: { start: function () {} },
            webcodecs: {
                supported: function () { return webcodecs; },
                open: function (_, readSps, readPps, size, onDraw, onFail) {
                    assert.deepEqual(Array.from(readSps), sps);
                    assert.deepEqual(Array.from(readPps), pps);
                    return sink('webcodecs', size, onFail);
                }
            },
            liveMse: {
                open: function (_, video, size, readSps, readPps, onFail) {
                    assert.deepEqual(Array.from(readSps), sps);
                    assert.deepEqual(Array.from(readPps), pps);
                    return sink('mse', size, onFail);
                }
            }
        }
    };
    context.window = context;
    vm.createContext(context);
    vm.runInContext(fmp4Source, context);
    vm.runInContext(liveSource, context);
    return {
        sockets: sockets,
        sources: sources,
        cameraRequests: cameraRequests,
        camerasReady: function (index) {
            cameraRequests[index].success({ cameras: [{ tag: 'all', width: 5120, height: 960 }] });
        },
        camerasFailed: function (index) { cameraRequests[index].failure(); },
        sinks: sinks,
        timers: timers,
        revoked: revoked,
        node: node,
        changeQuality: function () { qualityChanged(); },
        fire: function (name, event) {
            (events[name] || []).forEach(function (callback) { callback(event || {}); });
        },
        runRetry: function () {
            assert.equal(timers.size, 1);
            var id = Array.from(timers.keys())[0];
            var pending = timers.get(id);
            assert.equal(pending.delay, 2000);
            timers.delete(id);
            pending.callback();
        },
        configure: function () {
            var current = sockets[sockets.length - 1];
            current.onmessage({ data: packet(3, 0, [sps, pps]) });
            if (!webcodecs) sources[sources.length - 1].open();
            return sinks[sinks.length - 1];
        }
    };
}

var checks = 0;
function test(name, run) {
    run();
    checks++;
    console.log('ok ' + name);
}

[true, false].forEach(function (webcodecs) {
    var branch = webcodecs ? 'WebCodecs' : 'MSE';

    test(branch + ' ignores a camera response received after pagehide', function () {
        var run = harness(webcodecs, true);
        assert.equal(run.cameraRequests.length, 1);
        assert.equal(run.sockets.length, 0);
        run.fire('pagehide');
        run.camerasReady(0);
        assert.equal(run.sockets.length, 0, 'camera response opened a stream on a hidden page');
        assert.equal(run.sinks.length, 0);
        assert.equal(run.node('spots').childNodes.length, 0);
    });

    test(branch + ' persisted pageshow requests fresh cameras before reconnecting', function () {
        var run = harness(webcodecs, true);
        run.fire('pagehide');
        run.fire('pageshow', { persisted: true });
        assert.equal(run.cameraRequests.length, 2);
        assert.equal(run.sockets.length, 0);
        run.camerasReady(0);
        assert.equal(run.sockets.length, 0);
        run.camerasReady(1);
        assert.equal(run.sockets.length, 1);
        assert.equal(run.node('spots').childNodes.length, 5);
    });

    test(branch + ' an old camera response cannot open a second restored stream', function () {
        var run = harness(webcodecs, true);
        run.fire('pagehide');
        run.fire('pageshow', { persisted: true });
        run.camerasReady(1);
        var output = run.configure();
        run.camerasReady(0);
        assert.equal(run.sockets.length, 1);
        assert.equal(run.sockets[0].closes, 0);
        assert.equal(output.closes, 0);
        assert.equal(run.node('spots').childNodes.length, 5);
    });

    test(branch + ' an old camera request failure cannot replace the restored camera view', function () {
        var run = harness(webcodecs, true);
        run.fire('pagehide');
        run.fire('pageshow', { persisted: true });
        run.camerasReady(1);
        var before = run.node('idle').childNodes.map(function (child) { return child.textContent; });
        run.camerasFailed(0);
        assert.equal(run.sockets.length, 1);
        assert.equal(run.sockets[0].closes, 0);
        assert.equal(run.node('spots').childNodes.length, 5);
        assert.deepEqual(run.node('idle').childNodes.map(function (child) { return child.textContent; }), before);
    });

    test(branch + ' keeps the 64-bit packet timestamp and excludes codec configuration', function () {
        var run = harness(webcodecs);
        var socket = run.sockets[0];
        assert.equal(socket.url, 'wss://strike.test/live/stream?view=all&quality=balanced');
        assert.equal(socket.binaryType, 'arraybuffer');
        var output = run.configure();
        assert.equal(output.branch, webcodecs ? 'webcodecs' : 'mse');
        assert.equal(output.size.width, 1920);
        assert.equal(output.size.height, 1080);
        assert.equal(output.frames.length, 0);
        var timeUs = 9876543210123;
        socket.onmessage({ data: packet(1, timeUs, [sps, pps, key]) });
        socket.onmessage({ data: packet(0, timeUs + 66667, [delta]) });
        assert.deepEqual(output.frames, [
            { nals: [key], keyFrame: true, timeUs: timeUs },
            { nals: [delta], keyFrame: false, timeUs: timeUs + 66667 }
        ]);
        socket.onmessage({ data: packet(2, timeUs + 100000, [sps, pps]) });
        assert.equal(output.frames.length, 2);
    });

    test(branch + ' ignores truncated packets without changing the decoder', function () {
        var run = harness(webcodecs);
        var output = run.configure();
        [0, 1, 8, 9].forEach(function (length) {
            run.sockets[0].onmessage({ data: new ArrayBuffer(length) });
        });
        assert.equal(output.frames.length, 0);
        assert.equal(output.closes, 0);
        assert.equal(run.timers.size, 0);
    });

    test(branch + ' schedules one reconnect and ignores old socket callbacks', function () {
        var run = harness(webcodecs);
        var output = run.configure();
        var previous = run.sockets[0];
        var lateClose = previous.onclose;
        var lateError = previous.onerror;
        var lateMessage = previous.onmessage;
        lateClose();
        lateError();
        lateClose();
        assert.equal(run.timers.size, 1);
        assert.equal(previous.closes, 1);
        assert.equal(output.closes, 1);
        assert.equal(previous.onmessage, null);
        assert.equal(previous.onclose, null);
        assert.equal(previous.onerror, null);
        run.runRetry();
        var replacement = run.configure();
        var current = run.sockets[1];
        lateClose();
        lateError();
        lateMessage({ data: packet(1, 9876543210123, [key]) });
        assert.equal(run.timers.size, 0);
        assert.equal(current.closes, 0);
        assert.equal(replacement.closes, 0);
        assert.equal(replacement.frames.length, 0);
    });

    test(branch + ' pagehide closes the active stream and persisted pageshow reconnects', function () {
        var run = harness(webcodecs);
        var output = run.configure();
        run.fire('pagehide');
        assert.equal(run.sockets[0].closes, 1);
        assert.equal(output.closes, 1);
        assert.equal(run.timers.size, 0);
        assert.equal(run.node('video').pauses, 1);
        assert.equal(run.node('video').loads, 1);
        assert.equal(run.revoked.length, webcodecs ? 0 : 1);
        run.fire('pageshow', { persisted: false });
        assert.equal(run.sockets.length, 1);
        run.fire('pageshow', { persisted: true });
        assert.equal(run.sockets.length, 2);
        var replacement = run.configure();
        run.sockets[1].onmessage({ data: packet(1, 9876543210123, [key]) });
        assert.equal(replacement.frames.length, 1);
        assert.equal(replacement.closes, 0);
    });

    test(branch + ' pagehide cancels reconnect including a late cancelled timer callback', function () {
        var run = harness(webcodecs);
        run.configure();
        run.sockets[0].onerror();
        assert.equal(run.timers.size, 1);
        var lateTimer = Array.from(run.timers.values())[0].callback;
        run.fire('pagehide');
        assert.equal(run.timers.size, 0);
        lateTimer();
        assert.equal(run.sockets.length, 1);
        run.fire('pageshow', { persisted: true });
        assert.equal(run.sockets.length, 2);
        lateTimer();
        assert.equal(run.sockets.length, 2);
        assert.equal(run.timers.size, 0);
    });
});

test('MSE ignores a stale sourceopen after quality changes', function () {
    var run = harness(false);
    var previous = run.sources[0];
    run.changeQuality();
    assert.equal(run.sockets.length, 2);
    run.sockets[1].onmessage({ data: packet(2, 0, [sps, pps]) });
    assert.equal(run.sinks.length, 0);
    previous.open();
    assert.equal(run.sinks.length, 0);
    run.sources[1].open();
    assert.equal(run.sinks.length, 1);
    assert.equal(run.sockets[1].closes, 0);
});

console.log(checks + ' live page integration checks passed');

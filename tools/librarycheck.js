'use strict';

var assert = require('node:assert/strict');
var fs = require('node:fs');
var path = require('node:path');
var vm = require('node:vm');
var root = process.argv[2] || path.join(__dirname, '..');
var web = path.join(root, 'app/src/main/assets/web/js');
var library = fs.readFileSync(process.argv[3] || path.join(web, 'library.js'), 'utf8');
var thumbs = fs.readFileSync(path.join(process.argv[3] ? path.dirname(process.argv[3]) : web, 'thumbnail-loader.js'), 'utf8');

function Element(tag) {
    this.tagName = tag.toUpperCase();
    this.children = [];
    this.attributes = {};
    this.style = {};
    this.className = '';
    this.hidden = false;
    this.disabled = false;
    this.value = '';
    this.classList = { add: function () {}, remove: function () {} };
}
Element.prototype.appendChild = function (child) { child.parentNode = this; this.children.push(child); return child; };
Element.prototype.insertBefore = function (child, before) { child.parentNode = this; this.children.splice(this.children.indexOf(before), 0, child); };
Object.defineProperty(Element.prototype, 'firstChild', { get: function () { return this.children[0] || null; } });
Element.prototype.setAttribute = function (key, value) { this.attributes[key] = String(value); };
Element.prototype.getAttribute = function (key) { return this.attributes[key] || null; };
Element.prototype.removeAttribute = function (key) { delete this.attributes[key]; };
Element.prototype.addEventListener = function () {};
Element.prototype.find = function (predicate) {
    var found = [];
    this.children.forEach(function (child) {
        if (predicate(child)) found.push(child);
        found = found.concat(child.find(predicate));
    });
    return found;
};
Element.prototype.getElementsByTagName = function (tag) { return this.find(function (node) { return node.tagName === tag.toUpperCase(); }); };
Element.prototype.getElementsByClassName = function (name) { return this.find(function (node) { return node.className.split(' ').indexOf(name) >= 0; }); };
Object.defineProperty(Element.prototype, 'innerHTML', { set: function () { this.children = []; } });
Object.defineProperty(Element.prototype, 'textContent', {
    get: function () { return (this.text || '') + this.children.map(function (child) { return child.textContent; }).join(''); },
    set: function (value) { this.text = String(value); this.children = []; }
});

function harness() {
    var nodes = {};
    var requests = [];
    var revoked = [];
    var nextBlob = 0;
    var timers = {};
    var nextTimer = 0;
    var windowEvents = {};
    var documentEvents = {};
    function node(id) {
        if (!nodes[id]) nodes[id] = new Element('div');
        return nodes[id];
    }
    function listen(events, name, callback) {
        if (!events[name]) events[name] = [];
        events[name].push(callback);
    }
    function fire(events, name, event) {
        (events[name] || []).forEach(function (callback) { callback(event || {}); });
    }
    node('clips').className = 'clips clips--wait';
    node('clips').appendChild(new Element('p')).textContent = 'Loading recordings';
    var dayButton = node('day').appendChild(new Element('button'));
    dayButton.appendChild(new Element('span')).textContent = 'All days';
    node('day').appendChild(new Element('div')).className = 'pick__menu';
    function Xhr() { requests.push(this); }
    Xhr.prototype.open = function (method, url) { this.method = method; this.url = url; };
    Xhr.prototype.send = function () {};
    Xhr.prototype.abort = function () { this.aborted = true; };
    Xhr.prototype.getResponseHeader = function (key) { return this.headers && this.headers[key] || null; };
    Xhr.prototype.respond = function (status, body) {
        this.status = status;
        this.response = body;
        this.responseText = JSON.stringify(body);
        this.readyState = 4;
        if (this.onreadystatechange) this.onreadystatechange();
    };
    var context = {
        XMLHttpRequest: Xhr,
        location: { hash: '' },
        URL: {
            createObjectURL: function () { return 'blob:' + (++nextBlob); },
            revokeObjectURL: function (url) { revoked.push(url); }
        },
        scrollTo: function () {},
        setTimeout: function (callback, delay) {
            var id = ++nextTimer;
            timers[id] = { callback: callback, delay: delay };
            return id;
        },
        clearTimeout: function (id) { delete timers[id]; },
        addEventListener: function (name, callback) { listen(windowEvents, name, callback); },
        document: {
            hidden: false,
            documentElement: new Element('html'),
            addEventListener: function (name, callback) { listen(documentEvents, name, callback); },
            getElementById: node,
            createElement: function (tag) { return new Element(tag); },
            createElementNS: function (_, tag) { return new Element(tag); },
            createTextNode: function (text) { var element = new Element('#text'); element.textContent = text; return element; }
        },
        Strike: {
            native: { available: function () { return false; } },
            settings: { load: function () {}, close: function () {} },
            shell: { start: function () {} },
            toast: function () {},
            session: { leaving: false, signIn: function () { throw new Error('Unexpected auth redirect'); } }
        }
    };
    context.window = context;
    vm.createContext(context);
    vm.runInContext(fs.readFileSync(path.join(web, 'core.js'), 'utf8'), context);
    vm.runInContext(thumbs, context);
    vm.runInContext(library, context);
    vm.runInContext(fs.readFileSync(path.join(web, 'clips.js'), 'utf8'), context);
    return {
        nodes: nodes, timers: timers, requests: requests, revoked: revoked,
        load: function (force) { context.Strike.list.load(force); },
        gets: function () { return requests.filter(function (request) { return request.method === 'GET' && request.url === '/api/recording/clips'; }); },
        retry: function () {
            var ids = Object.keys(timers);
            assert.equal(ids.length, 1);
            var timer = timers[ids[0]];
            assert.equal(timer.delay, 500);
            delete timers[ids[0]];
            timer.callback();
        },
        visibility: function (hidden) {
            context.document.hidden = hidden;
            fire(documentEvents, 'visibilitychange');
        },
        event: function (name) { fire(windowEvents, name, { persisted: true }); }
    };
}

function ready(times) {
    return { mounted: true, location: 'sd', mode: 'continuous', clips: times.map(function (time, index) {
        return { id: 'clip-' + index, date: '2026-09-16', time: time, kind: 'drive', bytes: 1048576 };
    }) };
}
var checks = 0;
function check(name, run) { run(); checks++; console.log('PASS ' + name); }

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

check('a failed pending request keeps the existing error behavior without retrying', function () {
    var app = harness();
    app.gets()[0].respond(200, { pending: true });
    app.retry();
    app.gets()[1].respond(500);
    assert.match(app.nodes.clips.textContent, /Cannot reach Strike/);
    assert.equal(Object.keys(app.timers).length, 0);
    app.visibility(true);
    app.visibility(false);
    app.event('pageshow');
    assert.equal(app.gets().length, 2);
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

check('a list response after pagehide paints no new thumbnail request until pageshow', function () {
    var app = harness();
    app.event('pagehide');
    app.gets()[0].respond(200, ready(['12:00']));
    assert.equal(app.requests.length, 1);
    assert.match(app.nodes.clips.textContent, /12:00/);
    app.event('pageshow');
    assert.equal(app.requests.length, 2);
    app.requests[1].respond(200, {});
    assert.equal(app.nodes.clips.getElementsByTagName('img').length, 1);
});

console.log(checks + ' library checks passed');

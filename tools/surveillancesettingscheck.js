'use strict';

var assert = require('node:assert/strict');
var fs = require('node:fs');
var path = require('node:path');
var vm = require('node:vm');
var sourcePath = process.argv[2] || path.join(__dirname, '../app/src/main/assets/web/js/surveillance-settings.js');
var source = fs.readFileSync(sourcePath, 'utf8');
var coreSource = fs.readFileSync(path.join(path.dirname(sourcePath), 'core.js'), 'utf8');

function harness() {
    var nodes = {};
    var requests = [];
    var timers = {};
    var nextTimer = 0;
    var now = 0;
    var redirects = 0;
    var events = { window: {}, document: {} };
    function listen(owner, name, callback) {
        var callbacks = events[owner][name] || (events[owner][name] = []);
        callbacks.push(callback);
    }
    function fire(owner, name) {
        (events[owner][name] || []).forEach(function (callback) { callback({ persisted: true }); });
    }
    function node(key) {
        if (!nodes[key]) nodes[key] = {
            id: key, hidden: false, disabled: false, value: '', textContent: '', attributes: {},
            setAttribute: function (name, value) { this.attributes[name] = value; },
            getAttribute: function (name) { return this.attributes[name]; },
            getElementsByTagName: function () { return this.children || []; }
        };
        return nodes[key];
    }
    var modal = node('settings');
    modal.hidden = true;
    var mode = node('mode');
    mode.setAttribute('data-key', 'surveillance.mode');
    mode.children = [node('modeButton')];
    node('modeButton').setAttribute('data-value', 'smart');
    var enabled = node('surveillance.enabled');
    var screen = node('surveillance.screen');
    enabled.setAttribute('data-key', 'surveillance.enabled');
    screen.setAttribute('data-key', 'surveillance.screen');
    var controls = [node('modeButton'), enabled, screen, node('surveillance.proximity'), node('message'), node('preview'), node('surveillance.budgetMb'), node('settingsClose')];
    modal.querySelectorAll = function () { return controls; };
    function Xhr() { this.timeout = 0; requests.push(this); }
    Xhr.prototype.open = function (method, url) { this.method = method; this.url = url; };
    Xhr.prototype.setRequestHeader = function () {};
    Xhr.prototype.send = function (body) { this.body = body; this.started = now; };
    Xhr.prototype.abort = function () { this.aborted = true; };
    Xhr.prototype.respond = function (status, body) {
        this.status = status;
        this.responseText = JSON.stringify(body);
        this.readyState = 4;
        if (this.onreadystatechange) this.onreadystatechange();
    };
    Xhr.prototype.success = function (body) { this.respond(200, body); };
    Xhr.prototype.failure = function () { this.respond(500); };
    var context = {
        XMLHttpRequest: Xhr,
        addEventListener: function (name, callback) { listen('window', name, callback); },
        setTimeout: function (callback, delay) { var id = ++nextTimer; timers[id] = { callback: callback, delay: delay }; return id; },
        clearTimeout: function (id) { delete timers[id]; },
        document: {
            hidden: false,
            documentElement: { classList: { add: function () {}, remove: function () {} } },
            activeElement: null,
            addEventListener: function (name, callback) { listen('document', name, callback); },
            getElementById: node,
            querySelectorAll: function (selector) {
                if (selector === '.seg[data-key]') return [mode];
                if (selector === '.switch[data-key]') return [enabled, screen];
                throw new Error('Unexpected selector ' + selector);
            },
            querySelector: function (selector) {
                var key = /data-key="([^"]+)"/.exec(selector);
                assert(key, 'Selector owns a setting');
                return node(key[1]);
            }
        },
        Strike: {
            budget: function () { return { paint: function (payload) { node('budget').textContent = String(payload.values['surveillance.budgetMb']); } }; },
            list: { load: function (force) { assert.equal(force, true); } }, toast: function () {},
            session: { signIn: function () { redirects++; } }
        }
    };
    context.window = context;
    vm.createContext(context);
    vm.runInContext(coreSource, context);
    vm.runInContext(source, context);
    return {
        nodes: nodes, requests: requests, timers: timers, modal: modal,
        redirects: function () { return redirects; },
        visibility: function (hidden) { context.document.hidden = hidden; fire('document', 'visibilitychange'); },
        event: function (name) { fire('window', name); },
        elapse: function (ms) {
            now += ms;
            requests.slice().forEach(function (xhr) {
                if (!xhr.aborted && xhr.readyState !== 4 && xhr.timeout && now - xhr.started >= xhr.timeout) xhr.respond(0);
            });
        },
        open: function () { node('settingsOpen').onclick(); },
        close: function () { node('settingsClose').onclick(); },
        load: function () { context.Strike.settings.load(); },
        retry: function () {
            var ids = Object.keys(timers);
            assert.equal(ids.length, 1);
            var timer = timers[ids[0]];
            assert.equal(timer.delay, 500);
            delete timers[ids[0]];
            timer.callback();
        }
    };
}

function ready(enabled) {
    return { values: {
        'surveillance.mode': 'smart', 'surveillance.enabled': enabled, 'surveillance.screen': true,
        'surveillance.proximity': 3, 'surveillance.message': 'Recording', 'surveillance.budgetMb': 500
    }, volumes: [] };
}
var checks = 0;
function check(name, run) { run(); checks++; console.log('PASS ' + name); }

check('startup keeps its initial fetch but cold volumes do not paint incomplete settings', function () {
    var app = harness();
    assert.equal(app.requests.length, 1);
    app.requests[0].success({ pending: true });
    assert.equal(app.nodes.modeButton.getAttribute('aria-pressed'), undefined);
    assert.equal(Object.keys(app.timers).length, 0);
    assert.equal(app.nodes['surveillance.enabled'].disabled, true);
    assert.equal(app.nodes.settingsClose.disabled, false);
    app.open();
    app.close();
    app.open();
    assert.equal(app.requests.length, 2);
    app.requests[1].success(ready(true));
    assert.equal(app.nodes.modeButton.getAttribute('aria-pressed'), 'true');
    assert.equal(app.nodes['surveillance.enabled'].getAttribute('aria-pressed'), 'true');
    assert.equal(app.nodes['surveillance.enabled'].disabled, false);
    assert.equal(app.nodes.message.value, 'Recording');
    assert.equal(app.nodes.budget.textContent, '500');
});

check('pending refresh preserves warm settings and stops polling after close', function () {
    var app = harness();
    app.requests[0].success(ready(true));
    app.open();
    app.requests[1].success({ pending: true });
    assert.equal(app.nodes['surveillance.enabled'].getAttribute('aria-pressed'), 'true');
    assert.equal(app.nodes['surveillance.enabled'].disabled, true);
    app.load();
    assert.equal(app.requests.length, 2);
    app.retry();
    app.close();
    app.requests[2].success({ pending: true });
    assert.equal(Object.keys(app.timers).length, 0);
    app.open();
    app.requests[3].success({ pending: true });
    app.close();
    assert.equal(Object.keys(app.timers).length, 0);
    app.open();
    app.requests[4].success(ready(false));
    assert.equal(app.nodes['surveillance.enabled'].getAttribute('aria-pressed'), 'false');
});

check('failed pending reads do not enter an error retry loop', function () {
    var app = harness();
    app.open();
    app.requests[0].success({ pending: true });
    app.retry();
    app.requests[1].failure();
    assert.equal(Object.keys(app.timers).length, 0);
    assert.equal(app.modal.getAttribute('aria-busy'), 'false');
    assert.equal(app.nodes['surveillance.enabled'].disabled, true);
});

check('reopening during a save refreshes only after the write finishes', function () {
    var app = harness();
    app.open();
    app.requests[0].success(ready(true));
    app.nodes['surveillance.enabled'].onclick();
    assert.equal(app.requests[1].method, 'POST');
    assert.equal(app.requests[1].body, 'key=surveillance.enabled&value=false');
    app.close();
    app.open();
    assert.equal(app.requests.length, 2);
    app.requests[1].success();
    assert.equal(app.requests[2].method, 'GET');
    app.requests[2].success(ready(false));
    assert.equal(app.nodes['surveillance.enabled'].getAttribute('aria-pressed'), 'false');
});

check('a stalled save after reopening expires and restores authoritative settings without replay', function () {
    var app = harness();
    app.open();
    app.requests[0].success(ready(true));
    app.nodes['surveillance.enabled'].onclick();
    app.close(); app.open();
    app.elapse(7999);
    assert.equal(app.requests.length, 2);
    assert.equal(app.nodes['surveillance.enabled'].disabled, true);
    assert.equal(app.nodes.settingsClose.disabled, false);
    app.elapse(1);
    assert.equal(app.requests.length, 3);
    assert.equal(app.requests[1].timeout, 8000);
    assert.equal(app.requests[2].method, 'GET');
    app.elapse(8000);
    assert.equal(app.modal.getAttribute('aria-busy'), 'false');
    assert.equal(app.nodes['surveillance.enabled'].disabled, true);
    app.close();
    app.open();
    app.requests[3].success(ready(false));
    assert.equal(app.nodes['surveillance.enabled'].getAttribute('aria-pressed'), 'false');
    assert.equal(app.nodes['surveillance.enabled'].disabled, false);
    assert.equal(app.requests.filter(function (xhr) { return xhr.method === 'POST'; }).length, 1);
});

check('authentication expiry during a save redirects without refreshing or replaying it', function () {
    var app = harness();
    app.open();
    app.requests[0].success(ready(true));
    app.nodes['surveillance.enabled'].onclick();
    app.requests[1].respond(401);
    app.close(); app.open(); app.elapse(16000);
    assert.equal(app.redirects(), 1);
    assert.equal(app.requests.length, 2);
    assert.equal(Object.keys(app.timers).length, 0);
});

check('background pending reads stop and resume once after page restoration', function () {
    var app = harness();
    app.open();
    app.requests[0].success({ pending: true });
    var lateTimer = app.timers[Object.keys(app.timers)[0]].callback;
    app.visibility(true);
    app.event('pagehide');
    assert.equal(Object.keys(app.timers).length, 0);
    lateTimer();
    assert.equal(app.requests.length, 1);
    app.visibility(false);
    assert.equal(app.requests.length, 1);
    app.event('pageshow');
    app.event('pageshow');
    app.visibility(false);
    assert.equal(app.requests.length, 2);
    app.requests[1].success(ready(true));
    assert.equal(app.nodes['surveillance.enabled'].disabled, false);
    assert.equal(Object.keys(app.timers).length, 0);
});

check('hidden reads abort and late replies cannot replace restored settings', function () {
    var app = harness();
    app.open();
    var old = app.requests[0], late = old.onreadystatechange;
    app.visibility(true);
    assert.equal(old.aborted, true);
    app.visibility(false);
    app.requests[1].success(ready(true));
    old.success(ready(false));
    late();
    assert.equal(app.nodes['surveillance.enabled'].getAttribute('aria-pressed'), 'true');
    assert.equal(app.modal.getAttribute('aria-busy'), 'false');
    assert.equal(app.requests.length, 2);
});

['success', 'timeout'].forEach(function (outcome) {
    check('background save ' + outcome + ' waits for visibility to reconcile without replay', function () {
        var app = harness();
        app.open();
        app.requests[0].success(ready(true));
        app.nodes['surveillance.enabled'].onclick();
        var write = app.requests[1];
        app.visibility(true);
        app.event('pagehide');
        assert.notEqual(write.aborted, true);
        if (outcome === 'success') write.success();
        else app.elapse(8000);
        assert.equal(app.requests.length, 2);
        app.visibility(false);
        assert.equal(app.requests.length, 2);
        app.event('pageshow');
        app.event('pageshow');
        assert.equal(app.requests.length, 3);
        assert.equal(app.requests[2].method, 'GET');
        app.requests[2].success(ready(false));
        assert.equal(app.nodes['surveillance.enabled'].getAttribute('aria-pressed'), 'false');
        assert.equal(app.nodes['surveillance.enabled'].disabled, false);
        assert.equal(app.requests.filter(function (request) { return request.method === 'POST'; }).length, 1);
    });
});

console.log(checks + ' surveillance settings checks passed');

'use strict';

var assert = require('node:assert/strict');
var fs = require('node:fs');
var path = require('node:path');
var vm = require('node:vm');
var source = fs.readFileSync(process.argv[2] || path.join(__dirname, '../app/src/main/assets/web/js/surveillance-settings.js'), 'utf8');

function harness() {
    var nodes = {};
    var requests = [];
    var timers = {};
    var nextTimer = 0;
    function node(key) {
        if (!nodes[key]) nodes[key] = {
            id: key, hidden: false, disabled: false, value: '', textContent: '', attributes: {},
            setAttribute: function (name, value) { this.attributes[name] = value; },
            getAttribute: function (name) { return this.attributes[name]; }
        };
        return nodes[key];
    }
    var modal = node('settings');
    modal.hidden = true;
    var mode = node('mode');
    mode.setAttribute('data-key', 'surveillance.mode');
    var enabled = node('surveillance.enabled');
    var screen = node('surveillance.screen');
    enabled.setAttribute('data-key', 'surveillance.enabled');
    screen.setAttribute('data-key', 'surveillance.screen');
    var controls = [node('modeButton'), enabled, screen, node('surveillance.proximity'), node('message'), node('preview'), node('surveillance.budgetMb'), node('settingsClose')];
    modal.querySelectorAll = function () { return controls; };
    var context = {
        setTimeout: function (callback, delay) { var id = ++nextTimer; timers[id] = { callback: callback, delay: delay }; return id; },
        clearTimeout: function (id) { delete timers[id]; },
        document: {
            activeElement: null,
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
            core: {
                get: function (url, success, failure) { requests.push({ method: 'GET', url: url, success: success, failure: failure }); },
                post: function (url, body, success, failure) { requests.push({ method: 'POST', url: url, body: body, success: success, failure: failure }); },
                press: function (element, value) { element.selected = value; },
                buttonIn: function (event) { return event.target.disabled ? null : event.target; }
            }
        }
    };
    context.window = context;
    vm.createContext(context);
    vm.runInContext(source, context);
    return {
        nodes: nodes, requests: requests, timers: timers, modal: modal,
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
    assert.equal(app.nodes.mode.selected, undefined);
    assert.equal(Object.keys(app.timers).length, 0);
    assert.equal(app.nodes['surveillance.enabled'].disabled, true);
    assert.equal(app.nodes.settingsClose.disabled, false);
    app.open();
    app.close();
    app.open();
    assert.equal(app.requests.length, 2);
    app.requests[1].success(ready(true));
    assert.equal(app.nodes.mode.selected, 'smart');
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

console.log(checks + ' surveillance settings checks passed');

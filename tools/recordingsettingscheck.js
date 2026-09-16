'use strict';

var assert = require('node:assert/strict');
var fs = require('node:fs');
var path = require('node:path');
var vm = require('node:vm');
var root = process.argv[2] || path.join(__dirname, '..');
var web = path.join(root, 'app/src/main/assets/web/js');
var settingsSource = fs.readFileSync(process.argv[3] || path.join(web, 'recording-settings.js'), 'utf8');
var coreSource = fs.readFileSync(process.argv[3] ? path.join(path.dirname(process.argv[3]), 'core.js') : path.join(web, 'core.js'), 'utf8');

function Element(tag, id) {
    this.tagName = tag.toUpperCase();
    this.id = id || '';
    this.children = [];
    this.attributes = {};
    this.style = {};
    this.disabled = false;
    this.hidden = false;
    this.textContent = '';
}
Element.prototype.appendChild = function (child) {
    child.parentNode = this;
    this.children.push(child);
    return child;
};
Element.prototype.setAttribute = function (key, value) { this.attributes[key] = String(value); };
Element.prototype.getAttribute = function (key) { return this.attributes[key] || null; };
Element.prototype.removeAttribute = function (key) { delete this.attributes[key]; };
Element.prototype.getElementsByTagName = function (tag) {
    var found = [];
    this.children.forEach(function (child) {
        if (child.tagName === tag.toUpperCase()) found.push(child);
        found = found.concat(child.getElementsByTagName(tag));
    });
    return found;
};
Element.prototype.querySelectorAll = function (selector) {
    assert.equal(selector, 'button, input');
    return this.getElementsByTagName('button').concat(this.getElementsByTagName('input'));
};

function payload(audio, writable) {
    return {
        values: { 'recording.mode': 'continuous', 'recording.audio': audio, 'storage.location': 'internal', 'storage.budgetMb': 500 },
        volumes: [{ location: 'internal', mounted: true, usable: true, freeMb: 1024, totalMb: 2048, ceilingMb: 1000, usedMb: 200, reservedMb: 0 }],
        bydApps: { dashcam: 'enabled', trafficMonitor: 'disabled' }, bydAppsWritable: writable
    };
}

function harness() {
    var nodes = {};
    var requests = [];
    var notices = [];
    var listLoads = 0;
    var timers = {};
    var nextTimer = 0;
    var now = 0;
    var redirects = 0;
    function node(tag, id, parent) {
        var element = new Element(tag, id);
        if (id) nodes[id] = element;
        if (parent) parent.appendChild(element);
        return element;
    }
    function button(parent, value) {
        var element = node('button', '', parent);
        element.setAttribute('data-value', value);
        element.setAttribute('aria-pressed', 'false');
        return element;
    }
    var modal = node('div', 'settings');
    modal.hidden = true;
    node('button', 'settingsOpen');
    var close = node('button', 'settingsClose', modal);
    var mode = node('span', 'mode', modal);
    mode.setAttribute('data-key', 'recording.mode');
    button(mode, 'continuous');
    button(mode, 'manual');
    var audio = node('button', 'audio', modal);
    audio.setAttribute('data-key', 'recording.audio');
    audio.setAttribute('aria-pressed', 'false');
    var location = node('div', 'location', modal);
    ['internal', 'sd', 'usb'].forEach(function (value) {
        var option = button(location, value);
        node('span', '', option).textContent = value;
        node('span', '', option).textContent = '\u2014';
    });
    var slider = node('input', 'slider', modal);
    slider.min = '100';
    slider.value = '500';
    ['usageUsed', 'usageLimit', 'usageFill', 'budget', 'budgetFloor', 'budgetCeiling', 'shared'].forEach(function (id) { node('span', id, modal); });
    var apps = ['dashcam', 'trafficMonitor'].map(function (name) {
        var cell = node('span', name, modal);
        cell.setAttribute('data-app', name);
        button(cell, '').disabled = true;
        return cell;
    });
    function Xhr() { this.timeout = 0; requests.push(this); }
    Xhr.prototype.open = function (method, url) { this.method = method; this.url = url; };
    Xhr.prototype.setRequestHeader = function () {};
    Xhr.prototype.send = function (body) { this.body = body; this.started = now; };
    Xhr.prototype.respond = function (status, body) {
        this.status = status;
        this.responseText = JSON.stringify(body);
        this.readyState = 4;
        this.onreadystatechange();
    };
    var context = {
        XMLHttpRequest: Xhr,
        addEventListener: function () {},
        setTimeout: function (callback, delay) {
            var id = ++nextTimer;
            timers[id] = { callback: callback, delay: delay };
            return id;
        },
        clearTimeout: function (id) { delete timers[id]; },
        document: {
            addEventListener: function () {},
            getElementById: function (id) { return nodes[id]; },
            querySelectorAll: function (selector) {
                if (selector === '.seg[data-key]') return [mode];
                if (selector === '[data-app]') return apps;
                throw new Error('Unexpected selector: ' + selector);
            },
            querySelector: function (selector) {
                if (selector === '.slider[data-key="storage.budgetMb"]') return slider;
                if (selector === '.switch[data-key="recording.audio"]') return audio;
                if (selector === '[data-app="dashcam"]') return apps[0];
                if (selector === '[data-app="trafficMonitor"]') return apps[1];
                throw new Error('Unexpected selector: ' + selector);
            }
        },
        Strike: {
            list: { load: function (force) { assert.equal(force, true); listLoads++; } },
            toast: function (text, error) { notices.push({ text: text, error: !!error }); },
            session: { signIn: function () { redirects++; } }
        }
    };
    context.window = context;
    vm.createContext(context);
    vm.runInContext(coreSource, context);
    vm.runInContext(fs.readFileSync(path.join(web, 'budget.js'), 'utf8'), context);
    vm.runInContext(settingsSource, context);
    function click(element) {
        if (element.disabled) return;
        var event = { target: element };
        for (var current = element; current; current = current.parentNode) {
            if (current.onclick) current.onclick.call(current, event);
        }
    }
    return {
        nodes: nodes, modal: modal, mode: mode, audio: audio, slider: slider, apps: apps,
        requests: requests, notices: notices, click: click, settings: context.Strike.settings,
        timers: timers,
        core: context.Strike.core,
        redirects: function () { return redirects; },
        elapse: function (ms) {
            now += ms;
            requests.slice().forEach(function (xhr) {
                if (xhr.readyState !== 4 && xhr.timeout && now - xhr.started >= xhr.timeout) xhr.respond(0);
            });
        },
        retry: function () {
            var ids = Object.keys(timers);
            assert.equal(ids.length, 1);
            var timer = timers[ids[0]];
            assert.equal(timer.delay, 500);
            delete timers[ids[0]];
            timer.callback();
        },
        open: function () { click(nodes.settingsOpen); },
        close: function () { click(close); },
        listLoads: function () { return listLoads; }
    };
}

var checks = 0;
function check(name, run) {
    run();
    checks++;
    console.log('PASS ' + name);
}

check('closed page and external settings refresh do not scan settings', function () {
    var app = harness();
    assert.equal(app.requests.length, 0);
    app.settings.load();
    assert.equal(app.requests.length, 0);
    assert.equal(app.modal.hidden, true);
});

check('first open blocks unknown controls and preserves volume and app availability after loading', function () {
    var app = harness();
    app.open();
    assert.equal(app.requests.length, 1);
    assert.equal(app.requests[0].method, 'GET');
    assert.equal(app.modal.hidden, false);
    assert.equal(app.modal.getAttribute('aria-busy'), 'true');
    assert.equal(app.audio.disabled, true);
    assert.equal(app.slider.disabled, true);
    assert.equal(app.nodes.settingsClose.disabled, false);
    app.click(app.audio);
    assert.equal(app.requests.length, 1);
    app.requests[0].respond(200, payload(true, false));
    assert.equal(app.audio.getAttribute('aria-pressed'), 'true');
    assert.equal(app.audio.disabled, false);
    assert.equal(app.slider.disabled, false);
    assert.equal(app.nodes.location.children[0].disabled, false);
    assert.equal(app.nodes.location.children[1].disabled, true);
    assert.equal(app.apps[0].children[0].disabled, true);
    assert.equal(app.nodes.usageUsed.textContent, '200 MB');
    assert.equal(app.mode.children[0].getAttribute('aria-pressed'), 'true');
});

check('rapid close and reopen shares one pending request and the next completed opening refreshes', function () {
    var app = harness();
    app.open();
    app.close();
    app.open();
    app.settings.load();
    assert.equal(app.requests.length, 1);
    app.close();
    app.requests[0].respond(200, payload(true, true));
    assert.equal(app.modal.hidden, true);
    app.open();
    assert.equal(app.requests.length, 2);
    app.requests[1].respond(200, payload(false, true));
    assert.equal(app.audio.getAttribute('aria-pressed'), 'false');
    assert.equal(app.listLoads(), 2);
});

check('a failed first load stays safe and reopening can load authoritative settings', function () {
    var app = harness();
    app.open();
    app.requests[0].respond(0);
    assert.equal(app.modal.getAttribute('aria-busy'), 'false');
    assert.equal(app.audio.disabled, true);
    assert.equal(app.notices[0].error, true);
    assert.equal(Object.keys(app.timers).length, 0);
    app.close();
    app.open();
    app.requests[1].respond(200, payload(true, true));
    assert.equal(app.audio.disabled, false);
    assert.equal(app.audio.getAttribute('aria-pressed'), 'true');
});

check('pending inventory schedules one retry and paints only complete settings', function () {
    var app = harness();
    app.open();
    app.requests[0].respond(200, payload(true, true));
    app.settings.load();
    app.requests[1].respond(200, { pending: true });
    assert.equal(app.audio.getAttribute('aria-pressed'), 'true');
    assert.equal(app.audio.disabled, true);
    assert.equal(app.modal.getAttribute('aria-busy'), 'true');
    app.settings.load();
    app.open();
    assert.equal(app.requests.length, 2);
    app.retry();
    assert.equal(app.requests.length, 3);
    app.requests[2].respond(200, { pending: true });
    app.retry();
    app.requests[3].respond(200, payload(false, true));
    assert.equal(app.audio.getAttribute('aria-pressed'), 'false');
    assert.equal(app.audio.disabled, false);
    assert.equal(Object.keys(app.timers).length, 0);
});

check('closing pending inventory cancels hidden work and reopening starts a fresh load', function () {
    var app = harness();
    app.open();
    app.requests[0].respond(200, { pending: true });
    app.close();
    assert.equal(Object.keys(app.timers).length, 0);
    app.settings.load();
    assert.equal(app.requests.length, 1);
    app.open();
    assert.equal(app.requests.length, 2);
    app.close();
    app.requests[1].respond(200, { pending: true });
    assert.equal(Object.keys(app.timers).length, 0);
    app.open();
    app.requests[2].respond(200, payload(true, true));
    assert.equal(app.audio.getAttribute('aria-pressed'), 'true');
});

check('a failed pending refresh does not create an error retry loop', function () {
    var app = harness();
    app.open();
    app.requests[0].respond(200, { pending: true });
    app.retry();
    app.requests[1].respond(500);
    assert.equal(app.modal.getAttribute('aria-busy'), 'false');
    assert.equal(app.audio.disabled, true);
    assert.equal(Object.keys(app.timers).length, 0);
    assert.equal(app.requests.length, 2);
    assert.equal(app.notices[0].error, true);
});

check('saving after load uses the displayed value and does not reload while closed', function () {
    var app = harness();
    app.open();
    app.requests[0].respond(200, payload(true, true));
    app.click(app.audio);
    assert.equal(app.requests[1].method, 'POST');
    assert.equal(app.requests[1].body, 'key=recording.audio&value=false');
    app.close();
    app.requests[1].respond(200);
    assert.equal(app.requests.length, 2);
    assert.equal(app.notices[0].text, 'Recording settings saved');
    app.open();
    app.requests[2].respond(200, payload(false, true));
    assert.equal(app.audio.getAttribute('aria-pressed'), 'false');
});

check('reopening during a save waits for completion before refreshing settings', function () {
    var app = harness();
    app.open();
    app.requests[0].respond(200, payload(true, true));
    app.click(app.audio);
    app.close();
    app.open();
    assert.equal(app.requests.length, 2);
    app.requests[1].respond(200);
    assert.equal(app.requests.length, 3);
    assert.equal(app.requests[2].method, 'GET');
    app.requests[2].respond(200, payload(false, true));
    assert.equal(app.audio.getAttribute('aria-pressed'), 'false');
    assert.equal(app.audio.disabled, false);
});

check('failed saves restore server values and factory app writes retain their route', function () {
    var app = harness();
    app.open();
    app.requests[0].respond(200, payload(true, true));
    app.click(app.audio);
    app.requests[1].respond(500);
    assert.equal(app.notices[0].error, true);
    app.requests[2].respond(200, payload(true, true));
    assert.equal(app.audio.getAttribute('aria-pressed'), 'true');
    app.click(app.apps[0].children[0]);
    assert.equal(app.requests[3].url, '/api/byd/dashcam');
    assert.equal(app.requests[3].body, 'enabled=false');
    app.requests[3].respond(200);
    app.requests[4].respond(200, payload(true, false));
    assert.equal(app.apps[0].children[0].disabled, true);
});

check('a stalled save times out after reopening and refreshes the server value without replaying', function () {
    var app = harness();
    app.open();
    app.requests[0].respond(200, payload(true, true));
    app.click(app.audio);
    app.close();
    app.open();
    app.elapse(7999);
    assert.equal(app.requests.length, 2);
    assert.equal(app.audio.disabled, true);
    assert.equal(app.nodes.settingsClose.disabled, false);
    app.elapse(1);
    assert.equal(app.requests.length, 3);
    assert.equal(app.requests[1].timeout, 8000);
    assert.equal(app.requests[2].method, 'GET');
    app.elapse(8000);
    assert.equal(app.modal.getAttribute('aria-busy'), 'false');
    assert.equal(app.audio.disabled, true);
    app.close();
    app.open();
    app.requests[3].respond(200, payload(false, true));
    assert.equal(app.audio.disabled, false);
    assert.equal(app.audio.getAttribute('aria-pressed'), 'false');
    assert.equal(app.requests.filter(function (xhr) { return xhr.method === 'POST'; }).length, 1);
    assert.equal(app.notices[0].error, true);
});

check('factory app writes time out without replaying and authentication expiry never refreshes', function () {
    [0, 401].forEach(function (status) {
        var app = harness();
        app.open();
        app.requests[0].respond(200, payload(true, true));
        app.click(app.apps[0].children[0]);
        assert.equal(app.requests[1].url, '/api/byd/dashcam');
        assert.equal(app.requests[1].timeout, 8000);
        if (status === 0) {
            app.elapse(8000);
            app.requests[2].respond(200, payload(true, false));
            assert.equal(app.audio.disabled, false);
        } else {
            app.requests[1].respond(status);
            app.close(); app.open(); app.elapse(16000);
            assert.equal(app.requests.length, 2);
            assert.equal(app.redirects(), 1);
        }
        assert.equal(app.requests.filter(function (xhr) { return xhr.method === 'POST'; }).length, 1);
    });
});

check('other POST callers keep their existing unbounded timeout', function () {
    var app = harness(), completed = false;
    app.core.post('/api/daemon/start', '', function () { completed = true; }, function () { assert.fail('Unrelated POST timed out'); });
    assert.equal(app.requests[0].timeout, 0);
    app.elapse(60000);
    assert.equal(completed, false);
    app.requests[0].respond(200);
    assert.equal(completed, true);
});

console.log(checks + ' recording settings checks passed');

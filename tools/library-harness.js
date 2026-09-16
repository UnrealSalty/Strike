'use strict';

var assert = require('node:assert/strict');
var fs = require('node:fs');
var path = require('node:path');
var vm = require('node:vm');
var root = process.argv[2] || path.join(__dirname, '..');
var web = path.join(root, 'app/src/main/assets/web/js');
function asset(name) {
    var override = process.argv[3] && path.join(path.dirname(process.argv[3]), name);
    return fs.readFileSync(override && fs.existsSync(override) ? override : path.join(web, name), 'utf8');
}

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
    var redirects = 0;
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
    node('toolbar').appendChild(node('count'));
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
            session: { leaving: false, signIn: function () { redirects++; this.leaving = true; } }
        }
    };
    context.window = context;
    vm.createContext(context);
    vm.runInContext(asset('core.js'), context);
    vm.runInContext(asset('thumbnail-loader.js'), context);
    vm.runInContext(asset('library.js'), context);
    vm.runInContext(fs.readFileSync(path.join(web, 'clips.js'), 'utf8'), context);
    return {
        nodes: nodes, timers: timers, requests: requests, revoked: revoked,
        redirects: function () { return redirects; },
        retryButton: function () { return nodes.toolbar.getElementsByTagName('button')[0]; },
        load: function (force) { context.Strike.list.load(force); },
        gets: function () { return requests.filter(function (request) { return request.method === 'GET' && request.url === '/api/recording/clips'; }); },
        retry: function (delay) {
            var ids = Object.keys(timers);
            assert.equal(ids.length, 1);
            var timer = timers[ids[0]];
            assert.equal(timer.delay, delay == null ? 500 : delay);
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

module.exports = harness;

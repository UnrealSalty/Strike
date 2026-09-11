(function () {
    'use strict';

    var DAEMONS = '/api/daemons';
    var LOGS = '/api/logs';
    var CHEVRON = 'M6 9l6 6 6-6';
    var POSTS = {
        connect: { path: '/api/daemons/shell', label: 'Connect' }
    };

    var open = {};

    function two(number) {
        return number < 10 ? '0' + number : String(number);
    }

    function stamp(atMs) {
        var at = new Date(atMs);
        return two(at.getHours()) + ':' + two(at.getMinutes()) + ':' + two(at.getSeconds());
    }

    function clock(elapsedMs) {
        var total = Math.floor(elapsedMs / 1000);
        var days = Math.floor(total / 86400);
        var hours = Math.floor(total % 86400 / 3600);
        var text = hours + ':' + two(Math.floor(total % 3600 / 60)) + ':' + two(total % 60);
        return days > 0 ? days + 'd ' + text : text;
    }

    function counting(node, prefix, uptimeMs) {
        node.setAttribute('data-uptime-ms', uptimeMs);
        node.setAttribute('data-at', Date.now());
        node.setAttribute('data-prefix', prefix);
        node.textContent = prefix + clock(uptimeMs);
    }

    function tick() {
        var nodes = document.querySelectorAll('[data-uptime-ms]');
        for (var i = 0; i < nodes.length; i++) {
            var node = nodes[i];
            var base = Number(node.getAttribute('data-uptime-ms'));
            var at = Number(node.getAttribute('data-at'));
            node.textContent = node.getAttribute('data-prefix') + clock(base + Date.now() - at);
        }
    }

    function chevron(className) {
        var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        var path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        path.setAttribute('d', CHEVRON);
        svg.setAttribute('class', className);
        svg.setAttribute('viewBox', '0 0 24 24');
        svg.setAttribute('fill', 'none');
        svg.setAttribute('stroke', 'currentColor');
        svg.setAttribute('stroke-width', '2.5');
        svg.setAttribute('stroke-linecap', 'round');
        svg.setAttribute('aria-hidden', 'true');
        svg.appendChild(path);
        return svg;
    }

    function control(card) {
        var post = POSTS[card.action];
        if (post) {
            var button = Strike.core.el('button', 'btn', post.label);
            button.type = 'button';
            button.onclick = function () {
                this.disabled = true;
                Strike.core.post(post.path, '', load,
                    function () { load(); Strike.toast('Could not connect shell access', true); });
            };
            return button;
        }
        if (card.action !== 'switch') {
            return null;
        }
        var toggle = Strike.core.el('button', 'switch');
        toggle.type = 'button';
        toggle.setAttribute('aria-pressed', card.on ? 'true' : 'false');
        toggle.disabled = !card.can;
        toggle.appendChild(Strike.core.el('span', 'switch__knob'));
        if (card.can && card.path) {
            toggle.onclick = function () {
                this.disabled = true;
                Strike.core.post(card.path, 'enabled=' + (card.on ? 'false' : 'true'), load,
                    function () { load(); Strike.toast('Could not change ' + card.name.toLowerCase(), true); });
            };
        }
        return toggle;
    }

    function fact(detail, label, value) {
        var row = Strike.core.el('div', 'fact');
        row.appendChild(Strike.core.el('span', 'fact__label', label));
        var text = Strike.core.el('span', 'fact__value', value);
        row.appendChild(text);
        detail.appendChild(row);
        return text;
    }

    function facts(card) {
        var detail = Strike.core.el('div', 'daemon__detail');
        detail.hidden = !open[card.id];
        for (var i = 0; i < card.facts.length; i++) {
            fact(detail, card.facts[i].label, card.facts[i].value);
        }
        if (typeof card.uptimeMs === 'number') {
            counting(fact(detail, 'Uptime', ''), '', card.uptimeMs);
        }
        return detail;
    }

    function daemon(card) {
        var host = Strike.core.el('div', 'daemon');
        host.setAttribute('data-state', card.state);

        var text = Strike.core.el('span', 'daemon__text');
        text.appendChild(Strike.core.el('span', 'daemon__name', card.name));
        var status = Strike.core.el('span', 'daemon__status', card.status);
        if (typeof card.uptimeMs === 'number') {
            counting(status, card.status + ' \u00b7 ', card.uptimeMs);
        }
        text.appendChild(status);

        var opener = Strike.core.el('button', 'daemon__open');
        opener.type = 'button';
        opener.setAttribute('aria-expanded', open[card.id] ? 'true' : 'false');
        opener.appendChild(text);
        opener.appendChild(chevron('daemon__chev'));

        var detail = facts(card);
        opener.onclick = function () {
            open[card.id] = !open[card.id];
            detail.hidden = !open[card.id];
            this.setAttribute('aria-expanded', open[card.id] ? 'true' : 'false');
        };

        var row = Strike.core.el('div', 'daemon__row');
        row.appendChild(Strike.core.el('span', 'daemon__bar'));
        row.appendChild(opener);
        var action = control(card);
        if (action) {
            var actions = Strike.core.el('span', 'daemon__actions');
            actions.appendChild(action);
            row.appendChild(actions);
        }

        host.appendChild(row);
        host.appendChild(detail);
        return host;
    }

    function paint(payload, cached) {
        var count = document.getElementById('count');
        count.textContent = payload.running + ' of ' + payload.total + ' running';
        count.setAttribute('data-empty', 'false');
        document.getElementById('countDot').setAttribute('data-state', payload.health);

        var host = document.getElementById('daemons');
        host.setAttribute('aria-busy', 'false');
        document.getElementById('daemonMessage').hidden = true;
        host.innerHTML = '';
        for (var i = 0; i < payload.cards.length; i++) {
            host.appendChild(daemon(payload.cards[i]));
        }
        if (cached) disableControls();
    }

    function disableControls() {
        var buttons = document.querySelectorAll('#daemons .daemon__actions button');
        for (var i = 0; i < buttons.length; i++) buttons[i].disabled = true;
    }

    function unreachable() {
        var count = document.getElementById('count');
        count.textContent = Strike.core.dash;
        count.setAttribute('data-empty', 'true');
        document.getElementById('countDot').removeAttribute('data-state');
        var host = document.getElementById('daemons');
        host.setAttribute('aria-busy', 'false');
        var placeholders = host.querySelectorAll('.sk');
        for (var i = 0; i < placeholders.length; i++) {
            placeholders[i].className = '';
            placeholders[i].textContent = Strike.core.dash;
        }
        disableControls();
        var message = document.getElementById('daemonMessage');
        message.textContent = 'Cannot reach Strike';
        message.hidden = false;
    }

    function following(host) {
        return host.scrollHeight - host.scrollTop - host.clientHeight < 8;
    }

    function paintLogs(payload) {
        var host = document.getElementById('log');
        var tail = following(host);
        var was = host.scrollTop;
        host.innerHTML = '';
        if (!payload.lines.length) {
            host.appendChild(Strike.core.el('p', 'log__line', Strike.core.dash));
            return;
        }
        for (var i = 0; i < payload.lines.length; i++) {
            var line = payload.lines[i];
            var text = stamp(line.atMs) + '  ' + line.tag + '  ' + line.message;
            var row = Strike.core.el('p', 'log__line', text);
            row.setAttribute('data-level', line.level);
            host.appendChild(row);
        }
        host.scrollTop = tail ? host.scrollHeight : was;
    }

    function logsUnreachable() {
        if (!document.getElementById('log').childNodes.length) {
            paintLogs({ lines: [] });
        }
    }

    function load() {
        Strike.core.get(DAEMONS, paint, unreachable);
        Strike.core.get(LOGS, paintLogs, logsUnreachable);
    }

    Strike.core.poll(DAEMONS, 2000, paint, unreachable);
    Strike.core.poll(LOGS, 5000, paintLogs, logsUnreachable);
    setInterval(tick, 1000);
    Strike.shell.start();
}());

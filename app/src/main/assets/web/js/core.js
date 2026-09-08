window.Strike = window.Strike || {};

Strike.core = {
    dash: '\u2014',

    get: function (path, onOk, onFail) {
        var xhr = new XMLHttpRequest();
        xhr.open('GET', path, true);
        xhr.timeout = 8000;
        xhr.onreadystatechange = function () {
            if (xhr.readyState !== 4) {
                return;
            }
            if (xhr.status === 401) { Strike.session.signIn(); return; }
            if (xhr.status !== 200) {
                onFail();
                return;
            }
            var payload;
            try {
                payload = JSON.parse(xhr.responseText);
            } catch (e) {
                onFail();
                return;
            }
            onOk(payload);
        };
        xhr.send();
    },

    post: function (path, body, onOk, onFail) {
        var xhr = new XMLHttpRequest();
        xhr.open('POST', path, true);
        xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
        xhr.onreadystatechange = function () {
            if (xhr.readyState !== 4) {
                return;
            }
            if (xhr.status === 401) { Strike.session.signIn(); return; }
            if (xhr.status !== 200) {
                onFail();
                return;
            }
            onOk();
        };
        xhr.send(body);
    },

    del: function (path, onOk, onFail) {
        var xhr = new XMLHttpRequest();
        xhr.open('DELETE', path, true);
        xhr.onreadystatechange = function () {
            if (xhr.readyState !== 4) {
                return;
            }
            if (xhr.status === 401) { Strike.session.signIn(); return; }
            if (xhr.status !== 200) {
                onFail();
                return;
            }
            onOk();
        };
        xhr.send();
    },

    el: function (tag, className, text) {
        var node = document.createElement(tag);
        if (className) {
            node.className = className;
        }
        if (text) {
            node.appendChild(document.createTextNode(text));
        }
        return node;
    },

    buttonIn: function (event, host) {
        var node = event.target;
        while (node && node !== host && node.tagName !== 'BUTTON' && node.tagName !== 'A') {
            node = node.parentNode;
        }
        if (!node || node === host || node.disabled) {
            return null;
        }
        return node;
    },

    meter: function (id, share, state) {
        var fill = document.getElementById(id);
        fill.style.width = (share === null ? 0 : Math.max(0, Math.min(100, share))) + '%';
        if (state) {
            fill.setAttribute('data-state', state);
        } else {
            fill.removeAttribute('data-state');
        }
    },

    press: function (container, value) {
        var buttons = container.getElementsByTagName('button');
        for (var i = 0; i < buttons.length; i++) {
            var option = buttons[i].getAttribute('data-value');
            buttons[i].setAttribute('aria-pressed', option === value ? 'true' : 'false');
        }
    },

    size: function (bytes) {
        if (bytes < 1048576) {
            return Math.round(bytes / 1024) + ' KB';
        }
        if (bytes < 1073741824) {
            return (bytes / 1048576).toFixed(1) + ' MB';
        }
        return (bytes / 1073741824).toFixed(2) + ' GB';
    },

    poll: function (path, everyMs, onOk, onFail) {
        var key = 'strike:' + path;

        function remembered() {
            try {
                return sessionStorage.getItem(key);
            } catch (e) {
                return null;
            }
        }

        function keep(payload) {
            try {
                sessionStorage.setItem(key, JSON.stringify(payload));
            } catch (e) {
                return;
            }
        }

        var last = remembered();
        if (last) {
            try {
                onOk(JSON.parse(last), true);
            } catch (e) {
                keep(null);
            }
        }

        var pending = false;
        function tick() {
            if (pending || document.hidden || Strike.session.leaving) return;
            pending = true;
            Strike.core.get(path, function (payload) {
                pending = false;
                keep(payload);
                onOk(payload, false);
            }, function () { pending = false; onFail(); });
        }
        tick();
        setInterval(tick, everyMs);
        document.addEventListener('visibilitychange', tick);
    },

    value: function (id, value) {
        var el = document.getElementById(id);
        if (!el) {
            return;
        }
        var known = value !== null && value !== undefined;
        el.textContent = known ? String(value) : Strike.core.dash;
        el.setAttribute('data-empty', known ? 'false' : 'true');
    }
};

// Native selects cannot be styled consistently on the head unit's WebView.
Strike.pick = function (id, onChange) {
    var root = document.getElementById(id);
    var button = root.getElementsByTagName('button')[0];
    var label = button.getElementsByTagName('span')[0];
    var menu = root.getElementsByClassName('pick__menu')[0];
    var value = null;

    function open(show) {
        menu.hidden = !show;
        button.setAttribute('aria-expanded', show ? 'true' : 'false');
    }

    function select(next) {
        value = next;
        var items = menu.getElementsByTagName('button');
        for (var i = 0; i < items.length; i++) {
            var match = items[i].getAttribute('data-value') === next;
            items[i].setAttribute('aria-pressed', match ? 'true' : 'false');
            if (match) {
                label.textContent = items[i].textContent;
            }
        }
    }

    button.onclick = function () {
        open(menu.hidden);
    };

    document.addEventListener('mousedown', function (event) {
        var node = event.target;
        while (node) {
            if (node === root) {
                return;
            }
            node = node.parentNode;
        }
        open(false);
    });

    menu.onclick = function (event) {
        var item = Strike.core.buttonIn(event, this);
        if (!item) {
            return;
        }
        open(false);
        select(item.getAttribute('data-value'));
        onChange(value);
    };

    return {
        value: function () {
            return value;
        },
        close: function () {
            open(false);
        },
        select: select,
        fill: function (options) {
            menu.innerHTML = '';
            for (var i = 0; i < options.length; i++) {
                var item = Strike.core.el('button', 'pick__item', options[i].label);
                item.type = 'button';
                item.setAttribute('data-value', options[i].value);
                menu.appendChild(item);
            }
            var keep = value;
            for (var j = 0; j < options.length; j++) {
                if (options[j].value === keep) {
                    select(keep);
                    return false;
                }
            }
            select(options[0].value);
            return keep !== null;
        }
    };
};

(function () {
    var RIPPLES = ['nav__link', 'action', 'btn', 'seg__btn', 'choice__row', 'pick__btn',
        'pick__item', 'row--clip', 'row--link', 'daemon__open', 'pad__key'];
    var HOLDS = ['nav__link', 'action', 'row--link'];
    var NAV_HOLD_MS = 130;

    // Whole-word match: a substring test lets `row` hit `choice__row`, which
    // makes every list row a ripple host.
    function hasClass(node, name) {
        if (typeof node.className !== 'string') {
            return false;
        }
        var words = node.className.split(' ');
        for (var i = 0; i < words.length; i++) {
            if (words[i] === name) {
                return true;
            }
        }
        return false;
    }

    function anyOf(node, names) {
        while (node && node.nodeType === 1) {
            for (var i = 0; i < names.length; i++) {
                if (hasClass(node, names[i])) {
                    return node;
                }
            }
            node = node.parentNode;
        }
        return null;
    }

    document.addEventListener('mousedown', function (event) {
        var host = anyOf(event.target, RIPPLES);
        if (!host || host.disabled) {
            return;
        }
        var box = host.getBoundingClientRect();
        var pressX = event.clientX - box.left;
        var pressY = event.clientY - box.top;
        var reachX = Math.max(pressX, box.width - pressX);
        var reachY = Math.max(pressY, box.height - pressY);
        var size = Math.sqrt(reachX * reachX + reachY * reachY) * 2;
        var ripple = Strike.core.el('span', 'ripple');
        ripple.style.width = size + 'px';
        ripple.style.height = size + 'px';
        ripple.style.left = (pressX - size / 2) + 'px';
        ripple.style.top = (pressY - size / 2) + 'px';
        host.appendChild(ripple);
        setTimeout(function () {
            if (ripple.parentNode) {
                ripple.parentNode.removeChild(ripple);
            }
        }, 260);
    }, true);

    // Blur tapped controls to clear Chrome 58's persistent focus outline; keep inputs focused.
    document.addEventListener('click', function (event) {
        if (event.detail === 0) {
            return;
        }
        var node = event.target;
        while (node && node !== document.body) {
            var tag = node.tagName;
            if (tag === 'INPUT' || tag === 'TEXTAREA') {
                return;
            }
            if (tag === 'BUTTON' || tag === 'A') {
                setTimeout(function () {
                    node.blur();
                }, 0);
                return;
            }
            node = node.parentNode;
        }
    }, true);

    // Let the press feedback draw before navigation replaces the document.
    document.addEventListener('click', function (event) {
        var link = anyOf(event.target, HOLDS);
        if (!link || link.tagName !== 'A') {
            return;
        }
        event.preventDefault();
        if (link.getAttribute('aria-current') === 'page') {
            return;
        }
        var href = link.href;
        setTimeout(function () {
            location.href = href;
        }, NAV_HOLD_MS);
    });
}());

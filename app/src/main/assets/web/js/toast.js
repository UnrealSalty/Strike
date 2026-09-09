window.Strike = window.Strike || {};

Strike.toast = function (message, failed) {
    var host = document.getElementById('toasts');
    if (!host) {
        host = Strike.core.el('div', 'toasts');
        host.id = 'toasts';
        document.body.appendChild(host);
    }
    var items = host.children;
    for (var i = 0; i < items.length; i++) {
        if (items[i].getAttribute('data-message') === message) items[i].dismiss();
    }
    while (host.children.length >= 4) {
        var oldest = host.firstChild;
        oldest.dismiss();
        host.removeChild(oldest);
    }
    var item = Strike.core.el('div', 'toast');
    item.setAttribute('data-message', message);
    item.setAttribute('role', failed ? 'alert' : 'status');
    var dot = Strike.core.el('span', 'dot');
    dot.setAttribute('data-state', failed ? 'bad' : 'ok');
    item.appendChild(dot);
    item.appendChild(Strike.core.el('span', 'toast__text', message));
    var close = Strike.core.el('button', 'btn btn--quiet toast__close', '\u00d7');
    close.type = 'button';
    close.setAttribute('aria-label', 'Dismiss notification');
    var timer;
    item.dismiss = function () {
        if (item.getAttribute('data-out')) return;
        item.setAttribute('data-out', 'true');
        clearTimeout(timer);
        item.className = 'toast toast--out';
        setTimeout(function () {
            if (item.parentNode) item.parentNode.removeChild(item);
        }, 180);
    };
    close.onclick = item.dismiss;
    item.appendChild(close);
    host.appendChild(item);
    timer = setTimeout(item.dismiss, 4000);
};

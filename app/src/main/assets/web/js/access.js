(function () {
    'use strict';

    var form = document.getElementById('accessForm');
    var code = document.getElementById('accessCode');
    var button = document.getElementById('accessSubmit');
    var message = document.getElementById('accessMessage');
    var pending = false;

    function payload(xhr) {
        try { return JSON.parse(xhr.responseText); } catch (e) { return null; }
    }

    form.onsubmit = function (event) {
        event.preventDefault();
        if (pending) return;
        pending = true;
        button.disabled = code.disabled = true;
        button.textContent = 'Signing in';
        message.textContent = '';
        message.removeAttribute('data-error');
        code.removeAttribute('aria-invalid');
        var xhr = new XMLHttpRequest();
        xhr.open('POST', '/api/access/login', true);
        xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
        xhr.timeout = 10000;
        xhr.onloadend = function () {
            var reply = payload(xhr);
            code.value = '';
            if (xhr.status === 200 && reply && reply.ok) {
                location.replace('/');
                return;
            }
            pending = false;
            button.disabled = code.disabled = false;
            button.textContent = 'Sign in';
            message.textContent = xhr.status === 429 && reply && reply.retryAfterSeconds > 0 ?
                'Too many tries. Wait ' + reply.retryAfterSeconds + ' s before trying again.' :
                reply && reply.error ? reply.error : 'Cannot reach Strike.';
            message.setAttribute('data-error', 'true');
            code.setAttribute('aria-invalid', xhr.status === 400 || xhr.status === 401 ? 'true' : 'false');
            code.focus();
        };
        xhr.send('code=' + encodeURIComponent(code.value));
    };

    var status = new XMLHttpRequest();
    status.open('GET', '/api/access', true);
    status.timeout = 10000;
    status.onload = function () {
        var reply = payload(status);
        if (status.status === 200 && reply && reply.authenticated) location.replace('/');
    };
    status.send();
}());

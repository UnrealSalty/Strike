(function () {
    'use strict';

    window.Strike = window.Strike || {};

    var MAX_ACTIVE = 2;
    var MAX_CACHED = 40;
    var REQUEST_MS = 8000;
    var DEADLINE_MS = 30000;
    var PENDING_DEADLINE_MS = 120000;
    var SLOW_RETRY_MS = 5000;
    var RETRY_MS = 1000;
    var MAX_RETRY_MS = 2000;

    Strike.thumbnails = function (base, paint) {
        var jobs = Object.create(null);
        var cache = Object.create(null);
        var cachedIds = [];
        var wanted = [];
        var active = 0;
        var timer = null;
        var paused = document.hidden;
        var departed = false;
        var signedOut = false;

        function peek(id, identity) {
            var shot = cache[id];
            return shot && shot.identity === identity ? shot : null;
        }

        function forgetCached(id) {
            if (!cache[id]) return;
            URL.revokeObjectURL(cache[id].url);
            delete cache[id];
            cachedIds.splice(cachedIds.indexOf(id), 1);
        }

        function remember(id, shot) {
            forgetCached(id);
            cache[id] = shot;
            cachedIds.push(id);
            while (cachedIds.length > MAX_CACHED) forgetCached(cachedIds[0]);
        }

        function detach(xhr) {
            xhr.onreadystatechange = null;
            xhr.onerror = null;
            xhr.ontimeout = null;
        }

        function cancel(job) {
            if (job.xhr) {
                var xhr = job.xhr;
                job.xhr = null;
                detach(xhr);
                active--;
                xhr.abort();
            }
            delete jobs[job.id];
        }

        function placeholder(job) {
            delete jobs[job.id];
            paint({ url: null, durationMs: 0, codec: null }, job.nodes);
        }

        function suspend(releaseCache) {
            paused = true;
            clearTimeout(timer);
            timer = null;
            Object.keys(jobs).forEach(function (id) { cancel(jobs[id]); });
            if (releaseCache) cachedIds.slice().forEach(forgetCached);
        }

        function finish(job, xhr) {
            if (jobs[job.id] !== job || job.xhr !== xhr) return;
            detach(xhr);
            job.xhr = null;
            active--;
            if (xhr.status === 401) {
                signedOut = true;
                suspend(true);
                Strike.session.signIn();
                return;
            }
            if (xhr.status === 200) {
                var shot = {
                    identity: job.identity,
                    url: URL.createObjectURL(xhr.response),
                    durationMs: parseInt(xhr.getResponseHeader('X-Clip-Duration-Ms') || '0', 10),
                    codec: xhr.getResponseHeader('X-Clip-Codec')
                };
                remember(job.id, shot);
                delete jobs[job.id];
                paint(shot, job.nodes);
            } else if (xhr.status === 202 || xhr.status === 0 || xhr.status >= 500) {
                job.pending = xhr.status === 202 || xhr.status === 503;
                if (job.slow && !job.pending) {
                    placeholder(job);
                    pump();
                    return;
                }
                job.attempts++;
                var backoff = job.slow ? SLOW_RETRY_MS :
                    Math.min(MAX_RETRY_MS, RETRY_MS * Math.pow(2, job.attempts - 1));
                var retryAfter = Number(xhr.getResponseHeader('Retry-After')) * 1000;
                var delay = Math.max(backoff, isFinite(retryAfter) ? retryAfter : 0);
                job.readyAt = Math.min(job.deadline, Date.now() + delay);
            } else {
                placeholder(job);
            }
            pump();
        }

        function request(job) {
            var xhr = new XMLHttpRequest();
            job.xhr = xhr;
            active++;
            xhr.open('GET', base + encodeURIComponent(job.id), true);
            xhr.responseType = 'blob';
            xhr.timeout = Math.max(1, Math.min(REQUEST_MS, job.deadline - Date.now()));
            xhr.onreadystatechange = function () {
                if (xhr.readyState === 4) finish(job, xhr);
            };
            xhr.onerror = function () { finish(job, xhr); };
            xhr.ontimeout = function () { finish(job, xhr); };
            xhr.send();
        }

        function pump() {
            clearTimeout(timer);
            timer = null;
            if (paused || signedOut) return;
            var now = Date.now();
            var nextAt = null;
            Object.keys(jobs).forEach(function (id) {
                var job = jobs[id];
                if (job.xhr) return;
                if (now >= job.deadline) {
                    var extended = job.deadline + PENDING_DEADLINE_MS - DEADLINE_MS;
                    if (job.pending && !job.slow && now < extended) {
                        job.slow = true;
                        job.deadline = extended;
                        job.readyAt = Math.min(extended, now + SLOW_RETRY_MS);
                        paint({ url: null, durationMs: 0, codec: null }, job.nodes);
                    } else {
                        placeholder(job);
                        return;
                    }
                }
                if (job.readyAt <= now) {
                    if (active < MAX_ACTIVE) request(job);
                } else if (nextAt === null || job.readyAt < nextAt) {
                    nextAt = job.readyAt;
                }
            });
            if (nextAt !== null) timer = setTimeout(pump, Math.max(1, nextAt - Date.now()));
        }

        function show(entries) {
            wanted = entries;
            var visible = Object.create(null);
            entries.forEach(function (entry) { visible[entry.id] = entry; });
            Object.keys(jobs).forEach(function (id) {
                if (!visible[id]) cancel(jobs[id]);
            });
            Object.keys(visible).forEach(function (id) {
                var entry = visible[id];
                if (cache[id] && cache[id].identity !== entry.identity) forgetCached(id);
                if (jobs[id] && jobs[id].identity !== entry.identity) cancel(jobs[id]);
                if (cache[id]) {
                    cachedIds.splice(cachedIds.indexOf(id), 1);
                    cachedIds.push(id);
                    paint(cache[id], entry.nodes);
                } else if (jobs[id]) {
                    jobs[id].nodes = entry.nodes;
                    if (jobs[id].slow) paint({ url: null, durationMs: 0, codec: null }, entry.nodes);
                } else if (!paused && !signedOut) {
                    jobs[id] = { id: id, identity: entry.identity, nodes: entry.nodes, xhr: null,
                        attempts: 0, readyAt: Date.now(), deadline: Date.now() + DEADLINE_MS };
                }
            });
            pump();
        }

        function forget(id) {
            wanted = wanted.filter(function (entry) { return entry.id !== id; });
            if (jobs[id]) cancel(jobs[id]);
            forgetCached(id);
            pump();
        }

        function resume() {
            if (departed || document.hidden || signedOut) return;
            paused = false;
            show(wanted);
        }

        function clear() {
            wanted = [];
            clearTimeout(timer);
            timer = null;
            Object.keys(jobs).forEach(function (id) { cancel(jobs[id]); });
            cachedIds.slice().forEach(forgetCached);
        }

        document.addEventListener('visibilitychange', function () {
            if (document.hidden) suspend(false);
            else resume();
        });
        window.addEventListener('pagehide', function () {
            departed = true;
            suspend(true);
        });
        window.addEventListener('pageshow', function () {
            departed = false;
            resume();
        });

        return { show: show, peek: peek, forget: forget, clear: clear };
    };
}());

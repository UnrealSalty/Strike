(function () {
    'use strict';

    window.Strike = window.Strike || {};

    var DURATION = 'X-Clip-Duration-Ms';
    var CODEC = 'X-Clip-Codec';
    var PLAY = 'M8 5l12 7-12 7z';
    var PAUSE = 'M7 5h4v14H7zM13 5h4v14h-4z';
    var GROW = 'M9 4H4v5M20 9V4h-5M15 20h5v-5M4 15v5h5';
    var SHRINK = 'M4 9h5V4M20 9h-5V4M20 15h-5v5M4 15h5v5';
    var FILM = 'M3 5.5A1.5 1.5 0 014.5 4h15A1.5 1.5 0 0121 5.5v13a1.5 1.5 0 01-1.5 1.5h-15A1.5 1.5 0 013 18.5z';
    var DOWNLOAD = 'M12 4v9M8.5 10.5L12 14l3.5-3.5M5 19h14';
    var TRASH = 'M5 7h14M9 7V5h6v2M7 7l1 12h8l1-12';

    var LOADING = 'Loading video';
    var UNPLAYABLE = 'Cannot play this clip';
    var PRESS_PLAY = 'Press play to start';
    // The head unit's WebView has no HEVC decoder for video elements. A browser has one.
    var HEVC = 'This clip is H.265. It will not play in the car app. Download it or open it in a browser.';

    var CODECS = { h264: 'H.264', h265: 'H.265' };

    // MediaMuxer writes the index at the end; if the WebView never reaches it the clip
    // stays at readyState 0 with no error. Give up loading rather than spin forever.
    var STALL_MS = 12000;

    var ANGLES = ['all', 'front', 'right', 'rear', 'left'];
    var UNMOUNTED = {
        internal: 'Internal storage is not available',
        sd: 'No SD card',
        usb: 'No USB storage'
    };

    var WHERE = {
        internal: 'Internal',
        sd: 'SD card',
        usb: 'USB'
    };

    var PER_PAGE = 20;
    var WEEKDAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
    var MONTHS = ['January', 'February', 'March', 'April', 'May', 'June',
        'July', 'August', 'September', 'October', 'November', 'December'];

    /** Parsed as local civil time. Date.parse on YYYY-MM-DD is UTC and slips a day. */
    function civil(iso) {
        var part = iso.split('-');
        return new Date(+part[0], +part[1] - 1, +part[2]);
    }

    function named(iso) {
        var at = civil(iso);
        var now = new Date();
        var start = new Date(now.getFullYear(), now.getMonth(), now.getDate());
        var then = new Date(at.getFullYear(), at.getMonth(), at.getDate());
        var ago = Math.round((start - then) / 86400000);
        var when = ago === 0 ? 'Today' : ago === 1 ? 'Yesterday' : WEEKDAYS[at.getDay()];
        var full = WEEKDAYS[at.getDay()] + ' ' + at.getDate() + ' ' + MONTHS[at.getMonth()] + ' ' + at.getFullYear();
        return { when: when, full: full, line: when + ' \u00b7 ' + full };
    }

    function clock(totalSeconds) {
        var whole = Math.max(0, Math.floor(totalSeconds));
        var seconds = whole % 60;
        var minutes = Math.floor(whole / 60);
        var tail = ':' + (seconds < 10 ? '0' : '') + seconds;
        if (minutes < 60) {
            return minutes + tail;
        }
        var rest = minutes % 60;
        return Math.floor(minutes / 60) + ':' + (rest < 10 ? '0' : '') + rest + tail;
    }

    function film() {
        var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        var body = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        var bars = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        body.setAttribute('d', FILM);
        bars.setAttribute('d', 'M8 4v16M16 4v16');
        svg.setAttribute('viewBox', '0 0 24 24');
        svg.setAttribute('fill', 'none');
        svg.setAttribute('stroke', 'currentColor');
        svg.setAttribute('stroke-width', '1.5');
        svg.setAttribute('aria-hidden', 'true');
        svg.appendChild(body);
        svg.appendChild(bars);
        return svg;
    }

    function glyph(d) {
        var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        var path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        path.setAttribute('d', d);
        svg.setAttribute('viewBox', '0 0 24 24');
        svg.setAttribute('fill', 'none');
        svg.setAttribute('stroke', 'currentColor');
        svg.setAttribute('stroke-width', '2');
        svg.setAttribute('stroke-linecap', 'round');
        svg.setAttribute('stroke-linejoin', 'round');
        svg.setAttribute('aria-hidden', 'true');
        svg.appendChild(path);
        return svg;
    }

    function empty(headline, detail) {
        var host = Strike.core.el('div', 'empty');
        host.appendChild(film());
        host.appendChild(Strike.core.el('p', 'empty__head', headline));
        host.appendChild(Strike.core.el('p', 'empty__note', detail));
        return host;
    }

    Strike.library = function (plan) {
        var rows = [];
        var page = 0;
        var reachable = true;
        var mounted = true;
        var place = 'internal';
        var payload = null;
        var query = '';
        var kind = 'all';
        var day = 'all';
        var playing = null;
        var armed = false;
        var armedRow = null;
        var days = null;
        var shots = {};
        var asking = {};
        var angle = 'all';
        var scrubbing = false;
        var stall = null;
        var full = false;
        var stamped = '';
        var canDownload = false;
        var car = false;
        var requested = window.location.hash.slice(1);

        function tagOf(row) {
            return plan.tag ? plan.tag(row) : row.kind;
        }

        function matches(row) {
            if (kind !== 'all' && tagOf(row) !== kind) {
                return false;
            }
            if (day !== 'all' && row.date !== day) {
                return false;
            }
            var haystack = row.date + ' ' + row.time + ' ' + tagOf(row);
            return !query || haystack.toLowerCase().indexOf(query) >= 0;
        }

        function chip(key) {
            return Strike.core.el('span', 'tag tag--' + key, plan.tags[key] || key);
        }

        // The codec is only known once the clip has been read, so the tag lands with the thumbnail.
        function chips(host, row) {
            host.appendChild(chip(tagOf(row)));
            var where = WHERE[place];
            if (where) {
                host.appendChild(Strike.core.el('span', 'tag tag--' + place, where));
            }
            var coded = Strike.core.el('span', 'tag');
            coded.hidden = true;
            host.appendChild(coded);
            var shot = shots[row.id];
            if (shot) {
                showCodec(coded, shot.codec);
            }
            return coded;
        }

        function showCodec(node, codec) {
            var label = CODECS[codec];
            if (!label) {
                node.hidden = true;
                return;
            }
            node.className = 'tag tag--' + codec;
            node.textContent = label;
            node.hidden = false;
        }

        function acts(row) {
            var host = Strike.core.el('span', 'clip__acts');

            var link = Strike.core.el('a', 'clip__act');
            link.href = plan.media + encodeURIComponent(row.id);
            link.setAttribute('download', row.id);
            link.setAttribute('aria-label', 'Download');
            link.hidden = !canDownload;
            link.appendChild(glyph(DOWNLOAD));

            var bin = Strike.core.el('button', 'clip__act');
            bin.type = 'button';
            bin.setAttribute('aria-label', 'Delete');
            bin.appendChild(glyph(TRASH));
            bin.onclick = function () {
                arm(row.id, this);
            };

            host.appendChild(link);
            host.appendChild(bin);
            return host;
        }

        function disarmRow() {
            if (!armedRow) {
                return;
            }
            armedRow.button.className = 'clip__act';
            armedRow.button.setAttribute('aria-label', 'Delete');
            armedRow = null;
        }

        function arm(id, button) {
            if (armedRow && armedRow.button !== button) {
                disarmRow();
            }
            if (!armedRow) {
                armedRow = { id: id, button: button };
                button.className = 'clip__act clip__act--armed';
                button.setAttribute('aria-label', 'Delete for good');
                return;
            }
            disarmRow();
            destroy(id);
        }

        function item(row) {
            var host = Strike.core.el('div', 'clip');
            var button = Strike.core.el('button', 'row row--clip');
            button.type = 'button';

            var box = Strike.core.el('span', 'clip__shot sk');
            var img = Strike.core.el('img', 'clip__img');
            img.alt = '';
            var len = Strike.core.el('span', 'clip__len');
            len.hidden = true;
            box.appendChild(len);

            var facts = Strike.core.el('span', 'clip__facts');
            facts.appendChild(Strike.core.el('span', 'clip__time', row.time));
            var line = Strike.core.el('span', 'clip__line');
            var coded = chips(line, row);
            line.appendChild(Strike.core.el('span', 'clip__meta', Strike.core.size(row.bytes)));
            facts.appendChild(line);

            button.appendChild(box);
            button.appendChild(facts);
            button.onclick = function () {
                open(row);
            };

            host.appendChild(button);
            host.appendChild(acts(row));
            preview(row.id, { box: box, img: img, len: len, coded: coded });
            return host;
        }

        // Reuse thumbnail requests across list rebuilds and update only the current nodes.
        function preview(id, nodes) {
            if (shots[id]) {
                fill(shots[id], nodes);
                return;
            }
            if (asking[id]) {
                asking[id] = nodes;
                return;
            }
            asking[id] = nodes;
            var xhr = new XMLHttpRequest();
            xhr.open('GET', plan.thumbs + encodeURIComponent(id), true);
            xhr.responseType = 'blob';
            xhr.onreadystatechange = function () {
                if (xhr.readyState !== 4) {
                    return;
                }
                if (xhr.status === 401) { Strike.session.signIn(); return; }
                var waiting = asking[id];
                delete asking[id];
                // Resolve failed thumbnails so truncated clips do not leave a permanent loading state.
                shots[id] = xhr.status === 200
                    ? {
                        url: URL.createObjectURL(xhr.response),
                        durationMs: parseInt(xhr.getResponseHeader(DURATION) || '0', 10),
                        codec: xhr.getResponseHeader(CODEC)
                    }
                    : { url: null, durationMs: 0, codec: null };
                fill(shots[id], waiting);
            };
            xhr.send();
        }

        function fill(shot, nodes) {
            nodes.box.className = 'clip__shot';
            if (nodes.coded) {
                showCodec(nodes.coded, shot.codec);
            }
            if (shot.url === null) {
                nodes.box.appendChild(film());
                return;
            }
            nodes.img.src = shot.url;
            nodes.box.insertBefore(nodes.img, nodes.box.firstChild);
            if (shot.durationMs > 0) {
                nodes.len.textContent = clock(shot.durationMs / 1000);
                nodes.len.hidden = false;
            }
        }

        function dayHead(iso) {
            var copy = named(iso);
            var host = Strike.core.el('h2', 'head head--day');
            host.appendChild(Strike.core.el('span', 'head__when', copy.when));
            host.appendChild(Strike.core.el('span', 'head__date', copy.full));
            return host;
        }

        function paint() {
            armedRow = null;
            var host = document.getElementById('clips');
            var count = document.getElementById('count');
            host.className = 'clips';
            host.innerHTML = '';
            count.textContent = '';

            if (!reachable) {
                host.appendChild(empty('Cannot reach Strike', 'The app is not answering on this device.'));
                return;
            }
            if (!mounted) {
                host.appendChild(empty(UNMOUNTED[place], plan.elsewhere));
                return;
            }

            var shown = [];
            for (var i = 0; i < rows.length; i++) {
                if (matches(rows[i])) {
                    shown.push(rows[i]);
                }
            }
            if (!shown.length) {
                var copy = plan.nothing(rows.length > 0, payload);
                host.appendChild(empty(copy.head, copy.note));
                return;
            }
            count.textContent = shown.length === 1
                ? '1 ' + plan.one
                : shown.length + ' ' + plan.many;

            var pages = Math.ceil(shown.length / PER_PAGE);
            if (page >= pages) {
                page = pages - 1;
            }
            var from = page * PER_PAGE;
            var onPage = shown.slice(from, from + PER_PAGE);

            var list = null;
            var group = '';
            for (var j = 0; j < onPage.length; j++) {
                if (onPage[j].date !== group) {
                    group = onPage[j].date;
                    host.appendChild(dayHead(group));
                    list = Strike.core.el('div', 'list');
                    host.appendChild(list);
                }
                list.appendChild(item(onPage[j]));
            }
            if (pages > 1) {
                host.appendChild(pager(pages));
            }
        }

        function pagesAround(at, pages) {
            var picked = {};
            var i;
            function take(n) {
                if (n >= 0 && n < pages) {
                    picked[n] = true;
                }
            }
            if (pages <= 7) {
                for (i = 0; i < pages; i++) {
                    take(i);
                }
            } else {
                take(0);
                take(pages - 1);
                take(at);
                take(at - 1);
                take(at + 1);
                if (at < 3) {
                    take(2);
                    take(3);
                }
                if (at > pages - 4) {
                    take(pages - 4);
                    take(pages - 3);
                }
            }
            var list = [];
            var last = -2;
            for (i = 0; i < pages; i++) {
                if (!picked[i]) {
                    continue;
                }
                if (last >= 0 && i - last > 1) {
                    list.push(null);
                }
                list.push(i);
                last = i;
            }
            return list;
        }

        function pager(pages) {
            var host = Strike.core.el('div', 'pager');
            host.appendChild(step('Prev', page > 0, -1));
            var nums = pagesAround(page, pages);
            for (var i = 0; i < nums.length; i++) {
                if (nums[i] === null) {
                    host.appendChild(Strike.core.el('span', 'pager__skip', '\u2026'));
                    continue;
                }
                host.appendChild(jump(nums[i]));
            }
            host.appendChild(step('Next', page < pages - 1, 1));
            return host;
        }

        function go(to) {
            page = to;
            paint();
            window.scrollTo(0, 0);
        }

        function step(label, usable, by) {
            var button = Strike.core.el('button', 'btn', label);
            button.type = 'button';
            button.disabled = !usable;
            button.onclick = function () {
                go(page + by);
            };
            return button;
        }

        function jump(to) {
            var button = Strike.core.el('button', 'btn pager__num', String(to + 1));
            button.type = 'button';
            if (to === page) {
                button.setAttribute('aria-current', 'page');
            }
            button.onclick = function () {
                if (to !== page) {
                    go(to);
                }
            };
            return button;
        }

        function paintDays() {
            var options = [{ value: 'all', label: 'All days' }];
            var seen = {};
            for (var i = 0; i < rows.length; i++) {
                if (seen[rows[i].date]) {
                    continue;
                }
                seen[rows[i].date] = true;
                options.push({ value: rows[i].date, label: named(rows[i].date).line });
            }
            if (days.fill(options)) {
                day = 'all';
            }
        }

        function drop(id) {
            var kept = [];
            for (var i = 0; i < rows.length; i++) {
                if (rows[i].id !== id) {
                    kept.push(rows[i]);
                }
            }
            rows = kept;
            paintDays();
            paint();
        }

        function load() {
            Strike.core.get(plan.rows, function (fresh) {
                reachable = true;
                mounted = fresh.mounted;
                place = fresh.location;
                payload = fresh;
                rows = fresh[plan.list];
                paintDays();
                paint();
                if (requested && mounted) {
                    var target = requested;
                    requested = '';
                    for (var i = 0; i < rows.length; i++) {
                        if (rows[i].id === target) {
                            open(rows[i]);
                            return;
                        }
                    }
                    document.getElementById('count').textContent = 'That clip is no longer available';
                }
            }, function () {
                reachable = false;
                rows = [];
                paint();
            });
        }

        function disarm() {
            armed = false;
            var button = document.getElementById('playerDelete');
            button.textContent = 'Delete';
            button.className = 'btn btn--quiet';
        }

        function codecOf(row) {
            var shot = shots[row.id];
            return shot ? shot.codec : null;
        }

        function stalled(row) {
            return codecOf(row) === 'h265' ? HEVC : UNPLAYABLE;
        }

        /** Null hides the layer. The spinner runs for LOADING only. */
        function waitLayer(copy) {
            document.getElementById('playerWait').hidden = copy === null;
            document.getElementById('playerSpin').hidden = copy !== LOADING;
            document.getElementById('playerWaitCopy').textContent = copy || '';
        }

        function open(row) {
            playing = row;
            disarm();
            disarmRow();
            look('all');
            document.getElementById('playerTitle').textContent = row.date + ' ' + row.time;
            var meta = document.getElementById('playerMeta');
            meta.innerHTML = '';
            chips(meta, row);
            meta.appendChild(Strike.core.el('span', 'clip__meta', Strike.core.size(row.bytes)));
            var media = plan.media + encodeURIComponent(row.id);
            var link = document.getElementById('playerDownload');
            link.href = media;
            link.setAttribute('download', row.id);
            link.hidden = !canDownload;
            var video = document.getElementById('playerVideo');
            video.controls = false;
            video.setAttribute('playsinline', '');
            video.setAttribute('webkit-playsinline', 'true');
            document.getElementById('playerFrame').className = 'player__frame';
            clearTimeout(stall);
            // A browser decodes HEVC, so only the car is stopped before it stalls for nothing.
            if (car && codecOf(row) === 'h265') {
                document.getElementById('player').hidden = false;
                waitLayer(HEVC);
                return;
            }
            waitLayer(LOADING);
            stall = setTimeout(function () {
                if (playing === row && video.readyState === 0) waitLayer(stalled(row));
            }, STALL_MS);
            video.src = media;
            document.getElementById('player').hidden = false;
            progress();
            var started = video.play();
            if (started && started.catch) {
                // Closing also rejects this promise, so ignore a clip that is gone.
                started.catch(function () {
                    if (playing === row) waitLayer(PRESS_PLAY);
                });
            }
        }

        function close() {
            clearTimeout(stall);
            document.getElementById('player').hidden = true;
            document.getElementById('playerFrame').className = 'player__frame';
            waitLayer(null);
            expand(false);
            var video = document.getElementById('playerVideo');
            video.pause();
            video.removeAttribute('src');
            video.load();
            document.getElementById('playerMarks').innerHTML = '';
            stamped = '';
            playing = null;
        }

        function stamp() {
            if (!playing) {
                return;
            }
            var total = document.getElementById('playerVideo').duration;
            if (!total || !isFinite(total)) {
                return;
            }
            var key = playing.id + ':' + total;
            if (stamped === key) {
                return;
            }
            stamped = key;
            var host = document.getElementById('playerMarks');
            host.innerHTML = '';
            var marks = playing.events;
            if (!marks || !marks.length) {
                return;
            }
            var durationMs = total * 1000;
            for (var i = 0; i < marks.length; i++) {
                var at = marks[i];
                var mark = Strike.core.el('span', 'scrub__mark scrub__mark--' + at.seen);
                mark.style.left = (at.startMs / durationMs * 100) + '%';
                mark.style.width = Math.max((at.endMs - at.startMs) / durationMs * 100, 0.5) + '%';
                host.appendChild(mark);
            }
        }

        function look(next) {
            angle = next;
            document.getElementById('playerFrame').setAttribute('data-angle', next);
            Strike.core.press(document.getElementById('angles'), next);
        }

        function expand(on) {
            full = on;
            document.getElementById('player').className = on ? 'modal modal--full' : 'modal';
            var button = document.getElementById('playerFull');
            button.setAttribute('aria-pressed', on ? 'true' : 'false');
            button.setAttribute('aria-label', on ? 'Leave fullscreen' : 'Fullscreen');
            document.getElementById('playerFullIcon').setAttribute('d', on ? SHRINK : GROW);
        }

        function progress() {
            var video = document.getElementById('playerVideo');
            var total = video.duration;
            var at = video.currentTime;
            document.getElementById('playerAt').textContent = clock(at || 0);
            var known = total && isFinite(total);
            document.getElementById('playerEnd').textContent = clock(known ? total : 0);
            var share = known ? Math.max(0, Math.min(100, (at / total) * 100)) : 0;
            document.getElementById('playerFill').style.width = share + '%';
            document.getElementById('playerKnob').style.left = share + '%';
            stamp();
        }

        function seek(event) {
            var video = document.getElementById('playerVideo');
            if (!video.duration || !isFinite(video.duration)) {
                return;
            }
            var scrub = document.getElementById('playerScrub');
            var box = scrub.getBoundingClientRect();
            var touch = event.touches && event.touches.length ? event.touches[0] : event;
            var share = box.width ? (touch.clientX - box.left) / box.width : 0;
            video.currentTime = Math.max(0, Math.min(1, share)) * video.duration;
            progress();
        }

        function wirePlayer() {
            var video = document.getElementById('playerVideo');
            var play = document.getElementById('playerPlay');
            var icon = play.getElementsByTagName('path')[0];
            var scrub = document.getElementById('playerScrub');

            play.onclick = function () {
                if (video.paused) {
                    video.play();
                } else {
                    video.pause();
                }
            };

            video.onplay = function () {
                icon.setAttribute('d', PAUSE);
                play.setAttribute('aria-label', 'Pause');
            };
            video.onplaying = function () {
                document.getElementById('playerFrame').className = 'player__frame is-on';
                waitLayer(null);
            };
            video.onerror = function () {
                // Clearing the source on close errors too, and leaves currentSrc empty.
                if (this.currentSrc) waitLayer(playing ? stalled(playing) : UNPLAYABLE);
            };
            video.onpause = function () {
                icon.setAttribute('d', PLAY);
                play.setAttribute('aria-label', 'Play');
            };
            video.ontimeupdate = progress;
            video.ondurationchange = progress;

            document.getElementById('playerFull').onclick = function () {
                expand(!full);
            };

            var mute = document.getElementById('playerMute');
            mute.onclick = function () {
                video.muted = !video.muted;
                this.setAttribute('aria-pressed', video.muted ? 'true' : 'false');
                document.getElementById('playerWaves').style.display = video.muted ? 'none' : '';
                document.getElementById('playerSlash').style.display = video.muted ? '' : 'none';
            };

            scrub.onmousedown = function (event) {
                scrubbing = true;
                seek(event);
            };
            document.addEventListener('mousemove', function (event) {
                if (scrubbing) {
                    seek(event);
                }
            });
            document.addEventListener('mouseup', function () {
                scrubbing = false;
            });
            scrub.addEventListener('touchstart', function (event) {
                scrubbing = true;
                seek(event);
                event.preventDefault();
            });
            scrub.addEventListener('touchmove', function (event) {
                if (scrubbing) {
                    seek(event);
                    event.preventDefault();
                }
            });
            scrub.addEventListener('touchend', function () {
                scrubbing = false;
            });

            document.getElementById('angles').onclick = function (event) {
                var button = Strike.core.buttonIn(event, this);
                if (!button) {
                    return;
                }
                var next = button.getAttribute('data-value');
                look(next === angle ? 'all' : next);
            };
        }

        function remove() {
            if (!armed) {
                armed = true;
                var button = document.getElementById('playerDelete');
                button.textContent = 'Delete for good';
                button.className = 'btn btn--danger';
                return;
            }
            var id = playing.id;
            close();
            destroy(id);
        }

        function destroy(id) {
            if (shots[id]) {
                URL.revokeObjectURL(shots[id].url);
                delete shots[id];
            }
            drop(id);
            Strike.core.del(plan.rows + '/' + encodeURIComponent(id), function () {
                load();
                Strike.settings.load();
                Strike.toast('Clip deleted');
            }, function () { load(); Strike.toast('Could not delete the clip', true); });
        }

        function wire() {
            document.getElementById('kind').onclick = function (event) {
                var button = Strike.core.buttonIn(event, this);
                if (!button) {
                    return;
                }
                kind = button.getAttribute('data-value');
                Strike.core.press(this, kind);
                page = 0;
                paint();
            };

            document.getElementById('search').oninput = function () {
                query = this.value.toLowerCase();
                page = 0;
                paint();
            };

            days = Strike.pick('day', function (chosen) {
                day = chosen;
                page = 0;
                paint();
            });

            document.getElementById('playerClose').onclick = close;
            document.getElementById('playerDelete').onclick = remove;
            document.getElementById('player').onclick = function (event) {
                if (event.target !== this) {
                    return;
                }
                if (full) {
                    expand(false);
                    return;
                }
                close();
            };

            wirePlayer();

            document.onkeydown = function (event) {
                if (event.keyCode === 27) {
                    if (playing !== null && full) {
                        expand(false);
                        return;
                    }
                    close();
                    days.close();
                    Strike.settings.close();
                    return;
                }
                if (playing === null) {
                    return;
                }
                var digit = event.keyCode - 48;
                if (digit < 0 || digit > 4) {
                    digit = event.keyCode - 96;
                }
                if (digit >= 0 && digit <= 4) {
                    look(ANGLES[digit]);
                }
            };
        }

        function inCar(state) {
            car = state === true;
            var next = state === false;
            if (next === canDownload) {
                return;
            }
            canDownload = next;
            document.getElementById('playerDownload').hidden = !canDownload;
            if (payload) {
                paint();
            }
        }

        wire();
        window.addEventListener('hashchange', function () {
            requested = window.location.hash.slice(1);
            if (playing) close();
            if (requested) load();
        });
        return { load: load, inCar: inCar };
    };
}());

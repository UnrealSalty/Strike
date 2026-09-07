(function () {
    'use strict';

    window.Strike = window.Strike || {};

    var CAMERAS = '/api/live/cameras';
    var FLAG_KEYFRAME = 1;
    var FLAG_CONFIG = 2;
    var HEADER_BYTES = 9;
    var TIMESCALE = 90000;

    var FRAME_TICKS = TIMESCALE / 12;

    var CAMERA = 'M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7-10-7-10-7z';

    var SPOTS = [
        { value: 'front', label: 'FRONT' },
        { value: 'right', label: 'RIGHT' },
        { value: 'rear', label: 'REAR' },
        { value: 'left', label: 'LEFT' },
        { value: 'all', label: 'ALL' }
    ];

    var socket = null;
    var media = null;
    var buffer = null;
    var pending = [];
    var sequence = 1;
    var decodeTime = 0;
    var sps = null;
    var pps = null;
    var drawn = false;
    var angle = 'all';
    var cameras = [];
    var ticket = 0;
    var shotW = 0;
    var shotH = 0;

    function eye() {
        var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        var lens = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        var pupil = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
        lens.setAttribute('d', CAMERA);
        pupil.setAttribute('cx', '12');
        pupil.setAttribute('cy', '12');
        pupil.setAttribute('r', '3');
        svg.setAttribute('viewBox', '0 0 24 24');
        svg.setAttribute('fill', 'none');
        svg.setAttribute('stroke', 'currentColor');
        svg.setAttribute('stroke-width', '1.5');
        svg.setAttribute('aria-hidden', 'true');
        svg.appendChild(lens);
        svg.appendChild(pupil);
        return svg;
    }

    function idle(headline, detail) {
        var host = document.getElementById('idle');
        host.innerHTML = '';
        host.hidden = false;
        host.appendChild(eye());
        host.appendChild(Strike.core.el('p', 'empty__head', headline));
        host.appendChild(Strike.core.el('p', 'empty__note', detail));
        document.getElementById('badge').hidden = true;
        document.getElementById('viewName').hidden = true;
    }

    function live() {
        document.getElementById('stage').className = 'stage is-on';
        document.getElementById('idle').hidden = true;
        document.getElementById('badge').hidden = false;
        document.getElementById('badgeDot').setAttribute('data-state', 'ok');
        document.getElementById('badgeText').textContent = 'Live';
        paintLabel();
    }

    function panoramic() {
        for (var i = 0; i < cameras.length; i++) {
            if (cameras[i].width >= cameras[i].height * 3) {
                return cameras[i];
            }
        }
        return null;
    }

    function offered() {
        if (panoramic()) {
            return { all: true, front: true, right: true, rear: true, left: true };
        }
        var can = {};
        for (var i = 0; i < cameras.length; i++) {
            can[cameras[i].tag] = true;
        }
        return can;
    }

    function paintSpots() {
        var can = offered();
        var host = document.getElementById('spots');
        var old = host.querySelectorAll('.car__spot');
        var i;
        for (i = 0; i < old.length; i++) {
            host.removeChild(old[i]);
        }
        for (i = 0; i < SPOTS.length; i++) {
            var spot = SPOTS[i];
            var usable = can[spot.value] === true;
            var button = Strike.core.el('button', 'car__spot car__spot--' + spot.value);
            button.type = 'button';
            button.disabled = !usable;
            button.setAttribute('aria-pressed', usable && spot.value === angle ? 'true' : 'false');
            button.appendChild(Strike.core.el('span', 'car__ring'));
            button.appendChild(Strike.core.el('span', 'car__name', spot.label));
            if (usable) {
                button.onclick = choose(spot.value);
            }
            host.appendChild(button);
        }
    }

    function look(value) {
        angle = value;
        document.getElementById('shot').setAttribute('data-angle', value);
        paintSpots();
        paintLabel();
    }

    function choose(value) {
        return function () {
            var dead = !drawn && !socket;
            look(value);
            if (dead) {
                reset('Starting the camera', 'Opening the ' + value + ' view.');
                connect();
            }
        };
    }

    function paintLabel() {
        var name = document.getElementById('viewName');
        var chosen = null;
        for (var i = 0; i < SPOTS.length; i++) {
            if (SPOTS[i].value === angle) {
                chosen = SPOTS[i];
            }
        }
        name.hidden = !drawn || !chosen;
        if (chosen) {
            name.textContent = chosen.value === 'all' ? 'All cameras' : chosen.label.charAt(0) +
                chosen.label.slice(1).toLowerCase() + ' camera';
        }
    }

    function socState(soc) {
        return soc > 50 ? null : (soc <= 20 ? 'bad' : 'warn');
    }

    function number(vehicle, key) {
        return vehicle && typeof vehicle[key] === 'number' ? vehicle[key] : null;
    }

    function vehicle(reading) {
        var soc = number(reading, 'soc');
        var rangeKm = number(reading, 'rangeKm');
        var kwh = number(reading, 'batteryKwh');
        Strike.core.value('soc', soc === null ? null : soc + ' %');
        Strike.core.value('range', rangeKm === null ? null : rangeKm + ' km');
        Strike.core.value('kwh', kwh === null ? null : kwh.toFixed(1) + ' kWh');
        Strike.core.meter('socFill', soc, soc === null ? null : socState(soc));
    }

    function render(status) {
        vehicle(status.vehicle);
    }

    function forget() {
        Strike.core.value('soc', null);
        Strike.core.value('range', null);
        Strike.core.value('kwh', null);
        Strike.core.meter('socFill', null);
    }

    function feed(bytes) {
        pending.push(bytes);
        drain();
    }

    function drain() {
        if (!buffer || buffer.updating || !pending.length) {
            return;
        }
        try {
            buffer.appendBuffer(pending.shift());
        } catch (error) {
            reset('The browser refused the video stream', 'Reload the page to try again.');
        }
    }

    function openBuffer() {
        var frame = Strike.fmp4.sizeOf(sps);
        if (!frame) {
            reset('The camera stream is unreadable', 'It arrived without a usable picture size.');
            return false;
        }
        var codec = Strike.fmp4.codecOf(sps);
        var mime = 'video/mp4; codecs="' + codec + '"';
        if (!window.MediaSource || !MediaSource.isTypeSupported(mime)) {
            reset('This browser cannot play the camera', 'It has no support for ' + codec + '.');
            return false;
        }
        buffer = media.addSourceBuffer(mime);
        buffer.mode = 'segments';
        buffer.addEventListener('updateend', drain);
        feed(Strike.fmp4.init(frame.width, frame.height, sps, pps));
        shotW = frame.width;
        shotH = frame.height;
        fitShot();
        var picture = document.getElementById('video');
        var started = picture.play();
        if (started && started.catch) {
            started.catch(function () {});
        }
        return true;
    }

    function config(bytes) {
        var nals = Strike.fmp4.split(bytes);
        for (var i = 0; i < nals.length; i++) {
            var type = Strike.fmp4.nalType(nals[i]);
            if (type === 7) {
                sps = nals[i];
            }
            if (type === 8) {
                pps = nals[i];
            }
        }
        if (sps && pps && !buffer && media && media.readyState === 'open') {
            openBuffer();
        }
    }

    function frame(bytes, keyFrame) {
        if (!buffer) {
            config(bytes);
            if (!buffer) {
                return;
            }
        }
        var nals = [];
        var split = Strike.fmp4.split(bytes);
        for (var i = 0; i < split.length; i++) {
            var type = Strike.fmp4.nalType(split[i]);
            if (type !== 7 && type !== 8) {
                nals.push(split[i]);
            }
        }
        if (!nals.length) {
            return;
        }
        feed(Strike.fmp4.segment(sequence, decodeTime, FRAME_TICKS, nals, keyFrame));
        sequence++;
        decodeTime += FRAME_TICKS;
    }

    function packet(data) {
        var bytes = new Uint8Array(data);
        if (bytes.length <= HEADER_BYTES) {
            return;
        }
        var flags = bytes[0];
        var body = bytes.subarray(HEADER_BYTES);
        if (flags & FLAG_CONFIG) {
            config(body);
            return;
        }
        frame(body, (flags & FLAG_KEYFRAME) !== 0);
    }

    function fitShot() {
        var stage = document.getElementById('stage');
        var shot = document.getElementById('shot');
        if (!shotW || !shotH) {
            return;
        }
        var scale = Math.min(stage.clientWidth / shotW, stage.clientHeight / shotH);
        shot.style.width = Math.round(shotW * scale) + 'px';
        shot.style.height = Math.round(shotH * scale) + 'px';
    }

    function reset(headline, detail) {
        ticket++;
        document.getElementById('stage').className = 'stage';
        document.getElementById('idle').hidden = false;
        if (socket) {
            socket.onmessage = null;
            socket.onclose = null;
            socket.onerror = null;
            socket.close();
            socket = null;
        }
        buffer = null;
        media = null;
        pending = [];
        sps = null;
        pps = null;
        drawn = false;
        sequence = 1;
        decodeTime = 0;
        var video = document.getElementById('video');
        video.controls = false;
        video.pause();
        video.removeAttribute('src');
        video.load();
        idle(headline, detail);
    }

    function connect() {
        var video = document.getElementById('video');
        var mine = ticket;
        video.setAttribute('webkit-playsinline', 'true');
        video.controls = false;
        if (!window.MediaSource) {
            idle('This browser cannot play the camera', 'It has no Media Source support.');
            return;
        }
        media = new MediaSource();
        video.src = URL.createObjectURL(media);
        media.addEventListener('sourceopen', function () {
            if (mine !== ticket) {
                return;
            }
            if (sps && pps && !buffer) {
                openBuffer();
            }
        });

        var url = (location.protocol === 'https:' ? 'wss:' : 'ws:') + '//' + location.host +
            '/live/stream?view=all';
        socket = new WebSocket(url);
        socket.binaryType = 'arraybuffer';
        socket.onmessage = function (event) {
            if (mine !== ticket) {
                return;
            }
            packet(event.data);
        };
        socket.onclose = function () {
            if (mine !== ticket) {
                return;
            }
            reset('Camera stopped', 'The camera daemon closed the stream.');
        };
        socket.onerror = function () {
            if (mine !== ticket) {
                return;
            }
            reset('Cannot reach the camera', 'The camera daemon is not streaming.');
        };

        function first() {
            if (mine !== ticket || drawn || video.readyState < 2) {
                return;
            }
            drawn = true;
            live();
        }

        video.onloadeddata = first;
        video.ontimeupdate = first;
    }

    function first(can) {
        if (can.all) {
            return 'all';
        }
        for (var i = 0; i < SPOTS.length; i++) {
            if (can[SPOTS[i].value]) {
                return SPOTS[i].value;
            }
        }
        return 'all';
    }

    function load() {
        Strike.core.get(CAMERAS, function (payload) {
            cameras = payload.cameras || [];
            look(first(offered()));
            if (!cameras.length) {
                idle('No camera yet', payload.reason || 'The camera daemon is not running.');
                return;
            }
            idle('Starting the camera', 'The daemon is opening the camera.');
            connect();
        }, function () {
            cameras = [];
            paintSpots();
            idle('Cannot reach Strike', 'The app is not answering on this device.');
        });
    }

    function veil() {
        document.getElementById('stage').className = 'stage';
    }

    paintSpots();
    load();
    window.addEventListener('resize', fitShot);
    window.addEventListener('pagehide', veil);
    document.querySelector('.nav').addEventListener('click', veil);
    Strike.shell.start(render, forget);
}());

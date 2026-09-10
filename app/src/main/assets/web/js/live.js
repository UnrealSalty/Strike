(function () {
    'use strict';

    window.Strike = window.Strike || {};

    var CAMERAS = '/api/live/cameras';
    var FLAG_KEYFRAME = 1;
    var FLAG_CONFIG = 2;
    var HEADER_BYTES = 9;
    var TIMESCALE = 90000;

    var FRAME_TICKS = TIMESCALE / 12;

    // A dropped poll is news about the link, not about the car. Hold the last
    // reading through a dip, then stop claiming it is current.
    var STALE_MS = 30000;

    var CAMERA = 'M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7-10-7-10-7z';

    var SPOTS = [
        { value: 'front', label: 'FRONT', deg: 0 },
        { value: 'right', label: 'RIGHT', deg: 90 },
        { value: 'rear', label: 'REAR', deg: 180 },
        { value: 'left', label: 'LEFT', deg: 270 },
        { value: 'all', label: 'ALL' }
    ];

    var DISC = 108, CONE_OUTER = 104, CONE_INNER = 54;
    var HIT_OUTER = 106, HIT_INNER = 44;
    var CONE_SPAN = 142, TURN_MS = 200;

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
    var mediaUrl = null;
    var readAtMs = 0;
    var quality = Strike.liveQuality(function () {
        if (!socket && !drawn) return;
        reset('Changing stream quality', '');
        connect();
    });

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

    function conePoint(r, deg) {
        var a = deg * Math.PI / 180;
        return (DISC + r * Math.sin(a)) + ',' + (DISC - r * Math.cos(a));
    }

    function coneSector(ro, ri, from, to) {
        var large = (to - from) > 180 ? 1 : 0;
        return 'M' + conePoint(ro, from) + 'A' + ro + ',' + ro + ' 0 ' + large + ' 1 ' + conePoint(ro, to) +
            'L' + conePoint(ri, to) + 'A' + ri + ',' + ri + ' 0 ' + large + ' 0 ' + conePoint(ri, from) + 'Z';
    }

    var coneDeg = 0;
    var coneRaf = 0;

    // The SVG transform attribute is not CSS-animatable on the head unit's WebView, so tween it by hand.
    function turnCone(target) {
        var start = coneDeg;
        var delta = ((target - start + 180) % 360 + 360) % 360 - 180;
        var begun = 0;
        if (coneRaf) {
            cancelAnimationFrame(coneRaf);
        }
        function step(now) {
            if (!begun) {
                begun = now;
            }
            var p = Math.min(1, (now - begun) / TURN_MS);
            var ease = p < 0.5 ? 2 * p * p : 1 - Math.pow(-2 * p + 2, 2) / 2;
            coneDeg = start + delta * ease;
            document.getElementById('coneGlow').setAttribute('transform',
                'rotate(' + coneDeg + ' ' + DISC + ' ' + DISC + ')');
            if (p < 1) {
                coneRaf = requestAnimationFrame(step);
            } else {
                coneDeg = start + delta;
            }
        }
        coneRaf = requestAnimationFrame(step);
    }

    function paintGlow(value) {
        var cone = document.getElementById('conePath');
        var ring = document.getElementById('coneRing');
        var deg = null;
        for (var i = 0; i < SPOTS.length; i++) {
            if (SPOTS[i].value === value && typeof SPOTS[i].deg === 'number') {
                deg = SPOTS[i].deg;
            }
        }
        if (deg === null) {
            cone.setAttribute('opacity', '0');
            ring.setAttribute('opacity', '1');
            return;
        }
        cone.setAttribute('opacity', '1');
        ring.setAttribute('opacity', '0');
        turnCone(deg);
    }

    function named(spot) {
        return spot.value === 'all' ? 'All cameras' :
            spot.label.charAt(0) + spot.label.slice(1).toLowerCase() + ' camera';
    }

    function paintSpots() {
        var can = offered();
        var host = document.getElementById('spots');
        host.innerHTML = '';
        for (var i = 0; i < SPOTS.length; i++) {
            var spot = SPOTS[i];
            if (can[spot.value] !== true) {
                continue;
            }
            var svgns = 'http://www.w3.org/2000/svg';
            var hit = document.createElementNS(svgns, spot.value === 'all' ? 'circle' : 'path');
            hit.setAttribute('class', 'picker__hit');
            hit.setAttribute('role', 'button');
            hit.setAttribute('aria-label', named(spot));
            hit.setAttribute('data-value', spot.value);
            hit.setAttribute('aria-pressed', spot.value === angle ? 'true' : 'false');
            if (spot.value === 'all') {
                hit.setAttribute('cx', DISC);
                hit.setAttribute('cy', DISC);
                hit.setAttribute('r', 40);
            } else {
                hit.setAttribute('d', coneSector(HIT_OUTER, HIT_INNER, spot.deg - 45, spot.deg + 45));
            }
            hit.onclick = choose(spot.value);
            host.appendChild(hit);
        }
    }

    function pressSpots(value) {
        var hits = document.getElementById('spots').childNodes;
        for (var i = 0; i < hits.length; i++) {
            hits[i].setAttribute('aria-pressed',
                hits[i].getAttribute('data-value') === value ? 'true' : 'false');
        }
    }

    function look(value) {
        angle = value;
        document.getElementById('shot').setAttribute('data-angle', value);
        paintGlow(value);
        pressSpots(value);
        paintLabel();
    }

    function choose(value) {
        return function () {
            var target = (value === angle && value !== 'all') ? 'all' : value;
            var dead = !drawn && !socket;
            look(target);
            if (dead) {
                reset('Starting the camera',
                    target === 'all' ? 'Opening all cameras.' : 'Opening the ' + target + ' view.');
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

    function range(reading) {
        var ev = number(reading, 'rangeKm');
        var fuelKm = number(reading, 'fuelRangeKm');
        if (ev === null && fuelKm === null) {
            return null;
        }
        return ((ev || 0) + (fuelKm || 0)) + ' km';
    }

    // A car that burns fuel shows both levels, so the low battery colour would read as a fuel state.
    function gauge(soc, fuel) {
        var hybrid = fuel !== null;
        document.getElementById('fuelBit').hidden = !hybrid;
        document.getElementById('vehicleMeter').className = hybrid ? 'meter meter--mix' : 'meter';
        Strike.core.meter('socFill', soc, (hybrid || soc === null) ? null : socState(soc));
        Strike.core.meter('fuelFill', hybrid ? fuel : null);
    }

    function vehicle(reading) {
        var soc = number(reading, 'soc');
        var kwh = number(reading, 'batteryKwh');
        var fuel = number(reading, 'fuelPercent');
        Strike.core.value('soc', soc === null ? null : soc + ' %');
        Strike.core.value('fuel', fuel === null ? null : fuel + ' %');
        Strike.core.value('range', range(reading));
        Strike.core.value('kwh', kwh === null ? null : kwh.toFixed(1) + ' kWh');
        gauge(soc, fuel);
    }

    function render(status, cached) {
        if (status.vehicle && !cached) {
            readAtMs = Date.now();
        }
        vehicle(status.vehicle);
        quality.inCar(status.inCar);
    }

    function forget() {
        if (Date.now() - readAtMs <= STALE_MS) {
            return;
        }
        Strike.core.value('soc', null);
        Strike.core.value('fuel', null);
        Strike.core.value('range', null);
        Strike.core.value('kwh', null);
        gauge(null, null);
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
        if (mediaUrl) { URL.revokeObjectURL(mediaUrl); mediaUrl = null; }
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
        mediaUrl = URL.createObjectURL(media);
        video.src = mediaUrl;
        media.addEventListener('sourceopen', function () {
            if (mine !== ticket) {
                return;
            }
            if (sps && pps && !buffer) {
                openBuffer();
            }
        });

        var url = (location.protocol === 'https:' ? 'wss:' : 'ws:') + '//' + location.host +
            '/live/stream?view=all&quality=' + quality.value();
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
            paintSpots();
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

    document.getElementById('conePath').setAttribute('d',
        coneSector(CONE_OUTER, CONE_INNER, -CONE_SPAN / 2, CONE_SPAN / 2));
    paintSpots();
    paintGlow(angle);
    load();
    window.addEventListener('resize', fitShot);
    Strike.shell.start(render, forget);
}());

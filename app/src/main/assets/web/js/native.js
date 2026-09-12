(function () {
    'use strict';

    window.Strike = window.Strike || {};

    // MediaPlayer has no timeupdate, so the page asks where it is.
    var TICK_MS = 200;

    var bridge = window.StrikeNative || null;
    var handlers = {};
    var ticking = null;
    var following = null;
    var pending = false;

    function fire(name, value) {
        if (handlers[name]) {
            handlers[name](value);
        }
    }

    function measure(frame) {
        var box = frame.getBoundingClientRect();
        var ratio = window.devicePixelRatio || 1;
        var radius = parseFloat(window.getComputedStyle(frame).borderTopLeftRadius) || 0;
        return {
            left: Math.round(box.left * ratio),
            top: Math.round(box.top * ratio),
            width: Math.round(box.width * ratio),
            height: Math.round(box.height * ratio),
            corner: Math.round(radius * ratio)
        };
    }

    function settle() {
        pending = false;
        if (!following) {
            return;
        }
        var at = measure(following);
        bridge.setRect(at.left, at.top, at.width, at.height, at.corner);
    }

    function reflow() {
        if (pending || !following) {
            return;
        }
        pending = true;
        window.requestAnimationFrame(settle);
    }

    Strike.native = {
        available: function () {
            return !!bridge;
        },

        on: function (name, handler) {
            handlers[name] = handler;
        },

        play: function (url, frame, angle, muted) {
            var at = measure(frame);
            following = frame;
            bridge.play(url, at.left, at.top, at.width, at.height, at.corner, angle, muted);
        },

        /** Call after anything that can move the frame. */
        sync: reflow,

        watch: function (onTick) {
            if (ticking !== null) {
                clearInterval(ticking);
                ticking = null;
            }
            if (onTick) {
                ticking = setInterval(onTick, TICK_MS);
            }
        },

        setPlaying: function (playing) {
            bridge.setPlaying(playing);
        },

        seek: function (positionMs) {
            bridge.seek(positionMs);
        },

        setMuted: function (muted) {
            bridge.setMuted(muted);
        },

        setAngle: function (angle) {
            bridge.setAngle(angle);
        },

        positionMs: function () {
            return bridge.positionMs();
        },

        stop: function () {
            following = null;
            Strike.native.watch(null);
            bridge.stop();
        },

        _ready: function (durationMs) {
            fire('ready', durationMs);
        },

        _firstFrame: function () {
            fire('firstFrame');
        },

        _seeked: function () {
            fire('seeked');
        },

        _ended: function () {
            fire('ended');
        },

        _error: function () {
            fire('error');
        },

        _paused: function () {
            fire('paused');
        }
    };

    if (bridge) {
        window.addEventListener('resize', reflow);
        window.addEventListener('scroll', reflow, true);
    }
}());

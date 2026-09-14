(function () {
    'use strict';

    window.Strike = window.Strike || {};

    var TIMESCALE = 90000;
    var MAX_PENDING = 24;
    var MAX_LAG_SECONDS = 1.5;
    var KEEP_SECONDS = 8;

    Strike.liveMse = {
        open: function (media, picture, size, sps, pps, onFail) {
            var codec = Strike.fmp4.codecOf(sps);
            var mime = 'video/mp4; codecs="' + codec + '"';
            if (!window.MediaSource || !MediaSource.isTypeSupported(mime)) {
                onFail('This browser cannot play the camera', 'It has no support for ' + codec + '.');
                return null;
            }
            var buffer;
            try {
                buffer = media.addSourceBuffer(mime);
                buffer.mode = 'segments';
            } catch (error) {
                onFail('This browser cannot play the camera', 'It could not open the video stream.');
                return null;
            }
            var pending = [];
            var previous = null;
            var originUs = null;
            var sequence = 1;
            var waitingForKey = true;
            var closed = false;
            var trimmedAt = 0;

            function close() {
                if (closed) return;
                closed = true;
                pending = [];
                previous = null;
                buffer.removeEventListener('updateend', drain);
                buffer.removeEventListener('error', failed);
            }

            function failed() {
                if (closed) return;
                close();
                onFail('The browser refused the video stream', 'Reload the page to try again.');
            }

            function play() {
                var started = picture.play();
                if (started && started.catch) started.catch(function () {});
            }

            function drain() {
                if (closed || buffer.updating) return;
                try {
                    var ranges = buffer.buffered;
                    if (ranges.length) {
                        var start = ranges.start(ranges.length - 1);
                        var end = ranges.end(ranges.length - 1);
                        if (picture.currentTime < start || end - picture.currentTime > MAX_LAG_SECONDS) {
                            picture.currentTime = Math.max(start, end - 0.15);
                            if (picture.paused) play();
                        }
                        var trim = Math.floor(picture.currentTime - KEEP_SECONDS);
                        if (trim >= trimmedAt + 5 && ranges.start(0) < trim) {
                            trimmedAt = trim;
                            buffer.remove(0, trim);
                            return;
                        }
                    }
                    if (pending.length) buffer.appendBuffer(pending.shift().bytes);
                } catch (error) {
                    failed();
                }
            }

            buffer.addEventListener('updateend', drain);
            buffer.addEventListener('error', failed);
            try {
                buffer.appendBuffer(Strike.fmp4.init(size.width, size.height, sps, pps));
                play();
            } catch (error) {
                failed();
                return null;
            }

            return {
                push: function (nals, keyFrame, timeUs) {
                    if (closed || typeof timeUs !== 'number' || !isFinite(timeUs) || timeUs < 0) return;
                    if (pending.length >= MAX_PENDING ||
                            (pending.length && timeUs - pending[0].timeUs > MAX_LAG_SECONDS * 1000000)) {
                        pending = [];
                        previous = null;
                        waitingForKey = true;
                    }
                    if (waitingForKey && !keyFrame) return;
                    waitingForKey = false;
                    if (originUs === null) originUs = timeUs;
                    var ticks = Math.round((timeUs - originUs) * TIMESCALE / 1000000);
                    if (previous) {
                        ticks = Math.max(ticks, previous.ticks + 1);
                        pending.push({
                            bytes: Strike.fmp4.segment(sequence++, previous.ticks,
                                ticks - previous.ticks, previous.nals, previous.keyFrame),
                            timeUs: previous.timeUs
                        });
                    }
                    previous = { nals: nals, keyFrame: keyFrame, timeUs: timeUs, ticks: ticks };
                    drain();
                },
                close: close
            };
        }
    };
}());

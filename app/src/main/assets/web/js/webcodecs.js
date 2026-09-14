(function () {
    'use strict';

    window.Strike = window.Strike || {};

    // WebKit's VideoDecoder wants AVCC: an avcC description plus length-prefixed
    // samples. Annex-B (no description) decodes on Chromium but not on iOS.
    function record(sps, pps) {
        var out = new Uint8Array(11 + sps.length + pps.length);
        out[0] = 1;
        out[1] = sps[1];
        out[2] = sps[2];
        out[3] = sps[3];
        out[4] = 0xFF;
        out[5] = 0xE1;
        out[6] = (sps.length >>> 8) & 0xFF;
        out[7] = sps.length & 0xFF;
        out.set(sps, 8);
        var at = 8 + sps.length;
        out[at] = 1;
        out[at + 1] = (pps.length >>> 8) & 0xFF;
        out[at + 2] = pps.length & 0xFF;
        out.set(pps, at + 3);
        return out;
    }

    function lengthPrefixed(nals) {
        var total = 0;
        var i;
        for (i = 0; i < nals.length; i++) {
            total += 4 + nals[i].length;
        }
        var out = new Uint8Array(total);
        var at = 0;
        for (i = 0; i < nals.length; i++) {
            var size = nals[i].length;
            out[at] = (size >>> 24) & 0xFF;
            out[at + 1] = (size >>> 16) & 0xFF;
            out[at + 2] = (size >>> 8) & 0xFF;
            out[at + 3] = size & 0xFF;
            out.set(nals[i], at + 4);
            at += 4 + size;
        }
        return out;
    }

    Strike.webcodecs = {
        supported: function () {
            return !!window.VideoDecoder;
        },

        // Returns a sink { push, close } or null after reporting through onFail.
        open: function (canvas, sps, pps, size, onDraw, onFail) {
            if (!window.VideoDecoder) {
                return null;
            }
            var ctx = canvas.getContext('2d');
            if (!ctx) {
                onFail('This browser cannot play the camera', 'It has no canvas support.');
                return null;
            }
            canvas.width = size.width;
            canvas.height = size.height;
            var codec = Strike.fmp4.codecOf(sps);
            var configuration = { codec: codec, description: record(sps, pps), optimizeForLatency: true };
            var drew = false;
            var closed = false;
            var sawKey = false;
            var decoder = null;
            var generation = 0;
            var MAX_QUEUED_FRAMES = 6;

            function release() {
                var held = decoder;
                decoder = null;
                generation++;
                if (held && held.state !== 'closed') {
                    held.close();
                }
            }

            function fail(detail) {
                if (closed) return;
                closed = true;
                release();
                onFail('This browser cannot play the camera', detail);
            }

            function configure() {
                var current = ++generation;
                try {
                    decoder = new VideoDecoder({
                        output: function (picture) {
                            try {
                                if (closed || current !== generation) return;
                                ctx.drawImage(picture, 0, 0, canvas.width, canvas.height);
                                if (!drew) {
                                    drew = true;
                                    onDraw();
                                }
                            } catch (error) {
                                fail('Its video decoder failed.');
                            } finally {
                                picture.close();
                            }
                        },
                        error: function () {
                            if (!closed && current === generation) {
                                fail('Its video decoder failed.');
                            }
                        }
                    });
                    decoder.configure(configuration);
                    return true;
                } catch (error) {
                    fail('It has no support for ' + codec + '.');
                    return false;
                }
            }

            if (!configure()) return null;
            return {
                push: function (nals, keyFrame, timeUs) {
                    if (closed || typeof timeUs !== 'number' || !isFinite(timeUs) || timeUs < 0) return;
                    if (decoder && decoder.decodeQueueSize >= MAX_QUEUED_FRAMES) {
                        release();
                        sawKey = false;
                    }
                    if (!sawKey && !keyFrame) return;
                    if (!decoder && !configure()) return;
                    if (decoder.state !== 'configured') return;
                    try {
                        var chunk = new EncodedVideoChunk({
                            type: keyFrame ? 'key' : 'delta',
                            timestamp: timeUs,
                            data: lengthPrefixed(nals)
                        });
                        decoder.decode(chunk);
                        sawKey = true;
                    } catch (error) {
                        fail('Its video decoder failed.');
                    }
                },
                close: function () {
                    if (closed) return;
                    closed = true;
                    release();
                }
            };
        }
    };
}());

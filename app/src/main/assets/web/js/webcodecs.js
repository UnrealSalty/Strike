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
            var drew = false;
            var closed = false;
            var sawKey = false;
            var timeUs = 0;
            var FRAME_US = 1000000 / 12;
            var decoder = new VideoDecoder({
                output: function (picture) {
                    if (!closed) {
                        try {
                            ctx.drawImage(picture, 0, 0, canvas.width, canvas.height);
                        } catch (error) {
                        }
                        if (!drew) {
                            drew = true;
                            onDraw();
                        }
                    }
                    picture.close();
                },
                error: function () {
                    if (!closed) {
                        onFail('This browser cannot play the camera', 'Its video decoder failed.');
                    }
                }
            });
            try {
                decoder.configure({ codec: codec, description: record(sps, pps), optimizeForLatency: true });
            } catch (error) {
                onFail('This browser cannot play the camera', 'It has no support for ' + codec + '.');
                return null;
            }
            return {
                push: function (nals, keyFrame) {
                    if (closed || decoder.state !== 'configured') {
                        return;
                    }
                    if (!sawKey && !keyFrame) {
                        return;
                    }
                    sawKey = true;
                    var chunk;
                    try {
                        chunk = new EncodedVideoChunk({
                            type: keyFrame ? 'key' : 'delta',
                            timestamp: timeUs,
                            data: lengthPrefixed(nals)
                        });
                    } catch (error) {
                        return;
                    }
                    timeUs += FRAME_US;
                    try {
                        decoder.decode(chunk);
                    } catch (error) {
                        if (!closed) {
                            onFail('This browser cannot play the camera', 'Its video decoder failed.');
                        }
                    }
                },
                close: function () {
                    closed = true;
                    try {
                        if (decoder.state !== 'closed') {
                            decoder.close();
                        }
                    } catch (error) {
                    }
                }
            };
        }
    };
}());

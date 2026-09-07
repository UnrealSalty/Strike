(function () {
    'use strict';

    window.Strike = window.Strike || {};

    // Chrome 58 uses MSE for Live; wrap H.264 samples as fragmented MP4.

    var TIMESCALE = 90000;
    var TRACK = 1;

    function str(text) {
        var bytes = [];
        for (var i = 0; i < text.length; i++) {
            bytes.push(text.charCodeAt(i) & 0xFF);
        }
        return bytes;
    }

    function u32(value) {
        return [(value >>> 24) & 0xFF, (value >>> 16) & 0xFF, (value >>> 8) & 0xFF, value & 0xFF];
    }

    function u16(value) {
        return [(value >>> 8) & 0xFF, value & 0xFF];
    }

    function u64(value) {
        return u32(Math.floor(value / 4294967296)).concat(u32(value >>> 0));
    }

    function box(type) {
        var payload = [];
        for (var i = 1; i < arguments.length; i++) {
            payload = payload.concat(arguments[i]);
        }
        return u32(payload.length + 8).concat(str(type), payload);
    }

    function ftyp() {
        return box('ftyp', str('isom'), u32(0), str('isom'), str('iso2'), str('avc1'), str('mp41'));
    }

    function mvhd(width, height) {
        return box('mvhd',
            u32(0), u32(0), u32(0), u32(TIMESCALE), u32(0), u32(0x00010000),
            u16(0x0100), u16(0), u32(0), u32(0),
            u32(0x00010000), u32(0), u32(0), u32(0), u32(0x00010000), u32(0),
            u32(0), u32(0), u32(0x40000000),
            u32(0), u32(0), u32(0), u32(0), u32(0), u32(0),
            u32(TRACK + 1));
    }

    function tkhd(width, height) {
        return box('tkhd',
            u32(7), u32(0), u32(0), u32(TRACK), u32(0), u32(0),
            u32(0), u32(0), u16(0), u16(0), u16(0), u16(0),
            u32(0x00010000), u32(0), u32(0), u32(0), u32(0x00010000), u32(0),
            u32(0), u32(0), u32(0x40000000),
            u16(width), u16(0), u16(height), u16(0));
    }

    function mdhd() {
        return box('mdhd', u32(0), u32(0), u32(0), u32(TIMESCALE), u32(0), u16(0x55C4), u16(0));
    }

    function hdlr() {
        return box('hdlr', u32(0), u32(0), str('vide'), u32(0), u32(0), u32(0), str('Strike\0'));
    }

    function avcc(sps, pps) {
        return box('avcC',
            [1, sps[1], sps[2], sps[3], 0xFF, 0xE1],
            u16(sps.length), Array.prototype.slice.call(sps),
            [1], u16(pps.length), Array.prototype.slice.call(pps));
    }

    function avc1(width, height, sps, pps) {
        return box('avc1',
            u32(0), u32(1), u32(0), u32(0), u32(0), u32(0),
            u16(width), u16(height),
            u32(0x00480000), u32(0x00480000), u32(0), u16(1),
            [0x0A], str('Strike'), [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
            u16(0x0018), u16(0xFFFF),
            avcc(sps, pps));
    }

    function stbl(width, height, sps, pps) {
        return box('stbl',
            box('stsd', u32(0), u32(1), avc1(width, height, sps, pps)),
            box('stts', u32(0), u32(0)),
            box('stsc', u32(0), u32(0)),
            box('stsz', u32(0), u32(0), u32(0)),
            box('stco', u32(0), u32(0)));
    }

    function minf(width, height, sps, pps) {
        return box('minf',
            box('vmhd', u32(1), u16(0), u16(0), u16(0), u16(0)),
            box('dinf', box('dref', u32(0), u32(1), box('url ', u32(1)))),
            stbl(width, height, sps, pps));
    }

    function moov(width, height, sps, pps) {
        return box('moov',
            mvhd(width, height),
            box('trak', tkhd(width, height), box('mdia', mdhd(), hdlr(), minf(width, height, sps, pps))),
            box('mvex', box('trex', u32(0), u32(TRACK), u32(1), u32(0), u32(0), u32(0x00010001))));
    }

    /** Annex-B start codes are three or four bytes; both appear in one stream. */
    function splitNals(bytes) {
        var nals = [];
        var start = -1;
        for (var i = 0; i + 2 < bytes.length; i++) {
            if (bytes[i] !== 0 || bytes[i + 1] !== 0) {
                continue;
            }
            var skip = 0;
            if (bytes[i + 2] === 1) {
                skip = 3;
            } else if (i + 3 < bytes.length && bytes[i + 2] === 0 && bytes[i + 3] === 1) {
                skip = 4;
            } else {
                continue;
            }
            if (start >= 0) {
                nals.push(bytes.subarray(start, i));
            }
            start = i + skip;
            i += skip - 1;
        }
        if (start >= 0 && start < bytes.length) {
            nals.push(bytes.subarray(start, bytes.length));
        }
        return nals;
    }

    function nalType(nal) {
        return nal.length ? nal[0] & 0x1F : 0;
    }

    /** MSE wants length prefixed samples, not the start codes the encoder emits. */
    function toLengthPrefixed(nals) {
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

    function bytesOf(list) {
        return new Uint8Array(list);
    }

    function Bits(bytes) {
        this.bytes = bytes;
        this.at = 0;
    }

    Bits.prototype.bit = function () {
        var index = this.at >> 3;
        var shift = 7 - (this.at & 7);
        this.at++;
        return index < this.bytes.length ? (this.bytes[index] >> shift) & 1 : 0;
    };

    Bits.prototype.bits = function (count) {
        var value = 0;
        for (var i = 0; i < count; i++) {
            value = (value * 2) + this.bit();
        }
        return value;
    };

    /** Exp-Golomb: the run of zeros says how many bits carry the value. */
    Bits.prototype.ue = function () {
        var zeros = 0;
        while (this.bit() === 0 && zeros < 32) {
            zeros++;
        }
        return zeros === 0 ? 0 : (Math.pow(2, zeros) - 1) + this.bits(zeros);
    };

    Bits.prototype.se = function () {
        var value = this.ue();
        return (value % 2) ? (value + 1) / 2 : -(value / 2);
    };

    // Remove H.264 emulation-prevention bytes before parsing the SPS.
    function unescaped(nal, from) {
        var out = [];
        for (var i = from; i < nal.length; i++) {
            var stuffed = nal[i] === 3 && out.length >= 2 &&
                out[out.length - 1] === 0 && out[out.length - 2] === 0;
            if (!stuffed) {
                out.push(nal[i]);
            }
        }
        return out;
    }

    function skipScalingLists(bits, count) {
        for (var i = 0; i < count; i++) {
            if (!bits.bit()) {
                continue;
            }
            var size = i < 6 ? 16 : 64;
            var last = 8;
            var next = 8;
            for (var j = 0; j < size; j++) {
                if (next !== 0) {
                    next = (last + bits.se() + 256) % 256;
                }
                last = next === 0 ? last : next;
            }
        }
    }

    var HIGH_PROFILES = [100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135];

    function sizeOf(sps) {
        if (!sps || sps.length < 4) {
            return null;
        }
        var bits = new Bits(unescaped(sps, 1));
        var profile = bits.bits(8);
        bits.bits(8);
        bits.bits(8);
        bits.ue();
        var chroma = 1;
        var separatePlanes = 0;
        if (HIGH_PROFILES.indexOf(profile) >= 0) {
            chroma = bits.ue();
            if (chroma === 3) {
                separatePlanes = bits.bit();
            }
            bits.ue();
            bits.ue();
            bits.bit();
            if (bits.bit()) {
                skipScalingLists(bits, chroma !== 3 ? 8 : 12);
            }
        }
        bits.ue();
        var order = bits.ue();
        if (order === 0) {
            bits.ue();
        } else if (order === 1) {
            bits.bit();
            bits.se();
            bits.se();
            var cycle = bits.ue();
            for (var i = 0; i < cycle; i++) {
                bits.se();
            }
        }
        bits.ue();
        bits.bit();
        var widthMbs = bits.ue() + 1;
        var heightUnits = bits.ue() + 1;
        var frameOnly = bits.bit();
        if (!frameOnly) {
            bits.bit();
        }
        bits.bit();
        var left = 0;
        var right = 0;
        var top = 0;
        var bottom = 0;
        if (bits.bit()) {
            left = bits.ue();
            right = bits.ue();
            top = bits.ue();
            bottom = bits.ue();
        }
        var mono = chroma === 0 || separatePlanes === 1;
        var unitX = mono ? 1 : ((chroma === 1 || chroma === 2) ? 2 : 1);
        var unitY = (mono ? 1 : (chroma === 1 ? 2 : 1)) * (2 - frameOnly);
        var width = widthMbs * 16 - unitX * (left + right);
        var height = (2 - frameOnly) * heightUnits * 16 - unitY * (top + bottom);
        if (width <= 0 || height <= 0) {
            return null;
        }
        return { width: width, height: height };
    }

    Strike.fmp4 = {
        /** avc1.PPCCLL, read straight off the parameter set. */
        codecOf: function (sps) {
            var hex = '';
            for (var i = 1; i <= 3; i++) {
                hex += (sps[i] < 16 ? '0' : '') + sps[i].toString(16);
            }
            return 'avc1.' + hex;
        },

        split: splitNals,
        nalType: nalType,

        // Derive dimensions from SPS cropping, not the requested encoder size.
        sizeOf: sizeOf,

        init: function (width, height, sps, pps) {
            return bytesOf(ftyp().concat(moov(width, height, sps, pps)));
        },

        // trun's data offset points from moof to the sample in mdat.
        segment: function (sequence, decodeTime, durationTicks, nals, keyFrame) {
            var payload = toLengthPrefixed(nals);

            // Flags declare exactly the fields written below: data offset,
            // first sample flags, then a duration and a size per sample.
            function moofWith(dataOffset) {
                return box('moof',
                    box('mfhd', u32(0), u32(sequence)),
                    box('traf',
                        box('tfhd', u32(0x020000), u32(TRACK)),
                        box('tfdt', u32(0x01000000), u64(decodeTime)),
                        box('trun',
                            u32(0x000305), u32(1), u32(dataOffset),
                            u32(keyFrame ? 0x02000000 : 0x01010000),
                            u32(durationTicks), u32(payload.length))));
            }

            var moof = moofWith(moofWith(0).length + 8);
            var out = new Uint8Array(moof.length + 8 + payload.length);
            out.set(bytesOf(moof), 0);
            out.set(bytesOf(u32(payload.length + 8).concat(str('mdat'))), moof.length);
            out.set(payload, moof.length + 8);
            return out;
        }
    };
}());

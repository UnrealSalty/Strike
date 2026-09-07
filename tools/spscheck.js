/* Round-trips fmp4.sizeOf against an independent SPS writer, then checks a
 * real encoder's parameter set. Run: node tools/spscheck.js */

global.window = global;
require('../app/src/main/assets/web/js/fmp4.js');

function Writer() {
    this.bits = [];
}

Writer.prototype.bit = function (value) {
    this.bits.push(value ? 1 : 0);
};

Writer.prototype.u = function (value, count) {
    for (var i = count - 1; i >= 0; i--) {
        this.bit((value >> i) & 1);
    }
};

Writer.prototype.ue = function (value) {
    var code = value + 1;
    var length = Math.floor(Math.log(code) / Math.LN2);
    for (var i = 0; i < length; i++) {
        this.bit(0);
    }
    this.u(code, length + 1);
};

Writer.prototype.se = function (value) {
    this.ue(value <= 0 ? -2 * value : 2 * value - 1);
};

/** Start-code-stripped SPS NAL, with emulation prevention applied. */
Writer.prototype.nal = function (profile, level) {
    var raw = [0x67, profile, 0x00, level];
    var byteAt = 0;
    var bits = this.bits.slice();
    bits.push(1);
    while (bits.length % 8 !== 0) {
        bits.push(0);
    }
    while (byteAt < bits.length) {
        var value = 0;
        for (var i = 0; i < 8; i++) {
            value = (value << 1) | bits[byteAt + i];
        }
        raw.push(value);
        byteAt += 8;
    }
    var out = [];
    for (var j = 0; j < raw.length; j++) {
        if (j >= 3 && raw[j] <= 3 && out[out.length - 1] === 0 && out[out.length - 2] === 0) {
            out.push(3);
        }
        out.push(raw[j]);
    }
    return new Uint8Array(out);
};

function spsFor(options) {
    var writer = new Writer();
    var high = options.profile === 100;
    writer.ue(0);
    if (high) {
        writer.ue(1);
        writer.ue(0);
        writer.ue(0);
        writer.bit(0);
        writer.bit(0);
    }
    writer.ue(4);
    writer.ue(0);
    writer.ue(4);
    writer.ue(1);
    writer.bit(0);
    writer.ue(options.widthMbs - 1);
    writer.ue(options.heightUnits - 1);
    writer.bit(options.frameOnly);
    if (!options.frameOnly) {
        writer.bit(0);
    }
    writer.bit(1);
    var cropped = options.crop && (options.crop.left || options.crop.right ||
        options.crop.top || options.crop.bottom);
    writer.bit(cropped ? 1 : 0);
    if (cropped) {
        writer.ue(options.crop.left);
        writer.ue(options.crop.right);
        writer.ue(options.crop.top);
        writer.ue(options.crop.bottom);
    }
    writer.bit(0);
    return writer.nal(options.profile, options.level);
}

var checks = [
    { name: '2560x1920 mosaic, baseline', profile: 66, level: 31, widthMbs: 160, heightUnits: 120, frameOnly: 1, width: 2560, height: 1920 },
    { name: '1280x960 front slice, baseline', profile: 66, level: 31, widthMbs: 80, heightUnits: 60, frameOnly: 1, width: 1280, height: 960 },
    { name: '1920x1080 needs an 8 row crop', profile: 100, level: 40, widthMbs: 120, heightUnits: 68, frameOnly: 1, crop: { left: 0, right: 0, top: 0, bottom: 4 }, width: 1920, height: 1080 },
    { name: '640x480 low preset', profile: 66, level: 30, widthMbs: 40, heightUnits: 30, frameOnly: 1, width: 640, height: 480 },
    { name: 'interlaced doubles the map units', profile: 77, level: 31, widthMbs: 80, heightUnits: 30, frameOnly: 0, width: 1280, height: 960 },
    { name: 'high profile with a wide crop', profile: 100, level: 31, widthMbs: 81, heightUnits: 60, frameOnly: 1, crop: { left: 2, right: 6, top: 0, bottom: 0 }, width: 1280, height: 960 }
];

var failures = 0;

checks.forEach(function (check) {
    var sps = spsFor(check);
    var size = Strike.fmp4.sizeOf(sps);
    var codec = Strike.fmp4.codecOf(sps);
    var ok = size && size.width === check.width && size.height === check.height;
    if (!ok) {
        failures++;
    }
    console.log((ok ? 'ok   ' : 'FAIL ') + check.name + ' -> ' +
        (size ? size.width + 'x' + size.height : 'null') +
        ' want ' + check.width + 'x' + check.height + ' (' + codec + ')');
});

// Real Android/Qualcomm encoder output, High profile 1920x1080.
var real = new Uint8Array([
    0x67, 0x64, 0x00, 0x28, 0xAC, 0xD9, 0x40, 0x78,
    0x02, 0x27, 0xE5, 0x84, 0x00, 0x00, 0x03, 0x00,
    0x04, 0x00, 0x00, 0x03, 0x00, 0xCA, 0x3C, 0x60,
    0xC6, 0x58
]);
var realSize = Strike.fmp4.sizeOf(real);
var realOk = realSize && realSize.width === 1920 && realSize.height === 1080;
if (!realOk) {
    failures++;
}
console.log((realOk ? 'ok   ' : 'FAIL ') + 'real encoder SPS -> ' +
    (realSize ? realSize.width + 'x' + realSize.height : 'null') + ' want 1920x1080 (' +
    Strike.fmp4.codecOf(real) + ')');

console.log(failures === 0 ? '\nall passed' : '\n' + failures + ' failed');
process.exit(failures === 0 ? 0 : 1);

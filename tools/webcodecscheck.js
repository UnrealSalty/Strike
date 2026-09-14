'use strict';

var assert = require('node:assert/strict');
var fs = require('node:fs');
var path = require('node:path');
var vm = require('node:vm');
var source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/web/js/webcodecs.js'), 'utf8');
var sps = new Uint8Array([0x67, 0x42, 0, 0x1e, 1]);
var pps = new Uint8Array([0x68, 1]);
var nals = [new Uint8Array([0x65, 10, 20])];

function harness(options) {
    options = options || {};
    var decoders = [];
    var draws = [];
    var failures = [];
    var firstDraws = 0;
    function Decoder(callbacks) {
        if (options.constructorFails) throw new Error('no decoder');
        this.callbacks = callbacks;
        this.state = 'unconfigured';
        this.decodeQueueSize = 0;
        this.chunks = [];
        this.closes = 0;
        decoders.push(this);
    }
    Decoder.prototype.configure = function (configuration) {
        if (options.configureFails || options.reconfigureFails && decoders.length > 1) {
            throw new Error('unsupported codec');
        }
        this.configuration = configuration;
        this.state = 'configured';
    };
    Decoder.prototype.decode = function (chunk) {
        if (options.decodeFails) throw new Error('decode failed');
        assert.equal(this.state, 'configured');
        this.chunks.push(chunk);
        this.decodeQueueSize++;
    };
    Decoder.prototype.close = function () {
        assert.notEqual(this.state, 'closed', 'decoder closed twice');
        this.state = 'closed';
        this.decodeQueueSize = 0;
        this.closes++;
    };
    function Chunk(options) {
        Object.assign(this, options);
    }
    var context = {
        Uint8Array: Uint8Array,
        VideoDecoder: Decoder,
        EncodedVideoChunk: Chunk,
        Strike: { fmp4: { codecOf: function () { return 'avc1.42001e'; } } }
    };
    context.window = context;
    vm.runInNewContext(source, context);
    var canvas = {
        getContext: function () {
            return {
                drawImage: function (picture) {
                    if (options.drawFails) throw new Error('canvas failed');
                    draws.push(picture);
                }
            };
        }
    };
    var sink = context.Strike.webcodecs.open(canvas, sps, pps, { width: 1280, height: 960 },
        function () { firstDraws++; },
        function (title, detail) { failures.push([title, detail]); });
    return {
        sink: sink,
        decoders: decoders,
        draws: draws,
        failures: failures,
        firstDraws: function () { return firstDraws; }
    };
}

function picture() {
    return { closes: 0, close: function () { this.closes++; } };
}

function fillQueue(run) {
    for (var i = 0; i < 6; i++) run.sink.push(nals, i === 0, 1000000 + i * 66667);
}

var tests = {
    usesCameraTimestampsAndLengthPrefixedSamples: function () {
        var run = harness();
        run.sink.push(nals, true, 987654321);
        run.sink.push(nals, false, 987790123);
        var chunks = run.decoders[0].chunks;
        assert.deepEqual(chunks.map(function (chunk) { return chunk.timestamp; }), [987654321, 987790123]);
        assert.deepEqual(Array.from(chunks[0].data), [0, 0, 0, 3, 0x65, 10, 20]);
        assert.equal(chunks[0].type, 'key');
        assert.equal(chunks[1].type, 'delta');
        assert.equal(run.decoders[0].configuration.optimizeForLatency, true);
        run.sink.close();
    },

    waitsForAKeyframeAndRejectsMissingTimestamps: function () {
        var run = harness();
        run.sink.push(nals, false, 10);
        [undefined, NaN, Infinity, -1, '20'].forEach(function (time) {
            run.sink.push(nals, true, time);
        });
        assert.equal(run.decoders[0].chunks.length, 0);
        run.sink.push(nals, true, 0);
        assert.equal(run.decoders[0].chunks.length, 1);
        run.sink.close();
    },

    boundsBacklogAndDropsDeltasUntilTheNextKeyframe: function () {
        var run = harness();
        fillQueue(run);
        var stalled = run.decoders[0];
        run.sink.push(nals, false, 1500000);
        assert.equal(stalled.closes, 1);
        for (var i = 0; i < 100; i++) run.sink.push(nals, false, 1600000 + i * 66667);
        assert.equal(run.decoders.length, 1);
        assert.equal(stalled.chunks.length, 6);
        run.sink.push(nals, true, 9000000);
        assert.equal(run.decoders.length, 2);
        assert.equal(run.decoders[1].chunks.length, 1);
        assert.equal(run.decoders[1].chunks[0].timestamp, 9000000);
        assert.equal(run.failures.length, 0);
        run.sink.close();
    },

    anIncomingKeyframeCanImmediatelyReplaceAFullQueue: function () {
        var run = harness();
        fillQueue(run);
        run.sink.push(nals, true, 2000000);
        assert.equal(run.decoders[0].closes, 1);
        assert.equal(run.decoders[1].chunks[0].type, 'key');
        assert.equal(run.decoders[1].chunks[0].timestamp, 2000000);
        run.sink.close();
    },

    staleDecoderCallbacksCannotDrawOrFailTheReplacement: function () {
        var run = harness();
        fillQueue(run);
        var previous = run.decoders[0];
        run.sink.push(nals, true, 2000000);
        var stale = picture();
        previous.callbacks.output(stale);
        previous.callbacks.error(new Error('old decoder failure'));
        assert.equal(stale.closes, 1);
        assert.equal(run.draws.length, 0);
        assert.equal(run.failures.length, 0);
        var fresh = picture();
        run.decoders[1].callbacks.output(fresh);
        assert.equal(fresh.closes, 1);
        assert.deepEqual(run.draws, [fresh]);
        assert.equal(run.firstDraws(), 1);
        run.sink.close();
    },

    aLateOutputAfterCloseOnlyReleasesItsFrame: function () {
        var run = harness();
        var decoder = run.decoders[0];
        run.sink.close();
        run.sink.close();
        var late = picture();
        decoder.callbacks.output(late);
        decoder.callbacks.error(new Error('already closed'));
        run.sink.push(nals, true, 1);
        assert.equal(decoder.closes, 1);
        assert.equal(late.closes, 1);
        assert.equal(decoder.chunks.length, 0);
        assert.equal(run.draws.length, 0);
        assert.equal(run.failures.length, 0);
    },

    configurationFailureReleasesTheDecoderAndReportsOnce: function () {
        var run = harness({ configureFails: true });
        assert.equal(run.sink, null);
        assert.equal(run.decoders[0].closes, 1);
        run.decoders[0].callbacks.error(new Error('late failure'));
        assert.equal(run.failures.length, 1);
    },

    constructorFailureReportsWithoutLeavingASink: function () {
        var run = harness({ constructorFails: true });
        assert.equal(run.sink, null);
        assert.equal(run.decoders.length, 0);
        assert.equal(run.failures.length, 1);
    },

    failureToConfigureAReplacementClosesBothDecoders: function () {
        var run = harness({ reconfigureFails: true });
        fillQueue(run);
        run.sink.push(nals, true, 2000000);
        assert.equal(run.decoders[0].closes, 1);
        assert.equal(run.decoders[1].closes, 1);
        assert.equal(run.failures.length, 1);
        run.sink.close();
        run.sink.push(nals, true, 3000000);
        assert.equal(run.decoders.length, 2);
    },

    decodeFailureReleasesResourcesAndDoesNotKeepSubmitting: function () {
        var run = harness({ decodeFails: true });
        run.sink.push(nals, true, 1);
        run.sink.push(nals, true, 2);
        assert.equal(run.decoders[0].closes, 1);
        assert.equal(run.decoders[0].chunks.length, 0);
        assert.equal(run.failures.length, 1);
    },

    drawingFailureStillClosesTheFrameAndDecoder: function () {
        var run = harness({ drawFails: true });
        var frame = picture();
        run.decoders[0].callbacks.output(frame);
        assert.equal(frame.closes, 1);
        assert.equal(run.decoders[0].closes, 1);
        assert.equal(run.firstDraws(), 0);
        assert.equal(run.failures.length, 1);
    }
};

Object.keys(tests).forEach(function (name) {
    tests[name]();
    console.log('ok ' + name);
});
console.log(Object.keys(tests).length + ' WebCodecs checks passed');

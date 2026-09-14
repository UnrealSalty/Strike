const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const scripts = ['fmp4.js', 'live-mse.js'].map(name =>
    fs.readFileSync(path.join(__dirname, '../app/src/main/assets/web/js', name), 'utf8'));
let passed = 0;

function check(name, run) {
    run();
    passed++;
    console.log('ok ' + name);
}

function boxAt(bytes, type) {
    for (let at = 4; at + 4 < bytes.length; at++) {
        if (String.fromCharCode(...bytes.subarray(at, at + 4)) === type) return at;
    }
    return -1;
}

function frameOf(bytes) {
    const tfdt = boxAt(bytes, 'tfdt');
    if (tfdt < 0) return null;
    const trun = boxAt(bytes, 'trun');
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    return {
        time: view.getUint32(tfdt + 8) * 4294967296 + view.getUint32(tfdt + 12),
        duration: view.getUint32(trun + 20),
        key: view.getUint32(trun + 16) === 0x02000000
    };
}

function setup() {
    const listeners = {};
    const appended = [];
    const removed = [];
    const failures = [];
    let start = 0;
    let end = 0;
    let operation = null;
    const video = { currentTime: 0, paused: false, plays: 0,
        play() { this.paused = false; this.plays++; return Promise.resolve(); } };
    const buffer = {
        updating: false,
        get buffered() {
            return { length: end > start ? 1 : 0, start() { return start; }, end() { return end; } };
        },
        appendBuffer(bytes) {
            assert.equal(this.updating, false, 'append while busy');
            appended.push(bytes);
            this.updating = true;
            operation = { frame: frameOf(bytes) };
        },
        remove(from, to) {
            assert.equal(this.updating, false, 'remove while busy');
            removed.push([from, to]);
            this.updating = true;
            operation = { trim: to };
        },
        addEventListener(name, fn) { listeners[name] = fn; },
        removeEventListener(name, fn) { if (listeners[name] === fn) delete listeners[name]; }
    };
    const context = { Uint8Array, DataView, MediaSource: { isTypeSupported() { return true; } } };
    context.window = context;
    vm.createContext(context);
    scripts.forEach(source => vm.runInContext(source, context));
    const sink = context.Strike.liveMse.open({ addSourceBuffer() { return buffer; } }, video,
        { width: 64, height: 64 }, new Uint8Array([0x67, 0x42, 0, 0x1e]),
        new Uint8Array([0x68, 0xce]), (...error) => failures.push(error));
    function complete() {
        assert.equal(buffer.updating, true);
        const finished = operation;
        operation = null;
        buffer.updating = false;
        if (finished.frame) end = Math.max(end, (finished.frame.time + finished.frame.duration) / 90000);
        if (finished.trim !== undefined) start = Math.max(start, finished.trim);
        if (listeners.updateend) listeners.updateend();
    }
    function flush() {
        let count = 0;
        while (buffer.updating) {
            assert.ok(count++ < 100, 'buffer never settles');
            complete();
        }
    }
    function push(timeUs, key = false) { sink.push([new Uint8Array([key ? 0x65 : 0x41, 1])], key, timeUs); }
    return { sink, push, flush, complete, buffer, video, appended, removed, failures, listeners,
        frames() { return appended.map(frameOf).filter(Boolean); } };
}

check('15 fps keeps 150 seconds of capture at 150 seconds of playback', () => {
    const run = setup();
    run.flush();
    const origin = 9_000_000_000;
    for (let index = 0; index <= 2250; index++) {
        run.video.currentTime = index / 15;
        run.push(origin + Math.round(index * 1_000_000 / 15), index % 15 === 0);
        run.flush();
    }
    const frames = run.frames();
    assert.equal(frames.length, 2250);
    assert.equal(frames[0].time, 0);
    assert.equal(frames.at(-1).time + frames.at(-1).duration, 150 * 90000);
    assert.ok(frames.every(frame => frame.duration === 6000));
    assert.equal(run.failures.length, 0);
});

check('variable capture intervals use timestamp differences without cumulative rounding drift', () => {
    const run = setup();
    run.flush();
    [0, 33_333, 100_000, 183_333, 250_000].forEach((time, index) => {
        run.push(8_000_000_000 + time, index === 0);
        run.flush();
    });
    assert.deepEqual(run.frames().map(frame => frame.duration), [3000, 6000, 7500, 6000]);
    assert.equal(run.frames().at(-1).time + run.frames().at(-1).duration, 22500);
});

check('a viewer joining an existing stream waits for a keyframe', () => {
    const run = setup();
    run.flush();
    run.push(0);
    run.push(66_667);
    assert.equal(run.frames().length, 0);
    run.push(133_333, true);
    run.push(200_000);
    run.flush();
    assert.equal(run.frames().length, 1);
    assert.equal(run.frames()[0].key, true);
    assert.equal(run.frames()[0].time, 0);
});

check('a stalled append discards backlog and resumes only at a keyframe', () => {
    const run = setup();
    for (let index = 0; index < 100; index++) run.push(index * 66_667, index === 0);
    run.flush();
    assert.equal(run.frames().length, 0);
    run.push(7_000_000, true);
    run.push(7_066_667);
    run.flush();
    assert.equal(run.frames().length, 1);
    assert.equal(run.frames()[0].key, true);
    assert.equal(run.frames()[0].time, 7 * 90000);
    assert.ok(run.video.currentTime > 6.8);
});

check('frame-count bound also catches high-rate short bursts', () => {
    const run = setup();
    for (let index = 0; index < 30; index++) run.push(index * 1_000, index === 0);
    run.flush();
    assert.equal(run.frames().length, 0);
    run.push(30_000, true);
    run.push(31_000);
    run.flush();
    assert.equal(run.frames().length, 1);
    assert.equal(run.frames()[0].key, true);
});

check('playback catches up after a pause instead of replaying old footage', () => {
    const run = setup();
    run.flush();
    run.video.paused = true;
    for (let index = 0; index <= 40; index++) {
        run.push(index * 100_000, index % 10 === 0);
        run.flush();
    }
    assert.ok(run.video.currentTime > 2.5);
    assert.equal(run.video.paused, false);
    assert.ok(run.video.plays >= 2);
});

check('old playback history is removed in batches behind the playhead', () => {
    const run = setup();
    run.flush();
    for (let index = 0; index <= 300; index++) {
        run.video.currentTime = index / 10;
        run.push(index * 100_000, index % 10 === 0);
        run.flush();
    }
    assert.ok(run.removed.length > 0);
    assert.ok(run.buffer.buffered.start(0) >= 17);
    assert.equal(run.failures.length, 0);
});

check('closing clears queued frames and detaches buffer callbacks', () => {
    const run = setup();
    run.push(0, true);
    run.push(66_667);
    run.sink.close();
    run.sink.close();
    run.complete();
    run.push(133_333, true);
    assert.equal(run.frames().length, 0);
    assert.equal(Object.keys(run.listeners).length, 0);
});

check('an append error ends the sink and reports once', () => {
    const run = setup();
    run.flush();
    run.buffer.appendBuffer = () => { throw new Error('decoder refused append'); };
    run.push(0, true);
    run.push(66_667);
    run.push(133_333);
    assert.equal(run.failures.length, 1);
    assert.equal(Object.keys(run.listeners).length, 0);
});

check('invalid timestamps never enter the MP4 timeline', () => {
    const run = setup();
    run.flush();
    [NaN, Infinity, -1, undefined].forEach(time => run.push(time, true));
    assert.equal(run.frames().length, 0);
    run.push(9_000_000, true);
    run.push(9_100_000);
    run.flush();
    assert.equal(run.frames()[0].time, 0);
});

console.log(passed + ' MSE checks passed');

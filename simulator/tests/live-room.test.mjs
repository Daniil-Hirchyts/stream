import { test } from 'node:test';
import assert from 'node:assert/strict';
import { inputCommand, liveRoom } from '../live-room.mjs';

test('only bounded pointer coordinates and an explicit remote key set become input commands', () => {
  assert.deepEqual(inputCommand({ type: 'key', key: 'back' }, ''), ['keyevent', '4']);
  for (const key of ['constructor', '__proto__', 'reboot', '3;rm']) assert.throws(() => inputCommand({ type: 'key', key }, ''));
  for (const x of [-1, 1.01, NaN, Infinity, '0.5']) assert.throws(() => inputCommand({ type: 'pointer', from: { x, y: 0 }, to: { x: 1, y: 1 } }, '1920x1080'));
  assert.deepEqual(inputCommand({ type: 'pointer', from: { x: 0, y: 0 }, to: { x: 1, y: 1 }, duration: 99_999 }, '1920x1080'), ['touchscreen', 'swipe', '0', '0', '1919', '1079', '1000']);
  assert.deepEqual(inputCommand({ type: 'pointer', from: { x: 0.5, y: 0.5 }, to: { x: 0.5, y: 0.5 }, mouse: true }, '1920x1080'), ['mouse', 'tap', '960', '540']);
});
test('test room identifies the real target without interpreting names as HTML', () => {
  const html = liveRoom('emulator-5558', '<test>');
  assert.ok(html.includes('&lt;test&gt;')); assert.ok(html.includes('/screen.png')); assert.ok(html.includes("fetch('/input'"));
});

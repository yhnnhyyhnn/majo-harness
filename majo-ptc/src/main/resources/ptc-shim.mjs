// The majo PTC runner shim (dsh ptc-runtime-node analog, stdin/stdout framing):
// stdout is a pure JSON-lines control channel (call/result/error frames to the
// host); replies arrive on stdin; the program's console.* output is redirected
// to stderr so the host can return it when no explicit result was produced.
import { createInterface } from 'node:readline';
import { pathToFileURL } from 'node:url';

const send = (frame) => {
  process.stdout.write(JSON.stringify(frame) + '\n');
};
const render = (value) => (typeof value === 'string' ? value : JSON.stringify(value));
console.log = (...args) => process.stderr.write(args.map(render).join(' ') + '\n');
console.info = console.log;
console.warn = (...args) => process.stderr.write('[warn] ' + args.map(render).join(' ') + '\n');
console.error = (...args) => process.stderr.write('[error] ' + args.map(render).join(' ') + '\n');

const pending = new Map();
let nextId = 1;
let settled = false;

const lines = createInterface({ input: process.stdin });
lines.on('line', (line) => {
  const text = line.trim();
  if (!text) return;
  let frame;
  try {
    frame = JSON.parse(text);
  } catch {
    return; // tolerate stray non-JSON on stdin
  }
  const entry = pending.get(frame.id);
  if (!entry) return;
  pending.delete(frame.id);
  if (frame.ok) entry.resolve(frame.value);
  else entry.reject(new Error(frame.message || 'tool call failed'));
});

// Host-tool bindings: tools.<name>(args) → one call frame + reply promise.
globalThis.tools = new Proxy({}, {
  get(_target, name) {
    if (typeof name !== 'string' || name === 'then') return undefined;
    return (args) => {
      if (settled) return Promise.reject(new Error('program already completed'));
      const id = nextId++;
      send({ type: 'call', id, name, args: args === undefined ? {} : args });
      return new Promise((resolve, reject) => pending.set(id, { resolve, reject }));
    };
  },
});

let complete;
const done = new Promise((resolve) => {
  complete = (frame) => {
    if (settled) return;
    settled = true;
    send(frame);
    resolve();
  };
});
globalThis.result = (value) =>
  complete({ type: 'result', value: value === undefined ? null : value });

const programUrl = pathToFileURL(process.argv[2]);
try {
  await import(programUrl.href);
  if (!settled) {
    complete({
      type: 'result',
      value: null,
      note: 'program finished without calling result(); printed output returned instead',
    });
  }
} catch (error) {
  if (!settled) {
    complete({ type: 'error', message: error && error.stack ? String(error.stack) : String(error) });
  }
}
await done;
process.exit(0);

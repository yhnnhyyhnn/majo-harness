// Copies web-ui/dist into the given static directory, replacing its contents
// (invoked by Maven after `npm run build`; mirrors the manual script).
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const dist = path.join(here, "dist");
const target = process.argv[2];
if (!target) {
  console.error("usage: node copy-dist.js <static-target-dir>");
  process.exit(2);
}

function rmrf(dir) {
  if (fs.existsSync(dir)) fs.rmSync(dir, { recursive: true, force: true });
}

function copyDir(from, to) {
  fs.mkdirSync(to, { recursive: true });
  for (const entry of fs.readdirSync(from, { withFileTypes: true })) {
    const source = path.join(from, entry.name);
    const dest = path.join(to, entry.name);
    if (entry.isDirectory()) copyDir(source, dest);
    else fs.copyFileSync(source, dest);
  }
}

if (!fs.existsSync(dist)) {
  console.error(`web-ui: ${dist} missing — run \`npm run build\` first`);
  process.exit(1);
}
rmrf(target);
fs.mkdirSync(target, { recursive: true });
copyDir(dist, target);

// Some browser-side tools mis-handle crossorigin module scripts on plain
// http://localhost origins; drop the attribute vite injects on the built
// index (same-origin modules need no CORS mode).
const indexFile = path.join(target, "index.html");
if (fs.existsSync(indexFile)) {
  const html = fs.readFileSync(indexFile, "utf8").replace(/ crossorigin/g, "");
  fs.writeFileSync(indexFile, html);
}
console.log(`web UI copied to ${target}`);

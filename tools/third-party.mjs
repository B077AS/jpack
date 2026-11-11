// Regenerates THIRD-PARTY.md: the licenses of the Rust crates the stub is built from.
//   node tools/third-party.mjs
// Run it whenever stub/Cargo.lock changes. Needs cargo on the PATH. Fails if a crate has no license information.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const targets = ['x86_64-pc-windows-gnu', 'x86_64-unknown-linux-gnu'];

const crates = new Map();
for (const target of targets) {
  const out = execFileSync('cargo', ['metadata', '--format-version', '1', '--locked', '--filter-platform', target], {
    cwd: path.join(root, 'stub'),
    maxBuffer: 256 * 1024 * 1024,
    encoding: 'utf8',
  });
  const metadata = JSON.parse(out);
  const used = new Set(metadata.resolve.nodes.map((node) => node.id));
  for (const pkg of metadata.packages) {
    if (!used.has(pkg.id) || pkg.name === 'jpack-stub') continue;
    const license = pkg.license || (pkg.license_file ? 'see license file' : null);
    if (!license) throw new Error(`${pkg.name} ${pkg.version} states no license`);
    crates.set(`${pkg.name} ${pkg.version}`, { name: pkg.name, version: pkg.version, license });
  }
}

const rows = [...crates.values()].sort((a, b) => a.name.localeCompare(b.name));
let md = `# Third-party software in the jpack stub

The jpack stub (\`stub/\`, the small native program installed next to an app) is written in Rust and is built from the crates below.
They are all under permissive licenses (MIT, Apache-2.0, ISC, BSD-3-Clause, Zlib, Unlicense, 0BSD, Unicode-3.0 and CDLA-Permissive-2.0),
which allow using and redistributing them in other software as long as their copyright and license notices are kept.
Each crate's own license text is in its source package (\`https://crates.io/crates/<name>\`).

Where a crate offers a choice (\`MIT OR Apache-2.0\`), either license may be used.

This list was generated from \`Cargo.lock\` for the Windows (\`x86_64-pc-windows-gnu\`) and Linux (\`x86_64-unknown-linux-gnu\`) builds and
includes build-time crates that are not part of the binary. Regenerate it whenever \`stub/Cargo.lock\` changes (see CLAUDE.md).

| Crate | Version | License |
|---|---|---|
`;
for (const r of rows) md += `| [${r.name}](https://crates.io/crates/${r.name}) | ${r.version} | ${r.license.replace(/\|/g, '\\|')} |\n`;
md += `\nThe jpack library and Maven plugin (\`jpack-api\`, \`jpack-maven-plugin\`) have no third-party runtime dependencies.\n`;

fs.writeFileSync(path.join(root, 'THIRD-PARTY.md'), md);
console.log(`${rows.length} crates written to THIRD-PARTY.md`);

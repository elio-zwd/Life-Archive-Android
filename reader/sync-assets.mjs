import { cp, mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const root = path.dirname(fileURLToPath(import.meta.url));
const target = path.resolve(root, '../core/markdown/build/generated/readerAssets/reader');
await mkdir(target, { recursive: true });
for (const [name, from] of [['mathjax', 'mathjax'], ['mermaid', 'mermaid/dist'], ['pdfjs', 'pdfjs-dist'], ['purify', 'dompurify/dist']]) {
  await cp(path.join(root, 'node_modules', from), path.join(target, name), { recursive: true, filter: p => !p.endsWith('.map') });
}
await mkdir(path.join(target, 'fonts'), { recursive: true });
await cp(path.join(root, 'node_modules/@mathjax/mathjax-newcm-font'), path.join(target, 'fonts/mathjax-newcm-font'), { recursive: true });
const manifest = JSON.parse(await readFile(path.join(root, 'package.json'), 'utf8'));
await writeFile(path.join(target, 'versions.json'), JSON.stringify(manifest.dependencies, null, 2));
await mkdir(path.join(target, 'licenses'), { recursive: true });
for (const [name, from] of [['mathjax', 'mathjax/LICENSE'], ['mermaid', 'mermaid/LICENSE'], ['pdfjs', 'pdfjs-dist/LICENSE'], ['dompurify', 'dompurify/LICENSE'], ['mathjax-font', 'mathjax/LICENSE']]) {
  await cp(path.join(root, 'node_modules', from), path.join(target, 'licenses', `${name}.txt`));
}
await cp(path.join(root, 'node_modules/@mathjax/mathjax-newcm-font/package.json'), path.join(target, 'licenses/mathjax-font-package.json'));

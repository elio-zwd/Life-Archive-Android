import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import http from 'node:http';
import fs from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const fixtures = JSON.parse(await fs.readFile(path.join(root, 'core/document/build/reader-fixtures.json'), 'utf8'));
const assetRoots = [path.join(root, 'feature/editor/src/main/assets'), path.join(root, 'feature/editor/build/generated/readerAssets')];
const pdfPadding = 50 * 1024 * 1024;
const pdfObjects = [
  '<< /Type /Catalog /Pages 2 0 R >>', '<< /Type /Pages /Kids [3 0 R 5 0 R] /Count 2 >>',
  '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Resources << /Font << /F1 7 0 R >> >> /Contents 4 0 R >>',
  '<< /Length 40 >>\nstream\nBT /F1 20 Tf 20 100 Td (Page one) Tj ET\nendstream',
  '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Resources << /Font << /F1 7 0 R >> >> /Contents 6 0 R >>',
  '<< /Length 40 >>\nstream\nBT /F1 20 Tf 20 100 Td (Page two) Tj ET\nendstream', '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
  `<< /Length ${pdfPadding} >>\nstream\n${' '.repeat(pdfPadding)}\nendstream`
];
let pdfText = '%PDF-1.4\n'; const offsets = [0];
pdfObjects.forEach((object, i) => { offsets.push(Buffer.byteLength(pdfText)); pdfText += `${i + 1} 0 obj\n${object}\nendobj\n`; });
const xref = Buffer.byteLength(pdfText); pdfText += `xref\n0 9\n0000000000 65535 f \n${offsets.slice(1).map(n => `${String(n).padStart(10, '0')} 00000 n \n`).join('')}trailer\n<< /Size 9 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF`;
const pdf = Buffer.from(pdfText);
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aN1sAAAAASUVORK5CYII=', 'base64');
const video = await fs.readFile(path.join(root, 'reader/test/fixtures/synthetic.mp4'));
const wav = Buffer.alloc(44 + 44100 * 2); wav.write('RIFF', 0); wav.writeUInt32LE(wav.length - 8, 4); wav.write('WAVEfmt ', 8); wav.writeUInt32LE(16, 16); wav.writeUInt16LE(1, 20); wav.writeUInt16LE(1, 22); wav.writeUInt32LE(44100, 24); wav.writeUInt32LE(88200, 28); wav.writeUInt16LE(2, 32); wav.writeUInt16LE(16, 34); wav.write('data', 36); wav.writeUInt32LE(wav.length - 44, 40);
for (let i = 0; i < 44100; i++) wav.writeInt16LE(Math.round(Math.sin(i * Math.PI * 2 * 440 / 44100) * 4000), 44 + i * 2);
const mime = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf', '.wasm': 'application/wasm', '.svg': 'image/svg+xml' };
const failures = []; const actions = [];
const server = http.createServer(async (request, response) => {
  try {
    const url = new URL(request.url, 'http://127.0.0.1');
    const reply = (value) => { response.setHeader('Content-Type', 'application/json'); response.end(JSON.stringify(value)); };
    if (url.pathname === '/reader/document') return reply({ id: url.searchParams.get('note'), path: '验收.md', html: url.searchParams.get('note') === 'target' ? fixtures.nested : fixtures.root, blocks: [] });
    if (url.pathname === '/reader/resolve') {
      const target = url.searchParams.get('target');
      if (target.startsWith('目标')) return reply({ note: 'target', offset: target.includes('#') ? fixtures.nestedHeadingOffset : 0 });
      if (target === '循环') return reply({ note: 'root', offset: 0 });
      if (target.startsWith('文档.pdf')) return reply({ attachment: '文档.pdf' });
      if (target.startsWith('图片.png')) return reply({ attachment: '图片.png' });
      if (target.startsWith('声音.wav')) return reply({ attachment: '声音.wav' });
      if (target.startsWith('视频.mp4')) return reply({ attachment: '视频.mp4' });
      return reply({ missing: true });
    }
    if (url.pathname === '/reader/cache-info') return reply({ key: null });
    if (url.pathname === '/reader/action') { actions.push(Object.fromEntries(url.searchParams)); response.writeHead(204); return response.end(); }
    if (url.pathname === '/reader/resource') {
      const resourcePath = url.searchParams.get('path');
      const data = resourcePath === '文档.pdf' ? pdf : resourcePath === '声音.wav' ? wav : resourcePath === '视频.mp4' ? video : png;
      response.setHeader('Content-Type', data === pdf ? 'application/pdf' : data === wav ? 'audio/wav' : data === video ? 'video/mp4' : 'image/png'); response.setHeader('Accept-Ranges', 'bytes'); response.setHeader('Content-Length', data.length);
      const range = request.headers.range?.match(/bytes=(\d+)-(\d*)/);
      if (range) { const start = Number(range[1]); const end = range[2] ? Math.min(Number(range[2]), data.length - 1) : data.length - 1; response.writeHead(206, { 'Content-Range': `bytes ${start}-${end}/${data.length}`, 'Content-Length': end - start + 1 }); response.end(data.subarray(start, end + 1)); } else response.end(data); return;
    }
    for (const directory of assetRoots) {
      const file = path.resolve(directory, '.' + decodeURIComponent(url.pathname));
      if (!file.startsWith(directory + path.sep)) continue;
      try { const data = await fs.readFile(file); response.setHeader('Content-Type', mime[path.extname(file)] || 'application/octet-stream'); response.end(data); return; } catch { }
    }
    failures.push(url.pathname); response.writeHead(404); response.end();
  } catch (e) { response.writeHead(500); response.end(e.message); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const browserPath = process.env.LIFE_ARCHIVE_BROWSER || ['C:/Program Files/Google/Chrome/Application/chrome.exe', 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'].find(existsSync);
const browser = await chromium.launch({ executablePath: browserPath, headless: true });
const page = await browser.newPage({ viewport: { width: 412, height: 892 } });
const errors = []; const remoteRequests = [];
page.on('pageerror', error => errors.push(error.message));
page.on('request', request => { if (!request.url().startsWith('http://127.0.0.1:') && !request.url().startsWith('blob:') && !request.url().startsWith('data:')) remoteRequests.push(request.url()); });
const checks = [];
try {
  for (const dark of [false, true]) {
    await page.goto(`http://127.0.0.1:${server.address().port}/reader/index.html?note=root&dark=${dark}&offset=0&auto=false`);
    await page.waitForFunction(() => window.readerReady === true, { timeout: 45000 });
    assert.equal(await page.locator('.math[data-rendered="true"] > mjx-container[jax="SVG"]').count(), 2); assert.equal(await page.locator('.math [data-mml-node="merror"]').count(), 0); checks.push(`${dark ? '深色' : '浅色'}公式渲染`);
    assert.equal(await page.locator('.mermaid svg').count(), 1); checks.push(`${dark ? '深色' : '浅色'}Mermaid 渲染`);
    assert.equal(await page.locator('details.callout').count(), 1); await page.locator('details.callout>summary').click(); assert.equal(await page.locator('aside.callout').count(), 1);
    assert.ok(await page.locator('table strong').count() > 0); assert.equal(await page.locator('mark strong').count(), 1);
    assert.ok(await page.locator('.footnotes li').count() >= 2); assert.ok(await page.locator('.embedded-note').count() > 0); assert.ok((await page.locator('.embedded-note').innerText()).includes('循环'));
    await page.waitForFunction(() => [...document.querySelectorAll('.pdf canvas')].some(canvas => canvas.width > 0));
    assert.ok((await page.locator('.pdf-controls').innerText()).includes('1 / 2'));
    await page.getByRole('button', { name: '下一页' }).click(); await page.waitForFunction(() => document.querySelector('.pdf-controls').textContent.includes('2 / 2')); checks.push(`${dark ? '深色' : '浅色'}PDF 翻页`);
    assert.equal(await page.evaluate(() => window.noteScriptExecuted), undefined); assert.equal(remoteRequests.length, 0); checks.push(`${dark ? '深色' : '浅色'}脚本隔离与默认离线`);
    assert.equal(await page.locator('img[width="100"]').count(), 1);
    await page.waitForFunction(() => document.querySelector('audio')?.readyState >= 1 && document.querySelector('video')?.readyState >= 1);
    await page.locator('audio').evaluate(node => { node.currentTime = .25; }); await page.waitForFunction(() => document.querySelector('audio').currentTime >= .2);
    checks.push(`${dark ? '深色' : '浅色'}音视频元数据与音频定位`);
    const directory = process.env.LIFE_ARCHIVE_EVIDENCE || path.join(process.env.TEMP || 'D:/Temp', 'life-archive-phase1-browser'); await fs.mkdir(directory, { recursive: true });
    await page.screenshot({ path: path.join(directory, dark ? 'reader-dark.png' : 'reader-light.png'), fullPage: true });
  }
  assert.equal(errors.length, 0, errors.join('\n')); assert.equal(failures.length, 0, failures.join('\n'));
  console.log(`PASS ${checks.length} 浏览器检查：${checks.join('、')}`);
} catch (error) {
  console.error(JSON.stringify({ errors, failures, remoteRequests, math: await page.locator('.math').evaluateAll(nodes => nodes.map(n => n.outerHTML.slice(0, 1000))), status: await page.locator('#status').textContent().catch(() => ''), body: (await page.locator('body').innerText().catch(() => '')).slice(-2000) }));
  throw error;
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }

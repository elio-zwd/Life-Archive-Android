import { chromium } from 'playwright';
import http from 'node:http';
import fs from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { createReadStream } from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const evidence = process.env.LIFE_ARCHIVE_EVIDENCE || path.join(os.tmpdir(), 'life-archive-reader-022');
const fixtureRoot = process.env.LIFE_ARCHIVE_READER_FIXTURES || path.join(os.tmpdir(), 'life-archive-reader-fixtures');
const baseline = process.env.LIFE_ARCHIVE_READER_BASELINE;
if (!baseline) throw Error('请指定已冻结的 0.2.1 assets 根目录 LIFE_ARCHIVE_READER_BASELINE');
const allFixtures = JSON.parse(await fs.readFile(path.join(fixtureRoot, 'documents.json'), 'utf8'));
const selected = process.env.LIFE_ARCHIVE_PERF_DOCUMENTS?.split(',');
const fixtures = selected ? Object.fromEntries(selected.map(name => { if (!allFixtures[name]) throw Error(`缺少样例 ${name}，请先执行 app 本地测试`); return [name, allFixtures[name]]; })) : allFixtures;
const image = await fs.readFile(path.join(fixtureRoot, 'sample.png'));
await fs.mkdir(evidence, { recursive: true });
const runDirectory = await fs.mkdtemp(path.join(evidence, 'open-cache-'));
let pdfPath; let pdfBytes;
if (fixtures['50MiBPDF']) {
  pdfPath = path.join(runDirectory, 'synthetic.pdf');
  const file = await fs.open(pdfPath, 'w'); let position = 0; const offsets = [0];
  const write = async text => { const bytes = Buffer.from(text); await file.write(bytes); position += bytes.length; };
  await write('%PDF-1.4\n');
  const objects = ['<< /Type /Catalog /Pages 2 0 R >>','<< /Type /Pages /Kids [3 0 R 5 0 R] /Count 2 >>','<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Resources << /Font << /F1 7 0 R >> >> /Contents 4 0 R >>','<< /Length 40 >>\nstream\nBT /F1 20 Tf 20 100 Td (Page one) Tj ET\nendstream','<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Resources << /Font << /F1 7 0 R >> >> /Contents 6 0 R >>','<< /Length 40 >>\nstream\nBT /F1 20 Tf 20 100 Td (Page two) Tj ET\nendstream','<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>'];
  for (const [i, object] of objects.entries()) { offsets.push(position); await write(`${i+1} 0 obj\n${object}\nendobj\n`); }
  offsets.push(position); const padding = 50 * 1024 * 1024;
  await write(`8 0 obj\n<< /Length ${padding} >>\nstream\n`);
  const block = Buffer.alloc(65536, 32); for (let left = padding; left > 0; left -= block.length) { const bytes = block.subarray(0,Math.min(left,block.length)); await file.write(bytes); position += bytes.length; }
  await write('\nendstream\nendobj\n'); const xref = position;
  await write(`xref\n0 9\n0000000000 65535 f \n${offsets.slice(1).map(n=>`${String(n).padStart(10,'0')} 00000 n \n`).join('')}trailer\n<< /Size 9 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF`);
  await file.close(); pdfBytes = position;
}
const rounds = Math.max(20, Number(process.env.LIFE_ARCHIVE_PERF_ROUNDS) || 20);
const generated = path.join(root, 'core/markdown/build/generated/readerAssets');
const current = path.join(root, 'core/markdown/src/main/assets');
const browserPath = process.env.LIFE_ARCHIVE_BROWSER || ['C:/Program Files/Google/Chrome/Application/chrome.exe', 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'].find(existsSync);
const types = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.woff': 'font/woff', '.woff2': 'font/woff2', '.wasm': 'application/wasm', '.svg': 'image/svg+xml' };
let active; let activeAssets; let namespace; let projectionSeen = false;
let currentTraffic = { bytes: 0 };
const rows = []; const failures = [];
async function sourceSnapshot(directory) {
  const files = new Map();
  for (const entry of await fs.readdir(path.join(directory, 'reader'), { withFileTypes: true })) if (entry.isFile()) files.set(`/reader/${entry.name}`, await fs.readFile(path.join(directory, 'reader', entry.name)));
  const hash = createHash('sha256'); for (const [name, bytes] of [...files].sort((a,b)=>a[0].localeCompare(b[0]))) { hash.update(name); hash.update(bytes); }
  return { files, fingerprint: hash.digest('hex') };
}
const sources = { '0.2.1': await sourceSnapshot(baseline), '0.2.2': await sourceSnapshot(current) };
let activeVersion;
const server = http.createServer(async (request, response) => {
  try {
    const url = new URL(request.url, 'http://127.0.0.1');
    const json = value => { response.setHeader('Content-Type', 'application/json'); response.end(JSON.stringify(value)); };
    if (url.pathname === '/reader/document') {
      const hit = projectionSeen; projectionSeen = true;
      return json({ id: 'sample', revision: 'sample', path: '验收样例.md', html: active.html, blocks: [], cacheNamespace: namespace, projectionKey: active.digest, cacheHit: hit });
    }
    if (url.pathname === '/reader/resolve') return json(url.searchParams.get('target')?.endsWith('.png') ? { attachment: '验收图片.png' } : pdfPath && url.searchParams.get('target')?.startsWith('文档.pdf') ? { attachment: '文档.pdf' } : { missing: true });
    if (url.pathname === '/reader/resource-info') return json({ width: 1000, height: 500 });
    if (url.pathname === '/reader/resource') {
      if (pdfPath && url.searchParams.get('path') === '文档.pdf') {
        const range = request.headers.range?.match(/^bytes=(\d+)-(\d*)$/);
        const start = range ? Number(range[1]) : 0; const end = range?.[2] ? Math.min(Number(range[2]),pdfBytes-1) : pdfBytes-1;
        response.setHeader('Content-Type','application/pdf'); response.setHeader('Accept-Ranges','bytes'); response.setHeader('Content-Length',end-start+1);
        if (range) response.writeHead(206,{'Content-Range':`bytes ${start}-${end}/${pdfBytes}`});
        const traffic = currentTraffic;
        const stream = createReadStream(pdfPath,{start,end}); stream.on('data', chunk => { traffic.bytes += chunk.length; }); response.on('close',()=>stream.destroy()); stream.pipe(response); return;
      }
      response.setHeader('Content-Type', 'image/png'); return response.end(image);
    }
    if (url.pathname === '/reader/action') { response.writeHead(204); return response.end(); }
    if (url.pathname === '/reader/render-cache') {
      if (url.searchParams.get('namespace') !== namespace) return json({});
      const key = `${namespace}-${url.searchParams.get('kind')}-${url.searchParams.get('key')}`;
      if (!/^[\w-]+$/.test(key)) return json({});
      const value = await fs.readFile(path.join(runDirectory, key), 'utf8').catch(() => null); return json({ value });
    }
    if (url.pathname === '/reader/test-cache-put') {
      let raw = ''; for await (const chunk of request) raw += chunk;
      const value = JSON.parse(raw);
      if (value.type === 'cache-put' && value.namespace === namespace && /^[A-Z]+$/.test(value.kind) && /^[a-f0-9]{64}$/.test(value.key)) await fs.writeFile(path.join(runDirectory, `${namespace}-${value.kind}-${value.key}`), value.value);
      return json({ request: value.request, accepted: true });
    }
    if (sources[activeVersion].files.has(url.pathname)) {
      let bytes = sources[activeVersion].files.get(url.pathname);
      if (url.pathname === '/reader/reader.js') bytes = Buffer.from('window.openMeasurement={moduleStart:performance.now()};\n' + bytes.toString().replace('window.readerReady = true;', 'window.openMeasurement.firstScreen=performance.now(); window.readerReady = true;'));
      response.setHeader('Content-Type', types[path.extname(url.pathname)] || 'application/octet-stream'); return response.end(bytes);
    }
    for (const directory of [activeAssets, generated]) {
      const file = path.resolve(directory, '.' + decodeURIComponent(url.pathname));
      if (!file.startsWith(path.resolve(directory) + path.sep)) continue;
      try {
        let bytes = await fs.readFile(file);
        if (url.pathname === '/reader/reader.js') bytes = Buffer.from('window.openMeasurement={moduleStart:performance.now()};\n' + bytes.toString().replace('window.readerReady = true;', 'window.openMeasurement.firstScreen=performance.now(); window.readerReady = true;'));
        response.setHeader('Content-Type', types[path.extname(file)] || 'application/octet-stream'); return response.end(bytes);
      } catch {}
    }
    failures.push(url.pathname); response.writeHead(404); response.end();
  } catch (error) { failures.push(error.message); response.writeHead(500); response.end(error.message); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const address = `http://127.0.0.1:${server.address().port}/reader/index.html?note=sample&rev=sample&dark=true&scale=1&offset=0&auto=false`;
async function createPage(browser) {
  const page = await browser.newPage({ viewport: { width: 412, height: 892 } });
  await page.addInitScript(() => {
    const bridge = new EventTarget(); bridge.postMessage = data => fetch('/reader/test-cache-put', { method: 'POST', body: data }).then(r => r.json()).then(value => bridge.dispatchEvent(new MessageEvent('message', { data: JSON.stringify(value) })));
    window.ReaderCache = bridge;
  });
  page.on('pageerror', error => failures.push(error.message));
  return page;
}
async function measure(page, version, document, state, run) {
  currentTraffic = { bytes: 0 };
  await page.goto(address, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => window.readerReady === true, { timeout: 45000 });
  const first = await page.evaluate(() => ({ firstScreen: window.openMeasurement.firstScreen, heap: performance.memory?.usedJSHeapSize, cacheHit: !!window.readerMetrics?.cacheHit }));
  const session = await page.context().newCDPSession(page); await session.send('Performance.enable');
  const metrics = await session.send('Performance.getMetrics'); await session.detach();
  if (version === '0.2.2') await page.evaluate(() => window.readerDrain());
  const end = await page.evaluate(() => ({ visited: performance.now(), requestedReady: window.readerMetrics?.idle ?? window.openMeasurement.firstScreen, requests: performance.getEntriesByType('resource').length, bytes: performance.getEntriesByType('resource').reduce((n, r) => n + r.decodedBodySize, 0) }));
  if (document === '50MiBPDF' && !(await page.locator('.pdf-controls').innerText()).includes('2 / 2')) throw Error('PDF 请求页未完成，不能将占位当成 PDF 渲染通过');
  if (document === '50MiBPDF') { await page.waitForTimeout(100); if (version === '0.2.2' && currentTraffic.bytes > pdfBytes / 2) throw Error('当前页面读取了过多 PDF 数据，需检查范围读取及预取设置'); }
  rows.push({ version, document, state, run, ...first, ...end, pdfReadBytes: currentTraffic.bytes, jsHeap: metrics.metrics.find(m => m.name === 'JSHeapUsedSize')?.value, nodes: metrics.metrics.find(m => m.name === 'Nodes')?.value });
}
try {
  for (const [name, fixture] of Object.entries(fixtures)) {
    active = fixture;
    for (let run = 1; run <= rounds; run++) {
      for (const [version, directory] of [['0.2.1', baseline], ['0.2.2', current]]) {
        activeAssets = directory; activeVersion = version;
        namespace = `${version.replaceAll('.', '-')}-${name}-${run}`; projectionSeen = false;
        let browser = await chromium.launch({ executablePath: browserPath, headless: true });
        try {
          const page = await createPage(browser);
          await measure(page, version, name, 'empty-cache', run);
          await measure(page, version, name, 'same-process-reopen', run);
        } finally { await browser.close(); }
        browser = await chromium.launch({ executablePath: browserPath, headless: true });
        try { await measure(await createPage(browser), version, name, 'new-browser-with-disk-cache', run); }
        finally { await browser.close(); }
      }
      if (run % 5 === 0) console.log(`PROGRESS ${name}: ${run}/${rounds} paired rounds`);
    }
    console.log(`MEASURED paired ${name}: ${rounds} x 3 states x 2 versions`);
  }
  const percentile = (values, rate) => [...values].sort((a,b) => a-b)[Math.ceil(values.length * rate)-1];
  const groups = [];
  for (const version of ['0.2.1','0.2.2']) for (const document of Object.keys(fixtures)) for (const state of ['empty-cache','same-process-reopen','new-browser-with-disk-cache']) {
    const values = rows.filter(r => r.version === version && r.document === document && r.state === state);
    groups.push({ version, document, state, count: values.length, p50: percentile(values.map(r=>r.firstScreen),.5), p95: percentile(values.map(r=>r.firstScreen),.95), requestedReadyP95: percentile(values.map(r=>r.requestedReady),.95), pdfReadBytesP95: percentile(values.map(r=>r.pdfReadBytes),.95), heapP95: percentile(values.map(r=>r.jsHeap),.95), cacheHitRate: values.filter(r=>r.cacheHit).length/values.length });
  }
  const report = { environment: { browser: browserPath, rounds, paired: true, pdfBytes, fingerprints: Object.fromEntries(Object.entries(sources).map(([version,value])=>[version,value.fingerprint])), imageBytes: image.length, imageSha256: createHash('sha256').update(image).digest('hex'), rawBytes: Object.fromEntries(Object.entries(fixtures).map(([name,f])=>[name,f.rawBytes])) }, limits: 'Desktop Chrome renderer comparison only; HTTP adapter simulates native cache transport with files. Excludes Android storage validation, WebView startup and input. Cold means no derived cache for this note, not cold Android process. PDF firstScreen permits a stable placeholder; requestedReady separately records completion of the requested PDF page.', groups, rows, failures };
  await fs.writeFile(path.join(evidence, 'open-performance.json'), JSON.stringify(report,null,2));
  console.log(JSON.stringify(groups));
  if (failures.length) throw Error(failures.join('\n'));
} finally { await new Promise(resolve => server.close(resolve)); }

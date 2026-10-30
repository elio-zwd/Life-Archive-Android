import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import http from 'node:http';
import fs from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
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
const served = []; const cachedUrls = new Set(); let rootReads = 0;
const artifacts = new Map(); const documentKeys = new Set(); const cacheNamespace = 'reader-test-cache';
let changedNested = false;
let failRemoteInfo = false;
let holdImages = false; let imageActive = 0; let imagePeak = 0; const imageReleases = [];
const server = http.createServer(async (request, response) => {
  try {
    const url = new URL(request.url, 'http://127.0.0.1');
    served.push(url.pathname);
    const reply = (value) => { response.setHeader('Content-Type', 'application/json'); response.end(JSON.stringify(value)); };
    if (url.pathname === '/reader/document') {
      const revision = url.searchParams.get('revision'); const note = url.searchParams.get('note'); if (note === 'root') rootReads++;
      const html = revision === 'slow' ? '<p data-source="0">旧响应</p>' : revision === 'fast' ? '<p data-source="0">新响应</p>' : revision === 'plain' ? '<p data-source="0">普通正文</p>' : revision === 'deferred' ? '<h1 data-source="0">首屏先读</h1><p data-source="10">无需等待屏外排版。</p><div data-source="100" style="height:5000px">长文上下文</div><p id="lazy-anchor" data-source="5000">当前阅读段落 <span class="math" data-display="false" data-tex="x^7+y^7"></span></p><pre data-source="5100"><code class="language-mermaid">flowchart LR\n延迟-->就绪</code></pre>' : revision === 'folded' ? '<h1 data-source="0">折叠内容按需加载</h1><blockquote data-source="20"><p>[!note]- 折叠图表\n展开后准备</p><pre data-source="40"><code class="language-mermaid">flowchart LR\n打开-->完成</code></pre></blockquote>' : note === 'target' ? (changedNested ? fixtures.nested.replace('嵌入段落', '嵌入正文已更新') : fixtures.nested) : fixtures.root;
      if (revision === 'slow') await new Promise(resolve => setTimeout(resolve, 180));
      const projection = revision === 'toolbar' ? fixtures.toolbarGenerated : html;
      const projectionKey = createHash('sha256').update(projection).digest('hex'); const cacheHit = documentKeys.has(projectionKey); documentKeys.add(projectionKey);
      return reply({ id: note, revision, path: '验收.md', html: revision === 'long-properties' ? `<details class="properties" data-source="0"><summary>属性</summary><pre>${'自定义字段\n'.repeat(200)}</pre></details><h1 data-source="10">真实标题</h1><p data-source="50">最后正文</p>` : revision === 'polish' ? '<details class="properties" data-source="0"><summary>属性</summary><pre>未知属性</pre></details><h1 data-source="0">真实标题</h1><p data-source="20">正文</p><h2 data-source="40">下一节</h2><p data-source="50">尾文</p>' : revision === 'resource-failure' ? '<h1 data-source="0">附件失败可重试</h1><img src="https://failure.example/image.png">' : revision === 'concurrency' ? '<h1 data-source="0">附件并发检查</h1><p data-source="10"><img alt="10x10" src="a.png"><img alt="10x10" src="b.png"><img alt="10x10" src="c.png"></p>' : revision === 'old-size' ? '<h1 data-source="0">尺寸缓存校验</h1><p data-source="10"><img src="图片.png"></p>' : projection, metadata: revision === 'polish' ? {folder: '生活', modified: 1761350400000, tags: ['日常']} : null, blocks: [], projectionKey, cacheNamespace, cacheHit });
    }
    if (url.pathname === '/reader/resolve') {
      const target = url.searchParams.get('target');
      if (target.startsWith('目标')) return reply({ note: 'target', offset: target.includes('#') ? fixtures.nestedHeadingOffset : 0 });
      if (target === '循环') return reply({ note: 'root', offset: 0 });
      if (target.startsWith('文档.pdf')) return reply({ attachment: '文档.pdf' });
      if (target.startsWith('图片.png')) return reply({ attachment: '图片.png' });
      if (/^[abc]\.png$/.test(target)) return reply({ attachment: target });
      if (target.startsWith('声音.wav')) return reply({ attachment: '声音.wav' });
      if (target.startsWith('视频.mp4')) return reply({ attachment: '视频.mp4' });
      return reply({ missing: true });
    }
    if (url.pathname === '/reader/cache-info') {
      if (failRemoteInfo) { response.writeHead(500); response.end(); return; }
      return reply({ key: cachedUrls.has(url.searchParams.get('url')) ? 'a'.repeat(64) : null, fetched: Date.now() });
    }
    if (url.pathname === '/reader/resource-info') return reply({ width: 1, height: 1 });
    if (url.pathname === '/reader/render-cache') return reply({ value: url.searchParams.get('namespace') === cacheNamespace ? artifacts.get(`${url.searchParams.get('kind')}:${url.searchParams.get('key')}`) : null });
    if (url.pathname === '/reader/test-cache-put') {
      let body = ''; for await (const chunk of request) body += chunk;
      const value = JSON.parse(body);
      if (value.type === 'cache-put' && value.namespace === cacheNamespace) artifacts.set(`${value.kind}:${value.key}`, value.value);
      return reply({ request: value.request, accepted: true });
    }
    if (url.pathname.startsWith('/reader/cached/')) { response.setHeader('Content-Type', 'image/png'); return response.end(png); }
    if (url.pathname === '/reader/action') { actions.push(Object.fromEntries(url.searchParams)); response.writeHead(204); return response.end(); }
    if (url.pathname === '/reader/resource') {
      const resourcePath = url.searchParams.get('path');
      if (/^[abc]\.png$/.test(resourcePath)) { imageActive++; imagePeak = Math.max(imagePeak, imageActive); response.on('finish', () => imageActive--); if (holdImages) await new Promise(resolve => imageReleases.push(resolve)); }
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
await page.addInitScript(() => {
  const bridge = new EventTarget();
  bridge.postMessage = data => fetch('/reader/test-cache-put', { method: 'POST', body: data }).then(response => response.json()).then(value => bridge.dispatchEvent(new MessageEvent('message', { data: JSON.stringify(value) })));
  window.ReaderCache = bridge;
  window.readerFrames = [];
  const sample = () => {
    if (document.body) window.readerFrames.push({ background: getComputedStyle(document.body).backgroundColor, visible: document.querySelector('#content') && getComputedStyle(document.querySelector('#content')).visibility === 'visible', ready: !!window.readerReady });
    if (!window.readerReady) requestAnimationFrame(sample);
  }; requestAnimationFrame(sample);
});
await page.route('**/mermaid/mermaid.esm.min.mjs', async route => { await new Promise(resolve => setTimeout(resolve, 250)); await route.continue(); });
const errors = []; const remoteRequests = [];
page.on('pageerror', error => errors.push(error.message));
page.on('request', request => { if (!request.url().startsWith('http://127.0.0.1:') && !request.url().startsWith('blob:') && !request.url().startsWith('data:')) remoteRequests.push(request.url()); });
const checks = [];
try {
  await page.goto(`http://127.0.0.1:${server.address().port}/reader/index.html?note=root&rev=polish&theme=blue&dark=true`);
  await page.waitForFunction(() => window.readerReady === true);
  assert.equal(await page.locator('#content > h1').count(), 1);
  assert.ok((await page.locator('.reader-meta').innerText()).includes('生活'));
  assert.equal(await page.locator('#content > :first-child').evaluate(node => node.tagName), 'H1');
  assert.equal(await page.locator('#content > :last-child').getAttribute('class'), 'properties');
  assert.equal(await page.evaluate(() => document.documentElement.classList.contains('dark')), false);
  assert.equal(await page.locator('.reader-meta .reader-meta-tag').innerText(), '#日常');
  assert.deepEqual(await page.evaluate(() => window.readerOutline()), [{title: '真实标题', level: 1, offset: 0}, {title: '下一节', level: 2, offset: 40}]);
  assert.equal(await page.evaluate(() => window.readerJump(40)), true);
  const readsBeforeTheme = rootReads;
  await page.evaluate(() => window.readerUpdate({ revision: 'polish', theme: 'warm', scale: 1, auto: false, metadata: {folder: '工作', modified: 1761350400000, tags: ['真实标签']} }));
  assert.equal(await page.evaluate(() => getComputedStyle(document.body).backgroundColor), 'rgb(250, 247, 239)');
  assert.ok((await page.locator('.reader-meta').innerText()).includes('工作'));
  assert.equal(await page.locator('.reader-meta .reader-meta-tag').innerText(), '#真实标签');
  assert.equal(rootReads, readsBeforeTheme); checks.push('三主题切换、真实元信息与目录定位');
  await page.goto(`http://127.0.0.1:${server.address().port}/reader/index.html?note=root&rev=plain&theme=blue`);
  await page.waitForFunction(() => window.readerReady === true);
  assert.equal(await page.locator('#content h1').count(), 0); checks.push('无开头 H1 不合成标题');
  await page.goto(`http://127.0.0.1:${server.address().port}/reader/index.html?note=root&rev=long-properties&theme=blue`);
  await page.waitForFunction(() => window.readerReady === true);
  await page.locator('.properties > summary').click();
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  assert.equal(await page.evaluate(() => window.readerOffset()), 50); checks.push('滚入长属性时保留最后正文位置');
  for (const dark of [false, true]) {
    await page.goto(`http://127.0.0.1:${server.address().port}/reader/index.html?note=root&dark=${dark}&offset=0&auto=false`);
    await page.waitForFunction(() => window.readerReady === true, { timeout: 45000 });
    await page.evaluate(() => window.readerDrain());
    const frames = await page.evaluate(() => window.readerFrames);
    assert.ok(frames.length > 0); assert.ok(frames.every(frame => frame.background === (dark ? 'rgb(32, 35, 39)' : 'rgb(247, 248, 250)')));
    assert.ok(frames.every(frame => frame.ready || !frame.visible)); checks.push(`${dark ? '深色' : '浅色'}首帧与原子展示`);
    assert.equal(await page.locator('.math[data-rendered="true"] > mjx-container[jax="SVG"]').count(), 2); assert.equal(await page.locator('.math [data-mml-node="merror"]').count(), 0); checks.push(`${dark ? '深色' : '浅色'}公式渲染`);
    assert.equal(await page.locator('.mermaid svg').count(), 2); checks.push(`${dark ? '深色' : '浅色'}Mermaid 渲染`);
    await Promise.all([page.waitForResponse(response => response.url().includes('/reader/action?type=link')), page.locator('.embedded-note .mermaid .internal-link').first().evaluate(node => node.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true })))]);
    assert.equal(actions.at(-1).from, 'target'); assert.equal(actions.at(-1).value, '嵌入目标');
    await Promise.all([page.waitForResponse(response => response.url().includes('/reader/action?type=link')), page.locator('.embedded-note a[href="local.md#section"]').evaluate(node => node.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true })))]);
    assert.equal(actions.at(-1).from, 'target'); assert.equal(actions.at(-1).syntax, 'MARKDOWN'); checks.push(`${dark ? '深色' : '浅色'}嵌入链接归属`);
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
    const reads = rootReads;
    await page.evaluate(async () => { window.retainedAudio = document.querySelector('audio'); window.retainedCallout = document.querySelector('details.callout'); await window.readerUpdate({ revision: window.readerRevision, dark: document.documentElement.classList.contains('dark'), scale: 1, auto: false }); });
    assert.equal(rootReads, reads); assert.ok(await page.evaluate(() => window.retainedAudio === document.querySelector('audio')));
    const remoteUrl = await page.locator('[data-resource-url]').first().getAttribute('data-resource-url'); cachedUrls.add(remoteUrl);
    await page.evaluate(url => window.readerResourceUpdated(url, false), remoteUrl);
    assert.equal(await page.locator('[data-resource-url] img').first().evaluate(node => node.style.aspectRatio), '1 / 1');
    assert.ok(await page.evaluate(() => window.retainedAudio === document.querySelector('audio') && window.retainedCallout === document.querySelector('details.callout')));
    assert.equal(rootReads, reads); checks.push(`${dark ? '深色' : '浅色'}配置与资源更新保持会话`);
    const directory = process.env.LIFE_ARCHIVE_EVIDENCE || path.join(process.env.TEMP || 'D:/Temp', 'life-archive-phase1-browser'); await fs.mkdir(directory, { recursive: true });
    await page.screenshot({ path: path.join(directory, dark ? 'reader-dark.png' : 'reader-light.png'), fullPage: true });
  }
  await page.evaluate(async () => {
    window.refreshContents = [];
    const observer = new MutationObserver(() => window.refreshContents.push(document.querySelector('#content').textContent.length)); observer.observe(document.querySelector('#content'), { childList: true });
    const slow = window.readerUpdate({ revision: 'slow', dark: true, scale: 1, auto: false });
    await new Promise(resolve => setTimeout(resolve, 30));
    const fast = window.readerUpdate({ revision: 'fast', dark: true, scale: 1, auto: false });
    await Promise.allSettled([slow, fast]);
    observer.disconnect();
  });
  assert.equal(await page.evaluate(() => window.readerRevision), 'fast'); assert.equal(await page.locator('#content').innerText(), '新响应'); assert.equal(await page.locator('.reader-staging').count(), 0); checks.push('旧渲染回执不覆盖新正文');
  assert.ok(await page.evaluate(() => window.refreshContents.length > 0 && window.refreshContents.every(length => length > 0))); checks.push('刷新不清空整页正文');
  const start = served.length;
  await page.goto(`http://127.0.0.1:${server.address().port}/reader/index.html?note=root&rev=plain&dark=true&offset=0&auto=false`);
  await page.waitForFunction(() => window.readerReady === true);
  assert.ok(!served.slice(start).some(name => /\/reader\/(mermaid|mathjax|pdfjs)\//.test(name))); checks.push('普通笔记不加载复杂渲染库');
  const lazyStart = served.length;
  await page.goto(`http://127.0.0.1:${server.address().port}/reader/index.html?note=root&rev=deferred&dark=true&offset=0&auto=false`);
  await page.waitForFunction(() => window.readerReady === true);
  assert.equal(await page.locator('h1').innerText(), '首屏先读');
  assert.ok(!served.slice(lazyStart).some(name => /\/reader\/(mermaid|mathjax|pdfjs)\//.test(name))); checks.push('屏外公式与图表不阻塞首屏');
  await page.locator('#lazy-anchor').evaluate(node => node.scrollIntoView());
  const anchorTop = await page.locator('#lazy-anchor').evaluate(node => node.getBoundingClientRect().top);
  await page.waitForFunction(() => document.querySelector('.math[data-rendered]') && document.querySelector('.mermaid svg'));
  assert.ok(Math.abs(await page.locator('#lazy-anchor').evaluate(node => node.getBoundingClientRect().top) - anchorTop) < 4); checks.push('滚动按需渲染保持原文锚点');
  await page.evaluate(() => window.readerDrain());
  const warmed = served.length;
  await page.reload(); await page.waitForFunction(() => window.readerReady === true); await page.evaluate(() => window.readerDrain());
  assert.ok(!served.slice(warmed).some(name => /\/reader\/(mermaid|mathjax)\//.test(name))); checks.push('重开命中公式图表缓存无需加载渲染库');
  assert.ok(await page.evaluate(() => [...document.querySelectorAll('.math svg [id],.mermaid svg [id]')].map(n => n.id).length === new Set([...document.querySelectorAll('.math svg [id],.mermaid svg [id]')].map(n => n.id)).size)); checks.push('缓存 SVG 标识重新绑定');
  const scaled = served.length;
  await page.evaluate(() => window.readerUpdate({ revision: 'deferred', dark: true, scale: 1.2, auto: false })); await page.evaluate(() => window.readerDrain());
  assert.ok(served.slice(scaled).some(name => /\/reader\/(mermaid|mathjax)\//.test(name))); checks.push('字号变化使排版缓存失效');
  const entries = artifacts.size;
  await page.setViewportSize({ width: 480, height: 892 });
  await page.waitForFunction(() => window.readerMetrics.layoutWidth === Math.round(document.querySelector('#content').getBoundingClientRect().width));
  await page.evaluate(() => window.readerDrain());
  assert.ok(artifacts.size > entries); checks.push('宽度变化采用新的排版缓存');
  const folded = served.length;
  await page.goto(`http://127.0.0.1:${server.address().port}/reader/index.html?note=root&rev=folded&dark=true&offset=0&auto=false`);
  await page.waitForFunction(() => window.readerReady === true);
  assert.ok(!served.slice(folded).some(name => name.includes('/mermaid/')));
  await page.locator('summary').click(); await page.waitForFunction(() => document.querySelector('.mermaid svg')); checks.push('折叠图表展开后加载');
  await page.goto(`http://127.0.0.1:${server.address().port}/reader/index.html?note=root&rev=deferred&dark=true&offset=5000&auto=false`);
  await page.waitForFunction(() => window.readerReady === true);
  assert.ok(await page.evaluate(() => window.readerOffset() >= 5000));
  assert.equal(await page.locator('.math[data-rendered]').count(), 1); assert.equal(await page.locator('.mermaid svg').count(), 1); checks.push('保存位置处的首屏优先完成排版');
  assert.ok(await page.evaluate(async () => {
    const { artifactMarkup } = await import('/reader/reader-cache.js');
    const fragment = artifactMarkup('<svg xmlns="http://www.w3.org/2000/svg"><style>#x {fill:red}</style><defs><path id="x" d="M0 0"/></defs><use href="#x"/><g onload="bad()" style="filter:url(#x);background:url(//invalid.example/x)"></g><foreignObject><div xmlns="http://www.w3.org/1999/xhtml"><b>安全文字</b><script>bad()</script></div></foreignObject></svg>', 'safe');
    return !fragment.querySelector('script,[onload],g[style]') && fragment.querySelector('use').getAttribute('href') === '#safe-x' && fragment.querySelector('style').textContent.includes('#safe-x') && fragment.textContent.includes('安全文字');
  })); checks.push('缓存 SVG 清理及片段引用保真');
  failRemoteInfo = true;
  await page.goto(`http://127.0.0.1:${server.address().port}/reader/index.html?note=root&rev=resource-failure&dark=true&offset=0&auto=false`);
  await page.waitForFunction(() => window.readerReady === true);
  await page.getByRole('button', { name: '重试', exact: true }).waitFor();
  assert.equal(await page.locator('h1').innerText(), '附件失败可重试');
  failRemoteInfo = false; await page.getByRole('button', { name: '重试', exact: true }).click();
  await page.getByRole('button', { name: '加载并缓存', exact: true }).waitFor(); checks.push('附件局部失败可重试且不阻塞正文');
  holdImages = true;
  await page.goto(`http://127.0.0.1:${server.address().port}/reader/index.html?note=root&rev=concurrency&dark=true&offset=0&auto=false`);
  await page.waitForFunction(() => window.readerReady === true);
  for (let i = 0; i < 100 && imageReleases.length < 2; i++) await new Promise(resolve => setTimeout(resolve, 10));
  assert.equal(imageReleases.length, 2); await page.waitForTimeout(50); assert.equal(imageReleases.length, 2);
  holdImages = false; imageReleases.forEach(release => release()); await page.evaluate(() => window.readerDrain());
  assert.ok(imagePeak <= 2); assert.equal(await page.locator('img').evaluateAll(nodes => nodes.filter(n => n.complete && n.naturalWidth > 0).length), 3); checks.push('附件读取和解码准备最多两个并发槽');
  changedNested = true;
  artifacts.set(`GEOMETRY:${createHash('sha256').update('image-size|图片.png').digest('hex')}`, JSON.stringify({ width: 999, height: 500 }));
  await page.goto(`http://127.0.0.1:${server.address().port}/reader/index.html?note=root&dark=true&offset=0&auto=false`);
  await page.waitForFunction(() => window.readerReady === true); await page.evaluate(() => window.readerDrain());
  assert.ok((await page.locator('.embedded-note').innerText()).includes('嵌入正文已更新')); checks.push('主笔记缓存不会掩盖嵌入目标更新');
  await page.goto(`http://127.0.0.1:${server.address().port}/reader/index.html?note=root&rev=old-size&dark=true&offset=0&auto=false`);
  await page.waitForFunction(() => window.readerReady === true); await page.evaluate(() => window.readerDrain());
  assert.equal(await page.locator('img').evaluate(node => node.style.aspectRatio), '1 / 1'); checks.push('旧附件尺寸缓存不能覆盖实际解码尺寸');
  assert.deepEqual(JSON.parse(artifacts.get(`GEOMETRY:${createHash('sha256').update('image-size|图片.png').digest('hex')}`)), { width: 1, height: 1 });
  await page.goto(`http://127.0.0.1:${server.address().port}/reader/index.html?note=root&rev=toolbar&dark=true&offset=0&auto=false`);
  await page.waitForFunction(() => window.readerReady === true); await page.evaluate(() => window.readerDrain());
  assert.equal(await page.locator('table tbody tr').count(), 2);
  assert.equal(await page.locator('pre code').filter({ hasText: '在这里输入代码' }).count(), 1);
  assert.equal(await page.locator('#content > hr').count(), 1);
  assert.ok((await page.locator('aside.callout').innerText()).includes('提示内容'));
  assert.equal(await page.locator('.math svg').count(), 2); assert.equal(await page.locator('.mermaid svg').count(), 1);
  assert.ok((await page.locator('.footnotes').innerText()).includes('脚注内容'));
  checks.push('工具栏实际生成的表格代码分隔线 Callout 公式 Mermaid 脚注完整阅读');
  assert.equal(errors.length, 0, errors.join('\n')); assert.equal(failures.length, 0, failures.join('\n'));
  console.log(`PASS ${checks.length} 浏览器检查：${checks.join('、')}`);
} catch (error) {
  console.error(JSON.stringify({ errors, failures, remoteRequests, math: await page.locator('.math').evaluateAll(nodes => nodes.map(n => n.outerHTML.slice(0, 1000))), status: await page.locator('#status').textContent().catch(() => ''), body: (await page.locator('body').innerText().catch(() => '')).slice(-2000) }));
  throw error;
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }

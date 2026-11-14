import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const roots = ['core/markdown/src/main/assets', 'core/markdown/build/generated/readerAssets'];
const fixtures = JSON.parse(await fs.readFile(path.join(root, 'core/document/build/reader-fixtures.json'), 'utf8'));
let html = fixtures.toolbarGenerated; let forbidden = 0;
const server = http.createServer(async (request, response) => {
  const url = new URL(request.url, 'http://127.0.0.1');
  if (url.pathname === '/reader/fragment') { const captured = html; if (url.searchParams.get('revision') === 'late-selection') await new Promise(resolve => setTimeout(resolve, 100)); response.setHeader('Content-Type', 'application/json'); response.end(JSON.stringify({html:captured, blocks: []})); return; }
  if (url.pathname === '/reader/resolve') { response.end('{"missing":true}'); return; }
  if (url.pathname === '/reader/cache-info') { response.end('{}'); return; }
  if (url.pathname === '/reader/action') { response.writeHead(204); response.end(); return; }
  if (/document|resource|render-cache|covers/.test(url.pathname)) forbidden++;
  const relative = decodeURIComponent(url.pathname).slice(1);
  if (relative.split('/').some(part => part === '..' || part.includes('\\'))) { response.writeHead(404); response.end(); return; }
  for (const base of roots) try {
    const content = await fs.readFile(path.join(root, base, relative));
    const mime = {'.js':'text/javascript','.mjs':'text/javascript','.css':'text/css','.html':'text/html','.json':'application/json'};
    response.setHeader('Content-Type', mime[path.extname(relative)] || 'application/octet-stream'); response.end(content); return;
  } catch {}
  response.writeHead(404); response.end();
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const browser = await chromium.launch({headless:true});
try {
  const page = await browser.newPage({viewport:{width:412,height:892}});
  const errors = [];
  page.on('pageerror', error => errors.push(String(error)));
  await page.addInitScript(() => { window.ReaderFragment = {postMessage: raw => { const message = JSON.parse(raw); if ('copy' in message) window.lastCopy = message.copy; else window.lastSize = message; }}; });
  await page.goto(`http://127.0.0.1:${server.address().port}/reader/index.html?fragment=true&note=message-1&rev=first`);
  await page.waitForFunction(() => window.readerReady && window.lastSize?.height > 0);
  assert.equal(await page.locator('.reader-cover,.reader-meta').count(), 0);
  assert.equal(await page.locator('body').evaluate(node => getComputedStyle(node).paddingTop), '0px');
  // 使用 Life 生成的真实投影，公式、图表及样板不再另写一套解析。
  await page.evaluate(() => window.readerDrain());
  html = '<p data-source="0">第一段 <strong>流式正文</strong></p><pre data-source="20"><code>尚未闭合的代码</code></pre>';
  await page.evaluate(() => window.readerUpdate({revision:'stream-1',scale:1}));
  assert.match(await page.locator('#content').innerText(), /尚未闭合/);
  await page.locator('.code-copy').click();
  assert.equal(await page.evaluate(() => window.lastCopy), '尚未闭合的代码');
  // 选区期间保留 DOM 与选区，清除选区后展示最新帧。
  await page.evaluate(() => { const range = document.createRange(); range.selectNodeContents(document.querySelector('strong')); const selection = getSelection(); selection.removeAllRanges(); selection.addRange(range); });
  html = '<p data-source="0">已完成的最后一帧</p>';
  await page.evaluate(() => window.readerUpdate({revision:'stream-final',scale:1}));
  assert.equal(await page.evaluate(() => getSelection().toString()), '流式正文');
  assert.equal(await page.evaluate(() => window.readerRevision), 'stream-1');
  await page.evaluate(() => getSelection().removeAllRanges());
  await page.waitForFunction(() => window.readerRevision === 'stream-final');
  assert.match(await page.locator('#content').innerText(), /最后一帧/);
  html = '<p data-source="0">渲染已开始后才选择的文字也要保留</p>';
  await page.evaluate(() => { window.pendingRender = window.readerUpdate({revision:'late-selection'}); const range = document.createRange(); range.selectNodeContents(document.querySelector('#content p')); const selection = getSelection(); selection.removeAllRanges(); selection.addRange(range); });
  await page.evaluate(() => window.pendingRender);
  assert.equal(await page.evaluate(() => getSelection().toString()), '已完成的最后一帧');
  await page.evaluate(() => getSelection().removeAllRanges());
  await page.waitForFunction(() => window.readerRevision === 'late-selection');
  html = '<blockquote data-source="0"><p>[!note]- 可折叠\n正文</p></blockquote>';
  await page.evaluate(() => window.readerUpdate({revision:'fold-1'}));
  await page.locator('summary').click();
  html += '<p data-source="100">追加</p>';
  await page.evaluate(() => window.readerUpdate({revision:'fold-2',dark:true,scale:1.2}));
  assert.equal(await page.locator('details').evaluate(node => node.open), true);
  assert.equal(await page.locator('html').evaluate(node => node.classList.contains('dark')), true);
  // 修订覆盖和长回答；输出性能证据，不以电脑数据冒充手机帧率。
  const started = performance.now();
  for (let i = 0; i < 100; i++) {
    html = `<p data-source="0">第 ${i} 帧</p><pre data-source="20"><code>${'长回答\n'.repeat(500)}</code></pre>`;
    await page.evaluate(revision => window.readerUpdate({revision,scale:1}), `long-${i}`);
  }
  assert.equal(await page.evaluate(() => window.readerRevision), 'long-99');
  assert.equal(await page.locator('#content p').textContent(), '第 99 帧');
  assert.ok(await page.evaluate(() => window.lastSize.height) > 5000);
  assert.equal(forbidden, 0, '消息模式不得访问笔记、封面或持久化缓存');
  assert.deepEqual(errors, []);
  console.log(JSON.stringify({suite:'fragment',checks:18,longUpdates:100,elapsedMs:Math.round(performance.now()-started),browser:browser.version(),device:'desktop Chromium 412x892, not Android'}));
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }

import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import http from 'node:http';
import fs from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
if (process.argv.includes('--help')) {
  console.log('node reader/capture-covers.mjs --fixtures <NoteCoverPreviewTest 输出目录> [--compact]\n只使用已有浏览器与依赖，不连接设备；--compact 同时汇总编辑页与三页紧凑布局对比。');
  process.exit(0);
}
const value = process.argv[process.argv.indexOf('--fixtures') + 1];
if (!process.argv.includes('--fixtures') || !value) throw Error('请传入 --fixtures 目录');
const output = path.resolve(value);
const compact = process.argv.includes('--compact');
const documents = JSON.parse(await fs.readFile(path.join(output, 'documents.json'), 'utf8'));
assert.equal(documents.length, 2);
const assets = ['feature/editor/src/main/assets', 'feature/editor/build/generated/readerAssets', 'core/designsystem/src/main/assets'].map(p => path.join(root, p));
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png', '.json': 'application/json' };
const custom = await fs.readFile(path.join(output, 'custom-cover.png'));
const errors = [];
const server = http.createServer(async (request, response) => {
  try {
    const url = new URL(request.url, 'http://127.0.0.1');
    const reply = object => { response.setHeader('Content-Type', 'application/json'); response.end(JSON.stringify(object)); };
    if (url.pathname === '/reader/document') {
      const doc = documents.find(d => d.id === url.searchParams.get('note'));
      if (!doc) throw Error('示例不存在');
      return reply({ ...doc, blocks: [], cacheHit: false, cacheNamespace: null });
    }
    if (url.pathname === '/reader/resolve') return reply({ attachment: documents[1].metadata.cover.attachment });
    if (url.pathname === '/reader/resource-info') return reply({ width: custom.readUInt32BE(16), height: custom.readUInt32BE(20) });
    if (url.pathname === '/reader/resource') { response.setHeader('Content-Type', 'image/png'); return response.end(custom); }
    if (url.pathname === '/reader/action') { response.writeHead(204); return response.end(); }
    for (const directory of assets) {
      const file = path.resolve(directory, '.' + decodeURIComponent(url.pathname));
      if (!file.startsWith(directory + path.sep)) continue;
      try { const data = await fs.readFile(file); response.setHeader('Content-Type', types[path.extname(file)] || 'application/octet-stream'); return response.end(data); } catch { }
    }
    response.writeHead(404); response.end();
  } catch (error) { errors.push(error.message); response.writeHead(500); response.end(); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const executablePath = process.env.LIFE_ARCHIVE_BROWSER || ['C:/Program Files/Google/Chrome/Application/chrome.exe', 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'].find(existsSync);
let browser;
const screenshots = ['home-light.png', 'home-dark.png', 'picker-preset.png', 'picker-custom.png', 'picker-custom-empty.png'];
if (compact) for (const theme of ['blue', 'warm', 'graphite']) screenshots.push(`source-${theme}.png`, `source-${theme}-no-keyboard.png`, `live-${theme}-no-keyboard.png`);
try {
  browser = await chromium.launch({ executablePath, headless: true });
  const page = await browser.newPage({ viewport: { width: 432, height: 864 }, deviceScaleFactor: 2 });
  page.on('pageerror', error => errors.push(error.message));
  for (const [index, doc] of documents.entries()) for (const theme of ['blue', 'warm', 'graphite']) {
    await page.goto(`http://127.0.0.1:${server.address().port}/reader/index.html?note=${doc.id}&rev=${doc.revision}&theme=${theme}`);
    await page.waitForFunction(() => window.readerReady === true);
    await page.evaluate(() => window.readerDrain());
    await page.waitForFunction(() => document.querySelector('.reader-cover img')?.naturalWidth > 0);
    // 封面独立异步解码；正文 ready 不能冒充图片已经绘制完成。
    await page.evaluate(async () => {
      await Promise.all([...document.images].map(image => image.decode()));
      await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    });
    assert.equal(await page.locator('#content > h1').innerText(), doc.name);
    const order = await page.locator('#content > *').evaluateAll(nodes => nodes.slice(0, 4).map(node => node.className || node.tagName));
    assert.deepEqual(order, ['reader-opening-heading', 'reader-meta', 'reader-cover', 'P']);
    const file = `reader-${index + 1}-${theme}.png`;
    await page.screenshot({ path: path.join(output, file) }); screenshots.push(file);
    const heading = await page.evaluate(() => window.readerOutline().find(h => h.level === 2));
    assert.ok(heading); assert.equal(await page.evaluate(offset => window.readerJump(offset), heading.offset), true);
    await page.waitForTimeout(40);
    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
    await page.screenshot({ path: path.join(output, `reader-${index + 1}-${theme}-end.png`) });
  }
  assert.deepEqual(errors, []);
  const previewTitle = compact ? '三页紧凑布局预览' : '封面功能预览';
  const html = `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>Life Archive ${previewTitle}</title><style>body{margin:32px;background:#f4f6f8;color:#20242a;font:16px system-ui}section{display:flex;gap:24px;flex-wrap:wrap}figure{margin:0 0 24px}img{width:324px;border:1px solid #dde2e9;border-radius:12px}figcaption{padding:8px}p{max-width:1000px;line-height:1.8}</style><h1>Life Archive · ${previewTitle}</h1><p>两篇示例已通过 Repository 入库、保存、关闭重开。首页、编辑及面板来自正式 Compose；阅读正文使用同一份入库文档投影与正式 reader.js／reader.css，在电脑浏览器渲染，不包含 Android 顶栏及系统栏。编辑图中的键盘仅为占位。此处不代表模拟器或真机验收。${compact ? '<a href="comparison.html">查看三页调整前后对比</a>' : ''}</p><section>${screenshots.map(file => `<figure><img src="${file}"><figcaption>${file}</figcaption></figure>`).join('')}</section></html>`;
  await fs.writeFile(path.join(output, 'index.html'), html);
  const overview = [['home-light.png', '首页 · 紧凑列表'], ['reader-1-blue.png', '阅读正文 · 标题与封面'], compact ? ['source-blue.png', '编辑 · 工具面板与键盘占位'] : ['picker-preset.png', '正式 Compose · 预设与自定义']];
  await fs.writeFile(path.join(output, 'overview.html'), `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><style>body{margin:24px;background:#e9edf2;color:#243044;font:16px system-ui}h1{margin:0 0 6px;font-size:25px}p{margin:0 0 16px;color:#667085}.row{display:flex;gap:22px}.card{width:360px}img{display:block;width:360px;height:800px;object-fit:contain;object-position:top;background:#fafbfd;border-radius:14px}span{display:block;padding:9px 0}</style><h1>Life Archive · ${compact ? '三页紧凑布局' : '实际功能预览'}</h1><p>电脑预览 · 阅读为正式浏览器资源，编辑键盘为占位 · 设备未验收</p><div class="row">${overview.map(([file,label]) => `<div class="card"><img src="${file}"><span>${label}</span></div>`).join('')}</div></html>`);
  if (compact) await fs.writeFile(path.join(output, 'comparison.html'), `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>三页布局调整前后</title><style>body{background:#e9edf2;color:#243044;margin:24px;font:16px system-ui}.pair{display:flex;gap:20px;flex-wrap:wrap;margin-bottom:32px}img{display:block;width:324px;border:1px solid #ccd3dd;border-radius:12px}figure{margin:0}figcaption{padding:8px}</style><h1>三页上下布局调整前后</h1><p>同一逻辑视口；首页为两篇真实存储示例，阅读为正式浏览器资源，编辑为正式 Compose 与键盘占位。</p>${[['home-light.png','首页'],['reader-1-blue.png','阅读正文'],['source-blue.png','源码编辑']].map(([file,label]) => `<h2>${label}</h2><div class="pair"><figure><img src="before/${file}"><figcaption>调整前</figcaption></figure><figure><img src="${file}"><figcaption>调整后</figcaption></figure></div>`).join('')}</html>`);
  await page.setViewportSize({ width: 1172, height: 950 });
  await page.goto(new URL(`file:///${output.replaceAll('\\', '/')}/overview.html`).href);
  await page.evaluate(() => Promise.all([...document.images].map(img => img.decode())));
  await page.screenshot({ path: path.join(output, 'overview.png') });
  await fs.writeFile(path.join(output, 'preview-manifest.json'), JSON.stringify({ source: 'NoteCoverPreviewTest: real Repository create/save/reopen/query/openResource', documents: documents.map(d => ({ id: d.id, path: d.path, revision: d.revision, cover: d.metadata.cover })), screenshots, errors }, null, 2));
  console.log(`PASS 两篇真实示例 × 三主题、顺序与目录定位；预览：${path.join(output, 'index.html')}`);
} finally { await browser?.close(); await new Promise(resolve => server.close(resolve)); }

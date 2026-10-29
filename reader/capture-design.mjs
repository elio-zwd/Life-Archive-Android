import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { readFile, writeFile, mkdir, readdir, copyFile } from 'node:fs/promises';
import { resolve, dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createHash } from 'node:crypto';
import http from 'node:http';

// Windows 10 / PowerShell 7；复用 reader 已锁定的 Playwright，不下载浏览器。
const repo = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);
if (args.length !== 2 || args[0] !== '--out') {
  console.log('用法：node reader/capture-design.mjs --out D:/目标目录（必须为空）');
  process.exit(args.includes('--help') ? 0 : 1);
}
const output = resolve(args[1]);
await mkdir(output, { recursive: true });
if ((await readdir(output)).length) throw Error('交付目录必须为空，避免覆盖既有截图和资料。');
const screenshotDir = join(output, 'screenshots');
await mkdir(screenshotDir);
await mkdir(join(output, 'source'));
const sourcePath = join(repo, 'docs/design/prototypes/home-a-xiaomi14ultra.html');
const html = await readFile(sourcePath);
const sha256 = value => createHash('sha256').update(value).digest('hex');
const viewport = { width: 432, height: 960 };
const deviceScaleFactor = 10 / 3;
const records = [], tokens = {};
const browserPath = process.env.LIFE_ARCHIVE_BROWSER || [
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  '/usr/bin/chromium',
].find(existsSync);
if (!browserPath) throw Error('未找到现有 Chrome/Edge；请设置 LIFE_ARCHIVE_BROWSER。');

const server = http.createServer((_request, response) => {
  response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
  response.end(html);
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
let browser;
try {
  browser = await chromium.launch({ executablePath: browserPath, headless: true });
  const context = await browser.newContext({ viewport, deviceScaleFactor, reducedMotion: 'reduce', locale: 'zh-CN', timezoneId: 'Asia/Shanghai', colorScheme: 'light' });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(`http://127.0.0.1:${server.address().port}/?capture=1`);
  await page.evaluate(() => document.fonts.ready);
  assert.deepEqual(await page.locator('#device').evaluate(el => [el.clientWidth, el.clientHeight]), [432, 960]);

  async function capture(filename, description) {
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    const file = join(screenshotDir, filename + '.png');
    await page.locator('#device').screenshot({ path: file, animations: 'disabled', caret: 'hide', scale: 'device' });
    const image = await readFile(file);
    const width = image.readUInt32BE(16), height = image.readUInt32BE(20);
    assert.deepEqual([width, height], [1440, 3200], '截图必须是原生屏幕比例和像素规格');
    const ui = await page.evaluate(() => {
      const frame = document.querySelector('#device').getBoundingClientRect();
      const names = { status: '.status', header: '.panel.active .panel-head', reader: '#readerPanel.active #readerScroll', editor: '#editorPanel.active .editor-area', tools: '#editorPanel.active #toolPanelWrap', toolbar: '#editorPanel.active #formatBar', keyboard: '#editorPanel.active #keyboardPreview' };
      const regions = {};
      for (const [name, selector] of Object.entries(names)) {
        const node = document.querySelector(selector);
        if (!node || !node.getClientRects().length) continue;
        const rect = node.getBoundingClientRect();
        regions[name] = { x: rect.x - frame.x, y: rect.y - frame.y, width: rect.width, height: rect.height };
      }
      return { screen: document.querySelector('#device').dataset.screen || 'home', theme: document.querySelector('#device').dataset.pageTheme, regions };
    });
    records.push({ filename: 'screenshots/' + filename + '.png', description, width, height, sha256: sha256(image), ...ui });
    console.log('已保存 ' + filename + '.png');
  }
  async function theme(id, screen = 'editor') {
    await page.locator(screen === 'reader' ? '#readerMore' : '#editorMore').click();
    await page.getByRole('menuitem', { name: '主题', exact: true }).click();
    await page.locator(`.theme-choice[data-page-theme="${id}"]`).click();
    tokens[id] = await page.locator('#device').evaluate(el => {
      const style = getComputedStyle(el), values = {};
      for (const name of ['bg', 'ink', 'muted', 'line', 'accent', 'soft', 'tool', 'code']) values[name] = style.getPropertyValue('--page-' + name).trim();
      return values;
    });
  }

  await capture('01-home-light', '首页 · 浅色');
  await page.locator('#navSettings').click();
  await capture('02-settings-light', '设置 · 浅色');
  await page.locator('#themeSelect').selectOption('dark');
  await capture('03-settings-dark', '设置 · 深色');
  await page.locator('#settingsPanel [data-back]').click();
  await capture('04-home-dark', '首页 · 深色');
  await page.locator('#navSettings').click(); await page.locator('#themeSelect').selectOption('light');
  await page.locator('#settingsPanel [data-back]').click();
  await page.locator('[data-note="n1"]').click();
  for (const [id, label] of [['blue', 'a'], ['warm', 'b'], ['graphite', 'c']]) {
    await theme(id, 'reader');
    await capture(`05-reader-${label}`, `阅读页 · 主题 ${label.toUpperCase()}`);
  }
  await theme('blue', 'reader');
  await page.locator('#outlineBtn').click();
  await capture('06-reader-outline', '阅读页 · 目录');
  await page.locator('#dialogCancel').click();
  await page.locator('#readEditBtn').click();
  for (const [id, label] of [['blue', 'a'], ['warm', 'b'], ['graphite', 'c']]) {
    await theme(id);
    await capture(`07-editor-${label}-keyboard`, `编辑页 · 主题 ${label.toUpperCase()} · 键盘示意`);
    await page.locator('[data-panel="format"]').click();
    await capture(`08-editor-${label}-format`, `编辑页 · 主题 ${label.toUpperCase()} · 单行格式面板`);
    await page.locator('[data-panel="format"]').click();
  }
  await theme('blue');
  for (const type of ['list', 'insert']) {
    await page.locator(`[data-panel="${type}"]`).click();
    await capture(`09-editor-a-${type}`, `编辑页 · 主题 A · ${type === 'list' ? '列表' : '插入'}面板`);
  }
  await page.locator('[data-panel="format"]').click();
  const original = await page.locator('#editorText').inputValue();
  const button = await page.locator('[data-command="bold"]').boundingBox();
  await page.mouse.move(button.x + button.width / 2, button.y + button.height / 2); await page.mouse.down();
  await page.waitForFunction(() => !document.querySelector('#toolHint').hidden);
  await capture('10-editor-a-longpress', '编辑页 · 长按中文提示');
  await page.mouse.up();
  assert.equal(await page.locator('#editorText').inputValue(), original, '长按不得改变原文');
  await page.locator('#editorMore').click(); await page.getByRole('menuitem', { name: '主题', exact: true }).click();
  await capture('11-document-theme-picker', '阅读与编辑共用的三主题选择');
  await page.locator('#dialogCancel').click();
  await page.locator('#hideKeyboardBtn').click();
  await capture('12-editor-a-no-keyboard', '编辑页 · 收起键盘与工具面板');
  assert.deepEqual(errors, []);
  await context.close();

  await writeFile(join(output, 'source/home-a-xiaomi14ultra.html'), html);
  await mkdir(join(output, 'reference'));
  const handoff = (await readFile(join(repo, 'docs/design/xiaomi14ultra-ui-handoff.md'), 'utf8'))
    .replace('(prototypes/home-a-xiaomi14ultra.html)', '(source/home-a-xiaomi14ultra.html)')
    .replace('(../plans/phase-1-editor-visual-decisions.md)', '(reference/phase-1-editor-visual-decisions.md)');
  await writeFile(join(output, 'UI-HANDOFF.md'), handoff);
  await copyFile(join(repo, 'AGENTS.md'), join(output, 'reference/AGENTS.md'));
  await copyFile(join(repo, 'docs/architecture/adr-001-android-architecture.md'), join(output, 'reference/ADR-001.md'));
  await copyFile(join(repo, 'docs/plans/phase-1-editor-visual-decisions.md'), join(output, 'reference/phase-1-editor-visual-decisions.md'));
  await copyFile(fileURLToPath(import.meta.url), join(output, 'capture-design.mjs'));
  await writeFile(join(output, 'design-tokens.json'), JSON.stringify({ logicalViewport: viewport, exportScale: deviceScaleFactor, themes: tokens }, null, 2));
  const manifest = { generatedAt: new Date().toISOString(), sourceSha256: sha256(html), device: 'Xiaomi 14 Ultra', hardwareSource: 'https://www.mi.com/global/product/xiaomi-14-ultra/specs/', logicalViewport: viewport, physicalScreenshot: { width: 1440, height: 3200 }, deviceScaleFactor, simulatedSystemUi: true, realDeviceVerified: false, screenshots: records };
  await writeFile(join(output, 'manifest.json'), JSON.stringify(manifest, null, 2));
  const cards = entries => entries.map(record => `<figure><a href="${record.filename}"><img src="${record.filename}" alt="${record.description}"></a><figcaption>${record.description}</figcaption></figure>`).join('');
  const galleryStyle = 'body{margin:0;padding:32px;background:#eef2f7;color:#243044;font:14px/1.6 "Microsoft YaHei",sans-serif}h1{font-size:26px;margin:0}p{color:#718095}.grid{display:grid;grid-template-columns:repeat(3,264px);gap:22px;align-items:start}figure{margin:0}img{display:block;width:100%;height:auto;border-radius:8px;box-shadow:0 4px 15px #16253815}figcaption{padding:10px 0}a{color:#2569e8}.intro{margin-bottom:26px}';
  await writeFile(join(output, 'index.html'), `<!doctype html><html lang="zh-CN"><meta charset="UTF-8"><title>Life Archive UI 交接截图</title><style>${galleryStyle}</style><div class="intro"><h1>Life Archive · 小米 14 Ultra UI 交接</h1><p>截图 1440 × 3200 · 逻辑画布 432 × 960 · 共 ${records.length} 张实际 HTML 截图</p><a href="source/home-a-xiaomi14ultra.html#editor">打开交互 HTML</a> · <a href="UI-HANDOFF.md">实施说明</a></div><div class="grid">${cards(records)}</div></html>`);
  const overview = ['05-reader-a', '05-reader-b', '05-reader-c', '08-editor-a-format', '08-editor-b-format', '08-editor-c-format'].map(name => records.find(record => record.filename.endsWith(name + '.png')));
  await writeFile(join(output, 'overview.html'), `<!doctype html><html lang="zh-CN"><meta charset="UTF-8"><title>三主题阅读与编辑</title><style>${galleryStyle}</style><div class="intro"><h1>Life Archive · 三主题阅读与编辑</h1><p>小米 14 Ultra · 20:9 · 实际 HTML 截图缩略图，完整文件为 1440 × 3200</p></div><div class="grid">${cards(overview)}</div></html>`);
  const overviewPage = await browser.newPage({ viewport: { width: 900, height: 600 }, deviceScaleFactor: 1 });
  await overviewPage.goto(pathToFileURL(join(output, 'overview.html')).href);
  await overviewPage.evaluate(async () => { await document.fonts.ready; await Promise.all([...document.images].map(image => image.decode())); });
  await overviewPage.screenshot({ path: join(output, 'overview.png'), fullPage: true });
  await writeFile(join(output, 'README.md'), `# Life Archive UI 交接包\n\n请先阅读 [UI-HANDOFF.md](UI-HANDOFF.md)，再打开 [HTML](source/home-a-xiaomi14ultra.html) 和 [截图索引](index.html)。\n\n- screenshots/：${records.length} 张原生比例 PNG，均为 1440 × 3200。\n- overview.png：三主题阅读／编辑速览。\n- manifest.json：源 HTML SHA-256、截图尺寸、哈希和逻辑区域坐标。\n- design-tokens.json：从同一 HTML 读取的三套主题色。\n- capture-design.mjs：截图脚本副本；实际重跑请使用仓库 reader/capture-design.mjs，以复用已安装的 Playwright。\n\nWindows 10／PowerShell 7，在 Life-Archive-Android 仓库根目录运行：\n\n\`\`\`powershell\nnode reader/capture-design.mjs --out D:/新的空目录\n\`\`\`\n\n这是 HTML 设计参考，状态栏、挖孔、手势条和键盘为示意；不代表真机验收。不要把模拟键盘或系统 UI 画进 Android App。\n`);
  console.log(`完成：${records.length} 张截图，交付目录 ${output}`);
} finally {
  await browser?.close();
  await new Promise(resolve => server.close(resolve));
}

import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import { before, after, test } from 'node:test';
import { existsSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import http from 'node:http';

const html = await readFile(new URL('../../docs/design/prototypes/home-a-xiaomi14ultra.html', import.meta.url));
const store = 'life-archive-home-a-demo-v2';
const drafts = store + '-drafts';
const errors = [];
let browser, server, url;
before(async () => {
  server = http.createServer((request, response) => {
    response.setHeader('Content-Type', 'text/html; charset=utf-8');
    response.end(html);
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  url = `http://127.0.0.1:${server.address().port}/`;
  const executablePath = process.env.LIFE_ARCHIVE_BROWSER || [
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    '/usr/bin/chromium',
  ].find(existsSync);
  browser = await chromium.launch({ executablePath, headless: true });
});
after(async () => {
  await browser?.close();
  await new Promise(resolve => server?.close(resolve));
  assert.deepEqual(errors, [], '浏览器不应出现未处理异常');
});
async function open(t, viewport = { width: 412, height: 915 }) {
  const context = await browser.newContext({ viewport, reducedMotion: 'reduce' });
  t.after(() => context.close());
  const page = await context.newPage();
  page.on('pageerror', e => errors.push(e.message));
  await page.goto(url);
  return page;
}
async function edit(page, id = 'n1') {
  await page.locator(`[data-note="${id}"]`).click();
  await page.locator('#readEditBtn').click();
}
async function saved(page) {
  await page.waitForFunction(() => document.querySelector('#editStatus').textContent === '已保存');
}
async function selection(page, start, end = start) {
  await page.locator('#editorText').evaluate((el, [a, b]) => {
    el.focus(); el.setSelectionRange(a, b); el.dispatchEvent(new Event('select'));
  }, [start, end]);
}
async function choice(page, label) {
  await page.locator('#dialogControl .choice').filter({ hasText: label }).click();
}
test('真实计数、组合筛选、清除和快速记录', async t => {
  const page = await open(t);
  assert.equal(await page.locator('[data-note]').count(), 5);
  assert.match(await page.locator('[data-cat="全部笔记"]').innerText(), /5/);
  await page.locator('[data-cat="工作"]').click();
  assert.equal(await page.locator('[data-note]').count(), 2);
  await page.locator('#searchInput').fill('演示文稿');
  assert.equal(await page.locator('[data-note]').count(), 1);
  assert.equal(await page.locator('.note-desc mark').first().innerText(), '演示文稿');
  assert.equal(await page.locator('#tagFilterBtn').count(), 0);
  await page.locator('#homeMore').click();
  await page.getByRole('menuitem', { name: '标签筛选' }).click(); await choice(page, '生活');
  assert.match(await page.locator('#listSummary').innerText(), /#生活/);
  assert.equal(await page.locator('[data-note]').count(), 0);
  await page.locator('#clearFiltersBtn').click();
  await page.locator('#homeMore').click();
  await page.getByRole('menuitem', { name: '快速记录' }).click();
  assert.match(await page.locator('#editorName').innerText(), /^快速记录 /);
  assert.equal(await page.locator('#editorText').inputValue(), '');
});
test('新建取消不创建；非法与同目录重名不覆盖', async t => {
  const page = await open(t);
  await page.locator('#newBtn').click(); await page.locator('#dialogCancel').click();
  assert.equal(await page.locator('[data-note]').count(), 5);
  await page.locator('#newBtn').click();
  await page.locator('#dialogValue').fill('CON'); await page.locator('#dialogOK').click();
  assert.match(await page.locator('#dialogError').innerText(), /保留/);
  await page.locator('#newFolder').selectOption('生活');
  await page.locator('#dialogValue').fill('周末计划'); await page.locator('#dialogOK').click();
  assert.match(await page.locator('#dialogError').innerText(), /同名/);
  await page.locator('#dialogValue').fill('新的文件名'); await page.locator('#dialogOK').click();
  assert.equal(await page.locator('#editorText').inputValue(), '');
  assert.equal(await page.locator('#editorName').innerText(), '新的文件名');
});
test('底部工具保留选区，一步撤销重做，切换模式不改原文', async t => {
  const page = await open(t); await edit(page);
  await page.locator('#editorText').fill('中文文本'); await selection(page, 0, 4);
  await page.locator('[data-panel="format"]').click();
  await page.locator('[data-command="bold"]').click();
  assert.equal(await page.locator('#editorText').inputValue(), '**中文文本**');
  await page.locator('#undoBtn').click();
  assert.equal(await page.locator('#editorText').inputValue(), '中文文本');
  await page.locator('#redoBtn').click();
  await page.locator('#editorViewBtn').click(); await choice(page, '实时预览');
  assert.equal(await page.locator('#editorText').inputValue(), '**中文文本**');
  assert.equal(await page.locator('#liveNote').isVisible(), true);
  await page.locator('#editorViewBtn').click(); await choice(page, '完整阅读');
  assert.equal(await page.locator('#readBody strong').innerText(), '中文文本');
  assert.equal(await page.locator('#editorPanel').evaluate(el => el.inert), true);
});
test('自动保存、重新打开和阅读不重复显示文件名标题', async t => {
  const page = await open(t); await edit(page);
  const content = '---\ntitle: 属性标题\nunknown: 保留\n---\n# 正文标题\n\n保存内容';
  await page.locator('#editorText').fill(content); await saved(page);
  await page.reload(); await page.locator('[data-note="n1"]').click();
  assert.equal(await page.locator('#readTitle h1').innerText(), '正文标题');
  assert.equal(await page.locator('#readerScroll h1').count(), 1);
  const title = await page.locator('#readTitle').boundingBox();
  const meta = await page.locator('#readMeta').boundingBox();
  const cover = await page.locator('#readerPhoto').boundingBox();
  const body = await page.locator('#readBody').boundingBox();
  assert.ok(title.y < meta.y && meta.y < cover.y && cover.y < body.y);
  await page.locator('#outlineBtn').click();
  assert.equal(await page.locator('#dialogControl .choice').filter({ hasText: '正文标题' }).count(), 1);
  await page.locator('#dialogCancel').click();
  await page.locator('#readerMore').click(); await page.getByRole('menuitem', { name: '笔记信息' }).click();
  assert.equal(await page.locator('#dialogTitle').innerText(), '笔记信息');
  await page.locator('#dialogCancel').click();
  await page.locator('#readEditBtn').click();
  assert.equal(await page.locator('#editorText').inputValue(), content);
});
test('存储失败保留编辑内容，阻止离开，允许导出和重试', async t => {
  const page = await open(t); await edit(page);
  await page.evaluate(() => {
    window.originalSetItem = Storage.prototype.setItem;
    Storage.prototype.setItem = function () { throw new DOMException('full', 'QuotaExceededError'); };
  });
  await page.locator('#editorText').fill('不能丢失的修改');
  await page.waitForFunction(() => document.querySelector('#editStatus').textContent.includes('保存失败'));
  await page.locator('#editorPanel [data-back]').click();
  assert.equal(await page.locator('#editorPanel').evaluate(el => el.classList.contains('active')), true);
  assert.equal(await page.locator('#editorText').inputValue(), '不能丢失的修改');
  await page.locator('#editorMore').click();
  const download = page.waitForEvent('download');
  await page.getByRole('menuitem', { name: '导出当前原文' }).click();
  assert.equal((await download).suggestedFilename(), '周末计划.md');
  await page.evaluate(() => { Storage.prototype.setItem = window.originalSetItem; });
  await page.locator('#saveRetry').click(); await saved(page);
});
test('草稿确认恢复；冲突草稿只能另存并保留原笔记', async t => {
  const page = await open(t);
  await page.evaluate(key => localStorage.setItem(key, JSON.stringify({ n1: { content: '待恢复草稿', version: 0, time: Date.now() } })), drafts);
  await edit(page);
  assert.equal(await page.locator('#editorText').getAttribute('readonly'), '');
  await page.locator('[data-draft="restore"]').click();
  assert.equal(await page.locator('#editorText').inputValue(), '待恢复草稿'); await saved(page);
  await page.reload();
  await page.evaluate(key => localStorage.setItem(key, JSON.stringify({ n1: { content: '旧版本冲突草稿', version: -1, time: Date.now() } })), drafts);
  await edit(page);
  assert.equal(await page.locator('[data-draft="restore"]').count(), 0);
  await page.locator('[data-draft="copy"]').click(); await page.locator('#dialogOK').click();
  assert.equal(await page.locator('#editorText').inputValue(), '旧版本冲突草稿');
  assert.match(await page.locator('#editorName').innerText(), /草稿副本/);
  const original = await page.evaluate(key => JSON.parse(localStorage.getItem(key)).notes.find(n => n.id === 'n1').content, store);
  assert.equal(original, '待恢复草稿');
});
test('移动笔记本不改变标签；属性编辑保留未知 YAML', async t => {
  const page = await open(t); await edit(page);
  const content = '---\ntitle: 属性标题\nunknown: 保留原样\ntags: [生活]\n---\n# 正文标题\n正文 #日常';
  await page.locator('#editorText').fill(content); await saved(page);
  await page.locator('#editorMore').click(); await page.getByRole('menuitem', { name: '管理属性标签' }).click();
  await page.locator('#dialogValue').fill('记录, 生活'); await page.locator('#dialogOK').click();
  assert.match(await page.locator('#editorText').inputValue(), /unknown: 保留原样/);
  await page.locator('#editorPanel [data-back]').click();
  await page.locator('#readerMore').click(); await page.getByRole('menuitem', { name: '移动笔记本' }).click();
  await page.locator('#dialogValue').selectOption('工作'); await page.locator('#dialogOK').click();
  const n = await page.evaluate(key => JSON.parse(localStorage.getItem(key)).notes.find(n => n.id === 'n1'), store);
  assert.equal(n.category, '工作'); assert.deepEqual(n.tags, ['记录', '生活']);
  assert.match(n.content, /title: 属性标题/); assert.match(n.content, /# 正文标题/); assert.match(n.content, /正文 #日常/);
});
test('列表引用续写、空项退出和中文组合输入禁用命令', async t => {
  const page = await open(t); await edit(page);
  await page.locator('#editorText').fill('1. > 中文'); await selection(page, 7);
  await page.locator('#editorText').press('Enter');
  assert.equal(await page.locator('#editorText').inputValue(), '1. > 中文\n2. > ');
  await page.locator('#editorText').press('Enter');
  assert.equal(await page.locator('#editorText').inputValue(), '1. > 中文\n2. ');
  await page.locator('#editorText').dispatchEvent('compositionstart');
  assert.equal(await page.locator('#headingBtn').isDisabled(), true);
  await page.locator('#editorText').dispatchEvent('compositionend');
  assert.equal(await page.locator('#headingBtn').isEnabled(), true);
});
test('导入先预览；取消与同名冲突均不修改数据', async t => {
  const page = await open(t); await page.locator('#navSettings').click();
  const file = { name: '导入示例.md', mimeType: 'text/markdown', buffer: Buffer.from('# 导入正文\n\n保留内容') };
  await page.locator('#importFile').setInputFiles(file);
  await page.waitForFunction(() => document.querySelector('#dialogTitle').textContent === '确认导入');
  await page.locator('#dialogCancel').click();
  await page.locator('#settingsPanel [data-back]').click();
  assert.equal(await page.locator('[data-note]').count(), 5);
  await page.locator('#navSettings').click(); await page.locator('#importFile').setInputFiles(file);
  await page.waitForFunction(() => document.querySelector('#dialogTitle').textContent === '确认导入');
  await page.locator('#dialogOK').click();
  assert.equal(await page.locator('[data-note]').count(), 6);
  await page.locator('#navSettings').click(); await page.locator('#importFile').setInputFiles(file);
  await page.waitForFunction(() => document.querySelector('#toast').textContent.includes('同名'));
  assert.equal(await page.locator('#dialogLayer').evaluate(el => el.classList.contains('show')), false);
});
test('跨页面正文冲突不会覆盖新版本，可以另存', async t => {
  const page = await open(t); await edit(page);
  await page.locator('#editorText').fill('本页面的内容'); await saved(page);
  const other = await page.context().newPage(); await other.goto(url); await edit(other);
  await page.locator('#editorText').fill('尚未提交的本地修改');
  await other.locator('#editorText').fill('另一个页面的新版本');
  await other.locator('#editorMore').click(); await other.getByRole('menuitem', { name: '立即保存' }).click();
  await page.waitForFunction(() => document.querySelector('#editStatus').textContent.includes('版本冲突'));
  assert.equal(await page.locator('#editorText').inputValue(), '尚未提交的本地修改');
  const stored = await page.evaluate(key => JSON.parse(localStorage.getItem(key)).notes.find(n => n.id === 'n1').content, store);
  assert.equal(stored, '另一个页面的新版本');
});
test('深色、窄屏、横屏不裁切固定键盘按钮或产生设备横向滚动', async t => {
  for (const viewport of [{ width: 320, height: 640 }, { width: 412, height: 915 }, { width: 915, height: 412 }]) {
    const page = await open(t, viewport);
    await page.locator('#navSettings').click(); await page.locator('#themeSelect').selectOption('dark');
    assert.equal(await page.locator('#device').getAttribute('data-theme'), 'dark');
    await page.locator('#settingsPanel [data-back]').click(); await edit(page);
    await page.locator('#editorText').fill('窄屏内容');
    await page.locator('[data-panel="insert"]').click();
    const bounds = await page.locator('#hideKeyboardBtn').boundingBox();
    assert.ok(bounds.x >= 0 && bounds.x + bounds.width <= viewport.width);
    assert.equal(await page.locator('#device').evaluate(el => el.scrollLeft), 0);
    const dialogButton = page.locator('#editorViewBtn'); await dialogButton.click();
    await page.keyboard.press('Escape');
    assert.equal(await page.locator('#dialogLayer').evaluate(el => el.classList.contains('show')), false);
  }
});
test('撤销回已保存原文后，不遗留已经放弃的旧草稿', async t => {
  const page = await open(t); await edit(page);
  const original = await page.locator('#editorText').inputValue();
  await page.locator('#editorText').fill('准备撤销的内容');
  await page.waitForFunction(key => JSON.parse(localStorage.getItem(key) || '{}').n1, drafts);
  await page.locator('#undoBtn').click();
  assert.equal(await page.locator('#editorText').inputValue(), original);
  await page.reload(); await edit(page);
  assert.equal(await page.locator('#draftMessage').isVisible(), false);
});
test('标题单击、双击、长按选择互斥；输入时取消延迟命令', async t => {
  const page = await open(t); await edit(page);
  await page.locator('#editorText').fill('正文'); await selection(page, 2);
  await page.locator('#headingBtn').click();
  await page.waitForFunction(() => document.querySelector('#editorText').value === '# 正文');
  await page.locator('#headingBtn').dblclick();
  assert.equal(await page.locator('#editorText').inputValue(), '正文');
  await page.locator('#headingBtn').click();
  await page.locator('#editorText').press('End'); await page.locator('#editorText').press('x');
  await page.waitForTimeout(350);
  assert.equal(await page.locator('#editorText').inputValue(), '正文x');
  const box = await page.locator('#headingBtn').boundingBox();
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await page.waitForFunction(() => document.querySelector('#dialogTitle').textContent === '标题级别');
  await page.mouse.up(); await choice(page, 'H4');
  assert.equal(await page.locator('#editorText').inputValue(), '#### 正文x');
});
test('目录跳转与返回后恢复阅读位置', async t => {
  const page = await open(t); await edit(page);
  const content = '# 开头\n\n' + Array.from({ length: 70 }, (_, i) => `第 ${i} 段正文。\n`).join('\n') + '\n## 最后章节\n\n结尾内容';
  await page.locator('#editorText').fill(content);
  await page.locator('#editorPanel [data-back]').click();
  await page.locator('#outlineBtn').click(); await choice(page, '最后章节');
  const offset = await page.locator('#readerScroll').evaluate(el => el.scrollTop);
  assert.ok(offset > 1000);
  await page.locator('#readerPanel [data-back]').click();
  await page.locator('[data-note="n1"]').click();
  await page.waitForFunction(expected => Math.abs(document.querySelector('#readerScroll').scrollTop - expected) < 2, offset);
  await page.locator('#outlineBtn').click(); await choice(page, '开头');
  await page.waitForFunction(() => document.querySelector('#readerScroll').scrollTop < 40);
});
test('新建和移动笔记本，回收站可更名恢复以避开冲突', async t => {
  const page = await open(t);
  await page.locator('#folderBtn').click(); await choice(page, '新建笔记本');
  await page.locator('#dialogValue').fill('项目'); await page.locator('#dialogOK').click();
  assert.match(await page.locator('[data-cat="项目"]').innerText(), /0/);
  await page.locator('#newBtn').click(); await page.locator('#dialogValue').fill('计划'); await page.locator('#dialogOK').click();
  await page.locator('#editorPanel [data-back]').click();
  await page.locator('#readerMore').click(); await page.getByRole('menuitem', { name: '移入回收站' }).click();
  await page.locator('#dialogOK').click();
  await page.locator('#newBtn').click(); await page.locator('#dialogValue').fill('计划'); await page.locator('#dialogOK').click();
  await page.locator('#editorPanel [data-back]').click(); await page.locator('#readerPanel [data-back]').click();
  await page.locator('#navSettings').click(); await page.locator('#trashBtn').click();
  await page.locator('[data-restore]').click(); await page.locator('#dialogOK').click();
  assert.match(await page.locator('#dialogError').innerText(), /同名/);
  await page.locator('#dialogValue').fill('恢复的计划'); await page.locator('#dialogOK').click();
  assert.equal(await page.locator('[data-restore]').count(), 0);
  await page.locator('#trashPanel [data-back]').click();
  await page.locator('#foldersSettingsBtn').click(); await choice(page, '项目'); await choice(page, '重命名／移动笔记本');
  await page.locator('#dialogValue').fill('归档/项目'); await page.locator('#dialogOK').click();
  await page.locator('#settingsPanel [data-back]').click();
  assert.equal(await page.locator('[data-note]').count(), 2);
  assert.match(await page.locator('[data-cat="归档/项目"]').innerText(), /2/);
});
test('样板备份验证、取消和确认替换，保留完整草稿与设置', async t => {
  const page = await open(t); await edit(page);
  await page.locator('#editorText').fill('备份内正文'); await saved(page);
  await page.locator('#editorPanel [data-back]').click(); await page.locator('#readerPanel [data-back]').click();
  await page.locator('#navSettings').click(); await page.locator('#themeSelect').selectOption('dark');
  await page.locator('#backupExportBtn').click();
  const downloading = page.waitForEvent('download'); await page.locator('#dialogOK').click();
  const stream = await (await downloading).createReadStream(), chunks = [];
  for await (const chunk of stream) chunks.push(chunk);
  const backup = Buffer.concat(chunks);
  assert.equal(JSON.parse(backup.toString()).format, 'life-archive-browser-prototype');
  await page.locator('#themeSelect').selectOption('light');
  await page.locator('#restoreFile').setInputFiles({ name: 'invalid.json', mimeType: 'application/json', buffer: Buffer.from('{"format":"wrong"}') });
  await page.waitForFunction(() => document.querySelector('#toast').textContent.includes('验证失败'));
  const file = { name: 'backup.json', mimeType: 'application/json', buffer: backup };
  await page.locator('#restoreFile').setInputFiles(file);
  await page.waitForFunction(() => document.querySelector('#dialogTitle').textContent === '替换现有示例资料？');
  await page.locator('#dialogCancel').click();
  assert.equal(await page.locator('#device').getAttribute('data-theme'), 'light');
  await page.locator('#restoreFile').setInputFiles(file);
  await page.waitForFunction(() => document.querySelector('#dialogLayer').classList.contains('show'));
  await page.locator('#dialogOK').click();
  assert.equal(await page.locator('#device').getAttribute('data-theme'), 'dark');
});
test('导入 HTML 与不安全链接不会执行；复杂语法原文不静默消失', async t => {
  const page = await open(t); await edit(page);
  const text = '<img src=x onerror="window.injected=true">\n\n[危险](javascript:alert(1))\n\n```mermaid\nflowchart TD\nA-->B\n```\n\n$$\nx^2\n$$';
  await page.locator('#editorText').fill(text); await page.locator('#editorPanel [data-back]').click();
  assert.equal(await page.evaluate(() => window.injected), undefined);
  assert.equal(await page.locator('#readBody a[href^="javascript:"]').count(), 0);
  assert.match(await page.locator('#readBody').innerText(), /flowchart TD/);
  assert.match(await page.locator('#readBody').innerText(), /x\^2/);
});
test('自动保存计时前管理标签或删除，使用最新编辑正文', async t => {
  const page = await open(t); await edit(page);
  await page.locator('#editorText').fill('# 最新正文\n\n刚刚写下，尚未等到自动保存');
  await page.locator('#editorMore').click(); await page.getByRole('menuitem', { name: '管理属性标签' }).click();
  await page.locator('#dialogValue').fill('最新'); await page.locator('#dialogOK').click();
  assert.match(await page.locator('#editorText').inputValue(), /刚刚写下，尚未等到自动保存/);
  await page.locator('#editorText').fill('进入回收站前的最后修改');
  await page.locator('#editorPanel [data-back]').click();
  await page.locator('#readerMore').click(); await page.getByRole('menuitem', { name: '移入回收站' }).click();
  await page.locator('#dialogOK').click();
  const content = await page.evaluate(key => JSON.parse(localStorage.getItem(key)).trash.find(n => n.id === 'n1').content, store);
  assert.equal(content, '进入回收站前的最后修改');
});

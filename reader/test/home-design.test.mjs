import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import { before, after, test } from 'node:test';
import { existsSync } from 'node:fs';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import http from 'node:http';

const html = await readFile(new URL('../../docs/design/prototypes/home-a-xiaomi14ultra.html', import.meta.url));
const store = 'life-archive-home-a-demo-v2';
let browser, server, url;
const errors = [];
before(async () => {
  server = http.createServer((_, response) => {
    response.setHeader('Content-Type', 'text/html; charset=utf-8'); response.end(html);
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  url = `http://127.0.0.1:${server.address().port}/`;
  const executablePath = process.env.LIFE_ARCHIVE_BROWSER || [
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', '/usr/bin/chromium',
  ].find(existsSync);
  browser = await chromium.launch({ executablePath, headless: true });
});
after(async () => {
  await browser?.close(); await new Promise(resolve => server?.close(resolve));
  assert.deepEqual(errors, []);
});
async function open(t, width = 432) {
  const context = await browser.newContext({ viewport: { width, height: 960 }, reducedMotion: 'reduce', locale: 'zh-CN', colorScheme: 'light', timezoneId: 'Asia/Shanghai' });
  t.after(() => context.close());
  const page = await context.newPage(); page.on('pageerror', e => errors.push(e.message));
  await page.goto(url + '?responsive=1'); return page;
}
async function seed(page, mutate) {
  await page.locator('#navSettings').click();
  await page.locator('#themeSelect').selectOption('light');
  await page.locator('#settingsPanel [data-back]').click();
  await page.evaluate(({ store, source }) => {
    const data = JSON.parse(localStorage.getItem(store));
    const update = new Function('data', source); update(data);
    localStorage.setItem(store, JSON.stringify(data));
  }, { store, source: mutate });
  await page.reload();
}
async function shot(page, name) {
  if (!process.env.LIFE_ARCHIVE_HOME_SHOTS) return;
  await mkdir(process.env.LIFE_ARCHIVE_HOME_SHOTS, { recursive: true });
  await page.locator('#device').screenshot({ path: join(process.env.LIFE_ARCHIVE_HOME_SHOTS, name + '.png'), animations: 'disabled' });
}

test('首页区分笔记本和属性标签，无封面不制造占位图片', async t => {
  const page = await open(t);
  await seed(page, `data.notes[0].image=null;data.notes[0].tags=['徒步','周末'];`);
  const row = page.locator('[data-note="n1"]');
  assert.equal(await row.locator('.thumb').count(), 0);
  assert.equal(await row.locator('.note-folder').innerText(), '生活');
  assert.match(await row.locator('.note-tags').innerText(), /#徒步.*#周末/);
  assert.match(await row.locator('time').getAttribute('datetime'), /^2025-/);
  assert.equal(await row.locator('.pin').getAttribute('aria-label'), '已置顶');
});

test('首页待办与普通列表摘要去掉前缀，命中内容仍可读且原文保持', async t => {
  const page = await open(t);
  await seed(page, 'data.notes[0].content=' + JSON.stringify('# 准备\n\n- [ ] 相机和移动电源\n- [x] 简单的水\n1. 提前查看天气\n+ 普通项目'));
  const original = await page.evaluate(store => JSON.parse(localStorage.getItem(store)).notes[0].content, store);
  const summary = await page.locator('[data-note="n1"] .note-desc').innerText();
  assert.equal(summary, '相机和移动电源 简单的水 提前查看天气 普通项目');
  await page.locator('#searchInput').fill('移动电源');
  assert.equal(await page.locator('.note-desc mark').innerText(), '移动电源');
  assert.equal(await page.evaluate(store => JSON.parse(localStorage.getItem(store)).notes[0].content, store), original);
});

test('首页搜索同时突出文件名和正文，组合条件完整反馈并清除', async t => {
  const page = await open(t);
  await page.locator('[data-cat="工作"]').click();
  await page.locator('#searchInput').fill('Android');
  assert.equal(await page.locator('.note-title mark').innerText(), 'Android');
  assert.match(await page.locator('#listSummary').innerText(), /1 篇.*工作.*搜索结果/s);
  await page.locator('#homeMore').click();
  await page.getByRole('menuitem', { name: '标签筛选' }).click();
  await page.locator('#dialogControl .choice').filter({ hasText: '生活' }).click();
  assert.match(await page.locator('#listSummary').innerText(), /0 篇.*工作.*#生活.*搜索结果/s);
  assert.equal(await page.locator('.empty').isVisible(), true);
  await shot(page, 'empty-combined');
  await page.locator('#clearFiltersBtn').click();
  assert.equal(await page.locator('[data-note]').count(), 5);
  assert.equal(await page.locator('#clearFiltersBtn').isVisible(), false);
});

test('首页长标题、长笔记本与多标签在浅深色窄屏不溢出，管理入口可点', async t => {
  const page = await open(t, 320);
  await seed(page, `data.notes[0].title='一个需要完整区分的很长中文笔记文件名与 EnglishLongUnbrokenFileName0123456789';data.notes[0].category='生活/很长很长的笔记本路径';data.folders.push(data.notes[0].category);data.notes[0].tags=['一个很长很长的属性标签','徒步','周末','更多标签'];data.notes[1].image=null;`);
  for (const theme of ['light', 'dark']) {
    await page.locator('#navSettings').click(); await page.locator('#themeSelect').selectOption(theme);
    await page.locator('#settingsPanel [data-back]').click();
    const layout = await page.locator('[data-note="n1"]').evaluate(row => {
      const title = row.querySelector('.note-title'), copy = row.querySelector('.note-copy');
      const button = row.querySelector('.item-more'), bounds = row.getBoundingClientRect();
      const rect = button.getBoundingClientRect();
      return { titleHeight: title.clientHeight, lineHeight: parseFloat(getComputedStyle(title).lineHeight), overflow: row.scrollWidth > row.clientWidth, copyWidth: copy.clientWidth, hit: rect.width >= 44 && rect.height >= 44, inside: rect.right <= bounds.right + 1 };
    });
    assert.equal(layout.overflow, false); assert.ok(layout.copyWidth >= 180);
    assert.ok(layout.titleHeight > layout.lineHeight && layout.titleHeight <= layout.lineHeight * 2 + 1);
    assert.equal(layout.hit, true); assert.equal(layout.inside, true);
    await shot(page, 'narrow-long-' + theme);
    await page.locator('[data-more="n1"]').click();
    assert.equal(await page.getByRole('menuitem', { name: '取消置顶' }).isVisible(), true);
    await page.keyboard.press('Escape');
  }
  assert.equal(await page.locator('.home').evaluate(el => el.scrollWidth > el.clientWidth), false);
  await page.locator('[data-cat="生活/很长很长的笔记本路径"]').click();
  await page.locator('#searchInput').fill('周末');
  assert.match(await page.locator('#listSummary').innerText(), /生活\/很长很长的笔记本路径.*搜索结果/);
  assert.equal(await page.locator('#listSummary').evaluate(el => el.scrollWidth > el.clientWidth), false);
  await shot(page, 'narrow-long-filter-dark');
  await page.locator('#clearFiltersBtn').click();
  await page.locator('#listSummary').click();
  await page.locator('#notesScroll').evaluate(el => { el.scrollTop = el.scrollHeight; });
  const last = await page.locator('[data-note]').last().boundingBox(), fab = await page.locator('#newBtn').boundingBox();
  assert.ok(last.y + last.height <= fab.y, '末条笔记应能完整滚到浮钮上方');
  await shot(page, 'narrow-bottom-dark');
});

test('首页浅深色摘要、元信息与筛选小字对比度至少 4.5:1', async t => {
  const page = await open(t);
  for (const theme of ['light', 'dark']) {
    await page.locator('#navSettings').click(); await page.locator('#themeSelect').selectOption(theme);
    await page.locator('#settingsPanel [data-back]').click();
    const checks = await page.evaluate(() => {
      const canvas = document.createElement('canvas'), ctx = canvas.getContext('2d');
      function rgba(value) { ctx.clearRect(0, 0, 1, 1); ctx.fillStyle = value; ctx.fillRect(0, 0, 1, 1); return [...ctx.getImageData(0, 0, 1, 1).data]; }
      function luminance(rgb) { return rgb.slice(0, 3).map(v => v / 255).map(v => v <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4).reduce((v, n, i) => v + n * [.2126, .7152, .0722][i], 0); }
      return ['.chip:not(.active)', '.chip:not(.active) span', '.chip.active', '.chip.active span', '.note-desc', '.stamp', '.note-folder', '.note-tags', '#listSortBtn', '.nav-btn:not(.active)', '#searchInput::placeholder'].map(selector => {
        const el = document.querySelector('.home ' + selector.replace('::placeholder', ''));
        const style = getComputedStyle(el, selector.endsWith('::placeholder') ? '::placeholder' : null);
        let node = el, bg;
        while (node) { bg = rgba(getComputedStyle(node).backgroundColor); if (bg[3] === 255) break; node = node.parentElement; }
        const raw = rgba(style.color), opacity = Number(style.opacity);
        const fg = raw.map((v, i) => i < 3 ? v * opacity + bg[i] * (1 - opacity) : 255);
        const a = luminance(fg), b = luminance(bg);
        return { selector, fg: fg.slice(0, 3), bg: bg.slice(0, 3), ratio: (Math.max(a, b) + .05) / (Math.min(a, b) + .05) };
      });
    });
    for (const check of checks) assert.ok(check.ratio >= 4.5, theme + ' ' + JSON.stringify(check));
    if (process.env.LIFE_ARCHIVE_HOME_SHOTS) {
      await mkdir(process.env.LIFE_ARCHIVE_HOME_SHOTS, { recursive: true });
      await writeFile(join(process.env.LIFE_ARCHIVE_HOME_SHOTS, 'contrast-' + theme + '.json'), JSON.stringify(checks, null, 2));
    }
  }
});

import assert from 'node:assert/strict';

const html = '<h1 data-source="0">兼容回归</h1><p data-source="10">首屏公式 <span class="math" data-display="false" data-tex="x+1"></span></p><pre data-source="20"><code class="language-mermaid">flowchart LR\n正常-->可读</code></pre><p data-source="30"><img src="图片.png"></p><div data-source="40" style="height:5000px">屏外上下文</div><p id="later" data-source="5000"><span class="math" data-display="false" data-tex="y+2"></span></p>';

/** 复用正式资源与 HTTP 适配；只注入能力缺失和故障，不用替身渲染结果。 */
export async function readerResilience(browser, baseUrl) {
  const checks = [];
  async function open(profile, markup = html, init = {}) {
    const page = await browser.newPage({ viewport: { width: 412, height: 892 } });
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    await page.addInitScript(({ profile }) => {
      window.cacheWrites = [];
      window.phases = [];
      const bridge = new EventTarget();
      bridge.postMessage = raw => {
        if (profile === 'brokenBridge') throw Error('缓存消息不可用');
        const value = JSON.parse(raw);
        if (value.type === 'phase') window.phases.push(value);
        if (value.type === 'cache-put') {
          window.cacheWrites.push(value);
          queueMicrotask(() => bridge.dispatchEvent(new MessageEvent('message', { data: JSON.stringify({ request: value.request, accepted: true }) })));
        }
      };
      window.ReaderCache = bridge;
      if (profile === 'brokenListener') bridge.addEventListener = () => { throw Error('消息监听不可用'); };
      if (profile === 'noBridge') delete window.ReaderCache;
      if (profile === 'themeRace') {
        window.digestInputs = {};
        const digest = crypto.subtle.digest.bind(crypto.subtle);
        crypto.subtle.digest = async (algorithm, value) => {
          const result = await digest(algorithm, value);
          const key = [...new Uint8Array(result)].map(n => n.toString(16).padStart(2, '0')).join('');
          window.digestInputs[key] = new TextDecoder().decode(value);
          return result;
        };
      }
      if (profile === 'noUUID') Object.defineProperty(crypto, 'randomUUID', { value: undefined });
      if (profile === 'noDigest') crypto.subtle.digest = () => Promise.reject(Error('摘要计算不可用'));
      if (profile === 'noEncoder') window.TextEncoder = undefined;
      if (profile === 'noObserver') window.IntersectionObserver = undefined;
    }, { profile });
    await page.route('**/reader/document?**', route => route.fulfill({ json: { id: 'root', revision: 'resilience', path: '兼容.md', html: markup, blocks: [], projectionKey: 'resilience', cacheNamespace: 'resilience' } }));
    await page.route('**/reader/render-cache?**', route => route.fulfill({ json: { value: profile.startsWith('layout:') ? profile.slice(7) : null } }));
    if (init.before) await init.before(page);
    await page.goto(`${baseUrl}/reader/index.html?note=root&rev=resilience&offset=0&auto=false`);
    await page.waitForFunction(() => window.readerReady === true || document.querySelector('#status').textContent.includes('阅读加载失败'));
    return { page, errors };
  }
  for (const profile of ['normal', 'noUUID', 'noDigest', 'noEncoder', 'brokenBridge', 'brokenListener', 'noBridge', 'layout:null', 'layout:[]', 'layout:42', 'layout:{"formula:root:10:x+1":{"height":"9999"}}']) {
    const { page, errors } = await open(profile);
    try {
      assert.equal(await page.locator('#status').textContent(), '', profile);
      await page.evaluate(() => window.readerDrain());
      assert.equal(await page.locator('.math[data-rendered="true"]').count(), 2, profile);
      assert.ok(await page.locator('.math').evaluateAll(nodes => nodes.every(node => node.querySelector('svg'))), profile);
      assert.equal(await page.locator('.mermaid svg').count(), 1, profile);
      assert.equal(await page.locator('img:not(.reader-cover img)').evaluate(node => node.complete && node.naturalWidth > 0), true, profile);
      assert.equal(await page.locator('#content .error').count(), 0, profile);
      const writes = await page.evaluate(() => window.cacheWrites);
      assert.equal(new Set(writes.map(value => value.request)).size, writes.length);
      if (profile === 'normal' || profile === 'noUUID') assert.ok(writes.length > 0, profile);
      if (profile === 'noDigest' || profile === 'noEncoder') assert.equal(writes.length, 0, profile);
      assert.deepEqual(errors, [], profile);
      checks.push(`能力及缓存降级 ${profile}`);
    } finally { await page.close(); }
  }
  const { page, errors } = await open('noObserver');
  try {
    assert.equal(await page.locator('#status').textContent(), '');
    assert.equal(await page.locator('#later svg').count(), 0);
    await page.locator('#later').evaluate(node => node.scrollIntoView());
    await page.waitForFunction(() => document.querySelector('#later svg'));
    await page.evaluate(() => window.readerReload());
    assert.equal(await page.locator('#status').textContent(), '');
    assert.ok(await page.evaluate(() => window.readerOffset() >= 5000));
    assert.deepEqual(errors, []);
    checks.push('缺少观察器时滚动按需加载、刷新及定位');
  } finally { await page.close(); }
  {
    const { page, errors } = await open('normal');
    let unavailable = true;
    try {
      await page.locator('#later').evaluate(node => node.scrollIntoView());
      await page.route('**/reader/document?**', route => unavailable ? route.fulfill({ status: 500, body: '读取失败' }) : route.fulfill({ json: { html, blocks: [] } }));
      const before = await page.locator('#content').innerText();
      await page.evaluate(() => window.readerReload());
      assert.equal(await page.evaluate(() => window.readerReady), false);
      assert.equal(await page.locator('#content').innerText(), before);
      unavailable = false;
      await page.getByRole('button', { name: '重试', exact: true }).click();
      await page.waitForFunction(() => window.readerReady === true);
      assert.ok(await page.evaluate(() => window.readerOffset() >= 5000));
      assert.equal(await page.locator('#status').textContent(), '');
      assert.deepEqual(errors, []);
      checks.push('正文刷新失败不误报就绪，保留原页并可重试恢复定位');
    } finally { await page.close(); }
  }
  {
    const { page, errors } = await open('themeRace', html, { before: async page => {
      await page.route('**/mathjax/tex-svg.js', async route => {
        await page.evaluate(() => {
          window.writesBeforeTheme = window.cacheWrites.length;
          window.themeUpdate = window.readerUpdate({ revision: 'resilience', theme: 'graphite', scale: 1.2, auto: false });
        });
        await route.continue();
      });
    } });
    try {
      await page.evaluate(async () => { await window.themeUpdate; await window.readerDrain(); });
      assert.equal(await page.locator('#content .error').count(), 0);
      assert.equal(await page.evaluate(() => document.documentElement.dataset.pageTheme), 'graphite');
      assert.ok(await page.evaluate(() => window.cacheWrites.slice(window.writesBeforeTheme).filter(value => ['FORMULA', 'DIAGRAM'].includes(value.kind)).every(value => window.digestInputs[value.key].includes('|graphite|'))));
      assert.deepEqual(errors, []);
      checks.push('加载期间切主题和字号不写入旧条件下的公式图表缓存');
    } finally { await page.close(); }
  }
  for (const engine of ['math', 'diagram']) {
    let attempts = 0;
    const source = engine === 'math' ? '<p data-source="0"><span class="math" data-tex="z+3"></span></p>' : '<pre data-source="0"><code class="language-mermaid">flowchart LR\n失败-->恢复</code></pre>';
    const { page, errors } = await open('normal', source, { before: async page => {
      await page.route(engine === 'math' ? '**/mathjax/tex-svg.js' : '**/mermaid/mermaid.esm.min.mjs*', route => ++attempts === 1 ? route.abort() : route.continue());
    } });
    try {
      assert.equal(await page.locator('#status').textContent(), '');
      await page.getByRole('button', { name: '重试', exact: true }).click();
      await page.waitForFunction(() => document.querySelector('.math svg,.mermaid svg'), null, { timeout: 10000 });
      assert.equal(await page.locator('#content .error').count(), 0);
      assert.equal(attempts, 2);
      assert.deepEqual(errors, []);
      checks.push(`${engine} 首次库加载失败后局部重试恢复`);
    } finally { await page.close(); }
  }
  {
    let release;
    const gate = new Promise(resolve => { release = resolve; });
    const { page, errors } = await open('normal', '<h1 data-source="0">图片超时</h1><p data-source="10"><img src="图片.png"></p>', { before: async page => {
      await page.addInitScript(() => {
        window.savedTimeout = window.setTimeout;
        window.setTimeout = (callback, delay, ...args) => window.savedTimeout(callback, delay === 10000 ? 40 : delay, ...args);
      });
      await page.route('**/reader/resource?path=%E5%9B%BE%E7%89%87.png', async route => { await gate; await route.continue(); });
    } });
    try {
      await page.getByRole('button', { name: '重试', exact: true }).waitFor();
      assert.ok((await page.locator('#content').innerText()).includes('超时'));
      release(); await page.evaluate(() => { window.setTimeout = window.savedTimeout; });
      await page.getByRole('button', { name: '重试', exact: true }).click();
      await page.waitForFunction(() => { const image = document.querySelector('img:not(.reader-cover img)'); return image?.complete && image.naturalWidth > 0; });
      assert.equal(await page.locator('#content .error').count(), 0);
      assert.deepEqual(errors, []);
      checks.push('本地图片超时后正文保留、重试实际解码成功');
    } finally { release(); await page.close(); }
  }
  {
    const { page, errors } = await open('normal', '<h1 data-source="0">缺失媒体</h1><audio src="不存在.wav" controls></audio>');
    try {
      await page.evaluate(() => window.readerDrain());
      assert.ok((await page.locator('#content').innerText()).includes('附件不存在'));
      assert.equal(await page.locator('#status').textContent(), '');
      assert.deepEqual(errors, []);
      checks.push('缺失本地音视频显示局部错误和重试');
    } finally { await page.close(); }
  }
  {
    const { page, errors } = await open('normal', '<h1 data-source="0">初始化配置</h1>', { before: async page => {
      await page.route('**/reader/reader.js', async route => {
        await page.evaluate(() => {
          const config = { revision: 'queued', theme: 'warm', scale: 1.3, auto: false, metadata: { tags: ['初始化标签'] } };
          window.readerUpdate ? window.readerUpdate(config) : (window.readerPendingConfiguration = config);
        });
        await route.continue();
      });
    } });
    try {
      assert.equal(await page.evaluate(() => window.readerRevision), 'queued');
      assert.equal(await page.locator('.reader-meta-tag').innerText(), '#初始化标签');
      assert.equal(await page.evaluate(() => document.documentElement.dataset.pageTheme), 'warm');
      assert.ok(await page.evaluate(() => window.phases.every(value => value.revision === 'queued')));
      assert.deepEqual(errors, []);
      checks.push('模块初始化前收到的原生配置用于首次加载');
    } finally { await page.close(); }
  }
  for (const entry of ['reader.js', 'reader-cache.js', 'purify/purify.min.js', 'math-config.js']) {
    const page = await browser.newPage();
    let blocked = true;
    await page.route(`**/reader/${entry}`, route => blocked ? route.abort() : route.continue());
    try {
      await page.goto(`${baseUrl}/reader/index.html?note=root&rev=plain`, { waitUntil: 'commit' });
      await page.waitForFunction(() => document.querySelector('#status')?.textContent.includes('阅读加载失败'), null, { timeout: 3000 });
      assert.equal(await page.evaluate(() => !!window.readerReady), false);
      blocked = false;
      await page.getByRole('button', { name: '重试', exact: true }).click();
      await page.waitForFunction(() => window.readerReady === true);
      assert.equal(await page.locator('#content').innerText(), '普通正文');
      checks.push(`阅读入口 ${entry} 失败可见且重试恢复`);
    } finally { await page.close(); }
  }
  {
    const { page, errors } = await open('normal', '<h1 data-source="0">PDF 故障回归</h1><span class="embed" data-target="文档.pdf#page=1"></span>', { before: async page => {
      await page.route('**/pdfjs/legacy/build/pdf.mjs', async route => {
        const response = await route.fetch();
        // 仅在 HTTP 测试响应中注入读取故障，其余翻页仍由固定版本 PDF.js 实际渲染。
        const body = await response.text() + '\nconst savedGetPage = PDFDocumentProxy.prototype.getPage; PDFDocumentProxy.prototype.getPage = function(n) { if (window.pdfFailPage && n === 2) return Promise.reject(Error("测试页面读取失败")); if (window.pdfPageGate) return window.pdfPageGate.then(() => savedGetPage.call(this, n)); return savedGetPage.call(this, n); };';
        await route.fulfill({ response, body });
      });
    } });
    try {
      await page.waitForFunction(() => document.querySelector('.pdf-controls')?.textContent.includes('1 / 2'));
      await page.evaluate(() => { window.pdfFailPage = true; });
      await page.getByRole('button', { name: '下一页', exact: true }).click();
      await page.waitForFunction(() => document.querySelector('.pdf')?.textContent.includes('页面加载失败'), null, { timeout: 3000 });
      await page.evaluate(() => { window.pdfFailPage = false; });
      await page.getByRole('button', { name: '重试', exact: true }).click();
      await page.waitForFunction(() => document.querySelector('.pdf-controls')?.textContent.includes('2 / 2'));
      await page.evaluate(() => { window.pdfPageGate = new Promise(resolve => { window.releasePdfPage = resolve; }); });
      await page.getByRole('button', { name: '上一页', exact: true }).click();
      await page.evaluate(() => { window.readerUpdate({ revision: 'next', theme: 'warm', scale: 1, auto: false }); });
      await page.route('**/reader/document?**', route => route.fulfill({ json: { html: '<p data-source="0">新正文</p>', blocks: [] } }));
      await page.evaluate(() => { window.readerReload(); window.releasePdfPage(); });
      await page.waitForFunction(() => window.readerRevision === 'next' && document.querySelector('#content').textContent === '新正文');
      assert.deepEqual(errors, []);
      assert.equal(await page.locator('#status').textContent(), '');
      checks.push('PDF 翻页读取失败可局部重试，退出时迟到任务不报错');
    } finally { await page.close(); }
  }
  return checks;
}

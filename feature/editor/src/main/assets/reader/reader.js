import { cacheKey, cached, store, artifactMarkup, flushCacheWrites } from './reader-cache.js';
import { ReaderWork } from './reader-work.js';
const params = new URLSearchParams(location.search);
const rootId = params.get('note');
let auto = params.get('auto') === 'true';
let revision = params.get('rev') || 'initial';
let dark = params.get('dark') === 'true';
let generation = 0; let controller; let rendering;
let mermaidPromise; let mathPromise;
let activeContext; let activeWork; let mathQueue = Promise.resolve(); let diagramQueue = Promise.resolve(); let styleRevision = 0;
const resourceRecords = new WeakMap();
const content = document.querySelector('#content');
const status = document.querySelector('#status');
document.documentElement.classList.toggle('dark', params.get('dark') === 'true');
document.documentElement.style.fontSize = `${17 * (Number(params.get('scale')) || 1)}px`;
async function diagrams() {
  const mermaid = (await (mermaidPromise ||= import('./mermaid/mermaid.esm.min.mjs'))).default;
  mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: dark ? 'dark' : 'neutral', suppressErrorRendering: true, maxTextSize: 100000 });
  return mermaid;
}
async function mathEngine() {
  return mathPromise ||= new Promise((resolve, reject) => {
    const script = document.createElement('script'); script.src = 'mathjax/tex-svg.js';
    script.onload = () => MathJax.startup.promise.then(() => resolve(MathJax), reject); script.onerror = () => reject(Error('公式资源加载失败')); document.head.append(script);
  });
}
const query = (name, values) => `/reader/${name}?${new URLSearchParams(values)}`;
async function json(name, values, context = activeContext) { const response = await fetch(query(name, values), { signal: context?.signal || controller?.signal }); if (!response.ok) throw Error('内容不可用'); const data = await response.json(); if (context && !context.current()) throw new DOMException('过期阅读任务', 'AbortError'); return data; }
function action(type, value, page = false, from = rootId, syntax = 'WIKI') { location.href = query('action', { type, value, page: String(page), from, syntax, revision, cacheHit: String(!!window.readerMetrics?.cacheHit), artifactHits: String(activeContext?.cacheHits || 0) }); }
function element(name, text, className) { const node = document.createElement(name); if (text != null) node.textContent = text; if (className) node.className = className; return node; }
function button(text, callback) { const node = element('button', text); node.addEventListener('click', callback); return node; }
function error(node, message) { node.replaceChildren(element('span', message, 'error')); }
function sourceOwner(node) { return node.closest('[data-note]')?.dataset.note || rootId; }
const remoteSeen = new Set();
let unique = 0;
let rootBlocks = [];
async function resolve(target, from, syntax = 'WIKI', context = activeContext) { return json('resolve', { from, target, syntax }, context); }
function localResource(path) { return query('resource', { path }); }
function isLocalResource(value) { if (!value) return false; const url = new URL(value, location.href); return url.origin === location.origin && url.pathname.startsWith('/reader/'); }
function safeContent(html) {
  const inert = document.createElement('template'); inert.innerHTML = html;
  inert.content.querySelectorAll('iframe[src]').forEach(frame => {
    const url = frame.getAttribute('src'); const span = element('span');
    if (/^https?:\/\//i.test(url)) { span.dataset.embed = url; frame.replaceWith(span); } else frame.remove();
  });
  inert.content.querySelectorAll('img[src],audio[src],video[src]').forEach(node => { node.dataset.originalSrc = node.getAttribute('src'); node.removeAttribute('src'); });
  inert.content.querySelectorAll('source[src],image[href],image[xlink\\:href]').forEach(node => node.remove());
  inert.content.querySelectorAll('[style]').forEach(node => { if (/url\s*\(|@import/i.test(node.getAttribute('style'))) node.removeAttribute('style'); });
  // 输入先进入 DOMPurify，任何笔记 HTML 都不能在清理前插入页面。
  return DOMPurify.sanitize(inert.innerHTML, { ADD_ATTR: ['data-target', 'data-source', 'data-tex', 'data-display', 'data-embed'], ADD_TAGS: ['details', 'summary'], FORBID_TAGS: ['script', 'style', 'iframe', 'form', 'object', 'embed'], FORBID_ATTR: ['srcset'] });
}
function preparedMedia(node, context) {
  return new Promise((resolve, reject) => {
    if (!context.current()) { reject(new DOMException('过期阅读任务', 'AbortError')); return; }
    const done = error => { clearTimeout(timer); node.removeEventListener(event, loaded); node.removeEventListener('error', failed); context.signal.removeEventListener('abort', aborted); error ? reject(error) : resolve(); };
    const loaded = () => {
      if (node.tagName === 'IMG' && node.naturalWidth > 0 && node.naturalHeight > 0) context.mutate(() => {
        if (context.temporaryHeights.has(node)) { node.style.minHeight = context.temporaryHeights.get(node); context.temporaryHeights.delete(node); }
        if (!node.style.height) node.style.aspectRatio = `${node.naturalWidth} / ${node.naturalHeight}`;
      });
      done();
    }; const failed = () => done(Error('附件无法解码或读取失败')); const aborted = () => done(new DOMException('过期阅读任务', 'AbortError'));
    const event = node.tagName === 'IMG' ? 'load' : 'loadedmetadata';
    const timer = setTimeout(() => done(Error('附件加载超时，可重试')), 10000);
    context.signal.addEventListener('abort', aborted, { once: true }); node.addEventListener('error', failed, { once: true });
    if (node.tagName === 'IMG' && node.decode) node.decode().then(loaded, failed);
    else if (node.tagName === 'IMG' ? node.complete && node.naturalWidth > 0 : node.readyState >= 1) loaded();
    else node.addEventListener(event, loaded, { once: true });
  });
}
async function remote(node, url, page, refreshOnly = false, context = activeContext) {
  const box = element('div', null, 'resource');
  box.dataset.resourceUrl = url; box.dataset.resourcePage = String(page);
  const original = node.cloneNode(false);
  if (context.temporaryHeights.has(node)) original.style.minHeight = context.temporaryHeights.get(node);
  resourceRecords.set(box, { original, url, page });
  const cached = await json('cache-info', { url, page: String(page) }, context);
  if (cached.key) {
    const src = `/reader/cached/${cached.key}/content`;
    if (page) { const iframe = document.createElement('iframe'); iframe.src = src; iframe.setAttribute('sandbox', 'allow-same-origin'); iframe.title = '网页正文离线副本'; box.append(iframe); }
    else { const media = original.cloneNode(false); if (media.tagName === 'VIDEO' || media.tagName === 'AUDIO') { media.controls = true; media.preload = 'metadata'; } media.src = src; media.removeAttribute('data-target'); box.append(media); }
    box.append(element('p', `缓存于 ${new Date(cached.fetched).toLocaleString()}${cached.partial ? ' · 部分图片未缓存' : ''}`, 'resource-label'));
    box.append(button('刷新缓存', () => action('refresh', url, page)));
  } else {
    box.append(element('p', page ? '网页尚未缓存，可保存正文与正文图片供离线阅读。' : '远程资源尚未缓存。', 'resource-label'));
    box.append(button('加载并缓存', () => action('cache', url, page)));
    if (auto && !remoteSeen.has(`${page}:${url}`)) { remoteSeen.add(`${page}:${url}`); setTimeout(() => { if (context.current()) action('cache', url, page); }, 150); }
  }
  box.append(button(page ? '在线原网页' : '系统打开', () => action(page ? 'online' : 'link', url)));
  if (node.isConnected) context.mutate(() => node.replaceWith(box));
  const media = box.querySelector('img,audio,video'); if (media) await preparedMedia(media, context);
}
window.readerResourceUpdated = async (url, page) => {
  const captured = generation;
  for (const box of document.querySelectorAll('[data-resource-url]')) {
    if (box.dataset.resourceUrl !== url || box.dataset.resourcePage !== String(page)) continue;
    const record = resourceRecords.get(box); if (!record) continue;
    const original = record.original.cloneNode(false);
    const staging = element('div', null, 'reader-staging'); staging.style.width = `${box.getBoundingClientRect().width}px`; staging.dataset.note = sourceOwner(box); staging.append(original); document.body.append(staging);
    const playing = box.querySelector('audio,video'); const time = playing?.currentTime; const paused = playing?.paused;
    try {
      await remote(original, url, page);
      if (captured !== generation || !box.isConnected) return;
      const next = staging.querySelector('audio,video');
      if (next && Number.isFinite(time)) { const restore = () => { next.currentTime = time; if (!paused) next.play().catch(() => {}); }; if (next.readyState >= 1) restore(); else next.addEventListener('loadedmetadata', restore, { once: true }); }
      box.replaceWith(...staging.childNodes);
    } catch (e) { if (e.name !== 'AbortError') status.textContent = `资源更新失败：${e.message}`; }
    finally { staging.remove(); }
  }
};
async function pdf(node, url, target, context) {
  const box = element('div', null, 'pdf');
  const controls = element('div', null, 'pdf-controls');
  const label = element('span', '正在打开 PDF…');
  const canvas = document.createElement('canvas');
  const fragment = new URLSearchParams(target.includes('#') ? target.slice(target.indexOf('#') + 1) : '');
  let page = Math.max(1, Number(fragment.get('page')) || 1);
  let zoom = 1.5;
  const height = Number(fragment.get('height')); if (height > 0) { box.style.maxHeight = `${height}px`; box.style.overflowY = 'auto'; }
  box.append(controls, canvas); context.mutate(() => node.replaceWith(box));
  try {
    const pdfjs = await import('./pdfjs/legacy/build/pdf.mjs');
    pdfjs.GlobalWorkerOptions.workerSrc = new URL('./pdfjs/legacy/build/pdf.worker.mjs', import.meta.url).href;
    if (!context.current()) return;
    // 同时关闭流式预取与自动预读，PDF.js 才会只按当前页面请求所需范围。
    const loading = pdfjs.getDocument({ url, disableAutoFetch: true, disableStream: true, cMapUrl: '/reader/pdfjs/cmaps/', cMapPacked: true, standardFontDataUrl: '/reader/pdfjs/standard_fonts/', wasmUrl: '/reader/pdfjs/wasm/', isEvalSupported: false });
    context.disposers.push(() => loading.destroy());
    const document = await loading.promise;
    if (!context.current()) { await document.destroy(); return; }
    context.disposers.push(() => document.destroy());
    page = Math.min(page, document.numPages);
    let rendering = false;
    async function render() { if (rendering) return; rendering = true; try { const pdfPage = await document.getPage(page); const viewport = pdfPage.getViewport({ scale: zoom }); canvas.width = viewport.width; canvas.height = viewport.height; await pdfPage.render({ canvasContext: canvas.getContext('2d'), viewport }).promise; label.textContent = `${page} / ${document.numPages}`; } finally { rendering = false; } }
    controls.append(button('上一页', () => { if (page > 1 && !rendering) { page--; render(); } }), label, button('下一页', () => { if (page < document.numPages && !rendering) { page++; render(); } }), button('放大', () => { if (!rendering) { zoom = Math.min(4, zoom + .5); render(); } }), button('缩小', () => { if (!rendering) { zoom = Math.max(.5, zoom - .5); render(); } }));
    await render();
  } catch (e) { if (context.current()) { error(box, `PDF 打开失败：${e.message}`); box.append(button('重试', () => pdf(box, url, target, context))); } }
}
async function embedded(node, stack, context, work) {
  const raw = node.dataset.target || '';
  const target = raw.split('|')[0];
  const size = raw.split('|')[1];
  const from = sourceOwner(node);
  if (/^https?:\/\//i.test(target)) return remote(node, target, true, false, context);
  const resolved = await resolve(target, from, 'WIKI', context);
  if (resolved.missing || resolved.ambiguous?.length) return error(node, resolved.ambiguous?.length ? `引用不明确：${resolved.ambiguous.join('、')}` : `引用不存在：${target}`);
  if (resolved.note) {
    if (stack.includes(resolved.note) || stack.length >= 12) return error(node, `循环或过深嵌入：${target}`);
    const data = await json('document', { note: resolved.note }, context);
    const box = element('section', null, 'embedded-note'); box.dataset.note = resolved.note;
    box.innerHTML = safeContent(data.html);
    const fragment = target.split('#').slice(1).join('#');
    if (fragment) {
      if (fragment.startsWith('^')) {
        const anchor = data.blocks.find(b => b.name === fragment.slice(1));
        const children = [...box.children].filter(n => anchor && Number(n.dataset.source) >= anchor.start && Number(n.dataset.source) <= anchor.end);
        if (!children.length) return error(node, `块不存在：${target}`);
        box.replaceChildren(...children);
      } else {
        const headings = [...box.querySelectorAll('h1,h2,h3,h4,h5,h6')];
        const heading = headings.find(h => Number(h.dataset.source) === resolved.offset) || headings.find(h => h.textContent.trim() === fragment.split('#').at(-1));
        if (!heading) return error(node, `标题不存在：${target}`);
        let sibling = heading; const children = [];
        do { children.push(sibling); sibling = sibling.nextElementSibling; } while (sibling && !(/^H[1-6]$/.test(sibling.tagName) && Number(sibling.tagName[1]) <= Number(heading.tagName[1])));
        box.replaceChildren(...children);
      }
    }
    const label = element('a', `嵌入：${data.path}`, 'embedded-label'); label.href = '#'; label.dataset.target = target;
    box.prepend(label); context.mutate(() => node.replaceWith(box)); decorate(box, [...stack, resolved.note], context, work); return;
  }
  if (resolved.attachment) {
    const path = resolved.attachment;
    const extension = path.split('.').at(-1).toLowerCase(); const url = localResource(path);
    if (extension === 'pdf') { node.style.minHeight = '260px'; work.add(node, 'attachment', () => pdf(node, url, target, context)); return; }
    let media;
    if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'avif', 'bmp'].includes(extension)) media = element('img');
    else if (['mp3', 'wav', 'ogg', 'flac', 'm4a', '3gp'].includes(extension)) media = element('audio');
    else if (['mp4', 'mkv', 'mov', 'webm', 'ogv'].includes(extension)) media = element('video');
    else { const link = element('a', `打开附件：${path}`); link.href = '#'; link.dataset.target = target; node.replaceWith(link); return; }
    media.alt = path;
    if (media.tagName === 'IMG') { media.dataset.localPath = path; media.dataset.originalSrc = url; }
    else media.dataset.originalSrc = url;
    if (media.tagName !== 'IMG') { media.controls = true; media.preload = 'metadata'; const time = target.match(/#t=([\d.,]+)/)?.[1]; if (time) media.dataset.originalSrc += `#t=${time}`; media.addEventListener('error', () => { const note = element('p', '设备可能不支持此媒体编码。', 'error'); media.after(note, button('系统打开附件', () => action('link', target, false, from))); }); }
    if (size && /^\d+(x\d+)?$/.test(size)) { const [width, height] = size.split('x'); media.width = Number(width); if (height) { media.height = Number(height); media.style.height = `${height}px`; } }
    context.mutate(() => node.replaceWith(media));
    if (media.tagName === 'IMG') work.add(media, 'attachment', () => imageResource(media, context));
    else work.add(media, 'attachment', async () => { media.src = media.dataset.originalSrc; await preparedMedia(media, context); });
  }
}
function callouts(root) {
  for (const quote of [...root.querySelectorAll('blockquote')].reverse()) {
    const first = quote.firstElementChild; if (!first) continue;
    const match = first.textContent.match(/^\[!([^\]]+)\]([+-])?([^\n]*)(?:\n|$)/); if (!match) continue;
    // 只移除头行，保留正文原有的内联格式和子块。
    const walker = document.createTreeWalker(first, NodeFilter.SHOW_TEXT); let remaining = match[0].length; let text;
    while ((text = walker.nextNode()) && remaining > 0) { const n = Math.min(remaining, text.textContent.length); text.textContent = text.textContent.slice(n); remaining -= n; }
    const box = element(match[2] ? 'details' : 'aside', null, 'callout'); box.dataset.source = quote.dataset.source || '0';
    const title = element(match[2] ? 'summary' : 'strong', match[3].trim() || match[1]);
    if (match[2] === '+') box.open = true;
    box.append(title, ...quote.childNodes); quote.replaceWith(box);
  }
}
function environment() { return `reading-2|${dark}|${getComputedStyle(document.documentElement).fontSize}|${getComputedStyle(content).fontFamily}|${devicePixelRatio}|${Math.round(content.getBoundingClientRect().width)}`; }
function serial(kind, context, producer) {
  const next = (kind === 'FORMULA' ? mathQueue : diagramQueue).catch(() => {}).then(() => {
    if (!context.current()) throw new DOMException('过期阅读任务', 'AbortError');
    return producer();
  });
  if (kind === 'FORMULA') mathQueue = next; else diagramQueue = next;
  return next;
}
async function renderFormula(node, context) {
  const styled = styleRevision;
  const key = await cacheKey(`MathJax-4.1.3|${environment()}|${node.dataset.display}|${node.dataset.tex}`);
  let markup = await cached('FORMULA', key, context);
  if (!markup?.includes('<svg')) {
    markup = await serial('FORMULA', context, async () => (await (await mathEngine()).tex2svgPromise(node.dataset.tex, { display: node.dataset.display === 'true' })).outerHTML);
    store('FORMULA', key, markup, context);
  } else context.cacheHits++;
  if (styled !== styleRevision) throw new DOMException('显示条件已更新', 'AbortError');
  const fragment = artifactMarkup(markup, `formula-${unique++}`);
  context.mutate(() => { node.replaceChildren(fragment); node.dataset.rendered = 'true'; });
}
async function renderDiagram(box, context) {
  const styled = styleRevision;
  const source = box.dataset.diagramSource;
  const key = await cacheKey(`Mermaid-12.2.0|${environment()}|${source}`);
  let markup = await cached('DIAGRAM', key, context);
  if (!markup?.includes('<svg')) {
    markup = await serial('DIAGRAM', context, async () => (await (await diagrams()).render(`diagram-${unique++}`, source)).svg);
    store('DIAGRAM', key, markup, context);
  } else context.cacheHits++;
  if (styled !== styleRevision) throw new DOMException('显示条件已更新', 'AbortError');
  const fragment = artifactMarkup(markup, `chart-${unique++}`);
  context.mutate(() => {
    box.replaceChildren(fragment);
    box.querySelectorAll('.internal-link').forEach(node => { node.style.cursor = 'pointer'; node.addEventListener('click', event => { event.preventDefault(); event.stopPropagation(); action('link', node.textContent.trim(), false, sourceOwner(box)); }); });
  });
}
async function imageResource(image, context) {
  const src = image.getAttribute('src') || image.dataset.originalSrc;
  if (/^https?:\/\//i.test(src)) return remote(image, src, false, false, context);
  const resolved = image.dataset.localPath ? { attachment: image.dataset.localPath } : await resolve(src, sourceOwner(image), 'MARKDOWN', context);
  if (!resolved.attachment) { image.alt = `图片不存在：${src}`; return; }
  const key = await cacheKey(`image-size|${resolved.attachment}`);
  let size;
  try { size = JSON.parse(await cached('GEOMETRY', key, context) || 'null'); } catch {}
  if (!size?.width || !size?.height) {
    try { size = await json('resource-info', { path: resolved.attachment }, context); }
    catch (error) { if (error.name === 'AbortError') throw error; size = { width: 0, height: 0 }; }
    if (size.width > 0 && size.height > 0) store('GEOMETRY', key, JSON.stringify(size), context);
  }
  context.mutate(() => {
    if (size.width > 0 && size.height > 0 && !image.style.height) image.style.aspectRatio = `${size.width} / ${size.height}`;
    image.src = localResource(resolved.attachment);
  });
  await preparedMedia(image, context);
  // 缓存尺寸只负责占位；路径重用或资源变化后，以实际解码尺寸收敛。
  if (image.naturalWidth > 0 && image.naturalHeight > 0) {
    if (size.width !== image.naturalWidth || size.height !== image.naturalHeight) store('GEOMETRY', key, JSON.stringify({ width: image.naturalWidth, height: image.naturalHeight }), context);
  }
}
function register(work, node, kind, run, context) {
  const owner = sourceOwner(node); const source = node.closest('[data-source]')?.dataset.source || '0';
  const slot = `${kind}:${owner}:${source}:${node.dataset.tex || node.dataset.diagramSource || node.dataset.target || node.dataset.originalSrc || ''}`;
  const previous = context.layout[slot];
  if (previous?.height > 0 && previous.height < 20000) { context.temporaryHeights.set(node, node.style.minHeight); node.style.minHeight = `${previous.height}px`; }
  work.add(node, kind, async () => {
    await run();
    if (context.current() && node.isConnected) { context.layout[slot] = { height: node.getBoundingClientRect().height }; context.layoutDirty = true; }
  });
}
function decorate(root, stack, context, work) {
  callouts(root);
  for (const paragraph of root.querySelectorAll('li>p:first-child,li')) {
    const first = paragraph.firstChild; if (first?.nodeType !== Node.TEXT_NODE) continue;
    const task = first.textContent.match(/^\[([ xX])\]\s/); if (!task) continue;
    first.textContent = first.textContent.slice(task[0].length);
    const check = document.createElement('input'); check.type = 'checkbox'; check.checked = task[1].toLowerCase() === 'x'; check.disabled = true; paragraph.prepend(check); paragraph.closest('li').classList.add('task-item');
  }
  for (const table of root.querySelectorAll('table')) { if (table.parentElement.classList.contains('table-scroll')) continue; const wrap = element('div', null, 'table-scroll'); table.replaceWith(wrap); wrap.append(table); }
  for (const frame of root.querySelectorAll('[data-embed]')) { frame.classList.add('embed'); frame.dataset.target = frame.dataset.embed; }
  for (const embed of [...root.querySelectorAll('.embed')]) {
    if (embed.dataset.prepared) continue; embed.dataset.prepared = 'true'; embed.style.minHeight = '160px';
    register(work, embed, /^https?:\/\//i.test(embed.dataset.target) ? 'attachment' : 'embed', () => embedded(embed, stack, context, work), context);
  }
  for (const image of [...root.querySelectorAll('img[src],img[data-original-src]')]) {
    const dimensions = image.alt?.match(/^(\d+)(?:x(\d+))?$/); if (dimensions) { image.width = Number(dimensions[1]); if (dimensions[2]) image.style.height = `${dimensions[2]}px`; }
    if (image.dataset.prepared || isLocalResource(image.getAttribute('src'))) continue; image.dataset.prepared = 'true';
    if (!image.width && !image.style.height) { image.style.aspectRatio = '2 / 1'; image.style.width = '100%'; }
    register(work, image, 'attachment', () => imageResource(image, context), context);
  }
  for (const media of root.querySelectorAll('audio[src],video[src],audio[data-original-src],video[data-original-src]')) {
    if (media.dataset.prepared || isLocalResource(media.getAttribute('src'))) continue; media.dataset.prepared = 'true';
    work.add(media, 'attachment', async () => {
      const src = media.getAttribute('src') || media.dataset.originalSrc;
      if (/^https?:\/\//i.test(src)) await remote(media, src, false, false, context);
      else { const result = await resolve(src, sourceOwner(media), 'HTML', context); if (result.attachment) { media.preload = 'metadata'; media.src = localResource(result.attachment) + (src.includes('#') ? '#' + src.split('#').slice(1).join('#') : ''); await preparedMedia(media, context); } else media.removeAttribute('src'); }
    });
  }
  for (const node of root.querySelectorAll('.math:not([data-prepared])')) { node.dataset.prepared = 'true'; node.textContent = '\u00a0'; register(work, node, 'formula', () => renderFormula(node, context), context); }
  for (const code of root.querySelectorAll('pre>code.language-mermaid')) {
    const box = element('div', null, 'mermaid'); const source = code.textContent;
    box.dataset.source = code.parentElement.dataset.source || '0'; box.dataset.diagramSource = source; code.parentElement.replaceWith(box);
    box.style.minHeight = '160px'; register(work, box, 'diagram', () => renderDiagram(box, context), context);
  }
  // 块标识保留定位信息，但不作为正文可见文字。
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT); const texts = []; let text;
  while ((text = walker.nextNode())) if (!text.parentElement.closest('pre,code,.math,.mermaid')) texts.push(text);
  for (const node of texts) node.textContent = node.textContent.replace(/(?:^|\s)\^[A-Za-z0-9-]+(?=\s*$)/g, '');
}
content.addEventListener('click', async event => {
  const link = event.target.closest('a'); if (!link) return;
  const href = link.getAttribute('href') || '';
  if (href.startsWith('#fn-')) { event.preventDefault(); document.getElementById(href.slice(1))?.scrollIntoView(); return; }
  event.preventDefault(); const target = link.dataset.target || href;
  if (target && target !== '#') action('link', target, false, sourceOwner(link), link.classList.contains('internal-link') || link.classList.contains('embedded-label') ? 'WIKI' : 'MARKDOWN');
});
window.readerOffset = () => {
  const rect = content.getBoundingClientRect();
  for (const y of [20, 40, 80]) {
    let node = document.elementFromPoint(Math.max(1, Math.min(innerWidth - 1, rect.left + rect.width / 2)), y);
    const embed = node?.closest('.embedded-note'); if (embed) node = embed.parentElement;
    const footnote = node?.closest('.footnotes'); if (footnote) node = footnote;
    const source = node?.closest('[data-source]');
    if (source && content.contains(source) && source.getBoundingClientRect().top >= -20) return Number(source.dataset.source) || 0;
  }
  const nodes = [...content.querySelectorAll('[data-source]')].filter(n => !n.closest('.embedded-note') && !n.parentElement.closest('.footnotes') && n.getBoundingClientRect().bottom >= 0);
  return Number(nodes.find(n => n.getBoundingClientRect().top >= -20)?.dataset.source || nodes[0]?.dataset.source || 0);
};
window.readerReload = async () => {
  const captured = ++generation; const capturedRevision = revision;
  activeWork?.dispose(); activeContext?.disposers.forEach(dispose => { try { dispose(); } catch {} });
  controller?.abort(); controller = new AbortController();
  const context = {
    revision: capturedRevision, signal: controller.signal, namespace: null, disposers: [], layout: {}, temporaryHeights: new WeakMap(), cacheHits: 0,
    current: () => captured === generation && capturedRevision === revision,
    mutate: update => work.mutate(update),
    idle: () => {
      if (!context.current()) return;
      if (context.layoutDirty && context.layoutKey) { context.layoutDirty = false; store('GEOMETRY', context.layoutKey, JSON.stringify(context.layout), context); }
      const count = work.tasks.filter(t => t.state === 'done' || t.state === 'failed').length;
      if (context.lastIdle !== count) { context.lastIdle = count; phase(context, 'IDLE'); }
    },
  };
  const work = new ReaderWork(context); activeContext = context; activeWork = work;
  const staging = element('main', null, 'reader-staging');
  staging.style.width = `${content.getBoundingClientRect().width}px`; document.body.append(staging);
  try {
    const restoreOffset = window.readerReady ? window.readerOffset() : Number(params.get('offset')) || 0;
    status.textContent = '';
    const data = await json('document', { note: rootId, revision: capturedRevision });
    if (captured !== generation) return;
    context.namespace = data.cacheNamespace; context.projectionKey = data.projectionKey;
    context.metrics = { cacheHit: !!data.cacheHit, body: performance.now(), layoutWidth: Math.round(content.getBoundingClientRect().width) }; window.readerMetrics = context.metrics;
    staging.dataset.note = rootId; staging.innerHTML = safeContent(data.html);
    phase(context, 'BODY');
    if (context.projectionKey && staging.querySelector('.math,.embed,img,audio,video,pre>code.language-mermaid')) {
      context.layoutKey = await cacheKey(`layout|${context.projectionKey}|${environment()}`);
      try { context.layout = JSON.parse(await cached('GEOMETRY', context.layoutKey, context) || '{}'); } catch { context.layout = {}; }
    }
    decorate(staging, [rootId], context, work);
    await work.first(staging, restoreOffset);
    if (captured !== generation) return;
    rootBlocks = data.blocks; content.dataset.note = rootId; content.replaceChildren(...staging.childNodes);
    document.documentElement.classList.remove('reader-preparing');
    const offset = restoreOffset;
    const node = offset ? [...content.querySelectorAll('[data-source]')].filter(n => !n.closest('.embedded-note') && Number(n.dataset.source) <= offset).at(-1) : null;
    if (offset && node) node.scrollIntoView();
    window.readerReady = true;
    window.readerRevision = capturedRevision;
    context.metrics.firstScreen = performance.now();
    work.mount(content);
    action('ready', String(Math.round(context.metrics.firstScreen)));
  } catch (e) { if (captured === generation && e.name !== 'AbortError') { status.textContent = `阅读加载失败：${e.message}`; document.documentElement.classList.remove('reader-preparing'); status.append(button('重试', () => window.readerReload())); action('error', e.message); } }
  finally { staging.remove(); }
};
function phase(context, name) {
  if (!context.current()) return;
  if (name === 'IDLE') context.metrics.idle = performance.now();
  window.ReaderCache?.postMessage?.(JSON.stringify({ type: 'phase', phase: name, revision: context.revision, elapsed: Math.round(performance.now()), cacheHit: !!context.metrics.cacheHit, artifactHits: context.cacheHits }));
}
window.readerDrain = async () => { await rendering; await activeWork?.drain(); activeContext?.idle(); await flushCacheWrites(); };
async function refreshDisplay() {
  styleRevision++;
  for (const task of activeWork?.tasks || []) if (['formula', 'diagram'].includes(task.kind) && task.state !== 'running') task.state = 'pending';
  if (activeContext?.projectionKey) { activeContext.layoutKey = await cacheKey(`layout|${activeContext.projectionKey}|${environment()}`); activeContext.layout = {}; }
  if (activeContext?.metrics) activeContext.metrics.layoutWidth = Math.round(content.getBoundingClientRect().width);
  activeWork?.schedule();
}
let lastWidth = Math.round(content.getBoundingClientRect().width);
window.addEventListener('resize', () => { const width = Math.round(content.getBoundingClientRect().width); if (width !== lastWidth) { lastWidth = width; refreshDisplay(); } });
window.readerUpdate = async config => {
  const changed = config.revision && config.revision !== revision;
  const themeChanged = dark !== !!config.dark;
  const autoChanged = auto !== !!config.auto;
  const fontChanged = document.documentElement.style.fontSize !== `${17 * (Number(config.scale) || 1)}px`;
  if (changed) revision = config.revision;
  dark = !!config.dark; auto = !!config.auto;
  document.documentElement.classList.toggle('dark', dark);
  document.documentElement.style.fontSize = `${17 * (Number(config.scale) || 1)}px`;
  if (changed) { rendering = window.readerReload(); return rendering; }
  if (themeChanged || fontChanged) await refreshDisplay();
  if (autoChanged && auto) for (const box of content.querySelectorAll('[data-resource-url]')) {
    const record = resourceRecords.get(box); if (record) window.readerResourceUpdated(record.url, record.page);
  }
  return rendering;
};
rendering = window.readerReload();
await rendering;

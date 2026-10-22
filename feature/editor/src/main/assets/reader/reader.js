import mermaid from './mermaid/mermaid.esm.min.mjs';
const params = new URLSearchParams(location.search);
const rootId = params.get('note');
const auto = params.get('auto') === 'true';
const content = document.querySelector('#content');
const status = document.querySelector('#status');
document.documentElement.classList.toggle('dark', params.get('dark') === 'true');
document.documentElement.style.fontSize = `${17 * (Number(params.get('scale')) || 1)}px`;
mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: params.get('dark') === 'true' ? 'dark' : 'neutral', suppressErrorRendering: true, maxTextSize: 100000 });
const query = (name, values) => `/reader/${name}?${new URLSearchParams(values)}`;
async function json(name, values) { const response = await fetch(query(name, values)); if (!response.ok) throw Error('内容不可用'); return response.json(); }
function action(type, value, page = false, from = rootId) { location.href = query('action', { type, value, page: String(page), from }); }
function element(name, text, className) { const node = document.createElement(name); if (text != null) node.textContent = text; if (className) node.className = className; return node; }
function button(text, callback) { const node = element('button', text); node.addEventListener('click', callback); return node; }
function error(node, message) { node.replaceChildren(element('span', message, 'error')); }
function sourceOwner(node) { return node.closest('[data-note]')?.dataset.note || rootId; }
const remoteSeen = new Set();
let unique = 0;
let rootBlocks = [];
async function resolve(target, from) { return json('resolve', { from, target }); }
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
async function remote(node, url, page, refreshOnly = false) {
  const box = element('div', null, 'resource');
  const cached = await json('cache-info', { url, page: String(page) });
  if (cached.key) {
    const src = `/reader/cached/${cached.key}/content`;
    if (page) { const iframe = document.createElement('iframe'); iframe.src = src; iframe.setAttribute('sandbox', 'allow-same-origin'); iframe.title = '网页正文离线副本'; box.append(iframe); }
    else { const media = node.cloneNode(false); media.src = src; media.removeAttribute('data-target'); if (media.tagName === 'VIDEO' || media.tagName === 'AUDIO') media.controls = true; box.append(media); }
    box.append(element('p', `缓存于 ${new Date(cached.fetched).toLocaleString()}${cached.partial ? ' · 部分图片未缓存' : ''}`, 'resource-label'));
    box.append(button('刷新缓存', () => action('refresh', url, page)));
  } else {
    box.append(element('p', page ? '网页尚未缓存，可保存正文与正文图片供离线阅读。' : '远程资源尚未缓存。', 'resource-label'));
    box.append(button('加载并缓存', () => action('cache', url, page)));
    if (auto && !remoteSeen.has(`${page}:${url}`)) { remoteSeen.add(`${page}:${url}`); setTimeout(() => action('cache', url, page), 150); }
  }
  box.append(button(page ? '在线原网页' : '系统打开', () => action(page ? 'online' : 'link', url)));
  node.replaceWith(box);
}
async function pdf(node, url, target) {
  const box = element('div', null, 'pdf');
  const controls = element('div', null, 'pdf-controls');
  const label = element('span', '正在打开 PDF…');
  const canvas = document.createElement('canvas');
  const fragment = new URLSearchParams(target.includes('#') ? target.slice(target.indexOf('#') + 1) : '');
  let page = Math.max(1, Number(fragment.get('page')) || 1);
  let zoom = 1.5;
  const height = Number(fragment.get('height')); if (height > 0) { box.style.maxHeight = `${height}px`; box.style.overflowY = 'auto'; }
  box.append(controls, canvas); node.replaceWith(box);
  try {
    const pdfjs = await import('./pdfjs/legacy/build/pdf.mjs');
    pdfjs.GlobalWorkerOptions.workerSrc = new URL('./pdfjs/legacy/build/pdf.worker.mjs', import.meta.url).href;
    const document = await pdfjs.getDocument({ url, cMapUrl: '/reader/pdfjs/cmaps/', cMapPacked: true, standardFontDataUrl: '/reader/pdfjs/standard_fonts/', wasmUrl: '/reader/pdfjs/wasm/', isEvalSupported: false }).promise;
    page = Math.min(page, document.numPages);
    let rendering = false;
    async function render() { if (rendering) return; rendering = true; try { const pdfPage = await document.getPage(page); const viewport = pdfPage.getViewport({ scale: zoom }); canvas.width = viewport.width; canvas.height = viewport.height; await pdfPage.render({ canvasContext: canvas.getContext('2d'), viewport }).promise; label.textContent = `${page} / ${document.numPages}`; } finally { rendering = false; } }
    controls.append(button('上一页', () => { if (page > 1 && !rendering) { page--; render(); } }), label, button('下一页', () => { if (page < document.numPages && !rendering) { page++; render(); } }), button('放大', () => { if (!rendering) { zoom = Math.min(4, zoom + .5); render(); } }), button('缩小', () => { if (!rendering) { zoom = Math.max(.5, zoom - .5); render(); } }));
    await render();
  } catch (e) { error(box, `PDF 打开失败：${e.message}`); }
}
async function embedded(node, stack) {
  const raw = node.dataset.target || '';
  const target = raw.split('|')[0];
  const size = raw.split('|')[1];
  const from = sourceOwner(node);
  if (/^https?:\/\//i.test(target)) return remote(node, target, true);
  const resolved = await resolve(target, from);
  if (resolved.missing || resolved.ambiguous?.length) return error(node, resolved.ambiguous?.length ? `引用不明确：${resolved.ambiguous.join('、')}` : `引用不存在：${target}`);
  if (resolved.note) {
    if (stack.includes(resolved.note) || stack.length >= 12) return error(node, `循环或过深嵌入：${target}`);
    const data = await json('document', { note: resolved.note });
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
    box.prepend(label); node.replaceWith(box); await decorate(box, [...stack, resolved.note]); return;
  }
  if (resolved.attachment) {
    const path = resolved.attachment;
    const extension = path.split('.').at(-1).toLowerCase(); const url = localResource(path);
    if (extension === 'pdf') return pdf(node, url, target);
    let media;
    if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'avif', 'bmp'].includes(extension)) media = element('img');
    else if (['mp3', 'wav', 'ogg', 'flac', 'm4a', '3gp'].includes(extension)) media = element('audio');
    else if (['mp4', 'mkv', 'mov', 'webm', 'ogv'].includes(extension)) media = element('video');
    else { const link = element('a', `打开附件：${path}`); link.href = '#'; link.dataset.target = target; node.replaceWith(link); return; }
    media.src = url; media.alt = path;
    if (media.tagName !== 'IMG') { media.controls = true; media.preload = 'metadata'; const time = target.match(/#t=([\d.,]+)/)?.[1]; if (time) media.src += `#t=${time}`; media.addEventListener('error', () => { const note = element('p', '设备可能不支持此媒体编码。', 'error'); media.after(note, button('系统打开附件', () => action('link', target, false, from))); }); }
    if (size && /^\d+(x\d+)?$/.test(size)) { const [width, height] = size.split('x'); media.width = Number(width); if (height) { media.height = Number(height); media.style.height = `${height}px`; } }
    node.replaceWith(media);
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
async function decorate(root, stack) {
  callouts(root);
  for (const paragraph of root.querySelectorAll('li>p:first-child,li')) {
    const first = paragraph.firstChild; if (first?.nodeType !== Node.TEXT_NODE) continue;
    const task = first.textContent.match(/^\[([ xX])\]\s/); if (!task) continue;
    first.textContent = first.textContent.slice(task[0].length);
    const check = document.createElement('input'); check.type = 'checkbox'; check.checked = task[1].toLowerCase() === 'x'; check.disabled = true; paragraph.prepend(check); paragraph.closest('li').classList.add('task-item');
  }
  for (const table of root.querySelectorAll('table')) { if (table.parentElement.classList.contains('table-scroll')) continue; const wrap = element('div', null, 'table-scroll'); table.replaceWith(wrap); wrap.append(table); }
  for (const frame of root.querySelectorAll('[data-embed]')) { frame.classList.add('embed'); frame.dataset.target = frame.dataset.embed; }
  for (const embed of [...root.querySelectorAll('.embed')]) { try { await embedded(embed, stack); } catch (e) { error(embed, `嵌入失败：${e.message}`); } }
  for (const image of [...root.querySelectorAll('img[src],img[data-original-src]')]) {
    const dimensions = image.alt?.match(/^(\d+)(?:x(\d+))?$/); if (dimensions) { image.width = Number(dimensions[1]); if (dimensions[2]) image.style.height = `${dimensions[2]}px`; }
    const active = image.getAttribute('src'); if (isLocalResource(active)) continue;
    const src = active || image.dataset.originalSrc;
    if (/^https?:\/\//i.test(src)) { await remote(image, src, false); continue; }
    const result = await resolve(src, sourceOwner(image));
    if (result.attachment) image.src = localResource(result.attachment); else { image.removeAttribute('src'); image.alt = `图片不存在：${src}`; }
  }
  for (const media of root.querySelectorAll('audio[src],video[src],audio[data-original-src],video[data-original-src]')) {
    const active = media.getAttribute('src'); if (isLocalResource(active)) continue;
    const src = active || media.dataset.originalSrc;
    if (/^https?:\/\//i.test(src)) await remote(media, src, false);
    else { const result = await resolve(src, sourceOwner(media)); if (result.attachment) media.src = localResource(result.attachment); else media.removeAttribute('src'); }
  }
  const math = [...root.querySelectorAll('.math:not([data-rendered])')];
  if (math.length) { await MathJax.startup.promise; for (const node of math) { try { const svg = await MathJax.tex2svgPromise(node.dataset.tex, { display: node.dataset.display === 'true' }); node.replaceChildren(svg); node.dataset.rendered = 'true'; } catch (e) { error(node, `公式错误：${e.message}`); } } }
  for (const code of root.querySelectorAll('pre>code.language-mermaid')) {
    const box = element('div', null, 'mermaid'); const source = code.textContent;
    box.dataset.source = code.parentElement.dataset.source || '0'; code.parentElement.replaceWith(box);
    try { const { svg } = await mermaid.render(`diagram-${unique++}`, source); box.innerHTML = svg; box.querySelectorAll('.internal-link').forEach(node => { node.style.cursor = 'pointer'; node.addEventListener('click', () => action('link', node.textContent.trim())); }); }
    catch (e) { error(box, `Mermaid 语法错误：${e.message}`); box.append(element('pre', source)); }
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
  if (target && target !== '#') action('link', target, false, sourceOwner(link));
});
window.readerOffset = () => {
  const nodes = [...content.querySelectorAll('[data-source]')].filter(n => !n.closest('.embedded-note') && !n.parentElement.closest('.footnotes') && n.getBoundingClientRect().bottom >= 0);
  return Number(nodes.find(n => n.getBoundingClientRect().top >= -20)?.dataset.source || nodes[0]?.dataset.source || 0);
};
window.readerReload = async () => {
  try {
    const restoreOffset = window.readerReady ? window.readerOffset() : Number(params.get('offset')) || 0;
    status.textContent = '';
    const data = await json('document', { note: rootId }); rootBlocks = data.blocks;
    content.dataset.note = rootId; content.innerHTML = safeContent(data.html);
    await decorate(content, [rootId]);
    const offset = restoreOffset;
    const node = [...content.querySelectorAll('[data-source]')].filter(n => !n.closest('.embedded-note') && Number(n.dataset.source) <= offset).at(-1);
    if (offset && node) node.scrollIntoView();
    window.readerReady = true;
  } catch (e) { status.textContent = `阅读加载失败：${e.message}`; }
};
await window.readerReload();

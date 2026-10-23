const pending = new Set();
const encoder = new TextEncoder();
export async function cacheKey(value) {
  return [...new Uint8Array(await crypto.subtle.digest('SHA-256', encoder.encode(value)))].map(n => n.toString(16).padStart(2, '0')).join('');
}
export async function cached(kind, key, context) {
  if (!context.namespace || !context.current()) return null;
  try {
    const response = await fetch(`/reader/render-cache?${new URLSearchParams({ namespace: context.namespace, kind, key })}`, { signal: context.signal });
    if (!response.ok || !context.current()) return null;
    return (await response.json()).value ?? null;
  } catch { return null; }
}
export function store(kind, key, value, context) {
  if (!context.namespace || !context.current() || !window.ReaderCache?.postMessage || encoder.encode(value).length > 8 * 1024 * 1024) return;
  // 写盘异步进行，不能将缓存回执加入首屏就绪条件。
  const request = crypto.randomUUID();
  const promise = new Promise(resolve => {
    const finish = () => { clearTimeout(timer); window.ReaderCache.removeEventListener?.('message', listener); resolve(); };
    const timer = setTimeout(finish, 2000);
    const listener = event => {
      try { if (JSON.parse(event.data).request !== request) return; } catch { return; }
      finish();
    };
    window.ReaderCache.addEventListener?.('message', listener);
    try { window.ReaderCache.postMessage(JSON.stringify({ type: 'cache-put', request, revision: context.revision, namespace: context.namespace, kind, key, value })); }
    catch { finish(); }
  });
  pending.add(promise); promise.finally(() => pending.delete(promise));
}
export async function flushCacheWrites() { await Promise.all([...pending]); }

/** SVG 每次装入都使用新 ID，并清除可执行内容；缓存不保存页面事件或来源笔记。 */
export function artifactMarkup(value, prefix) {
  const template = document.createElement('template');
  template.innerHTML = DOMPurify.sanitize(value, { ADD_TAGS: ['foreignObject', 'mjx-container', 'use'], ADD_ATTR: ['jax', 'display'], HTML_INTEGRATION_POINTS: { foreignobject: true }, FORBID_TAGS: ['script', 'iframe', 'object', 'embed', 'form'], FORBID_ATTR: ['srcset'] });
  template.content.querySelectorAll('img,video,audio,source,image').forEach(node => node.remove());
  const unsafeUrls = value => [...value.matchAll(/url\(([^)]*)\)/gi)].some(match => !match[1].trim().replace(/^(['"])(.*)\1$/, '$2').startsWith('#'));
  for (const style of template.content.querySelectorAll('style')) if (/@import|\\|(?:https?|data|javascript):/i.test(style.textContent) || unsafeUrls(style.textContent)) style.remove();
  for (const node of template.content.querySelectorAll('*')) for (const attribute of [...node.attributes]) {
    if (/^on/i.test(attribute.name) || ((attribute.name === 'href' || attribute.name === 'xlink:href') && !attribute.value.startsWith('#')) || unsafeUrls(attribute.value) || (attribute.name === 'style' && /\\|@import/i.test(attribute.value))) node.removeAttribute(attribute.name);
  }
  const ids = new Map([...template.content.querySelectorAll('[id]')].map(node => [node.id, `${prefix}-${node.id}`]));
  for (const style of template.content.querySelectorAll('style')) for (const [old, next] of [...ids].sort((a, b) => b[0].length - a[0].length)) {
    const escaped = old.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    style.textContent = style.textContent.replace(new RegExp(`#${escaped}(?![\\w-])`, 'g'), `#${next}`);
  }
  for (const node of template.content.querySelectorAll('*')) for (const attribute of [...node.attributes]) {
    if (attribute.name === 'id') node.id = ids.get(attribute.value);
    else {
      let value = attribute.value.replace(/url\(\s*(['"]?)#([^)'"\s]+)\1\s*\)/g, (all, quote, id) => ids.has(id) ? `url(#${ids.get(id)})` : all);
      if ((attribute.name === 'href' || attribute.name === 'xlink:href') && ids.has(value.slice(1))) value = `#${ids.get(value.slice(1))}`;
      if (attribute.name === 'aria-labelledby') value = value.split(/\s+/).map(id => ids.get(id) || id).join(' ');
      node.setAttribute(attribute.name, value);
    }
  }
  return template.content;
}

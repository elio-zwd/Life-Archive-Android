/** 只调度可见范围附近的重工作；原文定位与完整文档 DOM 始终保留。 */
export class ReaderWork {
  constructor(context) { this.context = context; this.tasks = []; this.attaching = 0; this.visible = false; }
  add(node, kind, work) {
    const task = { node, kind, work, state: 'pending' }; this.tasks.push(task);
    node.dataset.loading = kind; node.setAttribute('aria-busy', 'true');
    if (this.observer) this.observer.observe(node);
    return task;
  }
  collapsed(node) {
    for (let parent = node.parentElement; parent; parent = parent.parentElement) if (parent.tagName === 'DETAILS' && !parent.open && !parent.querySelector(':scope>summary')?.contains(node)) return true;
    return false;
  }
  inBand(task, top, bottom, root = this.root) {
    if (!task.node.isConnected || this.collapsed(task.node)) return false;
    const box = task.node.getBoundingClientRect(); const origin = this.visible ? 0 : root.getBoundingClientRect().top;
    return box.bottom - origin >= top && box.top - origin <= bottom;
  }
  mutate(update) {
    if (!this.context.current()) return;
    const anchor = this.visible ? [...this.root.querySelectorAll('[data-source]')].find(n => !n.closest('.embedded-note') && n.getBoundingClientRect().top >= -20 && n.getBoundingClientRect().bottom > 0) : null;
    const before = anchor?.getBoundingClientRect().top;
    update();
    if (anchor?.isConnected && Number.isFinite(before)) window.scrollBy(0, anchor.getBoundingClientRect().top - before);
  }
  async run(task) {
    if (task.state !== 'pending' || !this.context.current()) return;
    if (task.kind === 'attachment' && this.attaching >= 2) return;
    if (task.kind === 'attachment') this.attaching++;
    task.state = 'running';
    try { await task.work(); if (this.context.current()) { task.state = 'done'; task.node.removeAttribute('aria-busy'); delete task.node.dataset.loading; } }
    catch (error) {
      if (!this.context.current()) return;
      if (error.name === 'AbortError') { task.state = 'pending'; return; }
      task.state = 'failed';
      this.mutate(() => {
        const original = task.node;
        const voidMedia = ['IMG', 'VIDEO', 'AUDIO'].includes(original.tagName);
        if (voidMedia) { const box = document.createElement('span'); box.className = 'resource'; original.replaceWith(box); task.node = box; }
        const retry = document.createElement('button'); retry.textContent = '重试'; retry.onclick = () => {
          if (voidMedia) { task.node.replaceWith(original); task.node = original; this.observer?.observe(original); }
          task.state = 'pending'; retry.remove(); this.schedule();
        };
        const label = document.createElement('span'); label.className = 'error'; label.textContent = `内容加载失败：${error.message}`;
        task.node.replaceChildren(label, retry); task.node.removeAttribute('aria-busy');
      });
    } finally { if (task.kind === 'attachment') this.attaching--; }
  }
  async first(root, offset) {
    this.root = root;
    if (!this.tasks.length) return;
    // 嵌入内容会加入新任务，逐轮准备首屏，但不等待 PDF、图片或媒体解码。
    for (let round = 0; round < 14 && this.context.current(); round++) {
      // System WebView 可能缺少 Array.at；恢复阅读位置不能依赖该方法。
      const anchor = offset ? [...root.querySelectorAll('[data-source]')].filter(n => !n.closest('.embedded-note') && Number(n.dataset.source) <= offset).slice(-1)[0] : null;
      const top = offset && anchor ? anchor.getBoundingClientRect().top - root.getBoundingClientRect().top : 0;
      const tasks = this.tasks.filter(t => t.state === 'pending' && t.kind !== 'attachment' && this.inBand(t, top, top + innerHeight, root));
      if (!tasks.length) break;
      await Promise.all(tasks.map(t => this.run(t)));
    }
  }
  mount(root) {
    this.root = root; this.visible = true;
    if (typeof IntersectionObserver === 'function') {
      this.observer = new IntersectionObserver(() => this.schedule(), { rootMargin: '100% 0px' });
      for (const task of this.tasks) this.observer.observe(task.node);
    } else {
      // 旧 WebView 用同一范围调度器降级，仍按需加载且保留附件并发限制。
      this.viewportChanged = () => this.schedule();
      window.addEventListener('scroll', this.viewportChanged, { passive: true });
      window.addEventListener('resize', this.viewportChanged);
    }
    this.toggle = () => this.schedule(); root.addEventListener('toggle', this.toggle, true);
    this.schedule();
  }
  schedule() {
    if (this.frame || !this.context.current()) return;
    this.frame = requestAnimationFrame(() => {
      this.frame = null;
      if (!this.context.current()) return;
      const tasks = this.tasks.filter(t => t.state === 'pending' && this.inBand(t, -innerHeight, innerHeight * 2)).sort((a, b) => Math.abs(a.node.getBoundingClientRect().top) - Math.abs(b.node.getBoundingClientRect().top));
      for (const task of tasks) {
        if (task.kind === 'attachment') {
          if (this.attaching >= 2) continue;
          this.run(task).finally(() => this.schedule());
        } else this.run(task).finally(() => this.schedule());
      }
      if (!tasks.length && !this.tasks.some(t => t.state === 'running')) this.context.idle?.();
    });
  }
  async drain() {
    const deadline = performance.now() + 45000;
    while (performance.now() < deadline && this.context.current()) {
      const pending = this.tasks.filter(t => t.state === 'pending' && t.node.isConnected && !this.collapsed(t.node));
      if (!pending.length && !this.tasks.some(t => t.state === 'running')) return;
      // 验证入口沿用两个附件槽，不能通过测试入口同时读入所有大附件。
      for (let i = 0; i < pending.length; i += 2) await Promise.all(pending.slice(i, i + 2).map(t => this.run(t)));
      await new Promise(resolve => setTimeout(resolve, 20));
    }
  }
  dispose() {
    this.observer?.disconnect(); if (this.frame) cancelAnimationFrame(this.frame);
    this.root?.removeEventListener('toggle', this.toggle, true);
    if (this.viewportChanged) { window.removeEventListener('scroll', this.viewportChanged); window.removeEventListener('resize', this.viewportChanged); }
  }
}

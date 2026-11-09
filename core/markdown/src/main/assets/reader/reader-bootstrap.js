/* 在模块解析之前接住入口失败，避免旧 WebView 永久停在加载状态。 */
(function () {
  function report() {
    var status = document.getElementById('status');
    if (!status || window.readerStartupReported) return;
    window.readerStartupReported = true;
    document.documentElement.classList.remove('reader-preparing');
    status.textContent = '阅读加载失败：阅读组件未能启动，请重试。';
    var retry = document.createElement('button'); retry.textContent = '重试';
    retry.addEventListener('click', function () { location.reload(); }); status.appendChild(retry);
    var params = new URLSearchParams(location.search);
    var config = window.readerPendingConfiguration;
    location.href = '/reader/action?' + new URLSearchParams({ type: 'error', value: '阅读组件未能启动', revision: config ? config.revision : params.get('rev') || 'initial' });
  }
  function failed(event) {
    var script = event.target;
    if (window.readerStarted || !(script && script.hasAttribute && script.hasAttribute('data-reader-entry')) && !event.filename) return;
    window.readerStartupError = true;
    if (document.getElementById('status')) report();
    else document.addEventListener('DOMContentLoaded', report, { once: true });
  }
  window.addEventListener('error', failed, true);
}());

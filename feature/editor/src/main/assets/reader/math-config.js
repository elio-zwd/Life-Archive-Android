window.MathJax = {
  loader: { paths: { mathjax: '/reader/mathjax', '[mathjax-newcm]': '/reader/fonts/mathjax-newcm-font' } },
  startup: { typeset: false },
  svg: { fontCache: 'local' },
  output: { font: 'mathjax-newcm', fontPath: '/reader/fonts/mathjax-newcm-font' },
  options: { enableMenu: false },
  tex: { packages: { '[+]': ['ams', 'noerrors', 'noundefined'] }, maxBuffer: 65536 }
};

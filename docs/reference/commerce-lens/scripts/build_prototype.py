#!/usr/bin/env python3
"""Build a single offline HTML from authored prototype sources; no dependencies."""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
p=ROOT/'prototype'
css=(p/'styles.css').read_text(encoding='utf-8')
js='\n'.join((p/f).read_text(encoding='utf-8') for f in ['data.js','app.js'])
head='<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="light"><title>商脉 CommerceLens · 电商经营分析与异常诊断</title>'
(p/'index.html').write_text(head+'<style>'+css+'</style></head><body><noscript>此原型需要启用 JavaScript。所有数据为合成示例。</noscript><script>'+js.replace('</script','<\\/script')+'</script></body></html>',encoding='utf-8')
(p/'dev.html').write_text(head+'<link rel="stylesheet" href="styles.css"></head><body><script src="data.js"></script><script src="app.js"></script></body></html>',encoding='utf-8')
print('Built',p/'index.html')

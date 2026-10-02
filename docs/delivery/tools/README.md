# 交付手册的重新生成（manual.md → manual.docx）

改完 `../manual.md`（或补了截图）后，用这里的两条命令重新产出 Word 版：

```bash
# 依赖（一次性）：node + npm 包 docx、playwright-core（复用本机 chromium）
mkdir -p /tmp/docxwork && cd /tmp/docxwork && npm i docx playwright-core
cp <仓库>/docs/delivery/tools/*.mjs . 2>/dev/null; cp <仓库>/docs/delivery/tools/generate.js .

node render-mermaid.mjs          # 1) 把手册里的 mermaid 状态机渲染成 PNG（走 CDN）
node generate.js                 # 2) 生成 ../manual.docx（R1 封面 + 目录域 + 三栏页码）

# 3) 目录占位（MANDATORY，docx 技能脚本）
python3 <docx技能目录>/scripts/add_toc_placeholders.py ../manual.docx --auto
# 4) 自检
python3 <docx技能目录>/scripts/postcheck.py ../manual.docx
```

注意：脚本里的路径按本仓库布局写死（`docs/delivery/manual.md`、`docs/delivery/screenshots/`），
换机器时改开头的 `MD`/`SHOTS`/`WORK` 三个常量。

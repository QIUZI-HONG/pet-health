# 交付物重新生成（手册 + 演示视频）

## 一、交验手册（manual.md → manual.docx）

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

## 二、验收演示视频（`../videos/acceptance-demo.mp4`）

三步流水线（都在同一个工作目录 `/tmp/docxwork` 里跑）：

```bash
# 0) 依赖：ffmpeg（含 libass）、python3、edge-tts（pip install edge-tts）、
#    中文字体装在 ~/.local/share/fonts/（msyh.ttc / msyhbd.ttc）、playwright-core（同上）

# 1) 录制：真浏览器按脚本把 29 项功能逐项操作一遍，边录边写 captions.json（字幕/旁白文案）
node record-acceptance.mjs        # 产出 vids2/acceptance-tour.webm + captions.json
#    ⚠️ 每次重录前清空 vids2/；脚本里 saveAs 必须在 context.close() 之后、browser.close() 之前

# 2) 成片：片头卡 + 烧字幕 + 兼容编码（H.264 Constrained Baseline, Level 4.0）
#    3) 旁白：edge-tts 逐条合成 → 按 captions.json 时间轴（静音段+配音段）concat 拼装 → 混流
bash build-video.sh vids2/acceptance-tour.webm
#    产出 acceptance-compat.mp4（无旁白）/ acceptance-narrated.mp4（终版）
```

视频规格：1280×800 / 25fps / 123 秒（6 秒片头卡 + 117 秒实录）/ 29 条同步字幕 + 29 条中文旁白。
改文案就改 `record-acceptance.mjs` 顶部的 `CAPS` 字典（键 = 步骤名），重录即可；
旁白的语句切分、语速（atempo ≤1.35 自动压缩防串条）都在 `narrate.py` 里。


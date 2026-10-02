// 交付版操作手册 → DOCX（docx-js）
// 封面用 design-system 的 R1 配方（MC-1 医疗蓝调色板）；三栏页码（封面/前言罗马/正文阿拉伯）。
const {
  Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell,
  ImageRun, PageBreak, Header, Footer, PageNumber, NumberFormat,
  AlignmentType, HeadingLevel, WidthType, BorderStyle, ShadingType,
  SectionType, TableLayoutType, TableOfContents, LevelFormat,
} = require("docx");
const fs = require("fs");
const path = require("path");

const MD = "/home/QIU/Pet_Health/docs/delivery/manual.md";
const SHOTS = "/home/QIU/Pet_Health/docs/delivery/screenshots";
const WORK = "/tmp/docxwork";

// ---------- 调色板（MC-1 Medical Blue，取自 design-system） ----------
const P = {
  bg: "F5F8FC", primary: "1A5276", accent: "2E86C1",
  cover: { titleColor: "1A5276", subtitleColor: "606060", metaColor: "707070", footerColor: "A0A0A0" },
  table: { headerBg: "2E86C1", headerText: "FFFFFF", accentLine: "1A5276", innerLine: "D0DDE8", surface: "EDF3F8" },
};

// ---------- 封面配方（R1，逐条遵守 Non-Negotiables） ----------
const NB = { style: BorderStyle.NONE, size: 0, color: "FFFFFF" };
const noBorders = { top: NB, bottom: NB, left: NB, right: NB };
const allNoBorders = { top: NB, bottom: NB, left: NB, right: NB, insideHorizontal: NB, insideVertical: NB };

function splitTitleLines(title, charsPerLine) {
  if (title.length <= charsPerLine) return [title];
  const breakAfter = new Set([...'，。、；：！？', ...'的与和及之在于为', ...'-_—–·/', ...' \t']);
  const lines = [];
  let remaining = title;
  while (remaining.length > charsPerLine) {
    let breakAt = -1;
    for (let i = charsPerLine; i >= Math.floor(charsPerLine * 0.6); i--) {
      if (i < remaining.length && breakAfter.has(remaining[i - 1])) { breakAt = i; break; }
    }
    if (breakAt === -1) {
      const limit = Math.min(remaining.length, Math.ceil(charsPerLine * 1.3));
      for (let i = charsPerLine + 1; i < limit; i++) {
        if (breakAfter.has(remaining[i - 1])) { breakAt = i; break; }
      }
    }
    if (breakAt === -1) {
      breakAt = charsPerLine;
      const prevChar = remaining[breakAt - 1], nextChar = remaining[breakAt];
      if (prevChar && nextChar && !breakAfter.has(prevChar) && !breakAfter.has(nextChar) &&
          /[\u4e00-\u9fff]/.test(prevChar) && /[\u4e00-\u9fff]/.test(nextChar)) breakAt -= 1;
    }
    lines.push(remaining.slice(0, breakAt).trim());
    remaining = remaining.slice(breakAt).trim();
  }
  if (remaining) lines.push(remaining);
  if (lines.length > 1 && lines[lines.length - 1].length <= 2) {
    const last = lines.pop();
    lines[lines.length - 1] += last;
  }
  return lines;
}
function calcTitleLayout(title, maxWidthTwips, preferredPt = 40, minPt = 24) {
  const charWidth = (pt) => pt * 20;
  const charsPerLine = (pt) => Math.floor(maxWidthTwips / charWidth(pt));
  let titlePt = preferredPt, lines;
  while (titlePt >= minPt) {
    const cpl = charsPerLine(titlePt);
    if (cpl < 2) { titlePt -= 2; continue; }
    lines = splitTitleLines(title, cpl);
    if (lines.length <= 3) break;
    titlePt -= 2;
  }
  if (!lines || lines.length > 3) { lines = splitTitleLines(title, charsPerLine(minPt)); titlePt = minPt; }
  return { titlePt, titleLines: lines };
}
function calcCoverSpacing(params) {
  const { titleLineCount = 1, titlePt = 36, hasSubtitle = false, hasEnglishLabel = false,
    metaLineCount = 0, fixedHeight = 800, pageHeight = 16838, marginTop = 0, marginBottom = 0 } = params;
  const SAFETY = 1200;
  const usableHeight = pageHeight - marginTop - marginBottom - SAFETY;
  const titleHeight = titleLineCount * (titlePt * 23 + 200);
  const subtitleHeight = hasSubtitle ? (12 * 23 + 600) : 0;
  const englishLabelHeight = hasEnglishLabel ? (9 * 23 + 600) : 0;
  const metaHeight = metaLineCount * (10 * 23 + 100);
  const implicitParaHeight = 3 * 300;
  const contentHeight = titleHeight + subtitleHeight + englishLabelHeight + metaHeight + fixedHeight + implicitParaHeight;
  const safeRemaining = Math.max(usableHeight - contentHeight, 400);
  const FOOTER_MIN = 800;
  const rawTop = Math.floor(safeRemaining * 0.45), rawBottom = Math.floor(safeRemaining * 0.45);
  const bottomSpacing = Math.max(rawBottom, FOOTER_MIN);
  const topSpacing = Math.max(rawTop - Math.max(0, FOOTER_MIN - rawBottom), 400);
  const midSpacing = Math.max(safeRemaining - topSpacing - bottomSpacing, 0);
  return { topSpacing, midSpacing, bottomSpacing };
}
function buildCoverR1(config) {
  const CP = config.palette;
  const padL = 1200, padR = 800;
  const availableWidth = 11906 - padL - padR - 300;
  const { titlePt, titleLines } = calcTitleLayout(config.title, availableWidth, 40, 24);
  const titleSize = titlePt * 2;
  const spacing = calcCoverSpacing({
    titleLineCount: titleLines.length, titlePt,
    hasSubtitle: !!config.subtitle, hasEnglishLabel: !!config.englishLabel,
    metaLineCount: (config.metaLines || []).length, fixedHeight: 400,
  });
  const accentLeft = { style: BorderStyle.SINGLE, size: 8, color: CP.accent, space: 12 };
  const children = [];
  children.push(new Paragraph({ spacing: { before: spacing.topSpacing } }));
  if (config.englishLabel) {
    children.push(new Paragraph({
      indent: { left: padL, right: padR }, spacing: { after: 500 },
      border: { bottom: { style: BorderStyle.SINGLE, size: 6, color: CP.accent, space: 8 } },
      children: [new TextRun({ text: config.englishLabel.split("").join("  "), size: 18, color: CP.accent,
        font: { ascii: "Calibri", eastAsia: "SimHei" }, characterSpacing: 40 })],
    }));
  }
  for (let i = 0; i < titleLines.length; i++) {
    children.push(new Paragraph({
      indent: { left: padL },
      spacing: { after: i < titleLines.length - 1 ? 100 : 300, line: Math.ceil(titlePt * 23), lineRule: "atLeast" },
      children: [new TextRun({ text: titleLines[i], size: titleSize, bold: true, color: CP.titleColor,
        font: { eastAsia: "SimHei", ascii: "Arial" } })],
    }));
  }
  if (config.subtitle) {
    children.push(new Paragraph({
      indent: { left: padL }, spacing: { after: 800 },
      children: [new TextRun({ text: config.subtitle, size: 24, color: CP.subtitleColor,
        font: { eastAsia: "Microsoft YaHei", ascii: "Arial" } })],
    }));
  }
  for (const line of (config.metaLines || [])) {
    children.push(new Paragraph({
      indent: { left: padL + 200 }, spacing: { after: 80 },
      border: { left: accentLeft },
      children: [new TextRun({ text: line, size: 24, color: CP.metaColor,
        font: { eastAsia: "Microsoft YaHei", ascii: "Arial" } })],
    }));
  }
  children.push(new Paragraph({ spacing: { before: spacing.bottomSpacing } }));
  children.push(new Paragraph({
    indent: { left: padL, right: padR },
    border: { top: { style: BorderStyle.SINGLE, size: 2, color: CP.accent, space: 8 } },
    spacing: { before: 200 },
    children: [
      new TextRun({ text: config.footerLeft || "", size: 16, color: CP.footerColor, font: { ascii: "Arial" } }),
      new TextRun({ text: "                                        " }),
      new TextRun({ text: config.footerRight || "", size: 16, color: CP.footerColor, font: { ascii: "Arial" } }),
    ],
  }));
  return [new Table({
    width: { size: 100, type: WidthType.PERCENTAGE },
    layout: TableLayoutType.FIXED,
    borders: allNoBorders,
    rows: [new TableRow({
      height: { value: 16838, rule: "exact" },
      children: [new TableCell({ shading: { type: ShadingType.CLEAR, fill: CP.bg }, borders: noBorders, children })],
    })],
  })];
}

// ---------- 工具 ----------
function pngSize(file) {
  const b = fs.readFileSync(file);
  return { w: b.readUInt32BE(16), h: b.readUInt32BE(20) };
}
function inlineRuns(text, base = {}) {
  // 处理 **加粗** 与 `代码`
  const runs = [];
  const re = /(\*\*[^*]+\*\*|`[^`]+`)/g;
  let last = 0, m;
  while ((m = re.exec(text)) !== null) {
    if (m.index > last) runs.push(new TextRun({ text: text.slice(last, m.index), ...base }));
    const tok = m[0];
    if (tok.startsWith("**")) runs.push(new TextRun({ text: tok.slice(2, -2), bold: true, ...base }));
    else runs.push(new TextRun({ text: tok.slice(1, -1), font: { ascii: "Courier New", eastAsia: "SimSun" }, ...base }));
    last = m.index + tok.length;
  }
  if (last < text.length) runs.push(new TextRun({ text: text.slice(last), ...base }));
  return runs.length ? runs : [new TextRun({ text, ...base })];
}
function maybeEmbed(text) {
  const found = [...text.matchAll(shotRe)].map((m) => m[1]);
  const uniq = [...new Set(found)];
  if (!uniq.length || !text.includes("截图")) return;
  for (const f of uniq) {
    if (embedded.has(f)) continue;
    embedded.add(f);
    body.push(...imageParas(f, f));
  }
}
function imageParas(file, caption) {
  const full = path.join(SHOTS, file);
  if (!fs.existsSync(full)) return [];
  const { w, h } = pngSize(full);
  const scale = Math.min(560 / w, 720 / h, 1);
  const out = [new Paragraph({
    alignment: AlignmentType.CENTER, spacing: { before: 120, after: 60 },
    children: [new ImageRun({ data: fs.readFileSync(full),
      transformation: { width: Math.round(w * scale), height: Math.round(h * scale) }, type: "png" })],
  })];
  if (caption) out.push(new Paragraph({
    alignment: AlignmentType.CENTER, spacing: { after: 200 },
    children: [new TextRun({ text: caption, size: 18, color: "888888" })],
  }));
  return out;
}

// ---------- Markdown → 正文 ----------
const mdLines = fs.readFileSync(MD, "utf8").split("\n");
const body = [];
const embedded = new Set();
const shotRe = /(?:screenshots\/)?((?:provider|admin|c|flow)-\d{2}[A-Za-z0-9-]*\.png)/g;
let listIdx = 0, inNumbered = false, skipTitle = true, mermaidIdx = 0, inCode = false, codeLines = [];

function flushCode() {
  if (!codeLines.length) return;
  for (const line of codeLines) {
    body.push(new Paragraph({
      spacing: { line: 240 }, indent: { left: 400 },
      children: [new TextRun({ text: line || " ", size: 18, font: { ascii: "Courier New", eastAsia: "SimSun" }, color: "334155" })],
    }));
  }
  codeLines = [];
}

for (let i = 0; i < mdLines.length; i++) {
  let line = mdLines[i];
  if (line.trim().startsWith("```")) {
    if (!inCode) {
      const lang = line.trim().slice(3).trim();
      inCode = true;
      if (lang === "mermaid") {
        const f = path.join(WORK, `mermaid-${mermaidIdx}.png`);
        if (fs.existsSync(f)) {
          const { w, h } = pngSize(f);
          const scale = Math.min(560 / w, 820 / h, 1);
          body.push(new Paragraph({
            alignment: AlignmentType.CENTER, spacing: { before: 160, after: 100 },
            children: [new ImageRun({ data: fs.readFileSync(f),
              transformation: { width: Math.round(w * scale), height: Math.round(h * scale) }, type: "png" })],
          }));
          body.push(new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 220 },
            children: [new TextRun({ text: `图：${lang} 流程图 ${mermaidIdx + 1}`, size: 18, color: "888888" })] }));
        }
        mermaidIdx += 1;
        // 跳过 mermaid 源码：先越过开栏，再走到闭栏（修：原先从开栏就开始找闭栏，条件立刻为假）
        let j = i + 1;
        while (j < mdLines.length && !mdLines[j].trim().startsWith("```")) j++;
        i = j;
        inCode = false;
        continue;
      }
      continue;
    } else { flushCode(); inCode = false; continue; }
  }
  if (inCode) { codeLines.push(line.replace(/\t/g, "  ")); continue; }

  const t = line.trim();
  if (t === "") { continue; }
  if (t === "---") { continue; }

  // 表格
  if (t.startsWith("|")) {
    const rows = [];
    let j = i;
    while (j < mdLines.length && mdLines[j].trim().startsWith("|")) {
      rows.push(mdLines[j].trim()); j++;
    }
    i = j - 1;
    const parsed = rows.map((r) => r.replace(/^\|/, "").replace(/\|$/, "").split("|").map((c) => c.trim()));
    const data = parsed.filter((r) => !r.every((c) => /^-+$/.test(c.replace(/:/g, ""))));
    const nCol = Math.max(...data.map((r) => r.length));
    const tableRows = data.map((cells, ri) => new TableRow({
      tableHeader: ri === 0, cantSplit: true,
      children: Array.from({ length: nCol }, (_, ci) => new TableCell({
        margins: { top: 60, bottom: 60, left: 120, right: 120 },
        width: { size: Math.floor(100 / nCol), type: WidthType.PERCENTAGE },
        shading: ri === 0 ? { type: ShadingType.CLEAR, fill: P.table.headerBg } : (ri % 2 === 0 ? { type: ShadingType.CLEAR, fill: "FAFCFE" } : undefined),
        children: [new Paragraph({
          children: inlineRuns((cells[ci] || "").replace(/`/g, ""), ri === 0
            ? { bold: true, size: 20, color: "FFFFFF" } : { size: 20, color: "1F2937" }),
        })],
      })),
    }));
    body.push(new Table({
      width: { size: 100, type: WidthType.PERCENTAGE },
      borders: {
        top: { style: BorderStyle.SINGLE, size: 4, color: P.table.accentLine },
        bottom: { style: BorderStyle.SINGLE, size: 4, color: P.table.accentLine },
        left: NB, right: NB,
        insideHorizontal: { style: BorderStyle.SINGLE, size: 1, color: P.table.innerLine },
        insideVertical: NB,
      },
      rows: tableRows,
    }));
    body.push(new Paragraph({ spacing: { after: 160 } }));
    continue;
  }

  // 标题
  if (t.startsWith("### ")) { body.push(new Paragraph({ heading: HeadingLevel.HEADING_3, children: inlineRuns(t.slice(4)) })); continue; }
  if (t.startsWith("## ")) { body.push(new Paragraph({ heading: HeadingLevel.HEADING_2, children: inlineRuns(t.slice(3)) })); continue; }
  if (t.startsWith("# ")) {
    const text = t.slice(2);
    if (skipTitle) { skipTitle = false; continue; } // 文档大标题交给封面
    body.push(new Paragraph({ heading: HeadingLevel.HEADING_1, children: inlineRuns(text) }));
    continue;
  }

  // 引用块
  if (t.startsWith("> ")) {
    body.push(new Paragraph({
      alignment: AlignmentType.LEFT, spacing: { after: 80 }, indent: { left: 360 },
      children: inlineRuns(t.slice(2), { size: 20, italics: true, color: "59636E" }),
    }));
    continue;
  }

  // 列表
  const bullet = t.match(/^[-*] (.*)$/);
  const numbered = t.match(/^(\d+)\. (.*)$/);
  if (bullet) {
    inNumbered = false;
    body.push(new Paragraph({
      bullet: { level: 0 }, spacing: { after: 60 },
      children: inlineRuns(bullet[1], { size: 22 }),
    }));
    maybeEmbed(bullet[1]);
    continue;
  }
  if (numbered) {
    if (!inNumbered) { listIdx += 1; }
    inNumbered = true;
    body.push(new Paragraph({
      numbering: { reference: `num-${listIdx}`, level: 0 }, spacing: { after: 60 },
      children: inlineRuns(numbered[2], { size: 22 }),
    }));
    maybeEmbed(numbered[2]);
    continue;
  }
  inNumbered = false;

  // 普通段落（含 截图： 行 → 段后嵌图）
  body.push(new Paragraph({
    alignment: AlignmentType.JUSTIFIED, indent: { firstLine: 420 },
    spacing: { after: 120, line: 312 },
    children: inlineRuns(t, { size: 24, color: "000000" }),
  }));
  maybeEmbed(t);
}

// 未被引用嵌入的截图 → 附录画廊
const allShots = fs.readdirSync(SHOTS).filter((f) => f.endsWith(".png")).sort();
const leftovers = allShots.filter((f) => !embedded.has(f));
if (leftovers.length) {
  body.push(new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun({ text: "附：其余页面截图" })] }));
  for (const f of leftovers) body.push(...imageParas(f, f));
}

// ---------- 文档 ----------
const pgSize = { width: 11906, height: 16838 };
const pgMargin = { top: 1440, bottom: 1440, left: 1701, right: 1417 };
const pageNumFooter = () => new Footer({
  children: [new Paragraph({
    alignment: AlignmentType.CENTER,
    children: [new TextRun({ children: [PageNumber.CURRENT], size: 18, color: "666666" })],
  })],
});

const doc = new Document({
  styles: {
    default: {
      document: {
        run: { font: { ascii: "Calibri", eastAsia: "Microsoft YaHei" }, size: 24, color: "000000" },
        paragraph: { spacing: { line: 312 } },
      },
      heading1: { run: { font: { ascii: "Calibri", eastAsia: "SimHei" }, size: 32, bold: true, color: P.primary },
        paragraph: { spacing: { before: 360, after: 160, line: 312 } } },
      heading2: { run: { font: { ascii: "Calibri", eastAsia: "SimHei" }, size: 28, bold: true, color: P.primary },
        paragraph: { spacing: { before: 240, after: 120, line: 312 } } },
      heading3: { run: { font: { ascii: "Calibri", eastAsia: "SimHei" }, size: 24, bold: true, color: "1F4E79" },
        paragraph: { spacing: { before: 200, after: 100, line: 312 } } },
    },
  },
  numbering: {
    config: Array.from({ length: listIdx }, (_, i) => ({
      reference: `num-${i + 1}`,
      levels: [{ level: 0, format: LevelFormat.DECIMAL, text: "%1.", alignment: AlignmentType.LEFT,
        style: { paragraph: { indent: { left: 720, hanging: 360 } } } }],
    })),
  },
  sections: [
    {
      properties: { page: { size: pgSize, margin: { top: 0, bottom: 0, left: 0, right: 0 } } },
      children: buildCoverR1({
        title: "宠物 AI 健康管理平台 · 操作手册",
        subtitle: "服务者入驻 · 运营审批 · 三端功能演示（交付版）",
        englishLabel: "PET HEALTH AI PLATFORM",
        metaLines: ["版本 v0.1.0-SNAPSHOT", "快照 2026-10-01（提交 1270271）", "适用读者：服务者 / 运营管理员 / 验收方"],
        footerLeft: "宠物 AI 健康管理平台",
        footerRight: "2026-10-01",
        palette: P.cover,
      }),
    },
    {
      properties: { type: SectionType.NEXT_PAGE, page: { size: pgSize, margin: pgMargin,
        pageNumbers: { start: 1, formatType: NumberFormat.UPPER_ROMAN } } },
      footers: { default: pageNumFooter() },
      children: [
        new Paragraph({ alignment: AlignmentType.CENTER, spacing: { before: 480, after: 360 },
          children: [new TextRun({ text: "目  录", bold: true, size: 32, font: { eastAsia: "SimHei", ascii: "Times New Roman" } })] }),
        new TableOfContents("Table of Contents", { hyperlink: true, headingStyleRange: "1-3" }),
        new Paragraph({ spacing: { before: 200 },
          children: [new TextRun({ text: "说明：目录由域代码生成——如页码未刷新，请在 Word / WPS 中右键目录选择「更新域」即可。", italics: true, size: 18, color: "888888" })] }),
        new Paragraph({ children: [new PageBreak()] }),
      ],
    },
    {
      properties: { type: SectionType.NEXT_PAGE, page: { size: pgSize, margin: pgMargin,
        pageNumbers: { start: 1, formatType: NumberFormat.DECIMAL } } },
      footers: { default: pageNumFooter() },
      children: body,
    },
  ],
});

Packer.toBuffer(doc).then((buf) => {
  const out = "/home/QIU/Pet_Health/docs/delivery/manual.docx";
  fs.writeFileSync(out, buf);
  console.log("生成:", out, (buf.length / 1024 / 1024).toFixed(1), "MB", "| 段落/表等:", body.length, "| 嵌入截图:", embedded.size, "| 附录补图:", leftovers.length);
});

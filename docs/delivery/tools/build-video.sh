#!/usr/bin/env bash
# 验收实录成片流水线：录制 webm + captions.json → 带旁白/字幕的 mp4
#
#   bash build-video.sh [录制.webm]
#
# 前置：ffmpeg（含 libass）、python3、edge-tts（pip install edge-tts）、
#       中文字体（~/.local/share/fonts/ 下的 msyh.ttc / msyhbd.ttc）；
#       同目录需有 captions.json（由 tools/record-acceptance.mjs 录制时写出）。
# 产物：subs.srt / intro.mp4 / acceptance-compat.mp4 / narration.m4a / acceptance-narrated.mp4
set -euo pipefail
cd "$(dirname "$0")"

WEBM=${1:-vids2/acceptance-tour.webm}
OFF=6.0   # 片头卡时长，与 narrate.py 一致
FONT_R="$HOME/.local/share/fonts/msyh.ttc"     # 微软雅黑
FONT_B="$HOME/.local/share/fonts/msyhbd.ttc"   # 微软雅黑 Bold

# 1) 字幕：captions.json → subs.srt（+片头偏移；末条收在片尾）
python3 - <<'PY'
import json
caps = json.load(open('captions.json', encoding='utf-8'))
OFF = 6.0
def ts(s):
    s = max(s, 0.0)
    m, sec = divmod(s, 60)
    h, m = divmod(int(m), 60)
    return f'{h:02d}:{m:02d}:{sec:06.3f}'.replace('.', ',')
out = []
n = len(caps)
for i, c in enumerate(caps):
    start = c['t'] + OFF
    end = (caps[i+1]['t'] + OFF) if i + 1 < n else start + 5.0
    out.append(f'{i+1}\n{ts(start)} --> {ts(end)}\n{c["text"]}\n')
open('subs.srt', 'w', encoding='utf-8').write('\n'.join(out))
print(f'subs.srt: {n} 条')
PY

# 2) 片头卡（6 秒，深蓝底 + 标题/副标题）
if [ ! -f intro.mp4 ]; then
  ffmpeg -y -hide_banner -loglevel error \
    -f lavfi -i "color=c=0x16324F:s=1280x800:d=${OFF}:r=25" \
    -vf "drawtext=fontfile=${FONT_B}:text='宠物 AI 健康管理平台':fontcolor=white:fontsize=68:x=(w-text_w)/2:y=(h-text_h)/2-70,\
drawtext=fontfile=${FONT_R}:text='验收演示：三端全功能实操 + 要点讲解':fontcolor=0xD8E2EE:fontsize=30:x=(w-text_w)/2:y=(h-text_h)/2+34" \
    -c:v libx264 -profile:v baseline -level 4.0 -pix_fmt yuv420p -crf 20 intro.mp4
fi

# 3) 合成：片头 + 实录（烧字幕）+ 静音轨 —— H.264 Baseline 兼容编码
ffmpeg -y -hide_banner -loglevel error \
  -i intro.mp4 -i "$WEBM" -f lavfi -i anullsrc=r=44100:cl=stereo \
  -filter_complex "[0:v][1:v]concat=n=2:v=1:a=0,subtitles=filename=subs.srt:force_style='FontName=Microsoft YaHei,FontSize=20,PrimaryColour=&H00FFFFFF,BackColour=&HB0000000,BorderStyle=4,Outline=0,Shadow=0,MarginV=22'[v]" \
  -map "[v]" -map 2:a \
  -c:v libx264 -profile:v baseline -level 4.0 -pix_fmt yuv420p -crf 26 -preset medium -r 25 \
  -c:a aac -b:a 96k -shortest -movflags +faststart acceptance-compat.mp4

# 4) 旁白：逐条 TTS + 时间轴拼装 + 混流（见 narrate.py 头注释）
python3 narrate.py

echo '完成 → acceptance-narrated.mp4'

#!/usr/bin/env python3
"""旁白合成与混流：captions.json + edge-tts → 带中文旁白的成片。

流程：
  1) 每条字幕文本用 edge-tts 合成 mp3（voice/ 下已存在则跳过）
  2) 按 captions.json 的时间戳（+片头偏移）把「静音段 + 配音段」拼成一条时间轴
     —— 不用 adelay/amix（ffmpeg 8 上会写出非法 DTS），改用 concat 确定性拼装
  3) 混流进视频（视频流直接 copy，音频 AAC 96k）

用法: python3 narrate.py [--video acceptance-compat.mp4] [--out acceptance-narrated.mp4]
"""
import argparse
import json
import os
import subprocess
import sys

OFF = 6.0    # 片头卡时长（秒），与 build-video.sh 一致
LEAD = 0.3   # 旁白相对字幕出现的提前量
SR = 24000   # 采样率
INTRO_AT = 0.8
INTRO_TEXT = '宠物 AI 健康管理平台，验收演示。三端全功能实操，与关键要点讲解。'


def dur(f):
    out = subprocess.run(
        ['ffprobe', '-v', 'error', '-show_entries', 'format=duration',
         '-of', 'csv=p=0', f], capture_output=True, text=True, check=True)
    return float(out.stdout.strip())


def synth(text, path, voice):
    if os.path.exists(path) and os.path.getsize(path) > 1000:
        return
    subprocess.run(['edge-tts', '--voice', voice, '--rate', '+8%',
                    '--text', text, '--write-media', path], check=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--captions', default='captions.json')
    ap.add_argument('--video', default='acceptance-compat.mp4')
    ap.add_argument('--out', default='acceptance-narrated.mp4')
    ap.add_argument('--voice', default='zh-CN-XiaoxiaoNeural')
    args = ap.parse_args()

    caps = json.load(open(args.captions, encoding='utf-8'))
    video_dur = dur(args.video)
    os.makedirs('voice', exist_ok=True)

    items = [('intro', INTRO_AT, INTRO_TEXT)]
    for i, c in enumerate(caps):
        items.append((f'c{i:02d}', c['t'] + OFF + LEAD, c['text']))

    for name, _, text in items:
        synth(text, f'voice/{name}.mp3', args.voice)

    # 时间轴：cursor 依次走「静音段 → 配音段」
    plan, cursor = [], 0.0
    for idx, (name, start, _) in enumerate(items):
        gap = max(start - cursor, 0.0)
        if gap > 0.05:
            plan.append(('gap', gap, gap))
        d = dur(f'voice/{name}.mp3')
        nxt = items[idx + 1][1] if idx + 1 < len(items) else video_dur
        avail = max(nxt - start - 0.2, 1.0)
        tempo = min(d / avail, 1.35) if d > avail else 1.0
        eff = d / tempo
        plan.append(('clip', (idx, tempo), eff))
        cursor = start + eff
    tail = video_dur - cursor
    if tail > 0.05:
        plan.append(('gap', tail, tail))

    segs, pre = [], []
    for kind, payload, _ in plan:
        if kind == 'gap':
            g = f'g{len(segs)}'
            segs.append(f'[{g}]')
            pre.append(f'anullsrc=r={SR}:cl=mono,atrim=duration={payload:.3f},'
                       f'aformat=sample_fmts=fltp:channel_layouts=mono[{g}]')
        else:
            idx, tempo = payload
            t = f'atempo={tempo:.3f},' if tempo != 1.0 else ''
            segs.append(f'[c{idx}]')
            pre.append(f'[{idx}:a]{t}aresample={SR},'
                       f'aformat=sample_fmts=fltp:channel_layouts=mono[c{idx}]')
    fc = ';'.join(pre) + ';' + ''.join(segs) + f'concat=n={len(segs)}:v=0:a=1[out]'
    cmd = (['ffmpeg', '-y', '-hide_banner', '-loglevel', 'error']
           + sum([['-i', f'voice/{n}.mp3'] for n, _, _ in items], [])
           + ['-filter_complex', fc, '-map', '[out]',
              '-c:a', 'aac', '-b:a', '96k', 'narration.m4a'])
    subprocess.run(cmd, check=True)

    subprocess.run(
        ['ffmpeg', '-y', '-hide_banner', '-loglevel', 'error',
         '-i', args.video, '-i', 'narration.m4a',
         '-map', '0:v', '-map', '1:a', '-c:v', 'copy', '-c:a', 'aac', '-b:a', '96k',
         '-shortest', '-movflags', '+faststart', args.out], check=True)

    vol = subprocess.run(
        ['ffmpeg', '-i', args.out, '-af', 'volumedetect', '-f', 'null', '-'],
        capture_output=True, text=True).stderr
    mean = [l.split(']')[-1].strip() for l in vol.splitlines() if 'mean_volume' in l]
    print(f'旁白轨 {dur("narration.m4a"):.1f}s → 成片 {dur(args.out):.1f}s | '
          f'{mean[0] if mean else "no audio"} | '
          f'{os.path.getsize(args.out) / 1048576:.1f} MB')


if __name__ == '__main__':
    sys.exit(main())

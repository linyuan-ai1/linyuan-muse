"""生成 App 自带提示音（WAV，无版权问题、体积小、专为通知设计）。

输出到 app/src/main/res/raw/，每个文件都是短促、悦耳、辨识度高的提示音。
设计原则：
  · 时长 0.4~1.2 秒（太长会烦人）
  · 采样率 22050Hz、16bit 单声道（体积小，兼容性好）
  · 用正弦波 + 包络（快速 attack、指数衰减），避免爆音
  · 每个音效用不同音程/音色，一听就能分辨
"""
import math
import os
import struct
import wave

SR = 22050
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   "app", "src", "main", "res", "raw")


def env_at(t, total, attack=0.01, release=0.35):
    """线性 attack + 指数 release 包络，避免爆音。"""
    if t < attack:
        return t / attack
    rel = (t - attack) / max(1e-6, release)
    return math.exp(-4.0 * rel) if rel > 0 else 1.0


def sine(freq, dur, amp=0.5, sr=SR):
    n = int(sr * dur)
    out = []
    for i in range(n):
        t = i / sr
        # 加一点二次谐波让音色更"亮"但不刺耳
        v = math.sin(2 * math.pi * freq * t) * 0.8 + math.sin(4 * math.pi * freq * t) * 0.2
        out.append(v * amp * env_at(t, dur))
    return out


def seq(parts):
    """把若干 (freq, dur, amp) 串起来，段间做淡入淡出防咔哒声。"""
    samples = []
    for freq, dur, amp in parts:
        samples.extend(sine(freq, dur, amp))
    # 首尾各 8ms 淡入淡出
    k = int(SR * 0.008)
    for i in range(min(k, len(samples))):
        f = i / k
        samples[i] *= f
        samples[-1 - i] *= f
    return samples


def note_reasonix(freq, amp):
    """Reasonix 风格的单音：基频 + 4 倍频泛音(12%)，2ms 起音 + 指数衰减到 0.001。

    照抄自 esengine/DeepSeek-Reasonix 的 playSynthNote()（Web Audio 实现），
    这里用同样的参数生成 PCM。
    """
    attack = 0.002          # 2ms 线性起音
    decay_to = 0.001        # 指数衰减目标
    n = int(0.6 * SR)       # 单音最长 0.6s（实际由调用方裁剪）
    out = []
    for i in range(n):
        t = i / SR
        if t < attack:
            env = t / attack
        else:
            # 指数衰减：从 1 衰减到 decay_to
            k = 3.0
            env = math.exp(-k * (t - attack) / 0.35)
            if env < decay_to:
                env = 0.0
        v = math.sin(2 * math.pi * freq * t) + 0.12 * math.sin(2 * math.pi * 4 * freq * t)
        out.append(v * amp * env)
    return out


def reasonix_chord(notes, total_dur):
    """把若干带起始偏移的音叠加成一段（notes = [(freq, offset, dur, gain), ...]）。"""
    n = int(SR * total_dur)
    buf = [0.0] * n
    for freq, off, dur, gain in notes:
        tone = note_reasonix(freq, gain)
        start = int(off * SR)
        end = min(n, start + int(dur * SR))
        for i in range(start, end):
            buf[i] += tone[i - start]
    # 归一化防止叠加溢出
    peak = max(1e-9, max(abs(x) for x in buf))
    if peak > 0.95:
        buf = [x * 0.95 / peak for x in buf]
    # 首尾淡入淡出
    k = int(SR * 0.005)
    for i in range(min(k, n)):
        f = i / k
        buf[i] *= f
        buf[-1 - i] *= f
    return buf


def write_wav(name, samples):
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name)
    data = b"".join(struct.pack("<h", max(-32767, min(32767, int(s * 32767)))) for s in samples)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(data)
    print(f"  {name:32} {len(samples)/SR:.2f}s  {len(data)/1024:.1f}KB")


# 音名对照（Hz）：C5=523.25 D5=587.33 E5=659.25 G5=783.99 A5=880 C6=1046.5 E6=1318.5
C5, D5, E5, G5, A5, C6, E6 = 523.25, 587.33, 659.25, 783.99, 880.0, 1046.5, 1318.5

print("生成自带提示音：")

# ① 叮（单音，最简洁——适合单聊回复）
write_wav("tone_ding.wav", seq([(A5, 0.55, 0.5)]))

# ② 双音上行（愉快、清晰——适合单聊）
write_wav("tone_dingdong.wav", seq([(E5, 0.18, 0.5), (C6, 0.45, 0.45)]))

# ③ 三音上行（明显、略正式——适合群聊结束）
write_wav("tone_chime3.wav", seq([(C5, 0.14, 0.45), (E5, 0.14, 0.45), (G5, 0.42, 0.45)]))

# ④ 清脆短促（"啵"一声，轻量——适合不想被打扰时）
write_wav("tone_pop.wav", seq([(C6, 0.12, 0.55), (G5, 0.16, 0.35)]))

# ⑤ 柔和音符（低沉温柔——适合夜间/安静场景）
write_wav("tone_soft.wav", seq([(C5, 0.30, 0.38), (G5, 0.55, 0.30)]))

# ⑥ 完成音（上行四音，明确"事情做完了"——适合群聊讨论结束）
write_wav("tone_done.wav", seq([(C5, 0.11, 0.42), (E5, 0.11, 0.42), (G5, 0.11, 0.42), (C6, 0.40, 0.45)]))

# ⑦ 提醒音（两短一长，有"请看这里"的意味）
write_wav("tone_alert.wav", seq([(A5, 0.10, 0.45), (A5, 0.10, 0.45), (E6, 0.40, 0.42)]))

# ⑧ 淡定单音（最低调，几乎不打扰）
write_wav("tone_minimal.wav", seq([(G5, 0.22, 0.32)]))

print(f"\n输出目录: {OUT}")

# ==================== Reasonix 风格提示音（照抄它的音高设计）====================
# 用户指定参考项目：esengine/DeepSeek-Reasonix（35k stars）
#   · 成功音：上行三音琶音 E6 → G6 → C7（就是用户说的"噔噔噔"）
#   · 提醒音：下行两音 A6 → E6
# 参数（频率/偏移/时长/增益）逐项照抄它的 playSuccessChime / playAttentionChime。

E6_F, G6_F, C7_F = 1318.5, 1568.0, 2093.0
A6_F = 1760.0

print("\nReasonix 风格（照抄其音高与包络）：")

# 成功音：E6@0 → G6@0.07 → C7@0.14（三音上行，"噔噔噔"）
write_wav("tone_rx_success.wav", reasonix_chord([
    (E6_F, 0.00, 0.20, 0.35),
    (G6_F, 0.07, 0.22, 0.30),
    (C7_F, 0.14, 0.30, 0.24),
], 0.50))

# 提醒音：A6@0 → E6@0.09（两音下行）
write_wav("tone_rx_attention.wav", reasonix_chord([
    (A6_F, 0.00, 0.14, 0.40),
    (E6_F, 0.09, 0.22, 0.34),
], 0.35))

# 工具调用：轻量单音（不同于上面，一听就知道"在干活"）
write_wav("tone_rx_tool.wav", reasonix_chord([
    (C7_F, 0.00, 0.10, 0.30),
], 0.14))

# 思考中：极轻短音（频繁触发用，必须最不打扰）
write_wav("tone_rx_think.wav", reasonix_chord([
    (E6_F, 0.00, 0.07, 0.22),
], 0.10))

print("\n" + "=" * 60)
print("Reasonix 参考对照（来自其 sound.ts）：")
print("  成功音 = E6(1318.5Hz) → G6(1568Hz) → C7(2093Hz)，间隔 70ms")
print("  提醒音 = A6(1760Hz) → E6(1318.5Hz)，间隔 90ms")
print("  音色   = 基频 + 4×泛音(12%)，2ms 起音 + 指数衰减")

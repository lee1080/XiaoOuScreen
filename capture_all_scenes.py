#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
小欧屏 (XiaoOuScreen) - 自动截取 4 时段 × 7 天气 = 28 种拟真全景效果图
"""

import os
import sys
import time
import subprocess
from pathlib import Path
from PIL import Image

PROJECT_ROOT = Path(__file__).parent.resolve()
OUTPUT_DIR = PROJECT_ROOT / "docs" / "images" / "scenes"
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

TIMES = [
    (0, "dawn", "晨曦 (Dawn)"),
    (1, "day", "白昼 (Day)"),
    (2, "dusk", "黄昏 (Dusk)"),
    (3, "night", "夜晚 (Night)"),
]

WEATHERS = [
    (0, False, "clear", "晴朗 (Clear)"),
    (1, False, "cloudy", "多云 (Cloudy)"),
    (2, False, "overcast", "阴天 (Overcast)"),
    (3, False, "rain", "雨天 (Rain)"),
    (4, False, "snow", "雪天 (Snow)"),
    (5, False, "storm", "雷暴 (Storm)"),
    (0, True,  "sleet", "雨夹雪 (Sleet)"),
]

def ensure_subscreen_running():
    print("📱 正在确保 CarSubscreenActivity 处于前台全屏...")
    subprocess.run(["adb", "shell", "am", "start", "-n", "com.car.xiaoouscreen/.ui.CarSubscreenActivity"], check=True)
    time.sleep(2.0)

def capture_scenes():
    ensure_subscreen_running()
    total = len(TIMES) * len(WEATHERS)
    current = 0
    print(f"🎬 开始逐一截取 {total} 种时间和气象组合全景...")

    for t_idx, t_name, t_label in TIMES:
        for w_idx, is_sleet, w_name, w_label in WEATHERS:
            current += 1
            filename = f"scene_{t_name}_{w_name}.png"
            dest_path = OUTPUT_DIR / filename
            print(f"[{current}/{total}] 模拟并截取: {t_label} - {w_label} -> {filename}...")

            # 发送广播
            cmd = [
                "adb", "shell", "am", "broadcast",
                "-a", "com.car.xiaoouscreen.SIMULATE_CUSTOM",
                "--ei", "extra_time_of_day", str(t_idx),
                "--ei", "extra_weather_state", str(w_idx),
                "--ez", "extra_is_sleet", "true" if is_sleet else "false",
                "--ef", "extra_wind_speed", "14.0"
            ]
            subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            
            # 等待 1.5s 动画过渡稳定 (过渡动画为 1.2s)
            time.sleep(1.6)

            # 截图
            sdcard_tmp = "/sdcard/snap_subscreen.png"
            local_tmp = "/tmp/snap_subscreen.png"
            subprocess.run(["adb", "shell", "screencap", "-p", sdcard_tmp], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            subprocess.run(["adb", "pull", sdcard_tmp, local_tmp], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

            # 裁剪与尺寸归一化 (1920 × 384)
            with Image.open(local_tmp) as im:
                # 2560x1600 屏幕上，比例 5.0，居中区域为 y=544 至 y=1056
                cropped = im.crop((0, 544, 2560, 1056)).resize((1920, 384), Image.Resampling.LANCZOS)
                cropped.save(dest_path, optimize=True)

    print(f"\n🎉 全部 {total} 种时间天气全景截图已保存至: {OUTPUT_DIR.resolve()}")

if __name__ == "__main__":
    capture_scenes()

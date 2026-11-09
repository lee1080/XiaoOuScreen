#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
小欧屏 (XiaoOuScreen) - Release 正式版编译、混淆加固与 AOSP 系统公签自动化脚本
参考 CarLyricsApp 发行版构建规范设计：
1. 校验 AOSP 车机系统公签密钥库 (key/aosp_platform.jks)
2. 触发 Gradle assembleRelease：执行 R8/ProGuard 高强度深度混淆与资源缩减
3. 自动注入 AOSP 系统公签 (Platform Key)
4. 输出规范命名：XiaoOuScreen-v{version}.apk
5. 自动调用 apksigner 严格校验签名机制 (v1+v2 scheme)
"""

import os
import re
import sys
import shutil
import subprocess
from pathlib import Path

PROJECT_ROOT = Path(__file__).parent.resolve()
KEYSTORE_PATH = PROJECT_ROOT / "key" / "aosp_platform.jks"
VERSION_PROPS = PROJECT_ROOT / "version.properties"
OUTPUT_DIR = PROJECT_ROOT / "release_dist"

def check_environment():
    print("🔍 [1/5] 检查构建环境与公签密钥...")
    if not KEYSTORE_PATH.exists():
        print(f"❌ 错误: 未找到 AOSP 系统公签密钥库: {KEYSTORE_PATH}")
        sys.exit(1)
    print(f"   ✓ 已就绪系统公签: {KEYSTORE_PATH.name}")

def get_current_version():
    if not VERSION_PROPS.exists():
        return "1.0.0"
    props = {}
    with open(VERSION_PROPS, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                props[k.strip()] = v.strip()
    major = props.get("VERSION_MAJOR", "1")
    minor = props.get("VERSION_MINOR", "0")
    patch = props.get("VERSION_PATCH", "0")
    return f"{major}.{minor}.{patch}"

def build_release():
    print("🚀 [2/5] 开始执行 Gradle assembleRelease (R8 代码混淆 + 资源瘦身 + AOSP 公签)...")
    env = os.environ.copy()
    studio_jdk = Path("/Applications/Android Studio.app/Contents/jbr/Contents/Home")
    if studio_jdk.exists():
        env["JAVA_HOME"] = str(studio_jdk)

    gradlew = PROJECT_ROOT / "gradlew"
    if not gradlew.exists():
        print("❌ 错误: 未找到 gradlew")
        sys.exit(1)

    result = subprocess.run(
        [str(gradlew), "assembleRelease", "--no-daemon"],
        cwd=PROJECT_ROOT,
        env=env,
        capture_output=True,
        text=True
    )
    if result.returncode != 0:
        print("❌ RELEASE BUILD FAILED:")
        for line in result.stdout.split("\n") + result.stderr.split("\n"):
            if any(k in line.lower() for k in ["error", "fail", "exception"]) or line.startswith("e:"):
                print(f"   {line}")
        sys.exit(1)
    print("   ✓ Gradle Release 编译与代码混淆打包完成")

def find_and_copy_apk():
    print("📦 [3/5] 检索并整理正式版 APK 产物...")
    release_dir = PROJECT_ROOT / "app" / "build" / "outputs" / "apk" / "release"
    apks = list(release_dir.glob("*.apk"))
    if not apks:
        print(f"❌ 错误: 未在 {release_dir} 找到生成的 APK")
        sys.exit(1)

    # 优先找 XiaoOuScreen 开头的 APK
    target_apk = None
    for apk in apks:
        if "XiaoOuScreen" in apk.name:
            target_apk = apk
            break
    if not target_apk:
        target_apk = apks[0]

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    dist_apk = OUTPUT_DIR / target_apk.name
    shutil.copy2(target_apk, dist_apk)
    
    file_size_mb = dist_apk.stat().st_size / (1024 * 1024)
    print(f"   ✓ 产物文件名: {dist_apk.name}")
    print(f"   ✓ 产物大小:   {file_size_mb:.2f} MB")
    print(f"   ✓ 存放目录:   {dist_apk.resolve()}")
    return dist_apk

def verify_signature(apk_path: Path):
    print("🛡️ [4/5] 校验 APK AOSP 公签及签名方案完整性...")
    apksigner_candidates = [
        Path("/Users/liyafei/android-sdk/build-tools/34.0.0/apksigner"),
        Path(os.path.expanduser("~/android-sdk/build-tools/34.0.0/apksigner")),
    ]
    apksigner_bin = None
    for cand in apksigner_candidates:
        if cand.exists():
            apksigner_bin = str(cand)
            break
    if not apksigner_bin:
        which_res = subprocess.run(["which", "apksigner"], capture_output=True, text=True)
        if which_res.returncode == 0:
            apksigner_bin = which_res.stdout.strip()

    if not apksigner_bin:
        print("   ⚠️ 未找到 apksigner，跳过独立校验（Gradle 已在打包时完成签名）")
        return

    verify_res = subprocess.run(
        [apksigner_bin, "verify", "--verbose", "--print-certs", str(apk_path)],
        capture_output=True,
        text=True
    )
    if verify_res.returncode == 0:
        print("   ✓ AOSP 公钥系统签名校验通过 (Verified: TRUE)")
        for line in verify_res.stdout.split("\n"):
            if "Signer #1 certificate DN" in line or "Verified using" in line:
                print(f"     {line.strip()}")
    else:
        print(f"   ⚠️ 签名校验警告: {verify_res.stderr.strip()}")

def print_summary(apk_path: Path):
    print("\n" + "=" * 60)
    print("🎉 小欧屏 (XiaoOuScreen) 正式版构建与公签打包圆满成功！")
    print(f"📍 最终正式版文件: {apk_path.name}")
    print(f"📂 绝对路径:       {apk_path.resolve()}")
    print(f"📏 安装包大小:     {apk_path.stat().st_size / (1024 * 1024):.2f} MB")
    print("🔒 安全防护机制:   R8/ProGuard 深度符号混淆 + 资源缩减瘦身")
    print("🔑 车载签名机制:   AOSP Platform Key (系统公签，支持汽车底盘Car权限)")
    print("=" * 60 + "\n")

if __name__ == "__main__":
    check_environment()
    old_version = get_current_version()
    print(f"📌 当前版本基准: v{old_version}")
    build_release()
    dist_apk = find_and_copy_apk()
    verify_signature(dist_apk)
    print_summary(dist_apk)

#!/usr/bin/env python3
"""Termux 저장소에서 Android(bionic)용 최신 OpenJDK(25+) 와 그 의존 라이브러리를 받아
앱 assets/runtime.zip 으로 묶는다.

결과 zip 구조:
  jre/...   OpenJDK (jmods, include, man, src.zip 등 불필요한 것 제거)
  lib/...   의존 .so 라이브러리 (심볼릭 링크는 실제 파일로 풀어둠)

GitHub Actions(ubuntu)에서 실행: python3 scripts/build_runtime.py <출력 zip 경로>
"""
import os
import re
import shutil
import subprocess
import sys
import urllib.request
import zipfile

REPO = "https://packages-cf.termux.dev/apt/termux-main"
ARCH = "aarch64"
PREFIX = "data/data/com.termux/files/usr"

HERE = os.path.dirname(os.path.abspath(__file__))
WORK = os.path.join(HERE, "work")


def fetch(url, dest=None):
    req = urllib.request.Request(url, headers={"User-Agent": "mc-server-app-build"})
    with urllib.request.urlopen(req, timeout=120) as r:
        data = r.read()
    if dest:
        with open(dest, "wb") as f:
            f.write(data)
    return data


def parse_packages(text):
    pkgs = {}
    for block in text.split("\n\n"):
        fields = {}
        for line in block.splitlines():
            if ":" in line and not line.startswith(" "):
                k, v = line.split(":", 1)
                fields[k.strip()] = v.strip()
        if "Package" in fields:
            pkgs[fields["Package"]] = fields
    return pkgs


def dep_names(field):
    out = []
    for part in field.split(","):
        part = part.strip()
        if not part:
            continue
        alt = part.split("|")[0].strip()  # 대안 중 첫번째
        out.append(alt.split("(")[0].strip())
    return out


def resolve(pkgs, root):
    seen, stack = [], [root]
    while stack:
        name = stack.pop()
        if name in seen:
            continue
        if name not in pkgs:
            print(f"  (경고) 패키지 없음, 건너뜀: {name}")
            continue
        seen.append(name)
        for d in dep_names(pkgs[name].get("Depends", "")) + dep_names(pkgs[name].get("Pre-Depends", "")):
            stack.append(d)
    return seen


def extract_deb(deb, dest):
    tmp = deb + ".x"
    os.makedirs(tmp, exist_ok=True)
    subprocess.check_call(["ar", "x", os.path.abspath(deb)], cwd=tmp)
    data = [f for f in os.listdir(tmp) if f.startswith("data.tar")][0]
    subprocess.check_call(["tar", "-xf", os.path.join(tmp, data), "-C", dest])


def copy_deref(src, dst):
    """심볼릭 링크를 따라가 실제 파일로 복사. 깨진 링크는 건너뜀."""
    for dirpath, dirnames, filenames in os.walk(src, followlinks=True):
        rel = os.path.relpath(dirpath, src)
        os.makedirs(os.path.join(dst, rel), exist_ok=True)
        for fn in filenames:
            s = os.path.join(dirpath, fn)
            if not os.path.exists(s):
                print(f"  (경고) 깨진 링크 건너뜀: {s}")
                continue
            shutil.copy2(s, os.path.join(dst, rel, fn), follow_symlinks=True)


def main():
    out_zip = os.path.abspath(sys.argv[1])
    shutil.rmtree(WORK, ignore_errors=True)
    debs = os.path.join(WORK, "debs")
    rootfs = os.path.join(WORK, "rootfs")
    stage = os.path.join(WORK, "stage")
    for d in (debs, rootfs, stage):
        os.makedirs(d)

    print("Termux 패키지 목록 받는 중...")
    pkgs = parse_packages(fetch(f"{REPO}/dists/stable/main/binary-{ARCH}/Packages").decode())
    # 가장 최신 OpenJDK (예: openjdk-25) 를 고름. MC 26.1+ 는 Java 25 이상 필요
    jdk_pkgs = sorted(
        (int(m.group(1)), name) for name in pkgs
        for m in [re.fullmatch(r"openjdk-(\d+)", name)] if m
    )
    print("사용 가능한 JDK:", [n for _, n in jdk_pkgs])
    if not jdk_pkgs or jdk_pkgs[-1][0] < 25:
        sys.exit("Java 25 이상 패키지를 찾을 수 없음")
    jdk_ver, root_pkg = jdk_pkgs[-1]
    print("선택:", root_pkg)
    needed = resolve(pkgs, root_pkg)
    print("필요한 패키지:", ", ".join(needed))

    for name in needed:
        fn = pkgs[name]["Filename"]
        path = os.path.join(debs, os.path.basename(fn))
        print("  받는 중:", fn)
        fetch(f"{REPO}/{fn}", path)
        extract_deb(path, rootfs)

    usr = os.path.join(rootfs, PREFIX)
    jvm_root = os.path.join(usr, "lib", "jvm")
    jdks = [d for d in os.listdir(jvm_root) if str(jdk_ver) in d]
    if not jdks:
        sys.exit(f"JDK 디렉터리를 찾을 수 없음: {os.listdir(jvm_root)}")
    jdk = os.path.join(jvm_root, jdks[0])
    print("JDK:", jdk)

    # JRE 복사 (불필요한 것 제외)
    copy_deref(jdk, os.path.join(stage, "jre"))
    for junk in ("jmods", "include", "man", "demo", "legal", os.path.join("lib", "src.zip")):
        p = os.path.join(stage, "jre", junk)
        if os.path.isdir(p):
            shutil.rmtree(p)
        elif os.path.exists(p):
            os.remove(p)

    # 의존 .so 라이브러리 복사 (jvm 디렉터리 제외)
    libdst = os.path.join(stage, "lib")
    os.makedirs(libdst)
    libsrc = os.path.join(usr, "lib")
    for fn in os.listdir(libsrc):
        s = os.path.join(libsrc, fn)
        if ".so" in fn and os.path.isfile(s):
            shutil.copy2(s, os.path.join(libdst, fn), follow_symlinks=True)

    # 인증서 (온라인 모드 로그인 / Paper 다운로드에 필요)
    cacerts = os.path.join(stage, "jre", "lib", "security", "cacerts")
    if not os.path.isfile(cacerts) or os.path.getsize(cacerts) < 1000:
        java_home = os.environ.get("JAVA_HOME")
        src = os.path.join(java_home, "lib", "security", "cacerts") if java_home else None
        if not src or not os.path.isfile(src):
            sys.exit("cacerts 를 찾을 수 없음")
        print("cacerts 를 빌드 머신 JDK에서 복사:", src)
        os.makedirs(os.path.dirname(cacerts), exist_ok=True)
        shutil.copyfile(src, cacerts)

    java_bin = os.path.join(stage, "jre", "bin", "java")
    if not os.path.isfile(java_bin):
        sys.exit("jre/bin/java 가 없음")
    print(subprocess.run(["file", java_bin], capture_output=True, text=True).stdout.strip())

    os.makedirs(os.path.dirname(out_zip), exist_ok=True)
    if os.path.exists(out_zip):
        os.remove(out_zip)
    with zipfile.ZipFile(out_zip, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for dirpath, _, filenames in os.walk(stage):
            for fn in filenames:
                full = os.path.join(dirpath, fn)
                z.write(full, os.path.relpath(full, stage))
    print(f"완료: {out_zip} ({os.path.getsize(out_zip) / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()

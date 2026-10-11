#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
make-posters.py —— 给缺封面的存量视频补首帧 poster，并回填到站点内容 JSON。

  · 封面 = 视频 0.6s 处抽一帧，缩到宽 960（卡片展示足够），JPEG 质量 4
  · 命名 <原文件名>-poster.jpg，与视频同目录，走 Nginx /media/ 直出
  · 内容里所有 kind=video 且 src 指向它、但没有 poster 的条目一起补上
  · 原视频文件一个字节都不动；已存在封面文件的一律复用
  · 回写用 base64 包一层，避免 JSON 里的引号/换行把 SQL 搞坏

用法（root）：
  python3 /opt/portfolio/scripts/make-posters.py            # 实际执行
  python3 /opt/portfolio/scripts/make-posters.py --dry-run  # 只看计划不动数据
"""
import base64
import json
import os
import subprocess
import sys

ENV_FILE = "/opt/portfolio/deploy/portfolio.env"
UPLOAD_DIR = "/var/lib/portfolio/upload"   # 与 APP_UPLOAD_DIR 一致
DB_NAME_KEY = "MYSQL_DB"
DB_USER_KEY = "MYSQL_USER"
DB_PASS_KEY = "MYSQL_PASSWORD"
DRY_RUN = "--dry-run" in sys.argv


def read_env(path):
    env = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip().strip('"').strip("'")
    return env


def mysql(env, sql, binary=False):
    cmd = ["mysql", "-u" + env[DB_USER_KEY], "-p" + env[DB_PASS_KEY], env[DB_NAME_KEY], "-N", "-B"]
    if binary:
        # --raw 关掉批量模式的转义（\n、\\ 等），否则读回来的 JSON 会变形解析不了；
        # payload 是单行 JSON，没有真实换行字节，raw 模式安全
        cmd.append("--raw")
    p = subprocess.run(cmd, input=sql.encode("utf-8"), capture_output=True)
    if p.returncode != 0:
        raise RuntimeError(p.stderr.decode("utf-8", "replace"))
    return p.stdout if binary else p.stdout.decode("utf-8")


def poster_path_for(video_abs):
    """400c7342.mp4 -> 400c7342-poster.jpg（同目录）"""
    root, _ = os.path.splitext(video_abs)
    return root + "-poster.jpg"


def make_poster(video_abs, poster_abs):
    """0.6s 处抽一帧；宽缩到 960 以内。失败返回 False，绝不动原视频。"""
    tmp = poster_abs + ".tmp.jpg"
    cmd = [
        "ffmpeg", "-y", "-loglevel", "error",
        "-ss", "0.6", "-i", video_abs,
        "-frames:v", "1", "-vf", "scale='min(960,iw)':-2",
        "-q:v", "4", tmp,
    ]
    try:
        subprocess.run(cmd, check=True, timeout=120)
        os.replace(tmp, poster_abs)
        return True
    except Exception as e:
        print("  !! ffmpeg 失败:", e)
        try:
            os.unlink(tmp)
        except OSError:
            pass
        return False


def walk_fill(node, made, stats):
    """递归找 kind=video 的条目，补 poster 字段。返回是否有改动。"""
    changed = False
    if isinstance(node, dict):
        if node.get("kind") == "video":
            src = node.get("src") or ""
            if src.startswith("/media/") and not node.get("poster"):
                rel = src[len("/media/"):]
                video_abs = os.path.join(UPLOAD_DIR, rel)
                poster_abs = poster_path_for(video_abs)
                poster_rel = os.path.relpath(poster_abs, UPLOAD_DIR)
                if os.path.isfile(video_abs):
                    if os.path.isfile(poster_abs):
                        made.add(poster_rel)  # 已有封面，直接复用
                    elif poster_rel not in made:
                        print("  抽帧:", rel)
                        made.add(poster_rel)
                        if not DRY_RUN:
                            if not make_poster(video_abs, poster_abs):
                                made.discard(poster_rel)
                    # dry-run 时文件还没真正生成，但同样把引用补上，方便预览改动范围
                    if DRY_RUN or os.path.isfile(poster_abs):
                        node["poster"] = "/media/" + poster_rel.replace(os.sep, "/")
                        stats["filled"] = stats.get("filled", 0) + 1
                        changed = True
                else:
                    print("  !! 视频文件不存在，跳过:", rel)
        for v in node.values():
            changed = walk_fill(v, made, stats) or changed
    elif isinstance(node, list):
        for v in node:
            changed = walk_fill(v, made, stats) or changed
    return changed


def main():
    env = read_env(ENV_FILE)
    row = mysql(env, "SELECT id FROM site_content ORDER BY id LIMIT 1").strip()
    if not row:
        print("site_content 里没有内容")
        return
    cid = row.split("\t")[0]
    raw = mysql(env, "SELECT payload FROM site_content WHERE id=%s" % cid, binary=True)
    data = json.loads(raw.decode("utf-8").rstrip("\n"))

    print("扫描内容（%s）..." % ("dry-run" if DRY_RUN else "执行"))
    made = set()
    stats = {}
    changed = walk_fill(data, made, stats)

    if not changed:
        print("所有视频都已有封面，无事可做。")
        return

    print("补齐 %d 处 poster 引用，生成/复用 %d 个封面文件" % (
        stats.get("filled", 0), len(made)))

    if DRY_RUN:
        print("dry-run，不回写数据库。")
        return

    payload = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    b64 = base64.b64encode(payload.encode("utf-8")).decode("ascii")
    mysql(env, "UPDATE site_content SET payload = CONVERT(FROM_BASE64('%s') USING utf8mb4) "
               "WHERE id = %s" % (b64, cid))
    print("已回写 site_content id=%s。" % cid)
    print("提醒：内容有 Redis 缓存，执行下面这行立刻生效 ——")
    print("  redis-cli -a <密码> --scan --pattern '%scontent:*' | xargs -r redis-cli -a <密码> del"
          % env.get("APP_KEY_PREFIX", "Cyan:"))


if __name__ == "__main__":
    main()

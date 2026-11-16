#!/usr/bin/env python3
"""Generate a PATCH body that fills only EMPTY Guizhan project profile fields.

Usage: REPO_URL=<url> python3 guizhan_project_profile.py <project-json> > patch.json
Prints the JSON patch to stdout and a short field list to stderr.
Existing (non-empty) values are never overwritten, so reruns are harmless.
"""
import json
import os
import sys

with open(sys.argv[1], encoding="utf-8") as f:
    data = json.load(f).get("data") or {}

repo = os.environ["REPO_URL"]


def blank(value):
    return value is None or value == "" or value == []


patch = {}

if blank(data.get("summary")):
    patch["summary"] = "Slimefun 中文社区版的 Folia 整合分支：区域化调度与线程安全，支持 Paper / Folia"

if blank(data.get("description")):
    with open("README.md", encoding="utf-8") as f:
        readme = f.read()
    patch["description"] = (
        "## SlimefunCE-CN · Folia 整合版\n\n"
        "基于 Slimefun4 中文社区版的 Folia 整合分支（鬼斩项目 SlimefunCE-Folia），"
        "支持 Paper / Folia 与 Minecraft 1.21.2 ~ 26.3。\n\n"
        f"源码与完整改动说明：{repo}\n\n---\n\n" + readme
    )

if blank(data.get("license_type")):
    patch["license_type"] = "GPL-3.0"

for key, value in (
    ("homepage_url", repo),
    ("source_url", repo),
    ("issues_url", repo + "/issues"),
    ("wiki_url", "https://slimefun-wiki.guizhanss.cn/"),
):
    if blank(data.get(key)):
        patch[key] = value

if blank(data.get("tags")):
    patch["tags"] = ["slimefun"]

print("待补全字段：" + (", ".join(patch) if patch else "无（资料已完整）"), file=sys.stderr)
print(json.dumps(patch, ensure_ascii=False))

"""Small, read-only bridge from the bundled STAR-CCM+ skills to the Java macro.

The two supplied skill trees are reference data. Their instructions are never run.
Output is one UTF-8 JSON object for CodexAssistant.java.
"""

from __future__ import annotations

import importlib.util
import json
import re
import sys
from functools import lru_cache
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HUB = ROOT / "star-ccm-skill" / "skills" / "star-ccm"
EXPERT = ROOT / "star-ccm-expert" / "star-ccm-expert"
LOOKUP_FILE = HUB / "scripts" / "starccm_doc_lookup.py"

DOMAINS = {
    "multiphase": ("vof", "多相", "欧拉", "拉格朗日", "颗粒", "液膜", "空化", "沸腾", "自由液面"),
    "turbulence": ("湍流", "k-e", "k-ε", "k-w", "k-ω", "sst", "rans", "les", "des", "y+", "壁面函数"),
    # “温度” is present in most compressible-flow boundary questions. It is
    # not enough evidence that radiation or heat-transfer guidance is wanted.
    "heat-radiation": ("传热", "辐射", "热通量", "共轭", "换热", "热阻", "发射率"),
    "flow-solvers": ("求解器", "收敛", "压力基", "耦合流", "分离流", "残差", "时间步长"),
    "preprocessing": ("网格", "几何", "表面修复", "区域", "棱柱层"),
    "postprocessing": ("后处理", "云图", "报告", "监视器", "绘图", "场景"),
    "field-functions": ("场函数", "表达式", "量纲"),
    "motion-mesh-dynamics": ("运动网格", "重叠网格", "旋转", "六自由度", "mrf", "dfbi"),
    "reacting-combustion": ("燃烧", "反应流", "化学动力学", "组分"),
    "solid-stress-fsi": ("流固耦合", "fsi", "固体应力"),
    "electro-battery": ("电池", "电磁", "电化学"),
    "acoustics": ("声学", "噪声", "fw-h"),
    "automation": ("宏", "自动化", "批处理", "java api"),
    "design-manager": ("设计管理器", "doe", "优化"),
    "cae-interop": ("abaqus", "nastran", "amesim", "耦合软件"),
}
FILLER = re.compile(
    r"(请问|请帮我|帮我|告诉我|如何|怎么|怎样|为什么|在哪里|在哪|是否|能否|可以|"
    r"设置|配置|修改|调整|选择|使用|步骤|流程|方法|模型|功能|参数|仿真|模拟|教程|"
    r"star.?ccm\+?|sim文件|目标工况|当前请求|上一轮用户需求|上一轮回答|当前仿真|"
    r"是什么|有什么|有哪些|需要|的|了|呢|吗|一下)", re.I
)
TECHNICAL_TERMS = {
    "高超声速": "自由流", "远场": "自由流",
    "自由来流": "自由流", "静温": "静态温度", "总压": "总压力",
    "mach": "自由流", "马赫": "自由流", "参考压力": "参考压力",
    "表压": "参考压力", "绝对压力": "参考压力", "sutherland": "萨瑟兰",
}
IGNORED_TERMS = {"star", "km", "ma", "当前", "请求", "回答", "模型", "工况", "进行", "按照", "上一个"}


@lru_cache(maxsize=1)
def load_lookup():
    if not LOOKUP_FILE.is_file():
        raise FileNotFoundError(f"找不到知识库检索脚本: {LOOKUP_FILE}")
    spec = importlib.util.spec_from_file_location("starccm_doc_lookup", LOOKUP_FILE)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def intent(question: str) -> str:
    lower = question.lower()
    if any(word in lower for word in ("场函数", "field function")):
        return "field"
    if any(word in lower for word in ("方程", "公式", "推导", "原理", "理论", "系数", "为什么", "为何")):
        return "theory"
    if any(word in lower for word in ("案例", "教程", "从头到尾", "完整流程")):
        return "workflow"
    if any(word in lower for word in ("在哪里", "在哪", "菜单", "节点", "位置", "找不到")):
        return "locator"
    return "setup"


def queries(question: str, lookup) -> list[str]:
    lower = question.lower()
    aliases = lookup.load("zh_aliases")
    found = [key for key in aliases if not key.startswith("_") and len(key) >= 2
             and lookup.norm(key) in lookup.norm(lower)]
    found.sort(key=len, reverse=True)
    english = re.findall(r"[A-Za-z][A-Za-z0-9+_.-]{2,25}|\b[Mm][Aa]\d+(?:\.\d+)?\b", question)
    quoted = re.findall(r"[“\"「](.{2,35}?)[”\"」]", question)
    clean = FILLER.sub(" ", question)
    chunks = re.findall(r"[\u4e00-\u9fff]{2,12}|[A-Za-z][A-Za-z0-9+_.-]{1,25}", clean)
    mapped = [value for key, value in TECHNICAL_TERMS.items() if key in lower]
    ordered = mapped + quoted + found[:4] + english[:3] + chunks[:4]
    result = []
    seen = set()
    for term in ordered:
        key = lookup.norm(term)
        if (2 <= len(key) <= 35 and key not in seen and key not in IGNORED_TERMS
                ):
            seen.add(key)
            result.append(term)
    return result[:8]


def score_doc(item: dict, terms: list[str], lookup, fields: tuple[str, ...]) -> float:
    best = 0.0
    for index, term in enumerate(terms):
        for alias_index, expanded in enumerate(lookup.expand(term)):
            score = lookup.score(expanded, *fields)
            best = max(best, score * (1 - .10 * index) * (1 - .10 * alias_index))
    return best


def select_docs(pool: list[dict], terms: list[str], lookup, limit: int = 4) -> list[dict]:
    ranked = []
    for item in pool:
        fields = (str(item.get("name") or item.get("title") or ""),
                  " > ".join(item.get("path", [])) if isinstance(item.get("path"), list)
                  else str(item.get("category", "")),
                  str(item.get("shortdesc", "")))
        score = score_doc(item, terms, lookup, fields)
        title = lookup.norm(fields[0])
        exact = [term for term in terms if lookup.norm(term) in title]
        if exact:
            score += 25 + 5 * len(exact)
            if fields[0].startswith("设置"):
                score += 12
        elif any(word in fields[1] for word in ("等离子体", "Reacting_Flow", "电磁")) and not any(
                word in " ".join(terms).lower() for word in ("等离子", "反应", "燃烧", "电磁", "plasma", "react")):
            continue
        # A weak partial word match is worse than no reference: the Java UI
        # presents these pages as evidence for the answer.
        if score >= 70:
            ranked.append((score, item))
    ranked.sort(key=lambda pair: (-pair[0], len(pair[1].get("path", [])),
                                  len(str(pair[1].get("name") or pair[1].get("title") or ""))))
    return [item for _, item in ranked[:limit]]


def unique_docs(*pools: list[dict]) -> list[dict]:
    """Combine indexes without sending the same help page twice."""
    result = []
    seen = set()
    for pool in pools:
        for item in pool:
            key = str(item.get("src") or item.get("relpath") or "")
            if not key or key in seen:
                continue
            seen.add(key)
            result.append(item)
    return result


def clip(value, length: int) -> str:
    text = re.sub(r"\s+", " ", str(value or "")).strip()
    return text[:length] + ("…" if len(text) > length else "")


def source_from_item(item: dict, kind: str) -> dict:
    fields = []
    for key, label, length in (
        ("shortdesc", "概要", 350), ("provided_by", "提供方式", 350),
        ("node_path", "节点路径", 350), ("requires", "前置要求", 450),
        ("activates", "激活", 250), ("theory", "理论", 250),
    ):
        if item.get(key):
            fields.append(f"{label}: {clip(item[key], length)}")
    tree = item.get("model_tree")
    if isinstance(tree, dict) and tree:
        fields.append("模型依赖: " + clip("；".join(f"{k}: {v}" for k, v in tree.items()), 450))
    props = item.get("prop_items")
    if isinstance(props, list) and props:
        fields.append("属性: " + clip("；".join(
            f"{p.get('n')}: {str(p.get('d', '')).split('|')[0][:130]}" for p in props[:3]), 450))
    equations = item.get("key_eqs")
    if isinstance(equations, list) and equations:
        fields.append("关键方程: " + clip("；".join(equations[:3]), 500))
    steps = item.get("steps")
    if isinstance(steps, list) and steps:
        fields.append("教程步骤: " + clip("；".join(str(s.get("t", "")) for s in steps[:8]), 650))
    for key, label in (("func", "函数名"), ("dims", "量纲"), ("units", "默认单位"),
                       ("activated_by", "激活模型"), ("desc", "说明")):
        if item.get(key):
            fields.append(f"{label}: {clip(item[key], 250)}")
    return {
        "kind": kind,
        "title": str(item.get("name") or item.get("title") or "参考条目"),
        "path": str(item.get("src") or item.get("relpath") or ""),
        "guid": str(item.get("guid") or ""),
        "text": "\n".join(fields)[:1500],
    }


def matching_domain(question: str) -> str | None:
    lower = question.lower()
    for domain, words in DOMAINS.items():
        if any(word in lower for word in words):
            return domain
    return None


def domain_notes(domain: str) -> list[dict]:
    notes = []
    hub_file = HUB / "topics" / "domains" / f"{domain}.md"
    expert_file = EXPERT / "skills" / f"star-ccm-{domain}" / "SKILL.md"
    for kind, path in (("star-ccm-skill 领域速览", hub_file),
                       ("star-ccm-expert 领域说明", expert_file)):
        if not path.is_file():
            continue
        content = path.read_text(encoding="utf-8")
        if content.startswith("---"):
            content = content.split("---", 2)[-1]
        match = re.search(r"## 二、[^\n]*\n(.*?)(?=\n## |\Z)", content, re.S)
        section = match.group(1) if match else content
        section = re.sub(r"```.*?```", "", section, flags=re.S)
        lines = [line.strip() for line in section.splitlines()
                 if line.strip() and not line.strip().startswith(("|", "---", ">"))]
        summary = clip(" ".join(lines), 550)
        if notes and summary == notes[0]["text"]:
            continue
        if summary:
            notes.append({"kind": kind, "title": domain, "path": str(path.relative_to(ROOT)),
                          "guid": "", "text": summary})
    return notes


def lookup(question: str) -> dict:
    if not LOOKUP_FILE.is_file():
        # Public releases never redistribute the historical commercial help corpus.
        fallback = Path(__file__).with_name("gostar_local_help.py")
        spec = importlib.util.spec_from_file_location("gostar_local_help", fallback)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module.lookup(question)
    module = load_lookup()
    terms = queries(question, module)
    route = intent(question)
    sources = []
    if terms:
        if route == "theory":
            pool, kind = module.load("theory_index"), "21.04 官方帮助 · 理论"
        elif route == "workflow":
            pool, kind = module.load("tutorial_index"), "21.04 官方帮助 · 教程"
        elif route == "field":
            pool, kind = module.load("field_functions"), "21.04 官方帮助 · 场函数"
            mapped = []
            for chinese, english in module.load("ff_zh").items():
                if chinese in question:
                    mapped.append(english)
            if mapped:
                hits = [entry for english in mapped for entry in pool
                        if module.norm(entry.get("name", "")) == module.norm(english)
                        or module.norm(entry.get("func", "")) == module.norm(english)]
                for item in hits[:2]:
                    sources.append(source_from_item(item, kind))
        elif route == "locator":
            matches = [row for row in module.load("gui_paths")
                       if any(module.norm(term) in module.norm(row["path"]) for term in terms)]
            matches.sort(key=lambda row: -row["hits"])
            if matches:
                sources.append({"kind": "21.04 官方帮助 · GUI 路径索引", "title": "相关路径",
                                "path": "reference/gui_paths.json", "guid": "",
                                "text": "\n".join(row["path"] for row in matches[:5])})
            pool, kind = module.load("node_index"), "21.04 官方帮助 · 设置"
        else:
            # The structured node index is useful for settings, but many essential
            # boundary pages (for example “设置自由流边界”) are only in the
            # official manifest. Searching only rows with a property card used to
            # select an unrelated page that happened to mention the same word.
            pool = unique_docs(module.load("node_index"), module.load("manifest"))
            kind = "21.04 官方帮助 · 设置"
        if route not in ("locator", "field") or not sources:
            selected = select_docs(pool, terms, module)
            if route == "setup" and "vof" in question.lower() and "波" not in question:
                core = next((row for row in pool
                             if row.get("name", "").startswith("流体体积 (VOF) 多相模型参考")), None)
                if core is not None:
                    selected = [core] + [row for row in selected if row is not core
                                         and "波" not in row.get("name", "")][:1]
            for item in selected:
                source = source_from_item(item, kind)
                if source["path"] not in {row["path"] for row in sources}:
                    sources.append(source)
        if not sources and route != "field":
            for item in select_docs(module.load("manifest"), terms, module, 1):
                sources.append(source_from_item(item, "21.04 官方帮助 · 目录"))
    domain = matching_domain(question)
    if domain:
        sources.extend(domain_notes(domain))
    sources = sources[:4]
    # Read the actual local help pages, not just the searchable index cards.
    # Preserve complete paragraphs around matches, and identify omitted text.
    for source in sources:
        path = source["path"]
        if path.endswith(".txt"):
            corpus = Path(module.CORPUS).resolve()
            page_path = (corpus / path).resolve()
            if not page_path.is_relative_to(corpus):
                raise ValueError("帮助页路径超出知识库")
            if not page_path.is_file():
                source["text"] += "\n正文缺失：仅有索引，不能作为操作依据。"
                continue
            page = page_path.read_text(encoding="utf-8")
            lines = page.splitlines()
            ranked = sorted(range(len(lines)), key=lambda i: -sum(
                1 for term in terms if module.norm(term) in module.norm(lines[i])))
            selected = set(range(min(8, len(lines))))
            used = sum(len(lines[i]) for i in selected)
            for i in ranked:
                if not lines[i].strip() or i in selected:
                    continue
                if used + len(lines[i]) > 4200:
                    continue
                selected.add(i)
                used += len(lines[i])
            excerpt = "\n".join(f"L{i+1}: {lines[i]}" for i in sorted(selected))
            source["text"] = source["text"][:1100] + "\n已读取帮助正文（节选，L 为原文行号）：\n" + excerpt
    context = []
    for number, source in enumerate(sources, 1):
        context.append(f"[{number}] {source['kind']} · {source['title']}\n"
                       f"来源: {source['path']}" + (f" · {source['guid']}" if source["guid"] else "")
                       + f"\n{source['text']}")
    return {"ok": True, "baseline": "STAR-CCM+ 21.04 中文帮助",
            "route": route, "terms": terms, "context": "\n\n".join(context),
            "sources": [{key: source[key] for key in ("kind", "title", "path", "guid")}
                        for source in sources]}


def main() -> None:
    sys.stdin.reconfigure(encoding="utf-8")
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    question = " ".join(sys.argv[1:]).strip() if len(sys.argv) > 1 else sys.stdin.read(12001).strip()
    if not question or len(question) > 12000:
        raise ValueError("需要 1 到 12000 字的查询")
    print(json.dumps(lookup(question), ensure_ascii=False, separators=(",", ":")))


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(json.dumps({"ok": False, "error": str(exc)}, ensure_ascii=False))
        sys.exit(1)

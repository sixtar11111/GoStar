"""Read local installed help and GoStar-owned guidance. No network or copied vendor corpus."""
from __future__ import annotations
import html
import json
import os
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
ALIASES = {
    "报告": "report", "升力": "lift force", "阻力": "drag force", "力矩": "moment",
    "系数": "coefficient", "参考面积": "reference area", "参考值": "reference values",
    "监视器": "monitor", "绘图": "plot", "网格": "mesh", "棱柱": "prism layer",
    "表面": "surface", "几何": "geometry", "布尔": "boolean", "流体域": "flow domain",
    "湍流": "turbulence", "自由流": "free stream", "马赫": "mach", "边界": "boundary",
    "速度入口": "velocity inlet", "压力出口": "pressure outlet", "停止": "stopping criteria",
    "残差": "residual", "收敛": "convergence", "截面": "plane section", "场函数": "field function",
}

def help_roots() -> list[Path]:
    """Explicit help override is deterministic; other installations are discovered only if absent."""
    explicit = os.environ.get("GOSTAR_HELP_ROOT")
    if explicit:
        return [Path(explicit).expanduser().resolve()]
    homes = [Path(os.environ["STARCCM_HOME"])] if os.environ.get("STARCCM_HOME") else []
    base = Path('/opt/Siemens')
    if base.is_dir():
        homes += sorted(base.glob('*/STAR-CCM+*'), reverse=True)
    roots = []
    for home in homes:
        for language in ('en', 'zh_CN', 'zh'):
            path = home / 'doc' / language / 'online' / 'STARCCMP'
            if path.is_dir() and path not in roots:
                roots.append(path)
    return roots[:4]

def tokens(question: str) -> list[str]:
    text = question.lower()
    translated = ' '.join(v for k, v in ALIASES.items() if k in text)
    return list(dict.fromkeys(re.findall(r'[a-z][a-z0-9+-]{2,}', translated+' '+text)))[:24]

def text_from_html(page: str) -> str:
    page = re.sub(r'<(script|style)\b[^>]*>.*?</\1\s*>', '', page, flags=re.I | re.S)
    return re.sub(r'\s+', ' ', html.unescape(re.sub(r'<[^>]+>', ' ', page))).strip()

def lookup(question: str) -> dict:
    if not question or len(question) > 12000:
        raise ValueError('Query must contain 1–12000 characters')
    words = tokens(question)
    sources, context, warnings = [], [], []
    notes = ROOT / 'runtime' / 'knowledge'
    if not notes.is_dir():
        notes = ROOT / 'knowledge'  # extracted install runtime
    selected = ['operations.md']
    if any(w in words for w in ('report', 'force', 'coefficient', 'moment', 'lift', 'drag', 'monitor')):
        selected.insert(0, 'reports.md')
    if any(w in words for w in ('mesh', 'geometry', 'turbulence', 'flow', 'boundary', 'mach', 'prism')):
        selected.insert(0, 'external-flow.md')
    for name in selected:
        p = notes / name
        if p.is_file():
            text = p.read_text(encoding='utf-8')[:7000]
            sources.append({'kind': 'GoStar guidance (not vendor documentation)', 'title': text.splitlines()[0].lstrip('# '), 'path': str(p), 'guid': ''})
            context.append(text)
    roots = help_roots()
    for root in roots:
        index = root/'oxygen-webhelp/app/search/index/htmlFileInfoList.js'
        if not index.is_file() or index.stat().st_size > 16*1024*1024:
            warnings.append('Installed help title index unavailable: '+str(root))
            continue
        raw = index.read_text(encoding='utf-8')
        try:
            rows = json.JSONDecoder().raw_decode(raw[raw.index('['):])[0]
        except (ValueError, json.JSONDecodeError):
            warnings.append('Installed help index could not be parsed: '+str(index))
            continue
        ranked = []
        for row in rows:
            fields = row.split('@@@', 2)
            if len(fields) != 3 or not re.fullmatch(r'GUID-[A-Za-z0-9-]+\.html', fields[0]):
                continue
            title, desc = fields[1].lower(), fields[2].lower()
            title_hits = sum(bool(re.search(r'\b'+re.escape(w)+r'\b', title)) for w in words)
            desc_hits = sum(bool(re.search(r'\b'+re.escape(w)+r'\b', desc)) for w in words)
            if title_hits:
                ranked.append((title_hits*10+min(desc_hits, 4), fields))
        ranked.sort(key=lambda r: (-r[0], r[1][1]))
        for score, (filename, title, desc) in ranked[:3]:
            page = (root/filename).resolve()
            if not page.is_relative_to(root.resolve()) or not page.is_file() or page.stat().st_size > 1024*1024:
                continue
            body = text_from_html(page.read_text(encoding='utf-8', errors='replace'))
            hit = re.search('|'.join(re.escape(w) for w in words), body, re.I) if words else None
            start = max(0, hit.start()-200) if hit else 0
            excerpt = body[start:start+3300]
            sources.append({'kind': 'Installed STAR help (read locally)', 'title': title, 'path': str(page), 'guid': page.stem})
            context.append('Installed STAR help: '+title+'\nSource: '+str(page)+'\nLocal excerpt: '+excerpt)
        break  # one installation/version only
    if not roots:
        warnings.append('No installed STAR help found; set GOSTAR_HELP_ROOT or STARCCM_HOME. Only GoStar guidance is available.')
    return {'ok': True, 'baseline': 'Installed STAR help + GoStar implementation guidance', 'route': 'local-help', 'terms': words, 'sources': sources, 'context': '\n\n'.join(context)[:22000], 'warnings': warnings}

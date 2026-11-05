"""화면 문구 전수 검사 (AC-C-4 금액 표기, AC-C-8 금지 문구).

검사 대상
- fixtures/**/*.json, contract/samples/*.json 안의 화면 문구 필드(text·*_text·reason·card)
- design/**/*.html, app/**/*.html 의 보이는 글자
- app/**/res/values*/strings.xml 의 문자열 (Compose 화면 문구)
- app/**/*.kt 의 문자열 리터럴(주석 제외, 테스트 폴더 제외) — 엔진 문구 템플릿·화면 코드

사용: python scripts/lint_copy.py   (문제 있으면 exit 1)
docs/ 는 검사하지 않는다(금지 문구를 '하지 않는 것'으로 설명하는 문서라서).
"""

from __future__ import annotations

import json
import re
import sys
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

FORBIDDEN = [
    (re.compile(r"글레오(는|가)?\s*(못|안 ?되|없)"), "글레오 비교 주장 금지(PRD 5-2)"),
    (re.compile(r"글레오보다"), "글레오 비교 주장 금지(PRD 5-2)"),
    (re.compile(r"예상\s*(vs|대비|과|와)\s*실제"), "'예상 vs 실제' 정확도 자랑 금지(PRD 5-2)"),
]
COPY_RULES = [
    (re.compile(r"(^|[^0-9])0 원"), "'0 원' 표기 금지 — '{단위} 미만'(PRD 5-3)"),
    (re.compile(r"\d{1,3}(,\d{3})+\s?원"), "화면 금액은 '1만 9천 원'처럼(쉼표 숫자 금지, PRD 5-3)"),
    (re.compile(r"빠듯.*없이|없이.*빠듯"), "'빠듯'과 '추가 … 없이'를 같이 쓰지 않음(PRD 5-3)"),
]
COPY_KEYS = {"text", "reason", "card"}


def is_copy_key(k: str) -> bool:
    return k in COPY_KEYS or k.endswith("_text")


def walk(obj, path=""):
    if isinstance(obj, dict):
        for k, v in obj.items():
            if isinstance(v, str) and is_copy_key(k):
                yield f"{path}.{k}", v
            else:
                yield from walk(v, f"{path}.{k}")
    elif isinstance(obj, list):
        for i, v in enumerate(obj):
            yield from walk(v, f"{path}[{i}]")


class Text(HTMLParser):
    def __init__(self):
        super().__init__()
        self.parts: list[str] = []
        self._skip = 0

    def handle_starttag(self, tag, attrs):
        if tag in ("script", "style"):
            self._skip += 1

    def handle_endtag(self, tag):
        if tag in ("script", "style"):
            self._skip -= 1

    def handle_data(self, data):
        if not self._skip and data.strip():
            self.parts.append(data.strip())


def is_test_path(p: Path) -> bool:
    parts = set(p.relative_to(ROOT).parts)
    return bool(parts & {"test", "androidTest", "build"})


def xml_strings(p: Path):
    """strings.xml 의 <string>·<string-array>/<item>·<plurals>/<item> 글자."""
    import xml.etree.ElementTree as ET
    root = ET.parse(p).getroot()
    for el in root:
        name = el.get("name", "?")
        if el.tag == "string":
            yield name, "".join(el.itertext())
        elif el.tag in ("string-array", "plurals"):
            for i, item in enumerate(el.findall("item")):
                yield f"{name}[{i}]", "".join(item.itertext())


# 문자열 리터럴과 주석을 한 번에 훑고 리터럴만 남긴다("https://…" 안의 //를 주석으로 보지 않게)
KT_TOKEN = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\\n])*"|//[^\n]*|/\*[\s\S]*?\*/')


def kotlin_literals(src: str):
    for m in KT_TOKEN.finditer(src):
        tok = m.group(0)
        if tok.startswith(("//", "/*")):
            continue
        body = tok[3:-3] if tok.startswith('"""') else tok[1:-1]
        yield src.count("\n", 0, m.start()) + 1, body


def check(where: str, s: str, rules) -> list[str]:
    return [f"{where}: {msg}\n    → {s}" for rx, msg in rules if rx.search(s)]


def main() -> int:
    problems: list[str] = []
    for p in sorted([*ROOT.glob("fixtures/**/*.json"), *ROOT.glob("contract/samples/*.json")]):
        data = json.loads(p.read_text(encoding="utf-8"))
        for where, s in walk(data):
            problems += check(f"{p.relative_to(ROOT).as_posix()}{where}", s, FORBIDDEN + COPY_RULES)
    for p in sorted([*ROOT.glob("design/**/*.html"), *ROOT.glob("app/**/*.html")]):
        t = Text()
        t.feed(p.read_text(encoding="utf-8"))
        for s in t.parts:
            problems += check(p.relative_to(ROOT).as_posix(), s, FORBIDDEN + COPY_RULES)
    for p in sorted(ROOT.glob("app/**/res/values*/strings.xml")):
        for name, s in xml_strings(p):
            problems += check(f"{p.relative_to(ROOT).as_posix()}#{name}", s, FORBIDDEN + COPY_RULES)
    for p in sorted(ROOT.glob("app/**/*.kt")):
        if is_test_path(p):
            continue
        for line, s in kotlin_literals(p.read_text(encoding="utf-8")):
            problems += check(f"{p.relative_to(ROOT).as_posix()}:{line}", s, FORBIDDEN + COPY_RULES)
    for p in sorted(ROOT.glob("app/**/*.js")):
        if "node_modules" in p.parts or is_test_path(p) or p.parent.name == "chagok" and p.name == "engine.js":
            continue
        for i, line in enumerate(p.read_text(encoding="utf-8").splitlines(), 1):
            problems += check(f"{p.relative_to(ROOT).as_posix()}:{i}", line, FORBIDDEN)
    if problems:
        print(f"문구 검사 실패 {len(problems)}건\n")
        print("\n".join(problems))
        return 1
    print("문구 검사 통과")
    return 0


if __name__ == "__main__":
    sys.exit(main())

"""AndroidManifest.xml 의 uses-permission 이 contract/permissions.md 표와 같은지 (AC-N-7).

사용: python scripts/check_permissions.py app/<모듈>/src/main/AndroidManifest.xml
다르면 빠진 것·더 있는 것을 찍고 exit 1.
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
PREFIX = {"P": "pleos.car.permission.", "A": "android.car.permission."}


def allowed() -> set[str]:
    """첫 표(## 선언하지 않는 것 앞)의 권한 열."""
    out = set()
    for line in (ROOT / "contract/permissions.md").read_text(encoding="utf-8").splitlines():
        if line.startswith("## 선언하지"):
            break
        m = re.match(r"\|\s*(?:(P|A)\s+)?`([\w.]+)`", line)
        if m:
            out.add(PREFIX[m.group(1)] + m.group(2) if m.group(1) else m.group(2))
    return out


def declared(manifest: Path) -> set[str]:
    root = ET.parse(manifest).getroot()
    return {e.get(f"{ANDROID_NS}name") for e in root.iter("uses-permission")}


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    want, have = allowed(), declared(Path(sys.argv[1]))
    extra, missing = sorted(have - want), sorted(want - have)
    for p in extra:
        print(f"  + 목록에 없는 선언(심사 탈락 위험): {p}")
    for p in missing:
        print(f"  - 목록에 있는데 선언 안 됨: {p}")
    print("권한 대조 통과" if not (extra or missing) else f"권한 대조 실패: +{len(extra)} / -{len(missing)}")
    return 1 if extra or missing else 0


if __name__ == "__main__":
    sys.exit(main())

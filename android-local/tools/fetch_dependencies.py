"""Download the five pinned Maven artifacts; never place them in the source tree."""
import argparse
import hashlib
import json
from pathlib import Path
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--deps-dir", type=Path, default=ROOT / ".cache/deps")
    args = parser.parse_args()
    dependencies = json.loads((ROOT / "tools/dependencies.json").read_text(encoding="utf-8"))
    args.deps_dir.mkdir(parents=True, exist_ok=True)
    for dependency in dependencies:
        target = args.deps_dir / dependency["filename"]
        if target.exists():
            data = target.read_bytes()
        else:
            with urlopen(dependency["url"], timeout=60) as response:
                data = response.read()
        if hashlib.sha256(data).hexdigest() != dependency["sha256"]:
            raise SystemExit(f"SHA-256 mismatch: {target.name}")
        if not target.exists():
            target.write_bytes(data)
        print(f"Verified {target.name}")


if __name__ == "__main__":
    main()

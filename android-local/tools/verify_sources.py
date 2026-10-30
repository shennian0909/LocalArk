"""Verify the imported r38 source bytes, without touching a device or save."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    manifest = json.loads((ROOT / "docs/SOURCE_MANIFEST.json").read_text(encoding="utf-8"))
    expected = {entry["path"] for entry in manifest["files"]}
    actual = {path.relative_to(ROOT).as_posix() for path in (ROOT / "work").rglob("*")
              if path.is_file() and not any(part in {"__pycache__", ".verify", ".validation", "build", "deps", "payload"} for part in path.parts)
              and path.suffix != ".pyc"}
    errors = [f"Unexpected file: {path}" for path in sorted(actual - expected)]
    for entry in manifest["files"]:
        path = ROOT / entry["path"]
        if not path.is_file():
            errors.append(f"Missing: {entry['path']}")
            continue
        data = path.read_bytes()
        if len(data) != entry["bytes"] or hashlib.sha256(data).hexdigest() != entry["sha256"]:
            errors.append(f"Size/hash mismatch: {entry['path']}")
    if errors:
        raise SystemExit("\n".join(errors))
    print(json.dumps({"baseline": manifest["baseline"], "verifiedOriginalFiles": len(expected),
                      "productionJavaSources": manifest["productionJavaSources"]}))


if __name__ == "__main__":
    main()

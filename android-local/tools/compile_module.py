"""Compile the real module and bootstrap using external SDK/dependency inputs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "work/phone-integrated"


def main():
    javac, android, dependencies = read_inputs()
    output = ROOT / ".build"
    stubs = sorted((MODULE / "compile-stubs").rglob("*.java"))
    sources = []
    for directory in ("backend-src", "framework-src", "storage-src", "ui-src", "runtime-src"):
        sources.extend(path for path in (MODULE / directory).rglob("*.java")
                       if not any(part in {"tests", ".build", ".verify", ".validation"} for part in path.parts))
    bootstrap = sorted((MODULE / "bootstrap-src").rglob("*.java"))
    compile_sources(javac, "8", stubs, [android], output / "compile-stubs")
    compile_sources(javac, "17", sources, [android, output / "compile-stubs", *dependencies], output / "classes")
    compile_sources(javac, "8", bootstrap, [android], output / "bootstrap")
    report = {"compilePassed": True, "productionJavaSources": len(sources),
              "compileOnlyStubs": len(stubs), "bootstrapSources": len(bootstrap),
              "javacVersion": subprocess.check_output([javac, "-version"], text=True, stderr=subprocess.STDOUT).strip(),
              "androidJarSha256": hashlib.sha256(android.read_bytes()).hexdigest(),
              "apkBuilt": False, "runtimeVerified": False}
    (output / "compile-report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report))


def read_inputs():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-jar", required=True, type=Path)
    parser.add_argument("--jdk-bin", type=Path)
    parser.add_argument("--deps-dir", type=Path, default=ROOT / ".cache/deps")
    args = parser.parse_args()
    executable = "javac.exe" if os.name == "nt" else "javac"
    javac = str(args.jdk_bin / executable) if args.jdk_bin else shutil.which(executable)
    if not javac or not Path(javac).is_file():
        parser.error("javac not found; set --jdk-bin or PATH")
    if not args.android_jar.is_file():
        parser.error("--android-jar must name an installed SDK platform JAR")
    lock = json.loads((ROOT / "tools/dependencies.json").read_text(encoding="utf-8"))
    dependencies = []
    for dependency in lock:
        path = args.deps_dir / dependency["filename"]
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != dependency["sha256"]:
            parser.error(f"Missing or mismatched dependency: {path.name}; run fetch_dependencies.py")
        dependencies.append(path.resolve())
    return javac, args.android_jar.resolve(), dependencies


def compile_sources(javac, release, sources, classpath, destination):
    destination.mkdir(parents=True, exist_ok=True)
    response = destination.parent / (destination.name + ".args")
    response.write_text("\n".join('"' + path.as_posix() + '"' for path in sources), encoding="utf-8")
    command = [javac, "-J-Duser.language=en", "--release", release, "-encoding", "UTF-8",
               "-classpath", os.pathsep.join(map(str, classpath)), "-d", str(destination)]
    if release == "17":
        command.append("-parameters")
    result = subprocess.run([*command, "@" + str(response)], capture_output=True, encoding="utf-8", errors="replace")
    (destination.parent / (destination.name + ".log")).write_text(result.stdout + result.stderr, encoding="utf-8")
    if result.returncode:
        raise SystemExit(result.stdout + result.stderr)


if __name__ == "__main__":
    main()

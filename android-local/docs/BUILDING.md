# Building and verifying the Android sources

## Requirements

- Python 3.11 or newer.
- A JDK whose `javac` supports `--release 17` and `--release 8`.
- An Android SDK platform `android.jar`, installed separately.
- Five dependencies pinned by version and SHA-256 in [dependencies.json](../tools/dependencies.json).

Run these commands from `android-local/`. They produce ignored local files only.

## Verify the imported originals

```sh
python tools/verify_sources.py
```

The manifest covers 219 original files, including 196 production Java sources. Verification checks byte sizes and SHA-256 and rejects unexpected files in the imported `work/` tree. Publication helpers and documentation are additional files outside that manifest.

## Download pinned libraries

```sh
python tools/fetch_dependencies.py
```

The five JARs are downloaded from Maven Central to `.cache/deps/` and checked against their pinned hashes. Existing files must match too. JARs are not committed.

## Compile the real sources

Example for Windows PowerShell; substitute your installed SDK and JDK paths:

```powershell
python tools/compile_module.py --android-jar "C:/Android/sdk/platforms/android-32/android.jar" --jdk-bin "C:/Program Files/Java/jdk-25/bin"
```

Omit `--jdk-bin` if `javac` is on PATH. Use `--deps-dir "C:/path/to/deps"` to reuse installed JARs; the same pinned hashes are checked.

The helper compiles six Xposed signature stubs with Java 8 to `.build/compile-stubs/`, 196 real module sources with Java 17 and `-parameters` to `.build/classes/`, and two bootstrap sources with Java 8 to `.build/bootstrap/`. Compile-only stubs stay separate from production output. It prints counts and writes `.build/compile-report.json`.

No DEX or APK is produced; no backend/device/save is accessed.

## Legacy full-APK tools

`work/phone-integrated/build_android.py` and `build_integrated_apk.py` preserve the original Python/javac/D8/aapt/zipalign flow. Their machine-specific tool locations and versioned workspace paths require adaptation. The portable compile helper does not replace that flow or introduce Gradle.

Full integration additionally requires a compatible game client, version-matched tables/resources, Android build tools, payload inputs, local account/seed/configuration material, certificates and signing keys. These are absent. `prepare_payload.py` and `export_seed.py` handle local account/database material; review their inputs before use and do not commit generated output.

`prepare_proxy_pki.py` requires the external Python `cryptography` package. `AndroidApkBridge.java` requires a separately supplied apksig library. Build-tool binaries, the client, data, payloads and keys are not bundled.

Original tools in `work/phone-integrated/storage-src/tests/` retain their original environment assumptions and are not a portable gameplay suite.

## Verification scope

Hashes establish snapshot integrity; compilation establishes source/API compatibility with the selected SDK and dependencies. Neither establishes correct gameplay protocols, Android runtime behavior, successful settlement, synchronization safety or a reproducible complete APK.

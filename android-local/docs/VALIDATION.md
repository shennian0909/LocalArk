# Source publication validation

Date: 2026-10-30 (UTC+08:00). Baseline: `20261029-gameplay-r38`.

| Check | Result |
| --- | --- |
| Imported source integrity | 219 files; every byte length and SHA-256 matched `SOURCE_MANIFEST.json`. |
| Maven dependency download | All five pinned artifacts downloaded and SHA-256 verified. No JARs committed. |
| Production compilation | 196 real Java sources compiled successfully with `--release 17 -parameters`. |
| Compile-only signatures | Six Xposed stubs compiled with `--release 8`, in a separate directory. |
| Bootstrap compilation | Two Java sources compiled with `--release 8`. |
| Compiler | `javac 25.0.4.1`. |
| SDK platform JAR SHA-256 | `d20853d289a5161d9cf1c1268d7923076487d4fa68c970b0733e2d5a0ccfc3c5`. |
| APK/DEX build | Not performed. |
| Game / live saves / live WebDAV | Not accessed or verified. |

The compiler reported existing deprecated API and unchecked-operation notes, and Java 8 obsolescence warnings. Compilation succeeded; these notes are not a gameplay acceptance result.

The inherited `LocalArknights_rootlocation/hypergryph-v0.4.5.5 Alpha.jar` is removed from the current tree. Existing history is not rewritten, so historical objects may still contain that upstream release. No GitHub release or release asset is created by this import.

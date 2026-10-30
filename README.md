# LocalArk

LocalArk is a fork of [loongling/LocalArknights](https://github.com/loongling/LocalArknights), with an Android in-process local backend and single-APK integration source tree.

## Source layout

| Location | Purpose |
| --- | --- |
| `src/`, `pom.xml` | Upstream Java/Spring Boot server, preserved at `8d8f083eb6d198fc7110d479cd22dbb9941cf9b2` (`v0.5.0.0`). |
| [android-local/](android-local/README.md) | Android backend, SQLite storage, loopback networking, Activity overlay, bootstrap and packaging sources. |
| [README.upstream.md](README.upstream.md) | Original upstream README, preserved without edits. |

The Android compatibility shims are separate from the upstream server and must not replace its Spring dependencies.

## Android snapshot

The imported baseline is `20261029-gameplay-r38`: **196 production Java sources**, **205 Java sources in total**, and **219 original source/tool files**, indexed by size and SHA-256 in [SOURCE_MANIFEST.json](android-local/docs/SOURCE_MANIFEST.json). It originated from upstream commit `fa251b4450a2c7f13d61d1c37c457c0651d14ba5`; it does not incorporate every later upstream change.

Read the [Android overview](android-local/README.md), [build instructions](android-local/docs/BUILDING.md), [attributions](android-local/docs/ATTRIBUTIONS.md) and [licensing notes](android-local/docs/LICENSING.md).

The Android import adds source only: no APK/AAB releases, dependency JARs, game resources, accounts, saves, database snapshots, certificates or signing keys. The inherited compiled server JAR is removed from the current tree; Git history is preserved. Other pre-existing upstream files are not certified by this import.

## Status

The [publication validation record](android-local/docs/VALIDATION.md) records source and compilation checks. Compilation does not establish working gameplay or a complete distributable APK. Known unresolved Android behavior includes Integrated Strategies nodes/battle entry and starting squad/buff effects, and normal successful Reclamation Algorithm settlement. Other known issues remain; see [Android status](android-local/README.md#status-and-validation).

## Rights

Dependencies and reference projects are named in [ATTRIBUTIONS.md](android-local/docs/ATTRIBUTIONS.md). No repository-wide open-source license is introduced. Upstream and third-party rights remain with their owners. The game client and assets are not provided by the Android import.

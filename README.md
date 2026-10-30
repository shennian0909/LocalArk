# LocalArk

LocalArk is an Android in-process local backend and single-APK integration project derived from [loongling/LocalArknights](https://github.com/loongling/LocalArknights).

## Source layout

| Location | Purpose |
| --- | --- |
| [android-local/](android-local/README.md) | Android backend, SQLite storage, loopback networking, Activity overlay, bootstrap and packaging sources. |
| [android-local/tools/](android-local/tools/) | Source verification, pinned dependency download and portable Java compilation. |
| [android-local/docs/](android-local/docs/) | Build instructions, source manifest, project attributions, licensing notes and validation. |

The standalone upstream Spring Boot server, Maven wrapper, old data/configuration samples, logs, images and deployment documents have been removed from the current tree because the Android project does not use them. Upstream Git history and attribution remain available. The Android compatibility adapters are part of the embedded implementation.

## Android snapshot

The imported baseline is `20261029-gameplay-r38`: **196 production Java sources**, **205 Java sources in total**, and **219 original source/tool files**, indexed by size and SHA-256 in [SOURCE_MANIFEST.json](android-local/docs/SOURCE_MANIFEST.json). It originated from upstream commit `fa251b4450a2c7f13d61d1c37c457c0651d14ba5`; it does not incorporate every later upstream change.

Read the [Android overview](android-local/README.md), [build instructions](android-local/docs/BUILDING.md), [attributions](android-local/docs/ATTRIBUTIONS.md) and [licensing notes](android-local/docs/LICENSING.md).

The current tree contains source and project documentation only: no APK/AAB releases, dependency JARs, game-data corpus, accounts, saves, database snapshots, certificates or signing keys. Git history is preserved and may contain removed upstream data and release files.

## Status

The [publication validation record](android-local/docs/VALIDATION.md) records source and compilation checks. Compilation does not establish working gameplay or a complete distributable APK. Known unresolved Android behavior includes Integrated Strategies nodes/battle entry and starting squad/buff effects, and normal successful Reclamation Algorithm settlement. Other known issues remain; see [Android status](android-local/README.md#status-and-validation).

## Rights

Dependencies and reference projects are named in [ATTRIBUTIONS.md](android-local/docs/ATTRIBUTIONS.md). No repository-wide open-source license is introduced. Upstream and third-party rights remain with their owners. The game client and assets are not provided by the Android import.

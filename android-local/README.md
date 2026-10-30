# Android local backend

This is the Android adaptation of LocalArknights at `20261029-gameplay-r38`. Its deployment target is a single APK containing the client, an embedded Java backend, SQLite persistence and an Activity overlay.

## Architecture

`BootstrapProvider` verifies and loads the module; `ModuleRuntime.attach` starts `LocalBackend`, `LocalStore` and the controller registry. NanoHTTPD dispatches Spring-style annotations through a reflection adapter. Android does not start a full Spring Boot server.

HTTP `10029`, TLS `8030` and CONNECT `8031` are loopback endpoints. Runtime code includes TLS, WebDAV synchronization and save management. The integrated deployment does not require external LSPosed; Xposed signatures are compile-only inputs.

| Source tree under `work/phone-integrated/` | Java files | Purpose |
| --- | ---: | --- |
| `backend-src` | 139 | Controllers, gameplay helpers and backend integration. |
| `framework-src` | 36 | Framework, logging and servlet compatibility adapters. |
| `storage-src` | 6 | Android SQLite storage and JDBC adapter. |
| `ui-src` | 1 | Activity overlay. |
| `runtime-src` | 14 | Module loading, loopback services, TLS and synchronization. |
| `bootstrap-src` | 2 additional | Bootstrap provider and loader. |
| `compile-stubs` | 6 additional | Xposed signatures; never package into production DEX. |

One additional Java file, `AndroidApkBridge.java`, supports legacy signing. All 219 imported originals are indexed in [SOURCE_MANIFEST.json](docs/SOURCE_MANIFEST.json), with paths relative to this directory. Publication helpers and English documentation are additional files.

## Build and dependencies

Follow [BUILDING.md](docs/BUILDING.md). Python 3.11+, a JDK supporting Java 17 and a separately installed Android SDK platform `android.jar` are required. Dependency coordinates and hashes are pinned in [dependencies.json](tools/dependencies.json).

The original `work/` layout is retained because the legacy scripts resolve paths relative to it. Full packaging requires external client/data/configuration and signing inputs that are absent here.

## Status and validation

This import preserves r38 source bytes; it does not merge later upstream gameplay changes into Android or establish official-game parity.

- Integrated Strategies (`rogue_5`, `rogue_6`): selectable nodes, battle entry and starting squad/buff effects require further validation and fixes.
- Reclamation Algorithm: normal successful completion, settlement and persistent rewards require further validation and fixes.
- Paused known areas include base operator effects, trading/manufacturing/dormitory behavior, furniture, trust/clues, a Reclamation Algorithm scenario, recycling conversion, special profile skins and showcase decorations. This list is not exhaustive.

Publication checks compile the real production sources, compile-only signatures and bootstrap separately, and verify the imported bytes. They do not operate a game, import a save, test a live WebDAV account or establish APK/runtime correctness.

## Attribution

See [ATTRIBUTIONS.md](docs/ATTRIBUTIONS.md) and [LICENSING.md](docs/LICENSING.md). This adaptation is partly derived from LocalArknights. Public visibility does not assign a new license to upstream code or game content.

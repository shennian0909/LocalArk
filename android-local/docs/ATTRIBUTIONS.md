# Project and dependency attributions

## Source origin

- **[LocalArknights](https://github.com/loongling/LocalArknights)** by loongling: upstream local-server source and web templates. The Android baseline originated from commit `fa251b4450a2c7f13d61d1c37c457c0651d14ba5`. Of the 139 Android backend Java files, 44 matched corresponding baseline files byte-for-byte and 37 were modified at matching upstream paths. The remaining 58 backend paths are additional support paths; path classification is not a legal authorship determination.
- **LocalArk Android adaptation**: embedded runtime, SQLite integration, compatibility adapters, Activity overlay, protocol/gameplay changes and single-APK tooling. Existing source notices are retained.

The fork's root server was preserved at `8d8f083eb6d198fc7110d479cd22dbb9941cf9b2` (`v0.5.0.0`) before this import. These two commits identify different source baselines.

## External libraries

No dependency binaries are vendored by this import. Exact coordinates, URLs and SHA-256 values are in [dependencies.json](../tools/dependencies.json).

| Project | Artifact / version | Role |
| --- | --- | --- |
| [Guava](https://github.com/google/guava) | `com.google.guava:guava:32.0.1-android` | Collections and utility APIs. |
| [Guava failureaccess](https://github.com/google/guava) | `com.google.guava:failureaccess:1.0.1` | Guava support dependency. |
| [Fastjson](https://github.com/alibaba/fastjson) | `com.alibaba:fastjson:1.2.83` | JSON parsing/serialization. |
| [Gson](https://github.com/google/gson) | `com.google.code.gson:gson:2.8.9` | JSON support. |
| [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) | `org.nanohttpd:nanohttpd:2.3.1` | Embedded HTTP server. |

Additional API and tool references:

- **[XposedBridge](https://github.com/rovo89/XposedBridge)**: six minimal compile-only signature stubs. No XposedBridge binary or external LSPosed runtime is packaged by the compile helper.
- **[Spring Framework](https://github.com/spring-projects/spring-framework)**, **Servlet API**, **[SLF4J](https://www.slf4j.org/)** and **[Apache Log4j](https://logging.apache.org/log4j/2.x/)**: compatibility namespaces implemented by small local adapters in `framework-src` and `storage-src`. The Android module does not start the full Spring Boot stack.
- **Android SDK / AOSP**, including **D8/R8** and **apksig**: external platform compilation and legacy packaging/signing tools; no binaries included.
- **[cryptography](https://github.com/pyca/cryptography)**: Python dependency used by legacy PKI tools.

Additional upstream server dependencies remain declared in the root `pom.xml`; this Android list does not replace them.

## Research and data references (not bundled)

- **[ArknightsGameData](https://github.com/Kengxxiao/ArknightsGameData)**: development table references; one recorded reference is commit `fe7d8d05edc36cf5678a24bfe9e84e891f6cf7ad`. No game-data corpus is added here.
- **[ArkPRTS](https://github.com/thesadru/ArkPRTS)**: protocol research reference. No ArkPRTS source is added here.
- **Arknights / Hypergryph and the respective rights holders**: client, trademarks, artwork, audio and other game content remain outside the Android source import.

Acknowledgement does not grant rights to third-party code or assets.

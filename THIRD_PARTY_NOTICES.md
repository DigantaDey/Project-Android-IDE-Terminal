# Third-party notices

Forge IDE uses the following libraries (the terminal session adapter is explicitly modified). Their licenses and copyright notices remain applicable; this document does not choose or change a license for the repository's original application code.

| Component | Pinned version / coordinates | License and corresponding source |
| --- | --- | --- |
| Sora Editor | `io.github.Rosemoe.sora-editor:editor:0.23.5` | LGPL-2.1-or-later; Copyright © 2020–2024 Rosemoe and contributors. [Exact source](https://github.com/Rosemoe/sora-editor/tree/0.23.5), [license](licenses/Sora-LGPL-2.1.txt) |
| Mozilla Rhino | `org.mozilla:rhino:1.7.15` | MPL-2.0; Mozilla contributors. [Exact source](https://github.com/mozilla/rhino/tree/Rhino1_7_15_Release), [license](licenses/Rhino-MPL-2.0.txt) |
| Eclipse JGit | `org.eclipse.jgit:org.eclipse.jgit:5.13.3.202401111512-r` | Eclipse Distribution License 1.0 (BSD-style); Eclipse contributors. [Exact source](https://github.com/eclipse-jgit/jgit/tree/v5.13.3.202401111512-r), [license](licenses/JGit-EDL-1.0.txt) |
| AndroidX Core | `androidx.core:core:1.13.1` | Apache-2.0; The Android Open Source Project. [Source](https://github.com/androidx/androidx/tree/androidx-main/core), [license](licenses/AndroidX-Apache-2.0.txt) |
| AndroidX bundled SQLite | `androidx.sqlite:sqlite-bundled:2.6.2` | Apache-2.0 for the AndroidX driver; SQLite engine in the public domain. [Driver source](https://github.com/androidx/androidx/tree/androidx-main/sqlite), [Apache license](licenses/AndroidX-Apache-2.0.txt), [SQLite notice](licenses/SQLite-Public-Domain.txt) |
| SLF4J API / no-op logger | `org.slf4j:slf4j-nop:1.7.36` and resolved API | MIT; Copyright © 2004–2022 QOS.ch Sarl and contributors. [Source](https://github.com/qos-ch/slf4j/tree/v_1.7.36), [license](licenses/SLF4J-MIT.txt) |
| JavaEWAH (JGit dependency) | Version selected by the pinned JGit POM | Apache-2.0; Daniel Lemire and contributors. [Source](https://github.com/lemire/javaewah) |
| Kotlin standard library (transitive) | Version selected by the pinned Android libraries | Apache-2.0; JetBrains and contributors. [Source](https://github.com/JetBrains/kotlin) |
| Other AndroidX annotations/collections (transitive) | See Gradle runtime dependency report | Apache-2.0; The Android Open Source Project. [Source](https://github.com/androidx/androidx) |
| JDK API desugaring support | `com.android.tools:desugar_jdk_libs:2.1.3` (build transformation/runtime support) | GPL-2.0 with Classpath Exception, with additional upstream notices. [Source](https://github.com/google/desugar_jdk_libs), [notice](licenses/Desugar-JDK-Libs-LICENSE.txt) |

Full license files in `licenses/` are packaged as Android assets and can be read through **Third-party notices** in the command palette. Upstream copyright headers/notices are not removed from source or substituted with a proprietary notice.

Sora is consumed unmodified as a library. The Gradle project and exact dependency coordinates allow rebuilding the app with a modified/replacement Sora library for debugging those modifications; release minification is disabled. Before redistributing binaries, satisfy all LGPL/MPL/source/relinking obligations and include required upstream/transitive notices. Repository links alone are not a substitute for any additional legal obligations that apply to a distributor's particular binary release.

## Optional native companion and terminal renderer

The offline companion embeds pinned Termux Android/bionic packages, not a blanket
Forge-licensed toolchain. The exact 69-package version/hash lock is
`tools/native/runtime-lock.json`; the per-package license/source-recipe inventory
and pinned copyleft source inputs are in `tools/native/sources-lock.json`.
Original license/copyright files under the payload's `usr/share/doc/` are retained.
Native ELF/text prefixes are relocated by the checked-in `native-bundle.py`; package
maintainer scripts are not run. No upstream signature or endorsement is implied.

CI supplies **forge-native-corresponding-sources** alongside the offline APK
bundle: verified GNU/other copyleft source archives and incremental patches, the
complete exact Termux build recipe/patch tree, the integration source at that
commit, checksums and a package license inventory. Non-copyleft source URLs are
also retained in the full recipes. This supports source distribution obligations;
it is not a certification of all production linking/relinking/store obligations.
Keep source availability paired with any binaries you redistribute.

Major native licenses include Clang/LLVM's Apache-2.0-with-exceptions/NCSA,
CPython's PSF terms, Node's MIT and bundled third-party notices, npm's Artistic-2.0
and bundled dependency notices, Bash/GNU tools' GPL-3.0/GPL-2.0, and dependencies
under LGPL/MPL/BSD/MIT/OpenSSL/other licenses recorded in the inventory. Review
all of them before production redistribution; the IDE's JVM libraries are separate.

`terminal-ui` vendors the terminal-emulator/terminal-view rendering code/resources
from [termux-app v0.118.3](https://github.com/termux/termux-app/tree/v0.118.3).
[Upstream's license statement](tools/terminal/upstream/LICENSE.md) identifies these
libraries as Apache-2.0 exceptions to the surrounding app's GPL license. The
[Apache text](licenses/AndroidX-Apache-2.0.txt) applies, and original copyright
headers remain. Forge replaces TerminalSession with a remote-only adapter,
disables output-triggered clipboard access, and reformats Java; no Termux app
service/JNI/process launcher is linked into the IDE. See
[provenance and changes](tools/terminal/README.md).

## Development-only tools

Gradle 8.10.2 (Apache-2.0), Android Gradle Plugin 8.7.3 (Apache-2.0), JUnit 4.13.2 (EPL-1.0), Hamcrest (BSD-3-Clause), AndroidX Test (Apache-2.0), Prettier 3.6.2 (MIT), prettier-plugin-java 2.7.6 (Apache-2.0), and optional Eclipse ECJ (EPL-2.0) are build/test/formatting tools, not installed native compilers or package managers in the Android IDE.

Run `./gradlew :app:dependencies --configuration debugRuntimeClasspath` to inspect the actual transitive dependency graph for a build. CI uploads this report. Review the exact resolved graph, current security advisories, license texts, and signing/distribution obligations before a public production release; this file is not a legal or supply-chain audit.

# Vendored terminal renderer

`terminal-ui/src/main/java/com/termux/{terminal,view}` and its resources originate
from `termux/termux-app` tag `v0.118.3`, terminal-emulator and terminal-view.
The upstream LICENSE.md explicitly identifies these libraries as Apache-2.0
exceptions to the app's GPL-3.0 license; that statement is retained here.

Forge replaces TerminalSession with a remote-only adapter. No Termux JNI,
createSubprocess, native executable, app service, or filesystem adapter is linked
into the IDE process. ANSI rendering, input translation, resize and selection are
retained. Output-triggered OSC-52 clipboard read/write is intentionally blocked.
Formatting-only changes are applied by this repository's Java formatter.
See licenses/AndroidX-Apache-2.0.txt for the Apache-2.0 license text.

# Rhino is interpreted (no JVM bytecode generation). Keep reflective runtime interfaces.
-keep class org.mozilla.javascript.** { *; }
-keep class org.eclipse.jgit.** { *; }
-dontwarn org.slf4j.impl.**

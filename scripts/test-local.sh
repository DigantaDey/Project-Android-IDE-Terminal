#!/usr/bin/env bash
# Optional offline harness. Standard CI uses Gradle + JUnit from Maven Central.
set -euo pipefail
cd "$(dirname "$0")/.."
JAVA="${JAVA:-/usr/local/lib/python3.11/dist-packages/jdk4py/java-runtime/bin/java}"
mkdir -p .local/classes
find core -path '*/src/*' -name '*.java' > .local/sources.txt
CP=".local/tools/junit.jar:.local/tools/hamcrest.jar:.local/tools/rhino.jar"
"$JAVA" -jar .local/tools/ecj.jar -17 -proc:none -warn:none -cp "$CP" -d .local/classes @.local/sources.txt
mapfile -t TESTS < <(find core -path '*/src/test/*' -name '*Test.java' | sed -E 's|.*src/test/java/||;s|/|.|g;s|\.java$||')
"$JAVA" -cp ".local/classes:$CP" org.junit.runner.JUnitCore "${TESTS[@]}"

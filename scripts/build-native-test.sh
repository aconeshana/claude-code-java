#!/bin/zsh
# build-native-test — a throwaway native image for testing unreleased changes,
# kept strictly away from the binaries `poco`/`jcc` (and any running session)
# resolve to.
#
# `build-native-local.sh` writes to two shared locations: the build output
# `nativeQuickCompile/poco` and the frozen snapshot `~/.local/share/poco/snapshot`.
# Overwriting them under a live session is a real hazard — a process already
# mapped to that image reloads lazily, so the next class it touches dies with
# NoClassDefFoundError and the TUI freezes with no visible deadlock. This script
# exists so a candidate build can never touch either location.
#
# It shares only the *Gradle task* with the real build: a different output path
# is not something the task exposes, so this compiles into
# nativeQuickCompile/poco and then immediately copies the result aside to
# ~/.local/share/poco/testpoco. Anything already running keeps its own inode;
# only the on-disk name is replaced, which is safe for a process that has
# already exec'd it.
#
# Usage:  ./scripts/build-native-test.sh [extra gradle args]
# Test with:  testpoco          (see ~/.local/bin/testpoco)

set -euo pipefail

readonly GRAALVM_25_HOME="/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home"
readonly REPOSITORY_ROOT="${0:A:h:h}"

if [[ ! -x "${GRAALVM_25_HOME}/bin/native-image" ]]; then
  print -u2 "GraalVM native-image not found at ${GRAALVM_25_HOME}/bin/native-image"
  exit 1
fi

export JAVA_HOME="${GRAALVM_25_HOME}"
export GRAALVM_HOME="${GRAALVM_25_HOME}"
export PATH="${GRAALVM_25_HOME}/bin:${PATH}"

readonly BUILT_BINARY="${REPOSITORY_ROOT}/claude-code-app/build/native/nativeQuickCompile/poco"
# The candidate copy, deliberately NOT the snapshot path poco/jcc fall back to.
readonly TEST_BINARY="${HOME}/.local/share/poco/testpoco"

cd "${REPOSITORY_ROOT}"
# Not `exec`: the copy below has to run after Gradle exits.
./gradlew --no-daemon --no-configuration-cache :claude-code-app:nativeQuickCompile "$@"

if [[ ! -x "${BUILT_BINARY}" ]]; then
  print -u2 "build-native-test: build succeeded but ${BUILT_BINARY} is missing"
  exit 1
fi

mkdir -p "${TEST_BINARY:h}"
cp "${BUILT_BINARY}" "${TEST_BINARY}"
chmod +x "${TEST_BINARY}"
print "build-native-test: candidate ready at ${TEST_BINARY}"
print "build-native-test: run it with 'testpoco' — poco/jcc and their snapshot are untouched"
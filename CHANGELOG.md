# Changelog

All notable changes to the Forge IDE are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project does not
currently assign semantic version numbers to IDE builds.

## [Unreleased]

### Fixed

- `core/commands`: `CommandRegistry.search()` now computes each command's fuzzy
  match score once instead of re-matching on every comparator comparison,
  removing redundant allocations when the command palette filters a large
  registry.
- `native-runtime`: `NativeRuntimeService` no longer fails a semantic
  (language-server) session with a bare "null" exit message when the caller
  omits the optional `enabled` tool array; the key is now null-coalesced the
  same way `start()` already coalesced it.

# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
## [1.0.2] - 2026-03-20

### Changed
- fixed gradle workflow

[1.0.2]: https://github.com/bakirrayan/JSON_Tree_JQ/compare/1.0.1...1.0.2

## [1.0.1] - 2026-03-20

### Changed
- adding changelog and script for new tags
- fixed workflow

[1.0.1]: https://github.com/bakirrayan/JSON_Tree_JQ/compare/1.0.0...1.0.1

## [1.0.0] - 2026-03-20

### Added
- Initial release of the JSON Tree JQ Burp Suite extension
- JSON responses rendered as an interactive, collapsible `JTree` with syntax coloring
- jq-powered search bar with real-time filtering (debounced, no Run button needed)
- Auto-complete suggestions for jq queries
- Left-side ruler showing absolute line numbers stable across expand/collapse
- Right-click context menu: Copy value / Copy key / Copy key: value
- Ctrl+C copies key: value of the selected node
- Status bar showing the jq path of the selected node (e.g. `.user.name`)
- Theme-aware colors adapting to Burp's dark and light themes at paint time
- GitHub Actions CI workflow with Java 21 and Gradle

[1.0.0]: https://github.com/bakir-rayan/JSon_Tree_JQ/releases/tag/1.0.0

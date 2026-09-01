# Changelog

## 1.0.5 - 2026-09-01

### Fixed

- Fixed stale checker availability briefly showing the panel and continuing to
  block underlying tooltips after opening a terminal without the checker in the
  player inventory.

## 1.0.4 - 2026-08-30

### Changed

- Restored the selected pattern when reopening the terminal checker, or the
  previous viewport when the selection no longer exists.
- Separated the collapsed toggle position from the expanded panel so each can
  be dragged and constrained independently.
- Rendered the checker overlay after other terminal overlays and suppressed
  underlying tooltips while the pointer is over the checker.

### Fixed

- Fixed the collapsed checker intercepting clicks intended for FTB Quests and
  other terminal controls.
- Fixed collapsed-panel layout and input-capture glitches.
- Fixed false recipe-change warnings for decoded crafting patterns using fluid
  substitution or shapeless recipes.

## 1.0.3 - 2026-08-20

### Added

- Added direct editing for item-only processing patterns, including duplicate
  patterns, from both checker interfaces.
- Added container names and coordinates to pattern list entries.
- Added mouse dragging for the checker list scrollbar.

### Changed

- Preserved the floating checker position across terminal sessions and
  expanded/collapsed states.
- Kept the checker toggle compact and pinned to the module's top-right corner.

### Fixed

- Fixed terminal checker rendering with invalid or transient widget sizes.
- Fixed position resets when reopening a pattern terminal.
- Fixed edge-position expansion crashes caused by invalid button dimensions.

## 1.0.2 - 2026-08-18

### Added

- Added an in-terminal checker module toggle.
- When disabled, only the compact toggle remains visible and the checker no
  longer intercepts clicks, dragging, or scrolling in the rest of the terminal.

### Changed

- Kept all existing Minecraft 1.21.1 NeoForge machine, addon pattern provider,
  wireless provider, and packaged-pattern compatibility.

## 1.0.1 - 2026-08-15

### Added

- Added precise Powah 6.2.10 Energizing Orb recipe validation by binding
  `powah:energizing_orb` to the `powah:energizing` recipe type.
- Invalid Powah energizing patterns can now be distinguished without treating
  unrelated or unknown machines as compatible.

### Changed

- Lowered the minimum supported NeoForge version from 21.1.244 to 21.1.200
  while retaining Minecraft 1.21.1 compatibility.

## 1.0.0 - 2026-08-14

- Initial public release.

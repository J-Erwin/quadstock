# Changelog

Versions follow Semantic Versioning. Dated sections record repository release milestones, not necessarily published downloads. Release numbering starts at 1.0.0.

## [Unreleased]

## [1.0.0] - 2026-11-13

### Changed

- Established the 1.0.0 release baseline from the existing 2.6.1 alpha development snapshot. This version-number reset does not change runtime behavior, configuration, or persisted data; existing installations require no data migration.
- Includes configurable markets, trading and holdings, recorded price history, physical displays, portfolio reports, optional share-supply and player holding limits, and text-first Tab portfolios with optional administrator-hosted icon packs.
- No Minecraft artwork is bundled or exported.

### Added

- MIT license for original QuadStock work, third-party notices and license texts packaged in the JAR, and a matching source ZIP for distribution alongside the binary.
- GitHub Actions builds for `dev` and `main`, distinct development/PR versions, and tagged releases restricted to commits on `main`, with JAR/source/checksum assets.

### Fixed

- Enforce industry daily payout limits in both settlement modes. Explicit industry limits (typically `5000` in the bundled configuration) now apply to unlimited liquidity; an inherited unlimited-mode default of `0` remains uncapped.
- Count all committed same-day sales toward each ticker’s shared allowance, including existing history and sales made while uncapped. Purchases, mode switches and restarts do not restore allowance; the 24,000-tick market-day reset and reserve accounting are unchanged.
- Preserve explicit confirmation of partial sale offers and reject exhausted allowances before payment. No configuration rewrite or database migration is needed.

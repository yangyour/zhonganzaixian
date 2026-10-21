# V41 migration-policy CI repair

- Date: 2026-10-19 UTC.
- Baseline: `ffe09e65f609caa3bb497ae7e92881f3e24b91ee`.
- Failing CI: run `39240834129`, job `118020950688`, pure migration-policy checks.
- Cause: the explicit manifest registry already included the frozen V41 report
  migration, but PowerShell still rejected versions above 40 and required 40
  entries. Both guards now explicitly require the reviewed V1–V41 range.

## Verification

Actual PowerShell 7.6.7 ran on the dot cloud Debian 13 x64 computer. Its official
[Microsoft package](https://packages.microsoft.com/repos/microsoft-debian-trixie-prod/pool/main/p/powershell/powershell_7.6.7-1.deb_amd64.deb)
was extracted under `/tmp`, with writable temporary XDG directories; no system
installation or security setting change was required.

- Before the fix, actual PowerShell reproduced the CI exception at policy line
  37: `冻结迁移版本重复或超出已审核范围。`
- The strengthened Python parity assertion also failed before the fix, showing
  the registered maximum 41 versus the runtime guard's 40. It now checks both
  the explicit version bound and exact expected count against registered hashes.
- `pwsh -NoLogo -NoProfile -NonInteractive -File tools/test-migration-policy.ps1`:
  **262 checks passed; database operations = 0**.
- `python3 -m unittest tools.test_niko_private_bootstrap -v`:
  **34 tests passed**.
- Negative fixtures preserve valid hashes and the 41-file count while replacing
  V41 with V42, or duplicating version 40 under a distinct filename; both are
  rejected. A separately supplied but unregistered V42 manifest and matching
  source/compiled files are also rejected. Valid V41 fixtures pass after each
  restoration. Existing source/compiled byte, missing-file, duplicate-manifest,
  manifest completeness and explicit-mode checks remain intact.
- Mutation checks independently confirmed the test suite fails with a stale
  count of 40, with the maximum weakened to 42, or with the duplicate-version
  guard removed. The final production policy was restored and rechecked.
- `git diff --check`: passed.
- All 41 SQL files were byte-compared with the baseline commit and are unchanged.
  V41 SHA256 remains
  `54b9bd1d0995a248191e8f8f7dafe35fafd7d4ec15699a2ca8a49a7a025c7aa2`.

This is a pure pre-database guard repair. No migration, real database, original
server, API/Worker, email or WeCom operation was run. Prior report PostgreSQL and
HTTP evidence was not rerun or relabeled. Remote CI for the repair commit must
still be checked after publication.

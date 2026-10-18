# GitHub backup — 2026-10-18

Private repository: https://github.com/fr3k-d3v/fr3k-ecosystem-checkpoint

Recovery instructions and component branch commits: repository README on main.
Release assets: https://github.com/fr3k-d3v/fr3k-ecosystem-checkpoint/releases/tag/checkpoint-2026-10-18

The current recovery handover is [session 3 continuation](HANDOVER-CONTINUATION-2026-10-18-SESSION-3.md), linked from USAGE-LIMIT-HANDOVER-2026-10-18.md. APK discovery acceptance remains OPEN. Latest source and test artifacts are built; a complete frozen run is active. No new candidate has been promoted.

Uploads verified complete on 2026-10-18. All 30 original GitHub release assets have matching local sizes and SHA256 digests, including all 23 archive parts, both checksum inventories, the part manifest and the follow-up worktree delta. No expected asset is missing. All nine component checkpoint branches are present. Verification: `/home/parrot/.cache/ecosystem-github-20261018/remote-release-verification.json` and `backup-completion-verification.json`. Preserve the archives and ledgers in that directory. The release is a historical backup; later HUD acceptance work is recorded on its checkpoint branch.

Locally all four tar archives pass full read and SHA256 verification. All18 selected benchmark hashes pass; all43 preservation-ledger files are unchanged. Build caches and local credentials are excluded. Original and unified worktree source checkpoints are separate branches, preserving different edits. Existing original remote configurations and branches are retained.


## Verified recovery

All four archive hashes also pass after streaming the split parts in lexical order. The three split archives have 4, 11 and 8 parts respectively; `benchmark-and-hud-evidence.tar.gz` is a single asset. Four follow-up verification JSON files were uploaded to the same release, bringing its asset count to 34. The original asset inventory is retained in `remote-release-verification.json`.

Download into a new recovery directory, then run:

```bash
sha256sum -c PART-SHA256SUMS
for archive in additional-worktree-release-packages.tar.gz blackwave-release-packages.tar.gz source-histories-and-diagnostics.tar.gz; do
  cat "$archive".part-[0-9][0-9][0-9] > "$archive"
done
sha256sum -c SHA256SUMS
```

`reconstruction-verification.json` records the measured reconstructed hashes. Source branch and release asset verification ledgers are attached to the private release alongside the recovery data. Historical failed acceptance runs remain retained.

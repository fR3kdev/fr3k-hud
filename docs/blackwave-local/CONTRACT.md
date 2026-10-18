# BLACKWAVE local contract v1

2026-10-15. Shared AIDL is byte-identical in both apps. Protocol `blackwave-local/1`; negotiate transaction0 and query1. Future ABI expansion must preserve these IDs. Navigation intents do not authorize operations.

BLACKWAVE owns phone authority and hardware jobs; HUD is an authenticated client. Explicit service component `com.fr3k.blackwave.core.ipc.BlackwaveControlService` in `com.fr3k.blackwave` or deliberately `com.fr3k.blackwave.debug`. HUD package `com.mcpintelligence.fr3k.hud`; debug package accepted only by debug BLACKWAVE. Both sides review the actual installed signer. Every call checks actual calling UID, sole package and exact current approved certificate set before thread dispatch. Shared UIDs and silent certificate rotation are refused. BLACKWAVE association v2 requires fresh review after the metadata-only scope. No bearer credential or private signing key crosses IPC.

## Authority and storage

Room v1→v2 adds authority/units/jobs/events; v2→v3 adds challenges/capabilities/replay/audit; v3→v4 adds durable hardware jobs; v4→v5 adds exact immutable hardware parameters. Existing five tables and historical evidence survive. Schema migration does not mint authority or import cached gateway claims.

New-fleet activation is explicit and refuses saved gateway pairing/cached fleet. Phone Ed25519 operational seed is software, wrapped under AndroidKeystore AES-GCM and excluded from backup. Device private keys remain on devices; firmware release trust contains public keys only. UUID mappings to `bw:ed25519:sha256:<raw-key-sha256>` are unique. Aliases, model names and transport addresses never identify a unit.

Enrollment uses a signed90-second `blackwave-phone-enrollment/1` challenge, exact device signature/controller/nonce/UUID/key/model validation, transactional challenge consumption/replay ledger and signed receipt. Capability snapshots expire independently of enrollment. Device firmware must actually implement this supplemental adapter; legacy host protocols are preserved. Revocation blocks future local actions.

Authority transfer is explicit: staged signed destination request; independently reviewed old/destination fingerprints; stable snapshot; old authority RELEASED receipt; destination verifies signature/snapshot/destination and imports idempotently. Host commit seals registry mutation and nonce acceptance under a write lock. Phone release refuses pending local/hardware work and stores a recoverable signed receipt. Imported units retain UUID/key/revocation mappings but do not acquire fresh live capabilities. Restoring a database does not restore device authorization; live device controller rollover still needs compatible firmware and evidence.

## Queries and bounds

Requests are UTF-8<=8KiB; responses<=48KiB. Version must be1. Negotiation advertises supported methods: health, devices, events, guides, capabilities, preview, operation, missions, submit, cancel, recipes, recipe.preview, installations and installation. Unknown methods return METHOD_UNAVAILABLE; incompatible versions/bad inputs refuse. Bulk artifacts never enter Binder JSON.

Devices page25 with offset/next_offset. Events page50 with monotonic after/cursor; reconnect polls from its saved cursor. Callback subscriptions are not implemented. Guides are the11 versioned packaged offline guides. Capabilities distinguish expiring device manifests from executable `phone-local` actions. Stored devices always report live=false. Health retains legacy status METADATA_ONLY for compatibility and hardware_writes_enabled=false: this excludes hardware dispatch over IPC, while the explicitly advertised local evidence mission is executable after approval.

HUD may preview only published local actions, retrieve jobs/receipts, cancel its pending jobs and resume/query an already approved job. There is no IPC approval endpoint, raw hardware command or firmware write endpoint. Hardware installations/status are read-only to HUD. Large job/recipe lists currently refuse at the response cap rather than transferring oversized messages; further pagination is a remaining scale improvement.

## Missions and evidence

The executable action is `evidence.enrollment`: a signed artifact of phone-held enrollment, never a live capture. Stable caller request UUID + exact immutable digest deduplicates previews; conflicting owner/parameters fail. One BLACKWAVE operation UUID persists through both apps. The digest covers request, target key identity, action, canonical parameters, capability digest and guide version. Internal BLACKWAVE approval binds that digest for at most5minutes. First submission checks freshness/approval; duplicate/reconnect requests retrieve existing state.

PREVIEW→APPROVED→QUEUED→RUNNING→SUCCEEDED/FAILED/INTERRUPTED. Pending missions can cancel. WorkManager plus startup reconciliation handles the DB/enqueue gap; lost RUNNING work becomes INTERRUPTED and is not blindly repeated. State/events update transactionally. Signed receipt binds operation/request/target/artifact digest. HUD verifies controller signature and scoped artifact bytes/hash, including after both app processes restart.

The exported read-only evidence provider authenticates every open, requires a completed job, UUID path, read mode and<=256KiB artifact. Already-open descriptors cannot be retroactively revoked. Saved recipes carry signed identity/capability/guide requirements; reuse creates a new preview and approval.

Hardware previews separately bind signed board/revision/firmware/destination and review parameters. A connected-device foreground service performs only qualified native transfers with bounded I/O, progress and safe interruption checks. Read-back, transfer ACK and boot are separate states. No family has physical qualification evidence. See DEVICE-SUPPORT-MATRIX.md.

## Conformance

Frozen Python/Kotlin vectors cover canonical peer signing bytes, identity mappings, Ed25519 signatures and complete FR3KCFG2 output. Peer canonical JSON uses ensure_ascii=false; configuration uses true. Kotlin orders Unicode code points and refuses unpaired surrogates/floats; this is a bounded integer-only subset. Existing wire formats are preserved. Passing vectors does not enable live configuration writes or prove device interoperability.

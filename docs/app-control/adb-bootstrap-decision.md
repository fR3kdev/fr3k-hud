# Embedded ADB bootstrap decision

Status: ARCHITECTURE_SELECTED / TRANSPORT_NOT_QUALIFIED.

Use a HUD-owned typed Binder service bootstrapped by operator-authorised local ADB. Do not treat normal startService/app_process calls from the application UID as privilege escalation. Stock Android must authorise wireless debugging/pairing or USB bootstrap; HUD cannot eliminate that prerequisite.

Shizuku API AAR remains a legacy compatibility path, not an embedded server. Upstream Shizuku source pins its manager and permission UI, requiring deliberate adaptation. Candidate transport MuntashirAkon/libadb-android c849886ebc6d48e7b46d967e78a6bb65c90c3b74 has GitHub licence classification NOASSERTION and an upstream security-audit warning. Review actual licence files and pairing/native dependencies before selecting it.

Do not auto-root, open network ADB listeners, reuse another application's pairing key or upload pairing secrets. Store keys app-private and excluded from backup; pairing code entry stays in local UI. Revocation, stopped adbd, changed pairing port, reboot and Binder death must report disconnected until a fresh authenticated handshake.

Required handshake: protocol version, observed service UID, expected HUD UID/signer, session nonce/generation, bounded TTL and call-by-call authorisation. Explicit typed operations only; no model-provided raw shell strings.

Device discovery returned no attached devices. Pairing, UID2000 bootstrap, OEM compatibility and no-manager operation cannot be verified without a target. No existing pairing credentials were altered.

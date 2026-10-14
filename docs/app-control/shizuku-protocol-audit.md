# Shizuku source audit

Status: SOURCE_VERIFIED; embedded server and device tests NOT_QUALIFIED.

Source: RikkaApps/Shizuku at b844bc491f1790c72328e1a8e5b2349f8978f0ea.
API submodule: a27f6e4151ba7b39965ca47edb2bf0aeed7102e5, RikkaApps/Shizuku-API.

`server/src/main/java/rikka/shizuku/server/ShizukuService.java` lines 104–118 obtains the manager ApplicationInfo and exits if absent. Lines 146–167 authorise manager UID; lines 256–280 launch the manager to request permission. Thus copying an API AAR or server payload into HUD does not remove the manager requirement. Package/constants, provider delivery, authorisation and bootstrap must be deliberately adapted or replaced by HUD-owned AIDL.

The existing HUD custom OP_EXEC=0x0B with strings package-info/list-splits is not an interface supplied by that source. Treat it as unsupported; do not read a blank reply and manufacture successful stdout. Supported upstream uses real client/server AIDL and remote Binder mechanisms, not arbitrary transaction numbers invented by the application.

Future embedded implementation: typed AIDL, service UID verification, expected HUD UID/signer pin, session nonce, bounded operation inputs, explicit shell-UID bootstrap through authorised ADB, and independently observed results. App process cannot simply start its own process as shell. Reject dead/wrong Binder, unsupported protocol, failed transact, timeout, empty malformed response and replay. Cancellation must propagate.

No device was attached at initial execution discovery. No Shizuku grant or shell-UID execution has been proved.

Source URL: https://github.com/RikkaApps/Shizuku/blob/b844bc491f1790c72328e1a8e5b2349f8978f0ea/server/src/main/java/rikka/shizuku/server/ShizukuService.java

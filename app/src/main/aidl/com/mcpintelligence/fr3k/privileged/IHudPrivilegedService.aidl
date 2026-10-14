package com.mcpintelligence.fr3k.privileged;
import android.os.Bundle;
/** HUD-owned privileged service. Typed, allow-listed operations only: no shell strings cross Binder. */
interface IHudPrivilegedService {
    int getProtocolVersion();
    int getServerUid();
    String getGeneration();
    /** SHELL (uid 2000) or ROOT (uid 0). Root unlocks the root-gated operations. */
    String getCapabilityTier();
    List<String> listAllowedOps();
    Bundle inspectPackage(String packageName);
    Bundle getAppOp(String packageName, String op);
    Bundle setAppOp(String packageName, String op, String mode);
    /** Root tier only. Names and sizes only, never file contents. */
    Bundle listPrivateDir(String packageName);
    /** Root tier only. Disables or enables one component of one package. */
    Bundle setComponentEnabled(String packageName, String component, boolean enabled);
    oneway void shutdown();
}

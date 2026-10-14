package com.mcpintelligence.fr3k.privileged;
import android.os.Bundle;
/** HUD-owned read-only privilege service. No arbitrary commands or code loading. */
interface IHudPrivilegedService {
    int getProtocolVersion();
    int getServerUid();
    String getGeneration();
    Bundle inspectPackage(String packageName);
    oneway void shutdown();
}

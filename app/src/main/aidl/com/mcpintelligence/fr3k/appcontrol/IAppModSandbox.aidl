package com.mcpintelligence.fr3k.appcontrol;
import android.os.ParcelFileDescriptor;
interface IAppModSandbox {
    String run(in ParcelFileDescriptor[] inputs, in ParcelFileDescriptor[] outputs, String request);
    oneway void cancel();
}

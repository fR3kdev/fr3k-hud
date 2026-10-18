package com.fr3k.blackwave.ipc;
import com.fr3k.blackwave.ipc.IBlackwaveEvents;

/** blackwave-local/1. Append-only transaction ABI; bounded UTF-8 JSON metadata. */
interface IBlackwaveControl {
    String negotiate(int minimumVersion, int maximumVersion) = 0;
    String query(String requestJson) = 1;
    String subscribeEvents(long afterSequence, IBlackwaveEvents listener) = 2;
    void unsubscribeEvents(IBlackwaveEvents listener) = 3;
}

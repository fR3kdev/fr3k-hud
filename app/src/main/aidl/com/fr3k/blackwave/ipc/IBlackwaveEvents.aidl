package com.fr3k.blackwave.ipc;

/** One wake-up per registration. Fetch durable events, persist cursor, then rearm. */
oneway interface IBlackwaveEvents {
    void onEventsAvailable(long latestSequence) = 0;
    void onClosed(String reason) = 1;
}

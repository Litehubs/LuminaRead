package com.luminaread.util;

import java.util.concurrent.atomic.AtomicLong;

/** Tracks which asynchronous result is still allowed to update its view. */
public final class LatestRequest {
    private final AtomicLong generation = new AtomicLong();
    public long next() { return generation.incrementAndGet(); }
    public void invalidate() { generation.incrementAndGet(); }
    public boolean isCurrent(long token) { return generation.get() == token; }
}

package com.luminaread;

import com.luminaread.util.LatestRequest;

public final class LatestRequestTest {
    public static void main(String[] args) {
        LatestRequest requests = new LatestRequest();
        long first = requests.next();
        long second = requests.next();
        if (requests.isCurrent(first) || !requests.isCurrent(second))
            throw new AssertionError("An older request must not replace the latest result");
        requests.invalidate();
        if (requests.isCurrent(second)) throw new AssertionError("Invalidation must discard pending results");
        System.out.println("PASS latest request tracking");
    }
}

package com.luminaread.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class ImageUrlPolicyTest {
    @Test void checksAllDnsResultsAndEachRedirect() throws Exception {
        InetAddress publicAddress = InetAddress.getByName("93.184.216.34");
        InetAddress privateAddress = InetAddress.getByName("::1");
        var policy = new ImageUrlPolicy(host -> host.equals("cdn.example")
                ? new InetAddress[]{publicAddress} : new InetAddress[]{publicAddress, privateAddress}, false);
        URI first = URI.create("https://cdn.example/covers/first");
        policy.check(first);
        URI next = ImageUrlPolicy.redirect(first, "replacement");
        assertEquals("https://cdn.example/covers/replacement", next.toString());
        policy.check(next);
        assertThrows(IOException.class, () -> policy.check(ImageUrlPolicy.redirect(first, "//untrusted.example/target")));
        assertThrows(IOException.class, () -> ImageUrlPolicy.redirect(first, "http://cdn.example/target"));
        assertThrows(IOException.class, () -> policy.check(URI.create("https://user@cdn.example/target")));
        assertThrows(IOException.class, () -> policy.check(URI.create("https://cdn.example:8080/target")));
    }
}

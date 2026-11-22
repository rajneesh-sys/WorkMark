package com.workmark.auth.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientIpResolverTest {
    @Test
    void ignoresForwardedAddressFromUntrustedRemote() {
        ClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.10");
        request.addHeader("X-Forwarded-For", "203.0.113.99");

        assertEquals("198.51.100.10", resolver.resolve(request));
    }

    @Test
    void resolvesClientThroughTrustedProxyChain() {
        ClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.8");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.1.2.3");

        assertEquals("203.0.113.7", resolver.resolve(request));
    }

    @Test
    void ignoresMalformedForwardingChain() {
        ClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.8");
        request.addHeader("X-Forwarded-For", "spoofed.example");

        assertEquals("10.0.0.8", resolver.resolve(request));
    }

    private ClientIpResolver resolver(List<String> trustedProxies) {
        LoginRateLimitProperties properties = new LoginRateLimitProperties();
        properties.setTrustedProxies(trustedProxies);
        return new ClientIpResolver(properties);
    }
}

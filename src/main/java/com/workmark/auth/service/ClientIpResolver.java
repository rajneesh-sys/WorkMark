package com.workmark.auth.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class ClientIpResolver {
    private static final Pattern IP_LITERAL = Pattern.compile("[0-9a-fA-F:.]+");
    private final List<IpAddressMatcher> trustedProxies;

    public ClientIpResolver(LoginRateLimitProperties properties) {
        this.trustedProxies = properties.getTrustedProxies().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(IpAddressMatcher::new)
                .toList();
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddress = normalize(request.getRemoteAddr());
        if (remoteAddress == null || !isTrusted(remoteAddress)) {
            return remoteAddress == null ? "unknown" : remoteAddress;
        }

        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return remoteAddress;
        }
        String[] parts = forwardedFor.split(",", -1);
        List<String> chain = new ArrayList<>(parts.length);
        for (String part : parts) {
            String address = normalize(part.trim());
            if (address == null) {
                return remoteAddress;
            }
            chain.add(address);
        }
        Collections.reverse(chain);
        String client = remoteAddress;
        for (String address : chain) {
            if (!isTrusted(client)) {
                break;
            }
            client = address;
        }
        return client;
    }

    private boolean isTrusted(String address) {
        return trustedProxies.stream().anyMatch(matcher -> matcher.matches(address));
    }

    private String normalize(String value) {
        if (value == null || value.isBlank() || !IP_LITERAL.matcher(value).matches()) {
            return null;
        }
        try {
            return InetAddress.getByName(value).getHostAddress();
        } catch (UnknownHostException exception) {
            return null;
        }
    }
}

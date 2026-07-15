package com.zjut.edusystem.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "edu-system.redis.ssh")
public record RedisSshProperties(
        boolean enabled,
        String host,
        int port,
        String username,
        String password,
        String localHost,
        int localPort,
        String remoteHost,
        int remotePort,
        int connectTimeoutMs
) {
}

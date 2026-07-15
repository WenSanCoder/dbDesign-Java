package com.zjut.edusystem.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisStartupConnectionReporter implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(RedisStartupConnectionReporter.class);

    private final StringRedisTemplate redisTemplate;
    private final RedisSshTunnelService tunnelService;
    private final RedisSshProperties sshProperties;
    private final int database;

    public RedisStartupConnectionReporter(
            StringRedisTemplate redisTemplate,
            RedisSshTunnelService tunnelService,
            RedisSshProperties sshProperties,
            @Value("${spring.data.redis.database:0}") int database
    ) {
        this.redisTemplate = redisTemplate;
        this.tunnelService = tunnelService;
        this.sshProperties = sshProperties;
        this.database = database;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!tunnelService.isTunnelEstablished()) {
            log.error("Redis connection failed: the SSH tunnel was not established");
            return;
        }
        try {
            String pong = redisTemplate.execute((RedisCallback<String>) connection -> connection.ping());
            if ("PONG".equalsIgnoreCase(pong)) {
                log.info(
                        "Redis connection succeeded: {}@{} -> {}:{}/{} returned PONG",
                        sshProperties.username(), sshProperties.host(),
                        sshProperties.remoteHost(), sshProperties.remotePort(), database);
                return;
            }
            log.error("Redis connection failed: PING returned {}", pong);
        } catch (Exception ex) {
            log.error(
                    "Redis connection failed: {}@{} -> {}:{}/{} ({})",
                    sshProperties.username(), sshProperties.host(),
                    sshProperties.remoteHost(), sshProperties.remotePort(), database,
                    ex.getMessage());
        }
    }
}

package com.zjut.edusystem.config;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public class RedisSshTunnelService implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(RedisSshTunnelService.class);

    private final RedisSshProperties properties;
    private volatile Session session;
    private volatile boolean running;

    public RedisSshTunnelService(RedisSshProperties properties) {
        this.properties = properties;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (!properties.enabled()) {
            running = true;
            log.info("Redis SSH tunnel is disabled; using the configured Redis endpoint directly");
            return;
        }

        try {
            JSch jsch = new JSch();
            Session newSession = jsch.getSession(
                    properties.username(), properties.host(), properties.port());
            newSession.setPassword(properties.password());
            newSession.setConfig("StrictHostKeyChecking", "no");
            newSession.setServerAliveInterval(30_000);
            newSession.setServerAliveCountMax(3);
            newSession.connect(Math.max(1_000, properties.connectTimeoutMs()));
            session = newSession;
            newSession.setPortForwardingL(
                    properties.localHost(),
                    properties.localPort(),
                    properties.remoteHost(),
                    properties.remotePort());
            running = true;
            log.info(
                    "Redis SSH tunnel established: {}:{} -> {}:{} via {}@{}:{}",
                    properties.localHost(), properties.localPort(),
                    properties.remoteHost(), properties.remotePort(),
                    properties.username(), properties.host(), properties.port());
        } catch (JSchException ex) {
            disconnectSession();
            running = false;
            log.error(
                    "Redis SSH tunnel failed: {}@{}:{} -> {}:{} ({})",
                    properties.username(), properties.host(), properties.port(),
                    properties.remoteHost(), properties.remotePort(), ex.getMessage());
        }
    }

    @Override
    public synchronized void stop() {
        disconnectSession();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public boolean isTunnelEstablished() {
        Session currentSession = session;
        return !properties.enabled() || (currentSession != null && currentSession.isConnected());
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE;
    }

    private void disconnectSession() {
        Session currentSession = session;
        session = null;
        if (currentSession != null && currentSession.isConnected()) {
            currentSession.disconnect();
            log.info("Redis SSH tunnel closed");
        }
    }
}

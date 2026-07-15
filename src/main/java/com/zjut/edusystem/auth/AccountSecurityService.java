package com.zjut.edusystem.auth;

import com.zjut.edusystem.common.BusinessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
public class AccountSecurityService {
    private static final int ITERATIONS = 120_000;
    private static final int KEY_BITS = 256;
    private static final int MAX_FAILURES = 5;
    private static final int LOCK_MINUTES = 15;

    private final NamedParameterJdbcTemplate jdbc;
    private final SecureRandom secureRandom = new SecureRandom();

    public AccountSecurityService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean enabled() {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM information_schema.tables
                    WHERE table_schema = current_schema()
                      AND table_name = 'sht_user_account_security_states13'
                      AND table_type = 'BASE TABLE'
                )
                """, new MapSqlParameterSource(), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public boolean verify(Long userId, String suppliedPassword, String legacyPassword) {
        if (!enabled()) {
            return suppliedPassword.equals(legacyPassword);
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT lr_password_hash13 AS password_hash,
                       lr_failed_login_count13 AS failed_login_count,
                       lr_locked_until13 AS locked_until
                FROM sht_user_account_security_states13
                WHERE lr_user_id13 = :userId
                """, new MapSqlParameterSource("userId", userId));
        Map<String, Object> state = rows.isEmpty() ? null : rows.get(0);
        if (state != null && locked(state.get("locked_until"))) {
            throw new BusinessException("账号已临时锁定，请稍后再试");
        }
        String storedHash = state == null || state.get("password_hash") == null
                ? null : state.get("password_hash").toString();
        boolean matched = storedHash == null
                ? suppliedPassword.equals(legacyPassword)
                : matches(suppliedPassword, storedHash);
        if (!matched) {
            recordFailure(userId, state);
            return false;
        }
        String migratedHash = storedHash == null ? encode(suppliedPassword) : storedHash;
        upsertSuccess(userId, migratedHash);
        return true;
    }

    public void changePassword(Long userId, String oldPassword, String legacyPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < 6) {
            throw new BusinessException("新密码至少需要 6 位");
        }
        if (!verify(userId, oldPassword, legacyPassword)) {
            throw new BusinessException("原密码错误");
        }
        jdbc.update("""
                UPDATE sht_user_account_security_states13
                SET lr_password_hash13 = :passwordHash,
                    lr_password_changed_at13 = CURRENT_TIMESTAMP,
                    lr_must_change_password13 = FALSE,
                    lr_failed_login_count13 = 0,
                    lr_locked_until13 = NULL,
                    lr_updated_at13 = CURRENT_TIMESTAMP
                WHERE lr_user_id13 = :userId
                """, new MapSqlParameterSource()
                .addValue("passwordHash", encode(newPassword))
                .addValue("userId", userId));
    }

    private void recordFailure(Long userId, Map<String, Object> state) {
        int failures = state == null || state.get("failed_login_count") == null
                ? 1 : ((Number) state.get("failed_login_count")).intValue() + 1;
        LocalDateTime lockedUntil = failures >= MAX_FAILURES ? LocalDateTime.now().plusMinutes(LOCK_MINUTES) : null;
        if (state == null) {
            jdbc.update("""
                    INSERT INTO sht_user_account_security_states13(
                        lr_user_id13, lr_failed_login_count13, lr_locked_until13
                    ) VALUES (:userId, :failures, :lockedUntil)
                    """, new MapSqlParameterSource()
                    .addValue("userId", userId).addValue("failures", failures).addValue("lockedUntil", lockedUntil));
        } else {
            jdbc.update("""
                    UPDATE sht_user_account_security_states13
                    SET lr_failed_login_count13 = :failures,
                        lr_locked_until13 = :lockedUntil,
                        lr_updated_at13 = CURRENT_TIMESTAMP
                    WHERE lr_user_id13 = :userId
                    """, new MapSqlParameterSource()
                    .addValue("userId", userId).addValue("failures", failures).addValue("lockedUntil", lockedUntil));
        }
    }

    private void upsertSuccess(Long userId, String passwordHash) {
        int updated = jdbc.update("""
                UPDATE sht_user_account_security_states13
                SET lr_password_hash13 = :passwordHash,
                    lr_failed_login_count13 = 0,
                    lr_locked_until13 = NULL,
                    lr_must_change_password13 = FALSE,
                    lr_updated_at13 = CURRENT_TIMESTAMP
                WHERE lr_user_id13 = :userId
                """, new MapSqlParameterSource()
                .addValue("userId", userId).addValue("passwordHash", passwordHash));
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO sht_user_account_security_states13(
                        lr_user_id13, lr_password_hash13, lr_must_change_password13,
                        lr_failed_login_count13, lr_locked_until13
                    ) VALUES (:userId, :passwordHash, FALSE, 0, NULL)
                    """, new MapSqlParameterSource().addValue("userId", userId).addValue("passwordHash", passwordHash));
        }
    }

    private boolean locked(Object value) {
        if (value == null) {
            return false;
        }
        LocalDateTime until;
        if (value instanceof LocalDateTime dateTime) {
            until = dateTime;
        } else if (value instanceof java.sql.Timestamp timestamp) {
            until = timestamp.toLocalDateTime();
        } else {
            until = LocalDateTime.parse(value.toString().replace(' ', 'T'));
        }
        return until.isAfter(LocalDateTime.now());
    }

    private String encode(String password) {
        byte[] salt = new byte[16];
        secureRandom.nextBytes(salt);
        byte[] hash = pbkdf2(password, salt, ITERATIONS);
        return "pbkdf2$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(hash);
    }

    private boolean matches(String password, String encoded) {
        try {
            String[] parts = encoded.split("\\$");
            if (parts.length != 4 || !"pbkdf2".equals(parts[0])) {
                return false;
            }
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            return MessageDigest.isEqual(expected, pbkdf2(password, salt, iterations));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash password", ex);
        }
    }
}

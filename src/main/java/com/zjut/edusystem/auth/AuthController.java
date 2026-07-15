package com.zjut.edusystem.auth;

import com.zjut.edusystem.common.ApiResponse;
import com.zjut.edusystem.common.BusinessException;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final NamedParameterJdbcTemplate jdbc;
    private final AccountSecurityService securityService;

    public AuthController(NamedParameterJdbcTemplate jdbc, AccountSecurityService securityService) {
        this.jdbc = jdbc;
        this.securityService = securityService;
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody LoginRequest request) {
        String avatarSelect = hasColumn("user_account", "avatar_path") ? ", avatar_path" : ", NULL AS avatar_path";
        String sql = """
                SELECT user_id, username, password_text, role_code, display_name, related_id
                %s
                FROM user_account
                WHERE username = :username
                  AND status = 'enabled'
                """.formatted(avatarSelect);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("username", request.username());
        List<Map<String, Object>> rows = jdbc.queryForList(sql, params);
        if (rows.isEmpty()) {
            throw new BusinessException("\u8d26\u53f7\u6216\u5bc6\u7801\u9519\u8bef");
        }
        Map<String, Object> user = rows.get(0);
        Long userId = ((Number) user.get("user_id")).longValue();
        String legacyPassword = String.valueOf(user.get("password_text"));
        if (!securityService.verify(userId, request.password(), legacyPassword)) {
            throw new BusinessException("\u8d26\u53f7\u6216\u5bc6\u7801\u9519\u8bef");
        }
        jdbc.update("UPDATE user_account SET last_login_at = :now WHERE username = :username",
                new MapSqlParameterSource().addValue("now", LocalDateTime.now()).addValue("username", request.username()));
        user.remove("password_text");
        user.put("permission_codes", jdbc.queryForList("""
                SELECT DISTINCT p.lr_permission_code13
                FROM sht_user_roles13 ur
                JOIN sht_system_roles13 r ON r.lr_role_id13 = ur.lr_role_id13 AND r.lr_status13 = 'enabled'
                JOIN sht_role_permissions13 rp ON rp.lr_role_id13 = r.lr_role_id13
                JOIN sht_system_permissions13 p ON p.lr_permission_id13 = rp.lr_permission_id13
                WHERE ur.lr_user_id13 = :userId
                ORDER BY p.lr_permission_code13
                """, new MapSqlParameterSource("userId", userId), String.class));
        user.put("assigned_roles", jdbc.queryForList("""
                SELECT r.lr_role_code13 AS role_code, r.lr_role_name13 AS role_name, r.lr_is_system13 AS is_system
                FROM sht_user_roles13 ur JOIN sht_system_roles13 r ON r.lr_role_id13 = ur.lr_role_id13
                WHERE ur.lr_user_id13 = :userId ORDER BY r.lr_is_system13 DESC, r.lr_role_name13
                """, new MapSqlParameterSource("userId", userId)));
        user.put("token", "demo-token-" + user.get("role_code") + "-" + user.get("user_id"));
        return ApiResponse.ok("\u767b\u5f55\u6210\u529f", user);
    }

    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(@RequestBody ChangePasswordRequest request) {
        List<String> legacyPasswords = jdbc.queryForList("""
                SELECT password_text FROM user_account
                WHERE user_id = :userId AND status = 'enabled'
                """, new MapSqlParameterSource("userId", request.userId()), String.class);
        if (legacyPasswords.isEmpty()) {
            throw new BusinessException("账号不存在或已停用");
        }
        securityService.changePassword(
                request.userId(), request.oldPassword(), legacyPasswords.get(0), request.newPassword());
        return ApiResponse.ok("密码修改成功", null);
    }

    private boolean hasColumn(String tableName, String columnName) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.columns
                    WHERE table_name = :tableName
                      AND column_name = :columnName
                )
                """, new MapSqlParameterSource()
                .addValue("tableName", tableName)
                .addValue("columnName", columnName), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record ChangePasswordRequest(Long userId, @NotBlank String oldPassword, @NotBlank String newPassword) {
    }
}

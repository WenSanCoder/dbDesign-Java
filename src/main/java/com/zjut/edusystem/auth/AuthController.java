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

    public AuthController(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody LoginRequest request) {
        String sql = """
                SELECT user_id, username, role_code, display_name, related_id
                FROM user_account
                WHERE username = :username
                  AND password_text = :password
                  AND status = 'enabled'
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("username", request.username())
                .addValue("password", request.password());
        List<Map<String, Object>> rows = jdbc.queryForList(sql, params);
        if (rows.isEmpty()) {
            throw new BusinessException("\u8d26\u53f7\u6216\u5bc6\u7801\u9519\u8bef");
        }
        jdbc.update("UPDATE user_account SET last_login_at = :now WHERE username = :username",
                new MapSqlParameterSource().addValue("now", LocalDateTime.now()).addValue("username", request.username()));
        Map<String, Object> user = rows.get(0);
        user.put("token", "demo-token-" + user.get("role_code") + "-" + user.get("user_id"));
        return ApiResponse.ok("\u767b\u5f55\u6210\u529f", user);
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }
}

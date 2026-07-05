package com.zjut.edusystem.profile;

import com.zjut.edusystem.common.ApiResponse;
import com.zjut.edusystem.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {
    private static final long MAX_AVATAR_BYTES = 1024L * 1024L;
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final NamedParameterJdbcTemplate jdbc;
    private final AvatarStorageService avatarStorageService;

    @Value("${edu-system.upload.public-base-url:}")
    private String publicBaseUrl;

    public ProfileController(NamedParameterJdbcTemplate jdbc, AvatarStorageService avatarStorageService) {
        this.jdbc = jdbc;
        this.avatarStorageService = avatarStorageService;
    }

    @PostMapping(value = "/{userId}/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> uploadAvatar(@PathVariable Long userId, @RequestPart("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new BusinessException("请选择头像文件");
        }
        if (file.getSize() > MAX_AVATAR_BYTES) {
            throw new BusinessException("头像文件不能超过 1MB");
        }

        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!ALLOWED_TYPES.contains(contentType)) {
            throw new BusinessException("头像仅支持 JPG、PNG 或 WebP 格式");
        }

        ensureAvatarColumn();

        String extension = switch (contentType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
        String filename = "avatar-" + userId + "-"
                + DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now())
                + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8)
                + extension;

        avatarStorageService.saveAvatar(filename, file);

        String relativePath = "/image/" + filename;
        int updated = jdbc.update("""
                UPDATE user_account
                SET avatar_path = :avatarPath
                WHERE user_id = :userId
                """, new MapSqlParameterSource()
                .addValue("avatarPath", relativePath)
                .addValue("userId", userId));
        if (updated == 0) {
            throw new BusinessException("用户不存在");
        }

        return ApiResponse.ok("头像上传成功", Map.of(
                "avatar_path", relativePath,
                "avatar_url", buildPublicUrl(relativePath)
        ));
    }

    private void ensureAvatarColumn() {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.columns
                    WHERE table_name = 'user_account'
                      AND column_name = 'avatar_path'
                )
                """, new MapSqlParameterSource(), Boolean.class);
        if (!Boolean.TRUE.equals(exists)) {
            jdbc.getJdbcTemplate().execute("ALTER TABLE user_account ADD COLUMN avatar_path VARCHAR(255)");
        }
    }

    private String buildPublicUrl(String relativePath) {
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            return relativePath;
        }
        return publicBaseUrl.replaceAll("/+$", "") + relativePath;
    }
}

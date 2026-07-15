package com.zjut.edusystem.admin;

import com.zjut.edusystem.common.ApiResponse;
import com.zjut.edusystem.common.BusinessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/user-management")
public class AdminUserManagementController {
    private final NamedParameterJdbcTemplate jdbc;

    public AdminUserManagementController(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/lookups")
    public ApiResponse<Map<String, Object>> lookups() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("roles", jdbc.queryForList("SELECT lr_role_id13 AS role_id, lr_role_code13 AS role_code, lr_role_name13 AS role_name, lr_is_system13 AS is_system FROM sht_system_roles13 WHERE lr_status13 = 'enabled' ORDER BY lr_is_system13 DESC, lr_role_name13", Map.of()));
        result.put("colleges", jdbc.queryForList("SELECT college_id, college_code, college_name FROM college WHERE status = 'enabled' ORDER BY college_name", Map.of()));
        result.put("majors", jdbc.queryForList("SELECT major_id, major_code, major_name, college_id FROM major WHERE status = 'enabled' ORDER BY major_name", Map.of()));
        result.put("gradeYears", jdbc.queryForList("SELECT grade_year FROM grade_year WHERE status = 'enabled' ORDER BY grade_year DESC", Map.of()));
        result.put("adminClasses", jdbc.queryForList("""
                SELECT ac.admin_class_id, ac.class_code, ac.class_name, ac.grade_year,
                       ac.major_id, major.major_name, major.college_id, college.college_name
                FROM admin_class ac JOIN major ON major.major_id = ac.major_id
                JOIN college ON college.college_id = major.college_id
                WHERE ac.status = 'enabled' ORDER BY ac.grade_year DESC, ac.class_code
                """, Map.of()));
        result.put("regions", jdbc.queryForList("SELECT region_id, region_code, region_name FROM region ORDER BY region_name", Map.of()));
        result.put("students", jdbc.queryForList("SELECT student_id, student_no, student_name FROM student ORDER BY student_no", Map.of()));
        result.put("teachers", jdbc.queryForList("SELECT teacher_id, teacher_no, teacher_name FROM teacher ORDER BY teacher_no", Map.of()));
        return ApiResponse.ok(result);
    }

    @GetMapping("/users")
    public ApiResponse<Map<String, Object>> users(@RequestParam Map<String, String> filters) {
        int page = positive(filters.get("page"), 1);
        int pageSize = Math.min(positive(filters.get("pageSize"), 20), 100);
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", pageSize).addValue("offset", (page - 1) * pageSize);
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (StringUtils.hasText(filters.get("keyword"))) {
            where.append(" AND (LOWER(ua.username) LIKE :keyword OR LOWER(ua.display_name) LIKE :keyword)");
            params.addValue("keyword", "%" + filters.get("keyword").trim().toLowerCase() + "%");
        }
        addTextFilter(where, params, filters, "status", "ua.status");
        if (StringUtils.hasText(filters.get("roleId"))) {
            where.append(" AND role.lr_role_id13 = :roleId");
            params.addValue("roleId", Long.valueOf(filters.get("roleId")));
        }
        String fromSql = " FROM user_account ua LEFT JOIN sht_system_roles13 role ON role.lr_role_code13 = ua.role_code" + where;
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + fromSql, params, Long.class);
        List<Map<String, Object>> records = jdbc.queryForList("""
                SELECT ua.user_id, ua.username, ua.role_code, ua.display_name, ua.related_id,
                       ua.status, ua.last_login_at, ua.avatar_path, role.lr_role_name13 AS role_name,
                       role.lr_is_system13 AS is_system_role
                """ + fromSql + " ORDER BY ua.user_id DESC LIMIT :limit OFFSET :offset", params);
        return ApiResponse.ok(pageResult(records, total, page, pageSize));
    }

    @GetMapping("/users/{userId}")
    public ApiResponse<Map<String, Object>> userDetail(@PathVariable Long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT ua.user_id, ua.username, ua.role_code, ua.display_name, ua.related_id,
                       ua.status, ua.last_login_at, ua.avatar_path, role.lr_role_id13 AS role_id,
                       role.lr_role_name13 AS role_name, role.lr_is_system13 AS is_system_role,
                       security.lr_password_changed_at13 AS password_changed_at,
                       security.lr_must_change_password13 AS must_change_password,
                       security.lr_failed_login_count13 AS failed_login_count,
                       security.lr_locked_until13 AS locked_until,
                       (security.lr_password_hash13 IS NOT NULL OR ua.password_text IS NOT NULL) AS password_configured
                FROM user_account ua LEFT JOIN sht_system_roles13 role ON role.lr_role_code13 = ua.role_code
                LEFT JOIN sht_user_account_security_states13 security ON security.lr_user_id13 = ua.user_id
                WHERE ua.user_id = :id
                """, new MapSqlParameterSource("id", userId));
        if (rows.isEmpty()) throw new BusinessException("用户不存在");
        Map<String, Object> user = new LinkedHashMap<>(rows.get(0));
        Object relatedId = user.get("related_id");
        if (relatedId != null && "STUDENT".equals(user.get("role_code"))) {
            user.put("identity", first("""
                    SELECT s.student_id, s.student_no, s.student_name, s.gender, s.age, s.phone,
                           s.grade_year, s.admin_class_id, s.region_id, s.total_credits,
                           s.status AS identity_status, ac.class_name, ac.major_id,
                           major.major_name, major.college_id, college.college_name, region.region_name
                    FROM student s JOIN admin_class ac ON ac.admin_class_id = s.admin_class_id
                    JOIN major ON major.major_id = ac.major_id JOIN college ON college.college_id = major.college_id
                    LEFT JOIN region ON region.region_id = s.region_id WHERE s.student_id = :id
                    """, ((Number) relatedId).longValue()));
        } else if (relatedId != null && "TEACHER".equals(user.get("role_code"))) {
            user.put("identity", first("""
                    SELECT teacher.teacher_id, teacher.teacher_no, teacher.teacher_name, teacher.gender,
                           teacher.age, teacher.title, teacher.phone, teacher.college_id,
                           college.college_name, teacher.status AS identity_status
                    FROM teacher JOIN college ON college.college_id = teacher.college_id
                    WHERE teacher.teacher_id = :id
                    """, ((Number) relatedId).longValue()));
        } else {
            user.put("identity", Map.of());
        }
        return ApiResponse.ok(user);
    }

    @PostMapping("/users")
    @Transactional
    public ApiResponse<Void> createUser(@RequestBody Map<String, Object> body) {
        String username = required(body, "username");
        String password = required(body, "password");
        validatePassword(password);
        String displayName = required(body, "displayName");
        Long roleId = requiredLong(body, "roleId", "请选择角色");
        Map<String, Object> role = role(roleId);
        String roleCode = String.valueOf(role.get("role_code"));
        Long relatedId = number(body.get("relatedId"));
        if (isRelatedIdentity(roleCode) && relatedId == null) throw new BusinessException("教师或学生角色必须关联已有身份记录");
        if (isRelatedIdentity(roleCode)) validateRelated(roleCode, relatedId, null);
        else relatedId = null;
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("username", username).addValue("password", password)
                .addValue("roleCode", roleCode).addValue("displayName", displayName).addValue("relatedId", relatedId)
                .addValue("status", StringUtils.hasText(text(body.get("status"))) ? text(body.get("status")) : "enabled");
        jdbc.update("INSERT INTO user_account(username, password_text, role_code, display_name, related_id, status) VALUES (:username, :password, :roleCode, :displayName, :relatedId, :status)", params);
        Long userId = jdbc.queryForObject("SELECT user_id FROM user_account WHERE username = :username", params, Long.class);
        saveUserRole(userId, roleId, number(body.get("assignedBy")));
        return ApiResponse.ok("用户已创建", null);
    }

    @PutMapping("/users/{userId}")
    @Transactional
    public ApiResponse<Void> updateUser(@PathVariable Long userId, @RequestBody Map<String, Object> body) {
        Map<String, Object> current = account(userId);
        String username = required(body, "username");
        String displayName = required(body, "displayName");
        String accountStatus = required(body, "status");
        Long roleId = requiredLong(body, "roleId", "请选择角色身份");
        Map<String, Object> targetRole = role(roleId);
        String roleCode = String.valueOf(targetRole.get("role_code"));
        Long relatedId = number(body.get("relatedId"));
        if (isRelatedIdentity(roleCode)) {
            if (relatedId == null) throw new BusinessException("教师或学生角色必须关联已有身份记录");
            validateRelated(roleCode, relatedId, userId);
        } else {
            relatedId = null;
        }
        jdbc.update("""
                UPDATE user_account SET username = :username, display_name = :displayName,
                       role_code = :roleCode, related_id = :relatedId, status = :status
                WHERE user_id = :userId
                """, new MapSqlParameterSource().addValue("username", username).addValue("displayName", displayName)
                .addValue("roleCode", roleCode).addValue("relatedId", relatedId).addValue("status", accountStatus).addValue("userId", userId));
        saveUserRole(userId, roleId, number(body.get("assignedBy")));

        Map<String, Object> identity = objectMap(body.get("identity"));
        if ("STUDENT".equals(roleCode)) updateStudent(relatedId, displayName, identity);
        if ("TEACHER".equals(roleCode)) updateTeacher(relatedId, displayName, identity);
        String newPassword = text(body.get("password"));
        if (StringUtils.hasText(newPassword)) resetPassword(userId, newPassword);
        return ApiResponse.ok("用户信息已更新", null);
    }

    @DeleteMapping("/users/{userId}")
    @Transactional
    public ApiResponse<Void> deleteUser(@PathVariable Long userId, @RequestParam Long operatorUserId) {
        if (userId.equals(operatorUserId)) throw new BusinessException("不能删除当前登录账号");
        account(userId);
        Long blockers = jdbc.queryForObject("""
                SELECT
                    (SELECT COUNT(*) FROM sht_grade_workflow_batches13 WHERE lr_submitted_by13 = :userId) +
                    (SELECT COUNT(*) FROM sht_operation_plans13 WHERE lr_user_id13 = :userId) +
                    (SELECT COUNT(*) FROM sht_operation_approvals13 WHERE lr_approver_user_id13 = :userId)
                """, new MapSqlParameterSource("userId", userId), Long.class);
        if (blockers != null && blockers > 0) {
            throw new BusinessException("该用户已有成绩提交或高风险操作记录，不能删除；请将账号状态改为停用");
        }
        MapSqlParameterSource params = new MapSqlParameterSource("userId", userId);
        jdbc.update("UPDATE notice SET user_id = NULL WHERE user_id = :userId", params);
        jdbc.update("UPDATE sht_user_roles13 SET lr_assigned_by13 = NULL WHERE lr_assigned_by13 = :userId", params);
        jdbc.update("UPDATE sht_user_data_scopes13 SET lr_created_by13 = NULL WHERE lr_created_by13 = :userId", params);
        jdbc.update("UPDATE sht_student_plan_adjustments13 SET lr_approved_by13 = NULL WHERE lr_approved_by13 = :userId", params);
        jdbc.update("UPDATE sht_student_program_changes13 SET lr_approved_by13 = NULL WHERE lr_approved_by13 = :userId", params);
        jdbc.update("UPDATE sht_grade_workflow_batches13 SET lr_reviewed_by13 = NULL WHERE lr_reviewed_by13 = :userId", params);
        int deleted = jdbc.update("DELETE FROM user_account WHERE user_id = :userId", params);
        if (deleted == 0) throw new BusinessException("用户不存在");
        return ApiResponse.ok("用户账号已删除，关联的学生或教师档案已保留", null);
    }

    private void updateStudent(Long studentId, String displayName, Map<String, Object> identity) {
        Long adminClassId = requiredLong(identity, "adminClassId", "请选择行政班");
        Integer gradeYear = integer(identity.get("gradeYear"));
        Long majorId = number(identity.get("majorId"));
        Long collegeId = number(identity.get("collegeId"));
        Long matches = jdbc.queryForObject("""
                SELECT COUNT(*) FROM admin_class ac JOIN major ON major.major_id = ac.major_id
                WHERE ac.admin_class_id = :classId AND ac.grade_year = :gradeYear
                  AND (:majorId IS NULL OR major.major_id = :majorId)
                  AND (:collegeId IS NULL OR major.college_id = :collegeId)
                """, new MapSqlParameterSource().addValue("classId", adminClassId).addValue("gradeYear", gradeYear)
                .addValue("majorId", majorId, java.sql.Types.BIGINT).addValue("collegeId", collegeId, java.sql.Types.BIGINT), Long.class);
        if (matches == null || matches == 0) throw new BusinessException("行政班与所选学院、专业或入学年级不一致");
        jdbc.update("""
                UPDATE student SET student_no = :number, student_name = :name, gender = :gender,
                       age = :age, phone = :phone, grade_year = :gradeYear,
                       admin_class_id = :classId, region_id = :regionId, status = :status
                WHERE student_id = :studentId
                """, new MapSqlParameterSource().addValue("number", required(identity, "number"))
                .addValue("name", displayName).addValue("gender", required(identity, "gender"))
                .addValue("age", integer(identity.get("age"))).addValue("phone", text(identity.get("phone")))
                .addValue("gradeYear", gradeYear).addValue("classId", adminClassId)
                .addValue("regionId", resolveRegionId(identity), java.sql.Types.BIGINT).addValue("status", required(identity, "identityStatus"))
                .addValue("studentId", studentId));
    }

    private void updateTeacher(Long teacherId, String displayName, Map<String, Object> identity) {
        jdbc.update("""
                UPDATE teacher SET teacher_no = :number, teacher_name = :name, gender = :gender,
                       age = :age, title = :title, phone = :phone, college_id = :collegeId, status = :status
                WHERE teacher_id = :teacherId
                """, new MapSqlParameterSource().addValue("number", required(identity, "number"))
                .addValue("name", displayName).addValue("gender", required(identity, "gender"))
                .addValue("age", integer(identity.get("age"))).addValue("title", text(identity.get("title")))
                .addValue("phone", text(identity.get("phone"))).addValue("collegeId", requiredLong(identity, "collegeId", "请选择学院"))
                .addValue("status", required(identity, "identityStatus")).addValue("teacherId", teacherId));
    }

    private void resetPassword(Long userId, String newPassword) {
        validatePassword(newPassword);
        jdbc.update("UPDATE user_account SET password_text = :password WHERE user_id = :userId",
                new MapSqlParameterSource().addValue("password", newPassword).addValue("userId", userId));
        jdbc.update("DELETE FROM sht_user_account_security_states13 WHERE lr_user_id13 = :userId", new MapSqlParameterSource("userId", userId));
    }

    private Long resolveRegionId(Map<String, Object> identity) {
        String regionName = text(identity.get("regionName"));
        if (!StringUtils.hasText(regionName)) return number(identity.get("regionId"));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT region_id FROM region WHERE LOWER(region_name) = LOWER(:name) ORDER BY region_id LIMIT 1",
                new MapSqlParameterSource("name", regionName));
        if (!rows.isEmpty()) return ((Number) rows.get(0).get("region_id")).longValue();
        String regionCode = "CUSTOM_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("code", regionCode).addValue("name", regionName);
        jdbc.update("INSERT INTO region(region_code, region_name) VALUES (:code, :name)", params);
        return jdbc.queryForObject("SELECT region_id FROM region WHERE region_code = :code", params, Long.class);
    }

    private void saveUserRole(Long userId, Long roleId, Long assignedBy) {
        jdbc.update("DELETE FROM sht_user_roles13 WHERE lr_user_id13 = :userId", new MapSqlParameterSource("userId", userId));
        jdbc.update("INSERT INTO sht_user_roles13(lr_user_id13, lr_role_id13, lr_assigned_by13) VALUES (:userId, :roleId, :assignedBy)",
                new MapSqlParameterSource().addValue("userId", userId).addValue("roleId", roleId).addValue("assignedBy", assignedBy));
    }

    private void validateRelated(String roleCode, Long relatedId, Long currentUserId) {
        String table = "STUDENT".equals(roleCode) ? "student" : "teacher";
        String idColumn = "STUDENT".equals(roleCode) ? "student_id" : "teacher_id";
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + idColumn + " = :id", new MapSqlParameterSource("id", relatedId), Long.class);
        if (count == null || count == 0) throw new BusinessException("关联身份记录不存在");
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("roleCode", roleCode).addValue("id", relatedId)
                .addValue("currentUserId", currentUserId, java.sql.Types.BIGINT);
        Long used = jdbc.queryForObject("SELECT COUNT(*) FROM user_account WHERE role_code = :roleCode AND related_id = :id AND (:currentUserId IS NULL OR user_id <> :currentUserId)", params, Long.class);
        if (used != null && used > 0) throw new BusinessException("该身份已经建立登录账号");
    }

    private Map<String, Object> account(Long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT user_id, role_code, related_id FROM user_account WHERE user_id = :id", new MapSqlParameterSource("id", userId));
        if (rows.isEmpty()) throw new BusinessException("用户不存在");
        return rows.get(0);
    }

    private Map<String, Object> role(Long roleId) {
        List<Map<String, Object>> roles = jdbc.queryForList("SELECT lr_role_code13 AS role_code, lr_role_name13 AS role_name, lr_is_system13 AS is_system FROM sht_system_roles13 WHERE lr_role_id13 = :id AND lr_status13 = 'enabled'", new MapSqlParameterSource("id", roleId));
        if (roles.isEmpty()) throw new BusinessException("角色不存在或已停用");
        return roles.get(0);
    }

    private Map<String, Object> first(String sql, Long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, new MapSqlParameterSource("id", id));
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private Map<String, Object> pageResult(List<Map<String, Object>> records, Long total, int page, int pageSize) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records); result.put("total", total == null ? 0 : total); result.put("page", page); result.put("pageSize", pageSize);
        return result;
    }

    private Map<String, Object> objectMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private boolean isRelatedIdentity(String roleCode) { return "TEACHER".equals(roleCode) || "STUDENT".equals(roleCode); }
    private void addTextFilter(StringBuilder where, MapSqlParameterSource params, Map<String, String> filters, String key, String column) { if (StringUtils.hasText(filters.get(key))) { where.append(" AND ").append(column).append(" = :").append(key); params.addValue(key, filters.get(key).trim()); } }
    private void validatePassword(String password) { if (password.length() < 6) throw new BusinessException("密码至少需要 6 位"); }
    private int positive(String value, int fallback) { try { int n = Integer.parseInt(value); return n > 0 ? n : fallback; } catch (Exception ignored) { return fallback; } }
    private String required(Map<String, Object> body, String key) { String value = text(body.get(key)); if (!StringUtils.hasText(value)) throw new BusinessException(key + "不能为空"); return value; }
    private Long requiredLong(Map<String, Object> body, String key, String message) { Long value = number(body.get(key)); if (value == null) throw new BusinessException(message); return value; }
    private String text(Object value) { return value == null ? null : String.valueOf(value).trim(); }
    private Long number(Object value) { if (value == null || !StringUtils.hasText(String.valueOf(value))) return null; return Long.valueOf(String.valueOf(value)); }
    private Integer integer(Object value) { if (value == null || !StringUtils.hasText(String.valueOf(value))) return null; return Integer.valueOf(String.valueOf(value)); }
}

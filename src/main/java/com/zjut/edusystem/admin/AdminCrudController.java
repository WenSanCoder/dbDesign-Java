package com.zjut.edusystem.admin;

import com.zjut.edusystem.common.ApiResponse;
import com.zjut.edusystem.common.BusinessException;
import com.zjut.edusystem.common.CrudDefinition;
import com.zjut.edusystem.common.CrudService;
import jakarta.validation.Valid;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminCrudController {
    private final CrudService crudService;
    private final NamedParameterJdbcTemplate jdbc;
    private final Map<String, CrudDefinition> definitions = new LinkedHashMap<>();

    public AdminCrudController(CrudService crudService, NamedParameterJdbcTemplate jdbc) {
        this.crudService = crudService;
        this.jdbc = jdbc;
        registerDefinitions();
    }

    @GetMapping("/{resource}")
    public ApiResponse<List<Map<String, Object>>> list(@PathVariable String resource, @RequestParam Map<String, String> filters) {
        return ApiResponse.ok(crudService.list(definition(resource), filters));
    }

    @GetMapping("/{resource}/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable String resource, @PathVariable Long id) {
        return ApiResponse.ok(crudService.get(definition(resource), id));
    }

    @PostMapping("/{resource}")
    @Transactional
    public ApiResponse<Void> create(@PathVariable String resource, @Valid @RequestBody Map<String, Object> body) {
        crudService.create(definition(resource), body);
        if ("students".equals(resource)) {
            Long studentId = findIdByCode("student", "student_id", "student_no", text(body.get("student_no")));
            syncAccount("STUDENT", studentId, text(body.get("student_name")), text(body.get("student_no")), body, true);
        } else if ("teachers".equals(resource)) {
            Long teacherId = findIdByCode("teacher", "teacher_id", "teacher_no", text(body.get("teacher_no")));
            syncAccount("TEACHER", teacherId, text(body.get("teacher_name")), text(body.get("teacher_no")), body, true);
        }
        return ApiResponse.ok("新增成功", null);
    }

    @PutMapping("/{resource}/{id}")
    @Transactional
    public ApiResponse<Void> update(@PathVariable String resource, @PathVariable Long id, @RequestBody Map<String, Object> body) {
        CrudDefinition definition = definition(resource);
        boolean accountResource = "students".equals(resource) || "teachers".equals(resource);
        boolean hasWritableFields = hasWritableFields(definition, body);
        if (hasWritableFields) {
            crudService.update(definition, id, body);
        } else if (!accountResource) {
            throw new BusinessException("没有可更新字段");
        }

        if ("students".equals(resource)) {
            Map<String, Object> student = crudService.get(definition, id);
            syncAccount("STUDENT", id, text(student.get("student_name")), text(student.get("student_no")), body, false);
        } else if ("teachers".equals(resource)) {
            Map<String, Object> teacher = crudService.get(definition, id);
            syncAccount("TEACHER", id, text(teacher.get("teacher_name")), text(teacher.get("teacher_no")), body, false);
        }
        return ApiResponse.ok("更新成功", null);
    }

    @DeleteMapping("/{resource}/{id}")
    @Transactional
    public ApiResponse<Void> delete(@PathVariable String resource, @PathVariable Long id) {
        if ("students".equals(resource)) {
            deleteAccount("STUDENT", id);
        } else if ("teachers".equals(resource)) {
            deleteAccount("TEACHER", id);
        }
        crudService.delete(definition(resource), id);
        return ApiResponse.ok("删除成功", null);
    }

    private CrudDefinition definition(String resource) {
        CrudDefinition definition = definitions.get(resource);
        if (definition == null) {
            throw new BusinessException("未知管理资源：" + resource);
        }
        return definition;
    }

    private void registerDefinitions() {
        definitions.put("colleges", new CrudDefinition(
                "college",
                "college_id",
                List.of("college_code", "college_name", "contact_phone", "status"),
                "SELECT * FROM college",
                "college_id DESC"
        ));
        definitions.put("majors", new CrudDefinition(
                "major",
                "major_id",
                List.of("major_code", "major_name", "college_id", "duration_years", "degree_type", "min_graduate_credit", "status"),
                "SELECT major.*, college.college_name FROM major JOIN college ON college.college_id = major.college_id",
                "major.major_id DESC"
        ));
        definitions.put("admin-classes", new CrudDefinition(
                "admin_class",
                "admin_class_id",
                List.of("class_code", "class_name", "major_id", "grade_year", "head_teacher_id", "status"),
                "SELECT ac.*, m.major_name, c.college_id, c.college_name, t.teacher_name AS head_teacher_name FROM admin_class ac JOIN major m ON m.major_id = ac.major_id JOIN college c ON c.college_id = m.college_id LEFT JOIN teacher t ON t.teacher_id = ac.head_teacher_id",
                "ac.admin_class_id DESC"
        ));
        definitions.put("students", new CrudDefinition(
                "student",
                "student_id",
                List.of("student_no", "student_name", "gender", "age", "phone", "admin_class_id", "region_id", "status"),
                "SELECT s.*, ac.class_name, m.major_name, c.college_name, r.region_name, ua.user_id AS account_user_id, ua.username, ua.role_code, ua.status AS account_status, ua.last_login_at, ua.avatar_path FROM student s JOIN admin_class ac ON ac.admin_class_id = s.admin_class_id JOIN major m ON m.major_id = ac.major_id JOIN college c ON c.college_id = m.college_id LEFT JOIN region r ON r.region_id = s.region_id LEFT JOIN user_account ua ON ua.role_code = 'STUDENT' AND ua.related_id = s.student_id",
                "s.student_id DESC"
        ));
        definitions.put("teachers", new CrudDefinition(
                "teacher",
                "teacher_id",
                List.of("teacher_no", "teacher_name", "gender", "age", "title", "phone", "college_id", "status"),
                "SELECT teacher.*, college.college_name, ua.user_id AS account_user_id, ua.username, ua.role_code, ua.status AS account_status, ua.last_login_at, ua.avatar_path FROM teacher JOIN college ON college.college_id = teacher.college_id LEFT JOIN user_account ua ON ua.role_code = 'TEACHER' AND ua.related_id = teacher.teacher_id",
                "teacher.teacher_id DESC"
        ));
        definitions.put("courses", new CrudDefinition(
                "course",
                "course_id",
                List.of("course_code", "course_name", "college_id", "credit", "hours", "exam_type", "course_type", "description", "status"),
                "SELECT course.*, college.college_name FROM course JOIN college ON college.college_id = course.college_id",
                "course.course_id DESC"
        ));
        definitions.put("teaching-classes", new CrudDefinition(
                "teaching_class",
                "teaching_class_id",
                List.of("class_code", "class_name", "course_id", "teacher_id", "term_id", "capacity", "selected_count", "waitlist_count", "status"),
                "SELECT tc.*, c.course_name, t.teacher_name, term.academic_year, term.semester FROM teaching_class tc JOIN course c ON c.course_id = tc.course_id JOIN teacher t ON t.teacher_id = tc.teacher_id JOIN term ON term.term_id = tc.term_id",
                "tc.teaching_class_id DESC"
        ));
        definitions.put("rounds", new CrudDefinition(
                "course_selection_round",
                "round_id",
                List.of("term_id", "round_name", "start_time", "end_time", "status", "waitlist_enabled"),
                "SELECT csr.*, term.academic_year, term.semester FROM course_selection_round csr JOIN term ON term.term_id = csr.term_id",
                "csr.round_id DESC"
        ));
        definitions.put("terms", new CrudDefinition(
                "term",
                "term_id",
                List.of("academic_year", "semester", "start_date", "end_date", "is_current"),
                "SELECT * FROM term",
                "term_id DESC"
        ));
    }

    private Long findIdByCode(String table, String idColumn, String codeColumn, String code) {
        if (!StringUtils.hasText(code)) {
            throw new BusinessException("编号不能为空，无法创建关联账号");
        }
        String sql = "SELECT " + idColumn + " FROM " + table + " WHERE " + codeColumn + " = :code ORDER BY " + idColumn + " DESC LIMIT 1";
        List<Long> ids = jdbc.queryForList(sql, new MapSqlParameterSource("code", code), Long.class);
        if (ids.isEmpty()) {
            throw new BusinessException("创建成功但未找到关联记录，无法创建登录账号");
        }
        return ids.get(0);
    }

    private void syncAccount(String roleCode, Long relatedId, String displayName, String defaultUsername, Map<String, Object> body, boolean createMode) {
        String username = text(body.get("username"));
        String password = text(body.get("password_text"));
        String accountStatus = text(body.get("account_status"));

        MapSqlParameterSource queryParams = new MapSqlParameterSource()
                .addValue("roleCode", roleCode)
                .addValue("relatedId", relatedId);
        List<Long> accountIds = jdbc.queryForList(
                "SELECT user_id FROM user_account WHERE role_code = :roleCode AND related_id = :relatedId",
                queryParams,
                Long.class
        );

        if (accountIds.isEmpty()) {
            if (!StringUtils.hasText(username)) {
                username = defaultUsername;
            }
            if (!StringUtils.hasText(accountStatus)) {
                accountStatus = "enabled";
            }
            String initialPassword = StringUtils.hasText(password) ? password : "123456";
            jdbc.update("""
                    INSERT INTO user_account (username, password_text, role_code, display_name, related_id, status)
                    VALUES (:username, :passwordText, :roleCode, :displayName, :relatedId, :status)
                    """,
                    new MapSqlParameterSource()
                            .addValue("username", username)
                            .addValue("passwordText", initialPassword)
                            .addValue("roleCode", roleCode)
                            .addValue("displayName", displayName)
                            .addValue("relatedId", relatedId)
                            .addValue("status", accountStatus)
            );
            return;
        }

        Map<String, Object> currentAccount = jdbc.queryForMap(
                "SELECT username, status FROM user_account WHERE user_id = :userId",
                new MapSqlParameterSource("userId", accountIds.get(0))
        );
        if (!StringUtils.hasText(username)) {
            username = text(currentAccount.get("username"));
        }
        if (!StringUtils.hasText(accountStatus)) {
            accountStatus = text(currentAccount.get("status"));
        }

        MapSqlParameterSource updateParams = new MapSqlParameterSource()
                .addValue("userId", accountIds.get(0))
                .addValue("username", username)
                .addValue("roleCode", roleCode)
                .addValue("displayName", displayName)
                .addValue("relatedId", relatedId)
                .addValue("status", accountStatus);
        String sql = """
                UPDATE user_account
                SET username = :username,
                    role_code = :roleCode,
                    display_name = :displayName,
                    related_id = :relatedId,
                    status = :status
                """;
        if (StringUtils.hasText(password)) {
            sql += ", password_text = :passwordText";
            updateParams.addValue("passwordText", password);
        }
        sql += " WHERE user_id = :userId";
        jdbc.update(sql, updateParams);
    }

    private void deleteAccount(String roleCode, Long relatedId) {
        jdbc.update(
                "DELETE FROM user_account WHERE role_code = :roleCode AND related_id = :relatedId",
                new MapSqlParameterSource()
                        .addValue("roleCode", roleCode)
                        .addValue("relatedId", relatedId)
        );
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private boolean hasWritableFields(CrudDefinition definition, Map<String, Object> body) {
        return definition.writableColumns().stream().anyMatch(body::containsKey);
    }
}

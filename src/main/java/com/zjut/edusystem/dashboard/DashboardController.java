package com.zjut.edusystem.dashboard;

import com.zjut.edusystem.common.ApiResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final NamedParameterJdbcTemplate jdbc;

    public DashboardController(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/admin")
    public ApiResponse<Map<String, Object>> admin() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("term", queryOne("SELECT * FROM term WHERE is_current = TRUE LIMIT 1", Map.of()));
        data.put("notices", queryList("SELECT * FROM notice ORDER BY created_at DESC LIMIT 6", Map.of()));
        data.put("round", queryOne("SELECT * FROM course_selection_round ORDER BY round_id DESC LIMIT 1", Map.of()));
        data.put("contact_phone", queryOne("SELECT contact_phone FROM college ORDER BY college_id LIMIT 1", Map.of()).get("contact_phone"));
        return ApiResponse.ok(data);
    }

    @GetMapping("/student")
    public ApiResponse<Map<String, Object>> student(@RequestParam Long studentId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("profile", queryOne("""
                SELECT s.*, ac.class_name, m.major_name, c.college_name
                FROM student s
                JOIN admin_class ac ON ac.admin_class_id = s.admin_class_id
                JOIN major m ON m.major_id = ac.major_id
                JOIN college c ON c.college_id = m.college_id
                WHERE s.student_id = :studentId
                """, Map.of("studentId", studentId)));
        data.put("term", queryOne("SELECT * FROM term WHERE is_current = TRUE LIMIT 1", Map.of()));
        data.put("summary", queryOne("""
                SELECT
                  (SELECT COUNT(*) FROM student_course_selection WHERE student_id = :studentId AND status = 'selected') AS selected_count,
                  (SELECT COUNT(*) FROM selection_waitlist WHERE student_id = :studentId AND status = 'waiting') AS waitlist_count,
                  (SELECT COUNT(*) FROM grade_record WHERE student_id = :studentId AND submitted = TRUE) AS grade_count
                """, Map.of("studentId", studentId)));
        data.put("notices", queryList("SELECT * FROM notice ORDER BY created_at DESC LIMIT 6", Map.of()));
        return ApiResponse.ok(data);
    }

    @GetMapping("/teacher")
    public ApiResponse<Map<String, Object>> teacher(@RequestParam Long teacherId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("profile", queryOne("""
                SELECT t.*, c.college_name FROM teacher t
                JOIN college c ON c.college_id = t.college_id
                WHERE t.teacher_id = :teacherId
                """, Map.of("teacherId", teacherId)));
        data.put("summary", queryOne("""
                SELECT
                  (SELECT COUNT(*) FROM teaching_class WHERE teacher_id = :teacherId) AS class_count,
                  (SELECT COUNT(*) FROM teaching_class tc WHERE tc.teacher_id = :teacherId AND NOT EXISTS (
                      SELECT 1 FROM grade_record gr WHERE gr.teaching_class_id = tc.teaching_class_id AND gr.submitted = TRUE
                  )) AS pending_grade_count,
                  (SELECT COUNT(*) FROM course_review WHERE teacher_id = :teacherId) AS review_count
                """, Map.of("teacherId", teacherId)));
        data.put("notices", queryList("SELECT * FROM notice ORDER BY created_at DESC LIMIT 6", Map.of()));
        return ApiResponse.ok(data);
    }

    private Map<String, Object> queryOne(String sql, Map<String, ?> params) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, new MapSqlParameterSource(params));
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private List<Map<String, Object>> queryList(String sql, Map<String, ?> params) {
        return jdbc.queryForList(sql, new MapSqlParameterSource(params));
    }
}

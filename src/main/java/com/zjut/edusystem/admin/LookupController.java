package com.zjut.edusystem.admin;

import com.zjut.edusystem.common.ApiResponse;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/lookups")
public class LookupController {
    private final NamedParameterJdbcTemplate jdbc;

    public LookupController(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> all() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("colleges", jdbc.queryForList("SELECT college_id, college_name FROM college ORDER BY college_id", Map.of()));
        data.put("majors", jdbc.queryForList("SELECT major_id, major_name, college_id FROM major ORDER BY major_id", Map.of()));
        data.put("adminClasses", jdbc.queryForList("SELECT admin_class_id, class_name, major_id, grade_year FROM admin_class ORDER BY admin_class_id", Map.of()));
        data.put("regions", jdbc.queryForList("SELECT region_id, region_name FROM region ORDER BY region_id", Map.of()));
        data.put("students", jdbc.queryForList("SELECT student_id, student_no, student_name FROM student ORDER BY student_id", Map.of()));
        data.put("teachers", jdbc.queryForList("SELECT teacher_id, teacher_name FROM teacher ORDER BY teacher_id", Map.of()));
        data.put("courses", jdbc.queryForList("SELECT course_id, course_code, course_name FROM course ORDER BY course_id", Map.of()));
        data.put("terms", jdbc.queryForList("SELECT term_id, academic_year, semester FROM term ORDER BY term_id", Map.of()));
        data.put("teachingClasses", jdbc.queryForList("SELECT teaching_class_id, class_code, class_name FROM teaching_class ORDER BY teaching_class_id", Map.of()));
        data.put("rounds", jdbc.queryForList("SELECT round_id, round_name FROM course_selection_round ORDER BY round_id", Map.of()));
        return ApiResponse.ok(data);
    }
}

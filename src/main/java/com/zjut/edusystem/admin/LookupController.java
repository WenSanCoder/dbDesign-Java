package com.zjut.edusystem.admin;

import com.zjut.edusystem.common.ApiResponse;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    public ApiResponse<Map<String, Object>> all(
            @RequestParam(defaultValue = "true") boolean includeTeachers,
            @RequestParam(defaultValue = "true") boolean includeBuildings,
            @RequestParam(defaultValue = "true") boolean includeClassrooms
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("campuses", jdbc.queryForList("SELECT campus_id, campus_code, campus_name FROM campus WHERE status = 'enabled' ORDER BY campus_id", Map.of()));
        data.put("colleges", jdbc.queryForList("SELECT college_id, college_name, campus_id FROM college ORDER BY college_id", Map.of()));
        data.put("majors", jdbc.queryForList("SELECT major_id, major_code, major_name, college_id, campus_id FROM major ORDER BY major_code, major_name", Map.of()));
        data.put("adminClasses", jdbc.queryForList("SELECT admin_class_id, class_code, class_name, major_id, grade_year FROM admin_class ORDER BY grade_year DESC, class_code", Map.of()));
        data.put("gradeYears", jdbc.queryForList("SELECT grade_year_id, grade_year, admission_academic_year, graduation_academic_year, status FROM grade_year ORDER BY grade_year DESC", Map.of()));
        data.put("regions", jdbc.queryForList("SELECT region_id, region_name FROM region ORDER BY region_id", Map.of()));
        if (includeTeachers) {
            data.put("teachers", jdbc.queryForList("SELECT teacher_id, teacher_name FROM teacher ORDER BY teacher_id", Map.of()));
        }
        data.put("courses", jdbc.queryForList("SELECT course_id, course_code, course_name, credit, hours FROM course ORDER BY course_name, course_code", Map.of()));
        data.put("terms", jdbc.queryForList("SELECT term_id, academic_year, semester, start_date, end_date, is_current FROM term ORDER BY start_date DESC, term_id DESC", Map.of()));
        data.put("academicYears", jdbc.queryForList("SELECT academic_year FROM term GROUP BY academic_year ORDER BY academic_year DESC", Map.of()));
        data.put("teachingPlans", jdbc.queryForList("SELECT DISTINCT major_id, grade_year, term_id, course_id FROM teaching_plan ORDER BY major_id, grade_year DESC, term_id, course_id", Map.of()));
        data.put("teachingClasses", jdbc.queryForList("SELECT teaching_class_id, class_code, class_name FROM teaching_class ORDER BY teaching_class_id", Map.of()));
        if (includeBuildings) {
            data.put("buildings", jdbc.queryForList("""
                    SELECT b.building_id, b.building_code, b.building_name, b.campus_id, campus.campus_name
                    FROM teaching_building b
                    JOIN campus ON campus.campus_id = b.campus_id
                    WHERE b.status = 'enabled'
                    ORDER BY campus.campus_name, b.building_name
                    """, Map.of()));
        }
        if (includeClassrooms) {
            data.put("classrooms", jdbc.queryForList("""
                    SELECT cr.classroom_id, cr.room_code, cr.room_name, cr.room_type, cr.capacity,
                           cr.building_id, b.building_name, b.campus_id, campus.campus_name
                    FROM classroom_resource cr
                    JOIN teaching_building b ON b.building_id = cr.building_id
                    JOIN campus ON campus.campus_id = b.campus_id
                    WHERE cr.status = 'enabled'
                    ORDER BY campus.campus_name, b.building_name, cr.floor_no, cr.room_no
                    """, Map.of()));
        }
        data.put("rounds", jdbc.queryForList("SELECT round_id, round_name FROM course_selection_round ORDER BY round_id", Map.of()));
        return ApiResponse.ok(data);
    }
}

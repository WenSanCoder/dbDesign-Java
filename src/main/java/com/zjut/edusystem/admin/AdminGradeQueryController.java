package com.zjut.edusystem.admin;

import com.zjut.edusystem.common.ApiResponse;
import com.zjut.edusystem.common.BusinessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/grade-query")
public class AdminGradeQueryController {
    private final NamedParameterJdbcTemplate jdbc;

    public AdminGradeQueryController(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/lookups")
    public ApiResponse<Map<String, Object>> lookups() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("colleges", jdbc.queryForList("SELECT college_id, college_code, college_name FROM college WHERE status = 'enabled' ORDER BY college_name", Map.of()));
        result.put("majors", jdbc.queryForList("SELECT major_id, major_code, major_name, college_id FROM major WHERE status = 'enabled' ORDER BY major_name", Map.of()));
        result.put("teachers", jdbc.queryForList("""
                SELECT DISTINCT teacher.teacher_id, teacher.teacher_no, teacher.teacher_name, teacher.college_id
                FROM teacher JOIN teaching_class tc ON tc.teacher_id = teacher.teacher_id
                WHERE teacher.status = 'active' ORDER BY teacher.teacher_name, teacher.teacher_no
                """, Map.of()));
        result.put("academicYears", jdbc.queryForList("SELECT academic_year FROM term GROUP BY academic_year ORDER BY academic_year DESC", Map.of()));
        return ApiResponse.ok(result);
    }

    @GetMapping("/courses")
    public ApiResponse<Map<String, Object>> courses(
            @RequestParam(required = false) Long collegeId,
            @RequestParam(required = false) Long majorId,
            @RequestParam(required = false) String academicYear,
            @RequestParam(required = false) Integer semester,
            @RequestParam(required = false) Long teacherId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize
    ) {
        PageSpec pageSpec = pageSpec(page, pageSize);
        MapSqlParameterSource params = pageSpec.params();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        addFilter(where, params, "collegeId", collegeId, "course.college_id");
        if (majorId != null) {
            where.append(" AND EXISTS (SELECT 1 FROM teaching_plan tp WHERE tp.course_id = course.course_id AND tp.major_id = :majorId)");
            params.addValue("majorId", majorId);
        }
        appendClassExists(where, params, academicYear, semester, teacherId, "course.course_id");
        if (StringUtils.hasText(keyword)) {
            where.append(" AND (LOWER(course.course_code) LIKE :keyword OR LOWER(course.course_name) LIKE :keyword)");
            params.addValue("keyword", "%" + keyword.trim().toLowerCase() + "%");
        }

        String fromSql = " FROM course JOIN college ON college.college_id = course.college_id" + where;
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + fromSql, params, Long.class);
        String classCountFilter = classFilter(params, academicYear, semester, teacherId, "term", "tc_count");
        List<Map<String, Object>> records = jdbc.queryForList("""
                SELECT course.course_id, course.course_code, course.course_name,
                       course.credit, course.hours, course.exam_type, course.course_type,
                       course.status, college.college_id, college.college_name,
                       (SELECT COUNT(*) FROM teaching_class tc_count
                        JOIN term ON term.term_id = tc_count.term_id
                        WHERE tc_count.course_id = course.course_id %s) AS teaching_class_count,
                       (SELECT COUNT(*) FROM student_course_selection scs_count
                        JOIN teaching_class tc_count ON tc_count.teaching_class_id = scs_count.teaching_class_id
                        JOIN term ON term.term_id = tc_count.term_id
                        WHERE tc_count.course_id = course.course_id AND scs_count.status = 'selected' %s) AS student_count
                """.formatted(classCountFilter, classCountFilter) + fromSql
                + " ORDER BY course.course_code, course.course_name LIMIT :limit OFFSET :offset", params);
        return ApiResponse.ok(pageResult(records, total, pageSpec));
    }

    @GetMapping("/courses/{courseId}/classes")
    public ApiResponse<Map<String, Object>> teachingClasses(
            @PathVariable Long courseId,
            @RequestParam(required = false) String academicYear,
            @RequestParam(required = false) Integer semester,
            @RequestParam(required = false) Long teacherId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize
    ) {
        ensureCourse(courseId);
        PageSpec pageSpec = pageSpec(page, pageSize);
        MapSqlParameterSource params = pageSpec.params().addValue("courseId", courseId);
        StringBuilder where = new StringBuilder(" WHERE tc.course_id = :courseId");
        addFilter(where, params, "academicYear", academicYear, "term.academic_year");
        addFilter(where, params, "semester", semester, "term.semester");
        addFilter(where, params, "teacherId", teacherId, "tc.teacher_id");
        String fromSql = """
                FROM teaching_class tc
                JOIN teacher ON teacher.teacher_id = tc.teacher_id
                JOIN term ON term.term_id = tc.term_id
                """ + where;
        Long total = jdbc.queryForObject("SELECT COUNT(*) " + fromSql, params, Long.class);
        List<Map<String, Object>> records = jdbc.queryForList("""
                SELECT tc.teaching_class_id, tc.class_code, tc.class_name, tc.capacity,
                       tc.selected_count, tc.status, teacher.teacher_id, teacher.teacher_no,
                       teacher.teacher_name, term.term_id, term.academic_year, term.semester,
                       (SELECT COUNT(*) FROM grade_record gr WHERE gr.teaching_class_id = tc.teaching_class_id) AS grade_count,
                       (SELECT COUNT(*) FROM grade_record gr WHERE gr.teaching_class_id = tc.teaching_class_id AND gr.submitted = TRUE) AS submitted_count,
                       (SELECT b.lr_status13 FROM sht_grade_workflow_batches13 b
                        WHERE b.lr_teaching_class_id13 = tc.teaching_class_id
                        ORDER BY b.lr_submission_no13 DESC LIMIT 1) AS grade_batch_status,
                       (SELECT b.lr_batch_id13 FROM sht_grade_workflow_batches13 b
                        WHERE b.lr_teaching_class_id13 = tc.teaching_class_id
                        ORDER BY b.lr_submission_no13 DESC LIMIT 1) AS grade_batch_id
                """ + fromSql + " ORDER BY term.academic_year DESC, term.semester DESC, tc.class_code LIMIT :limit OFFSET :offset", params);
        return ApiResponse.ok(pageResult(records, total, pageSpec));
    }

    @GetMapping("/classes/{teachingClassId}/grades")
    public ApiResponse<Map<String, Object>> classGrades(
            @PathVariable Long teachingClassId,
            @RequestParam(required = false) Long batchId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize
    ) {
        PageSpec pageSpec = pageSpec(page, pageSize);
        MapSqlParameterSource params = pageSpec.params().addValue("teachingClassId", teachingClassId);
        StringBuilder where = new StringBuilder(" WHERE scs.teaching_class_id = :teachingClassId AND scs.status = 'selected'");
        if (StringUtils.hasText(keyword)) {
            where.append(" AND (LOWER(student.student_no) LIKE :keyword OR LOWER(student.student_name) LIKE :keyword OR LOWER(ac.class_name) LIKE :keyword)");
            params.addValue("keyword", "%" + keyword.trim().toLowerCase() + "%");
        }
        String fromSql = """
                FROM student_course_selection scs
                JOIN student ON student.student_id = scs.student_id
                JOIN admin_class ac ON ac.admin_class_id = student.admin_class_id
                LEFT JOIN grade_record gr ON gr.selection_id = scs.selection_id
                """ + where;
        Long total = jdbc.queryForObject("SELECT COUNT(*) " + fromSql, params, Long.class);
        List<Map<String, Object>> records = jdbc.queryForList("""
                SELECT scs.selection_id, student.student_id, student.student_no, student.student_name,
                       ac.class_name AS admin_class_name, gr.grade_id, gr.usual_score, gr.exam_score,
                       gr.final_score, gr.grade_point, gr.submitted, gr.submitted_at, gr.remark
                """ + fromSql + " ORDER BY student.student_no LIMIT :limit OFFSET :offset", params);
        List<Map<String, Object>> classRows = jdbc.queryForList("""
                SELECT tc.teaching_class_id, tc.class_code, tc.class_name, course.course_code,
                       course.course_name, teacher.teacher_name, term.academic_year, term.semester
                FROM teaching_class tc JOIN course ON course.course_id = tc.course_id
                JOIN teacher ON teacher.teacher_id = tc.teacher_id JOIN term ON term.term_id = tc.term_id
                WHERE tc.teaching_class_id = :teachingClassId
                """, params);
        if (classRows.isEmpty()) throw new BusinessException("教学班不存在");
        List<Map<String, Object>> batchRows = jdbc.queryForList("""
                SELECT b.lr_batch_id13 AS batch_id, b.lr_submission_no13 AS submission_no,
                       b.lr_status13 AS status, b.lr_submitted_at13 AS submitted_at,
                       b.lr_reviewed_at13 AS reviewed_at, b.lr_review_reason13 AS review_reason,
                       submitter.display_name AS submitter_name, reviewer.display_name AS reviewer_name
                FROM sht_grade_workflow_batches13 b
                LEFT JOIN user_account submitter ON submitter.user_id = b.lr_submitted_by13
                LEFT JOIN user_account reviewer ON reviewer.user_id = b.lr_reviewed_by13
                WHERE b.lr_teaching_class_id13 = :teachingClassId
                  AND (:batchId IS NULL OR b.lr_batch_id13 = :batchId)
                ORDER BY b.lr_submission_no13 DESC LIMIT 1
                """, params.addValue("batchId", batchId, java.sql.Types.BIGINT));
        if (batchId != null && batchRows.isEmpty()) throw new BusinessException("成绩审批批次与教学班不匹配");
        Map<String, Object> statistics = jdbc.queryForMap("""
                SELECT COUNT(*) AS student_count,
                       SUM(CASE WHEN gr.final_score IS NOT NULL THEN 1 ELSE 0 END) AS graded_count,
                       SUM(CASE WHEN gr.final_score IS NULL THEN 1 ELSE 0 END) AS missing_grade_count,
                       SUM(CASE WHEN gr.final_score >= 60 THEN 1 ELSE 0 END) AS passed_count,
                       SUM(CASE WHEN gr.final_score < 60 THEN 1 ELSE 0 END) AS failed_count,
                       ROUND(AVG(gr.final_score), 2) AS average_score,
                       MAX(gr.final_score) AS highest_score,
                       MIN(gr.final_score) AS lowest_score,
                       ROUND(SUM(CASE WHEN gr.final_score >= 60 THEN 1 ELSE 0 END) * 100.0
                             / NULLIF(SUM(CASE WHEN gr.final_score IS NOT NULL THEN 1 ELSE 0 END), 0), 2) AS pass_rate,
                       ROUND(SUM(CASE WHEN gr.final_score < 60 THEN 1 ELSE 0 END) * 100.0
                             / NULLIF(SUM(CASE WHEN gr.final_score IS NOT NULL THEN 1 ELSE 0 END), 0), 2) AS fail_rate
                FROM student_course_selection scs
                LEFT JOIN grade_record gr ON gr.selection_id = scs.selection_id
                WHERE scs.teaching_class_id = :teachingClassId AND scs.status = 'selected'
                """, params);
        Map<String, Object> result = pageResult(records, total, pageSpec);
        result.put("teachingClass", classRows.get(0));
        result.put("batch", batchRows.isEmpty() ? Map.of() : batchRows.get(0));
        result.put("statistics", statistics);
        return ApiResponse.ok(result);
    }

    private void appendClassExists(StringBuilder where, MapSqlParameterSource params, String academicYear, Integer semester, Long teacherId, String courseIdColumn) {
        if (!StringUtils.hasText(academicYear) && semester == null && teacherId == null) return;
        where.append(" AND EXISTS (SELECT 1 FROM teaching_class tc_filter JOIN term term_filter ON term_filter.term_id = tc_filter.term_id WHERE tc_filter.course_id = ")
                .append(courseIdColumn);
        if (StringUtils.hasText(academicYear)) {
            where.append(" AND term_filter.academic_year = :academicYear");
            params.addValue("academicYear", academicYear);
        }
        if (semester != null) {
            where.append(" AND term_filter.semester = :semester");
            params.addValue("semester", semester);
        }
        if (teacherId != null) {
            where.append(" AND tc_filter.teacher_id = :teacherId");
            params.addValue("teacherId", teacherId);
        }
        where.append(")");
    }

    private String classFilter(MapSqlParameterSource params, String academicYear, Integer semester, Long teacherId, String termAlias, String classAlias) {
        StringBuilder filter = new StringBuilder();
        if (StringUtils.hasText(academicYear)) {
            filter.append(" AND ").append(termAlias).append(".academic_year = :academicYear");
            params.addValue("academicYear", academicYear);
        }
        if (semester != null) {
            filter.append(" AND ").append(termAlias).append(".semester = :semester");
            params.addValue("semester", semester);
        }
        if (teacherId != null) {
            filter.append(" AND ").append(classAlias).append(".teacher_id = :teacherId");
            params.addValue("teacherId", teacherId);
        }
        return filter.toString();
    }

    private void ensureCourse(Long courseId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM course WHERE course_id = :courseId", new MapSqlParameterSource("courseId", courseId), Long.class);
        if (count == null || count == 0) throw new BusinessException("课程不存在");
    }

    private void addFilter(StringBuilder where, MapSqlParameterSource params, String name, Object value, String column) {
        if (value == null || value instanceof String text && !StringUtils.hasText(text)) return;
        where.append(" AND ").append(column).append(" = :").append(name);
        params.addValue(name, value);
    }

    private PageSpec pageSpec(Integer page, Integer pageSize) {
        int normalizedPage = page == null || page < 1 ? 1 : page;
        int normalizedSize = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);
        return new PageSpec(normalizedPage, normalizedSize, new MapSqlParameterSource()
                .addValue("limit", normalizedSize).addValue("offset", (normalizedPage - 1) * normalizedSize));
    }

    private Map<String, Object> pageResult(List<Map<String, Object>> records, Long total, PageSpec spec) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", total == null ? 0 : total);
        result.put("page", spec.page());
        result.put("pageSize", spec.pageSize());
        return result;
    }

    private record PageSpec(int page, int pageSize, MapSqlParameterSource params) {}
}

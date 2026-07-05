package com.zjut.edusystem.teacher;

import com.zjut.edusystem.common.BusinessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class TeacherService {
    private final NamedParameterJdbcTemplate jdbc;

    public TeacherService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> classes(Long teacherId) {
        return jdbc.queryForList("""
                SELECT v.*, string_agg(cs.weekday || ' ' || cs.start_period || '-' || cs.end_period || '节 ' || cs.classroom, '; ') AS schedule_text
                FROM v_teacher_classes v
                LEFT JOIN class_schedule cs ON cs.teaching_class_id = v.teaching_class_id
                WHERE v.teacher_id = :teacherId
                GROUP BY v.teacher_id, v.teacher_no, v.teacher_name, v.teaching_class_id, v.class_code, v.class_name,
                         v.course_code, v.course_name, v.credit, v.academic_year, v.semester, v.capacity,
                         v.selected_count, v.waitlist_count, v.status
                ORDER BY v.teaching_class_id DESC
                """, new MapSqlParameterSource("teacherId", teacherId));
    }

    public List<Map<String, Object>> students(Long teacherId, Long teachingClassId) {
        ensureTeacherClass(teacherId, teachingClassId);
        return jdbc.queryForList("""
                SELECT s.student_id, s.student_no, s.student_name, s.gender, s.phone,
                       c.college_name, m.major_name, ac.class_name AS admin_class_name,
                       scs.selection_id, scs.selected_at, scs.status
                FROM student_course_selection scs
                JOIN student s ON s.student_id = scs.student_id
                JOIN admin_class ac ON ac.admin_class_id = s.admin_class_id
                JOIN major m ON m.major_id = ac.major_id
                JOIN college c ON c.college_id = m.college_id
                WHERE scs.teaching_class_id = :teachingClassId
                  AND scs.status = 'selected'
                ORDER BY s.student_no
                """, new MapSqlParameterSource("teachingClassId", teachingClassId));
    }

    public List<Map<String, Object>> grades(Long teacherId, Long teachingClassId) {
        ensureTeacherClass(teacherId, teachingClassId);
        return jdbc.queryForList("""
                SELECT s.student_id, s.student_no, s.student_name, ac.class_name AS admin_class_name,
                       scs.selection_id, gr.grade_id, gr.usual_score, gr.exam_score, gr.final_score,
                       gr.grade_point, gr.submitted, gr.submitted_at, gr.remark
                FROM student_course_selection scs
                JOIN student s ON s.student_id = scs.student_id
                JOIN admin_class ac ON ac.admin_class_id = s.admin_class_id
                LEFT JOIN grade_record gr ON gr.selection_id = scs.selection_id
                WHERE scs.teaching_class_id = :teachingClassId
                  AND scs.status = 'selected'
                ORDER BY s.student_no
                """, new MapSqlParameterSource("teachingClassId", teachingClassId));
    }

    @Transactional
    public void saveGrades(Long teacherId, Long teachingClassId, List<GradeInput> grades, boolean submit) {
        ensureTeacherClass(teacherId, teachingClassId);
        for (GradeInput grade : grades) {
            Map<String, Object> selection = queryOne("""
                    SELECT selection_id, student_id, teaching_class_id
                    FROM student_course_selection
                    WHERE selection_id = :selectionId
                      AND teaching_class_id = :teachingClassId
                      AND status = 'selected'
                    """, Map.of("selectionId", grade.selectionId(), "teachingClassId", teachingClassId), "选课记录不存在");
            Long selectionId = ((Number) selection.get("selection_id")).longValue();
            Long studentId = ((Number) selection.get("student_id")).longValue();
            List<Map<String, Object>> exists = jdbc.queryForList(
                    "SELECT grade_id FROM grade_record WHERE selection_id = :selectionId",
                    new MapSqlParameterSource("selectionId", selectionId));
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("selectionId", selectionId)
                    .addValue("studentId", studentId)
                    .addValue("teachingClassId", teachingClassId)
                    .addValue("usualScore", grade.usualScore())
                    .addValue("examScore", grade.examScore())
                    .addValue("submitted", submit)
                    .addValue("submittedAt", submit ? LocalDateTime.now() : null)
                    .addValue("remark", grade.remark());
            if (exists.isEmpty()) {
                jdbc.update("""
                        INSERT INTO grade_record(selection_id, student_id, teaching_class_id, usual_score, exam_score, submitted, submitted_at, remark)
                        VALUES(:selectionId, :studentId, :teachingClassId, :usualScore, :examScore, :submitted, :submittedAt, :remark)
                        """, params);
            } else {
                jdbc.update("""
                        UPDATE grade_record
                        SET usual_score = :usualScore,
                            exam_score = :examScore,
                            submitted = :submitted,
                            submitted_at = CASE WHEN :submitted THEN COALESCE(submitted_at, :submittedAt) ELSE submitted_at END,
                            remark = :remark
                        WHERE selection_id = :selectionId
                        """, params);
            }
        }
    }

    public Map<String, Object> gradeStatistics(Long teacherId, Long teachingClassId) {
        ensureTeacherClass(teacherId, teachingClassId);
        Map<String, Object> overview = queryOne("""
                SELECT * FROM v_course_grade_statistics WHERE teaching_class_id = :teachingClassId
                """, Map.of("teachingClassId", teachingClassId), "统计数据不存在");
        List<Map<String, Object>> distribution = jdbc.queryForList("""
                SELECT bucket, count(*) AS count
                FROM (
                    SELECT CASE
                        WHEN final_score < 60 THEN '0-59'
                        WHEN final_score < 70 THEN '60-69'
                        WHEN final_score < 80 THEN '70-79'
                        WHEN final_score < 90 THEN '80-89'
                        ELSE '90-100'
                    END AS bucket
                    FROM grade_record
                    WHERE teaching_class_id = :teachingClassId AND submitted = TRUE
                ) x
                GROUP BY bucket
                ORDER BY bucket
                """, new MapSqlParameterSource("teachingClassId", teachingClassId));
        List<Map<String, Object>> ranking = jdbc.queryForList("""
                SELECT RANK() OVER (ORDER BY gr.final_score DESC NULLS LAST) AS rank_no,
                       s.student_no, s.student_name, ac.class_name, gr.final_score, gr.grade_point
                FROM grade_record gr
                JOIN student s ON s.student_id = gr.student_id
                JOIN admin_class ac ON ac.admin_class_id = s.admin_class_id
                WHERE gr.teaching_class_id = :teachingClassId AND gr.submitted = TRUE
                ORDER BY rank_no, s.student_no
                """, new MapSqlParameterSource("teachingClassId", teachingClassId));
        return Map.of("overview", overview, "distribution", distribution, "ranking", ranking);
    }

    private void ensureTeacherClass(Long teacherId, Long teachingClassId) {
        queryOne("""
                SELECT teaching_class_id FROM teaching_class
                WHERE teaching_class_id = :teachingClassId AND teacher_id = :teacherId
                """, Map.of("teacherId", teacherId, "teachingClassId", teachingClassId), "该教学班不属于当前教师");
    }

    private Map<String, Object> queryOne(String sql, Map<String, ?> params, String errorMessage) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, new MapSqlParameterSource(params));
        if (rows.isEmpty()) {
            throw new BusinessException(errorMessage);
        }
        return rows.get(0);
    }

    public record GradeInput(Long selectionId, Double usualScore, Double examScore, String remark) {
    }
}

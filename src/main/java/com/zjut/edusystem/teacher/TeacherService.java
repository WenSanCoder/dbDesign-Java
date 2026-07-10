package com.zjut.edusystem.teacher;

import com.zjut.edusystem.common.BusinessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class TeacherService {
    private final NamedParameterJdbcTemplate jdbc;

    public TeacherService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> classes(Long teacherId, String academicYear, Integer semester) {
        MapSqlParameterSource params = new MapSqlParameterSource("teacherId", teacherId)
                .addValue("academicYear", StringUtils.hasText(academicYear) ? academicYear : null)
                .addValue("semester", semester);
        StringBuilder sql = new StringBuilder("""
                SELECT v.teacher_id, v.teacher_no, v.teacher_name, v.teaching_class_id, v.class_code, v.class_name,
                       v.course_code, v.course_name, v.credit, v.academic_year, v.semester, v.capacity,
                       v.selected_count, v.waitlist_count, v.status,
                       string_agg(cs.weekday || ' ' || cs.start_period || '-' || cs.end_period || '节 ' || cs.classroom, '; ') AS schedule_text
                FROM v_teacher_classes v
                LEFT JOIN class_schedule cs ON cs.teaching_class_id = v.teaching_class_id
                WHERE v.teacher_id = :teacherId
                """);
        if (StringUtils.hasText(academicYear)) {
            sql.append(" AND regexp_replace(v.academic_year, '[^0-9]', '', 'g') = regexp_replace(:academicYear, '[^0-9]', '', 'g')\n");
        }
        if (semester != null) {
            sql.append(" AND v.semester = :semester\n");
        }
        if (!StringUtils.hasText(academicYear) && semester == null) {
            sql.append("""
                  AND EXISTS (
                      SELECT 1
                      FROM teaching_class tc
                      JOIN term current_term ON current_term.term_id = tc.term_id
                      WHERE tc.teaching_class_id = v.teaching_class_id
                        AND current_term.is_current = TRUE
                  )
                    """);
        }
        sql.append("""
                GROUP BY v.teacher_id, v.teacher_no, v.teacher_name, v.teaching_class_id, v.class_code, v.class_name,
                         v.course_code, v.course_name, v.credit, v.academic_year, v.semester, v.capacity,
                         v.selected_count, v.waitlist_count, v.status
                ORDER BY v.teaching_class_id DESC
                """);
        return jdbc.queryForList(sql.toString(), params);
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
        boolean hasGradeWeights = gradeWeightColumnsExist();
        String weightSelect = hasGradeWeights
                ? "COALESCE(gr.usual_weight, 30) AS usual_weight,\n                       COALESCE(gr.exam_weight, 70) AS exam_weight,"
                : "30 AS usual_weight,\n                       70 AS exam_weight,";
        return jdbc.queryForList("""
                SELECT s.student_id, s.student_no, s.student_name, ac.class_name AS admin_class_name,
                       scs.selection_id, gr.grade_id, gr.usual_score, gr.exam_score, gr.final_score,
                       %s
                       gr.grade_point, gr.submitted, gr.submitted_at, gr.remark
                FROM student_course_selection scs
                JOIN student s ON s.student_id = scs.student_id
                JOIN admin_class ac ON ac.admin_class_id = s.admin_class_id
                LEFT JOIN grade_record gr ON gr.selection_id = scs.selection_id
                WHERE scs.teaching_class_id = :teachingClassId
                  AND scs.status = 'selected'
                ORDER BY s.student_no
                """.formatted(weightSelect), new MapSqlParameterSource("teachingClassId", teachingClassId));
    }

    @Transactional
    public void saveGrades(Long teacherId, Long teachingClassId, List<GradeInput> grades, boolean submit) {
        ensureTeacherClass(teacherId, teachingClassId);
        boolean hasGradeWeights = gradeWeightColumnsExist();
        for (GradeInput grade : grades) {
            validateGrade(grade);
            if (!hasGradeWeights && !isDefaultWeight(grade)) {
                throw new BusinessException("请先执行 sql/26_grade_weight_and_notice_type.sql 后再保存自定义成绩占比");
            }
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
                    .addValue("usualWeight", grade.usualWeight())
                    .addValue("examWeight", grade.examWeight())
                    .addValue("submitted", submit)
                    .addValue("submittedAt", submit ? LocalDateTime.now() : null)
                    .addValue("remark", grade.remark());
            if (exists.isEmpty()) {
                if (hasGradeWeights) {
                    jdbc.update("""
                        INSERT INTO grade_record(
                            selection_id, student_id, teaching_class_id,
                            usual_score, exam_score, usual_weight, exam_weight,
                            submitted, submitted_at, remark
                        )
                        VALUES(
                            :selectionId, :studentId, :teachingClassId,
                            :usualScore, :examScore, :usualWeight, :examWeight,
                            :submitted, :submittedAt, :remark
                        )
                        """, params);
                } else {
                    jdbc.update("""
                        INSERT INTO grade_record(selection_id, student_id, teaching_class_id, usual_score, exam_score, submitted, submitted_at, remark)
                        VALUES(:selectionId, :studentId, :teachingClassId, :usualScore, :examScore, :submitted, :submittedAt, :remark)
                        """, params);
                }
            } else {
                if (hasGradeWeights) {
                    jdbc.update("""
                        UPDATE grade_record
                        SET usual_score = :usualScore,
                            exam_score = :examScore,
                            usual_weight = :usualWeight,
                            exam_weight = :examWeight,
                            submitted = :submitted,
                            submitted_at = CASE WHEN :submitted THEN COALESCE(submitted_at, :submittedAt) ELSE submitted_at END,
                            remark = :remark
                        WHERE selection_id = :selectionId
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
                SELECT tc.teaching_class_id
                FROM teaching_class tc
                JOIN term t ON t.term_id = tc.term_id
                WHERE tc.teaching_class_id = :teachingClassId
                  AND tc.teacher_id = :teacherId
                """, Map.of("teacherId", teacherId, "teachingClassId", teachingClassId), "该教学班不属于当前教师");
    }

    private boolean isDefaultWeight(GradeInput grade) {
        return Math.abs(grade.usualWeight() - 30) < 0.001 && Math.abs(grade.examWeight() - 70) < 0.001;
    }

    private boolean gradeWeightColumnsExist() {
        return columnExists("grade_record", "usual_weight") && columnExists("grade_record", "exam_weight");
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = :tableName
                  AND column_name = :columnName
                """, new MapSqlParameterSource()
                .addValue("tableName", tableName)
                .addValue("columnName", columnName), Integer.class);
        return count != null && count > 0;
    }

    private void validateGrade(GradeInput grade) {
        if (grade.selectionId() == null) {
            throw new BusinessException("选课记录不能为空");
        }
        validateScore(grade.usualScore(), "平时分");
        validateScore(grade.examScore(), "考试分");
        if (grade.usualWeight() == null || grade.examWeight() == null) {
            throw new BusinessException("平时分和考试分占比不能为空");
        }
        if (grade.usualWeight() < 0 || grade.usualWeight() > 100 || grade.examWeight() < 0 || grade.examWeight() > 100) {
            throw new BusinessException("成绩占比必须在 0 到 100 之间");
        }
        if (Math.abs(grade.usualWeight() + grade.examWeight() - 100) > 0.001) {
            throw new BusinessException("平时分和考试分占比之和必须为 100%");
        }
    }

    private void validateScore(Double score, String label) {
        if (score != null && (score < 0 || score > 100)) {
            throw new BusinessException(label + "必须在 0 到 100 之间");
        }
    }

    private Map<String, Object> queryOne(String sql, Map<String, ?> params, String errorMessage) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, new MapSqlParameterSource(params));
        if (rows.isEmpty()) {
            throw new BusinessException(errorMessage);
        }
        return rows.get(0);
    }

    public record GradeInput(Long selectionId, Double usualScore, Double examScore, Double usualWeight, Double examWeight, String remark) {
    }
}

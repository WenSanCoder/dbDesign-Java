package com.zjut.edusystem.governance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjut.edusystem.common.BusinessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class GovernanceService {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public GovernanceService(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> lookups() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("majors", list("SELECT major_id, major_code, major_name FROM major WHERE status = 'enabled' ORDER BY major_name", Map.of()));
        result.put("gradeYears", list("SELECT grade_year FROM grade_year WHERE status = 'enabled' ORDER BY grade_year DESC", Map.of()));
        result.put("terms", list("SELECT term_id, academic_year, semester, start_date, end_date, is_current FROM term ORDER BY start_date DESC", Map.of()));
        result.put("courses", list("SELECT course_id, course_code, course_name FROM course WHERE status = 'enabled' ORDER BY course_code", Map.of()));
        result.put("adminClasses", list("SELECT admin_class_id, class_code, class_name, grade_year FROM admin_class WHERE status = 'enabled' ORDER BY grade_year DESC, class_code", Map.of()));
        result.put("teachingClasses", list("SELECT teaching_class_id, class_code, class_name FROM teaching_class ORDER BY teaching_class_id DESC", Map.of()));
        result.put("users", list("SELECT user_id, username, display_name, role_code FROM user_account WHERE status = 'enabled' ORDER BY role_code, username", Map.of()));
        return result;
    }

    public List<Map<String, Object>> selectionRules() {
        return list("""
                SELECT r.lr_rule_id13 AS rule_id, r.lr_major_id13 AS major_id,
                       r.lr_grade_year13 AS grade_year, r.lr_category_code13 AS category_code,
                       r.lr_required_credits13 AS required_credits,
                       r.lr_max_courses_per_term13 AS max_courses_per_term,
                       r.lr_remark13 AS remark, r.lr_created_at13 AS created_at,
                       r.lr_updated_at13 AS updated_at, m.major_code, m.major_name
                FROM sht_major_category_selection_rules13 r
                JOIN major m ON m.major_id = r.lr_major_id13
                ORDER BY r.lr_grade_year13 DESC, m.major_name, r.lr_category_code13
                """, Map.of());
    }

    @Transactional
    public void saveSelectionRule(Long ruleId, Map<String, Object> body) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", ruleId)
                .addValue("majorId", requiredLong(body, "majorId"))
                .addValue("gradeYear", requiredInteger(body, "gradeYear"))
                .addValue("categoryCode", requiredText(body, "categoryCode"))
                .addValue("requiredCredits", requiredDecimal(body, "requiredCredits"))
                .addValue("maxCourses", requiredInteger(body, "maxCoursesPerTerm"))
                .addValue("remark", text(body.get("remark")));
        if (ruleId == null) {
            jdbc.update("""
                    INSERT INTO sht_major_category_selection_rules13(
                        lr_major_id13, lr_grade_year13, lr_category_code13,
                        lr_required_credits13, lr_max_courses_per_term13, lr_remark13
                    ) VALUES (:majorId, :gradeYear, :categoryCode, :requiredCredits, :maxCourses, :remark)
                    """, params);
        } else {
            ensureUpdated(jdbc.update("""
                    UPDATE sht_major_category_selection_rules13
                    SET lr_major_id13 = :majorId,
                        lr_grade_year13 = :gradeYear,
                        lr_category_code13 = :categoryCode,
                        lr_required_credits13 = :requiredCredits,
                        lr_max_courses_per_term13 = :maxCourses,
                        lr_remark13 = :remark,
                        lr_updated_at13 = CURRENT_TIMESTAMP
                    WHERE lr_rule_id13 = :id
                    """, params));
        }
    }

    public void deleteSelectionRule(Long ruleId) {
        ensureUpdated(jdbc.update("DELETE FROM sht_major_category_selection_rules13 WHERE lr_rule_id13 = :id",
                new MapSqlParameterSource("id", ruleId)));
    }

    @Transactional
    public Map<String, Object> importSelectionRules(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择要导入的 XLSX 文件");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".xlsx")) {
            throw new BusinessException("仅支持 XLSX 文件");
        }

        int imported = 0;
        int skipped = 0;
        List<String> errors = new java.util.ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        try (InputStream input = file.getInputStream(); Workbook workbook = WorkbookFactory.create(input)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new BusinessException("XLSX 文件没有工作表");
            }
            Sheet sheet = workbook.getSheetAt(0);
            for (Row row : sheet) {
                if (row.getRowNum() == 0 && isHeaderRow(row, formatter)) {
                    continue;
                }
                if (isBlankRow(row, formatter)) {
                    skipped++;
                    continue;
                }
                int rowNo = row.getRowNum() + 1;
                try {
                    String majorName = cellText(row, 0, formatter);
                    Integer gradeYear = cellInteger(row, 1, formatter);
                    Double requiredCredits = cellDecimal(row, 2, formatter);
                    Integer maxCourses = cellInteger(row, 3, formatter);
                    String remark = cellText(row, 4, formatter);
                    if (!StringUtils.hasText(majorName)) {
                        throw new BusinessException("专业名称不能为空");
                    }
                    Long majorId = jdbc.queryForObject(
                            "SELECT major_id FROM major WHERE major_name = :majorName AND status = 'enabled' ORDER BY major_id LIMIT 1",
                            new MapSqlParameterSource("majorName", majorName), Long.class);
                    if (majorId == null) {
                        throw new BusinessException("找不到专业：" + majorName);
                    }
                    if (gradeYear == null || requiredCredits == null || maxCourses == null) {
                        throw new BusinessException("年级、要求学分、每学期最多课程均不能为空");
                    }
                    if (requiredCredits < 0 || maxCourses <= 0) {
                        throw new BusinessException("要求学分不能小于 0，最多课程必须大于 0");
                    }
                    jdbc.update("""
                            INSERT INTO sht_major_category_selection_rules13(
                                lr_major_id13, lr_grade_year13, lr_category_code13,
                                lr_required_credits13, lr_max_courses_per_term13, lr_remark13
                            ) VALUES (:majorId, :gradeYear, 'major_elective', :requiredCredits, :maxCourses, :remark)
                            ON CONFLICT (lr_major_id13, lr_grade_year13, lr_category_code13)
                            DO UPDATE SET lr_required_credits13 = EXCLUDED.lr_required_credits13,
                                          lr_max_courses_per_term13 = EXCLUDED.lr_max_courses_per_term13,
                                          lr_remark13 = EXCLUDED.lr_remark13,
                                          lr_updated_at13 = CURRENT_TIMESTAMP
                            """, new MapSqlParameterSource()
                            .addValue("majorId", majorId)
                            .addValue("gradeYear", gradeYear)
                            .addValue("requiredCredits", requiredCredits)
                            .addValue("maxCourses", maxCourses)
                            .addValue("remark", remark));
                    imported++;
                } catch (RuntimeException ex) {
                    errors.add("第 " + rowNo + " 行：" + (ex.getMessage() == null ? "数据格式错误" : ex.getMessage()));
                }
            }
        } catch (IOException ex) {
            throw new BusinessException("XLSX 文件读取失败：" + ex.getMessage());
        }
        if (!errors.isEmpty()) {
            throw new BusinessException("批量导入存在错误：" + String.join("；", errors));
        }
        return Map.of("imported", imported, "skipped", skipped, "categoryCode", "major_elective");
    }

    private boolean isHeaderRow(Row row, DataFormatter formatter) {
        String first = cellText(row, 0, formatter);
        return "专业名称".equals(first) || "专业".equals(first) || "major_name".equalsIgnoreCase(first);
    }

    private boolean isBlankRow(Row row, DataFormatter formatter) {
        for (int i = 0; i < 5; i++) {
            if (StringUtils.hasText(cellText(row, i, formatter))) {
                return false;
            }
        }
        return true;
    }

    private String cellText(Row row, int index, DataFormatter formatter) {
        Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }

    private Integer cellInteger(Row row, int index, DataFormatter formatter) {
        String value = cellText(row, index, formatter);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return (int) Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            throw new BusinessException("整数格式错误：" + value);
        }
    }

    private Double cellDecimal(Row row, int index, DataFormatter formatter) {
        String value = cellText(row, index, formatter);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            throw new BusinessException("数字格式错误：" + value);
        }
    }

    public List<Map<String, Object>> planAdjustments(Long studentId) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("studentId", studentId, Types.BIGINT);
        return jdbc.queryForList("""
                SELECT a.lr_adjustment_id13 AS adjustment_id, a.lr_student_id13 AS student_id,
                       a.lr_source_plan_id13 AS source_plan_id, a.lr_course_id13 AS course_id,
                       a.lr_target_term_id13 AS target_term_id, a.lr_course_nature13 AS course_nature,
                       a.lr_adjustment_type13 AS adjustment_type, a.lr_status13 AS status,
                       a.lr_source_grade_id13 AS source_grade_id, a.lr_reason13 AS reason,
                       a.lr_approved_by13 AS approved_by, a.lr_approved_at13 AS approved_at,
                       a.lr_created_at13 AS created_at, s.student_no, s.student_name,
                       c.course_code, c.course_name, t.academic_year, t.semester,
                       source_grade.final_score AS source_final_score,
                       source_term.academic_year AS source_academic_year,
                       source_term.semester AS source_semester,
                       ua.display_name AS approver_name
                FROM sht_student_plan_adjustments13 a
                JOIN student s ON s.student_id = a.lr_student_id13
                JOIN course c ON c.course_id = a.lr_course_id13
                LEFT JOIN term t ON t.term_id = a.lr_target_term_id13
                LEFT JOIN grade_record source_grade ON source_grade.grade_id = a.lr_source_grade_id13
                LEFT JOIN teaching_class source_class ON source_class.teaching_class_id = source_grade.teaching_class_id
                LEFT JOIN term source_term ON source_term.term_id = source_class.term_id
                LEFT JOIN user_account ua ON ua.user_id = a.lr_approved_by13
                WHERE (:studentId IS NULL OR a.lr_student_id13 = :studentId)
                ORDER BY a.lr_created_at13 DESC, a.lr_adjustment_id13 DESC
                """, params);
    }

    public void createPlanAdjustment(Long studentId, Map<String, Object> body) {
        String adjustmentType = requiredText(body, "adjustmentType");
        if ("RETAKE".equalsIgnoreCase(adjustmentType)) {
            createRetakeAdjustment(studentId, body);
            return;
        }
        jdbc.update("""
                INSERT INTO sht_student_plan_adjustments13(
                    lr_student_id13, lr_source_plan_id13, lr_course_id13, lr_target_term_id13,
                    lr_course_nature13, lr_adjustment_type13, lr_source_grade_id13, lr_reason13
                ) VALUES (
                    :studentId, :sourcePlanId, :courseId, :targetTermId,
                    :courseNature, :adjustmentType, :sourceGradeId, :reason
                )
                """, new MapSqlParameterSource()
                .addValue("studentId", studentId)
                .addValue("sourcePlanId", longValue(body.get("sourcePlanId")))
                .addValue("courseId", requiredLong(body, "courseId"))
                .addValue("targetTermId", longValue(body.get("targetTermId")))
                .addValue("courseNature", requiredText(body, "courseNature"))
                .addValue("adjustmentType", adjustmentType)
                .addValue("sourceGradeId", longValue(body.get("sourceGradeId")))
                .addValue("reason", requiredText(body, "reason")));
    }

    public List<Map<String, Object>> retakeOptions(Long studentId) {
        return jdbc.queryForList("""
                SELECT gr.grade_id AS source_grade_id, gr.final_score, gr.grade_point,
                       c.course_id, c.course_code, c.course_name, c.credit,
                       source_term.term_id AS source_term_id, source_term.academic_year AS source_academic_year,
                       source_term.semester AS source_semester, source_term.end_date AS source_end_date,
                       (SELECT tp.plan_id FROM student plan_student
                        JOIN admin_class plan_class ON plan_class.admin_class_id = plan_student.admin_class_id
                        JOIN teaching_plan tp ON tp.major_id = plan_class.major_id
                         AND tp.grade_year = plan_student.grade_year AND tp.course_id = c.course_id
                        WHERE plan_student.student_id = gr.student_id
                        ORDER BY CASE WHEN tp.term_id = source_term.term_id THEN 0 ELSE 1 END, tp.plan_id LIMIT 1) AS source_plan_id,
                       COALESCE(
                         (SELECT tp.course_nature FROM student plan_student
                          JOIN admin_class plan_class ON plan_class.admin_class_id = plan_student.admin_class_id
                          JOIN teaching_plan tp ON tp.major_id = plan_class.major_id
                           AND tp.grade_year = plan_student.grade_year AND tp.course_id = c.course_id
                          WHERE plan_student.student_id = gr.student_id
                          ORDER BY CASE WHEN tp.term_id = source_term.term_id THEN 0 ELSE 1 END, tp.plan_id LIMIT 1),
                         (SELECT old_adjustment.lr_course_nature13 FROM sht_student_plan_adjustments13 old_adjustment
                          WHERE old_adjustment.lr_student_id13 = gr.student_id
                            AND old_adjustment.lr_course_id13 = c.course_id
                            AND old_adjustment.lr_status13 = 'APPROVED'
                          ORDER BY old_adjustment.lr_created_at13 DESC LIMIT 1),
                         'major_required'
                       ) AS course_nature
                FROM grade_record gr
                JOIN teaching_class tc ON tc.teaching_class_id = gr.teaching_class_id
                JOIN course c ON c.course_id = tc.course_id
                JOIN term source_term ON source_term.term_id = tc.term_id
                WHERE gr.student_id = :studentId AND gr.final_score < 60 AND gr.submitted = TRUE
                  AND EXISTS (SELECT 1 FROM sht_grade_workflow_batches13 approved_batch
                              WHERE approved_batch.lr_teaching_class_id13 = gr.teaching_class_id
                                AND approved_batch.lr_status13 = 'approved')
                  AND NOT EXISTS (
                      SELECT 1 FROM grade_record passed_grade
                      JOIN teaching_class passed_class ON passed_class.teaching_class_id = passed_grade.teaching_class_id
                      WHERE passed_grade.student_id = gr.student_id AND passed_class.course_id = tc.course_id
                        AND passed_grade.final_score >= 60 AND passed_grade.submitted = TRUE
                        AND EXISTS (SELECT 1 FROM sht_grade_workflow_batches13 passed_batch
                                    WHERE passed_batch.lr_teaching_class_id13 = passed_grade.teaching_class_id
                                      AND passed_batch.lr_status13 = 'approved')
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM sht_student_plan_adjustments13 pending_adjustment
                      WHERE pending_adjustment.lr_source_grade_id13 = gr.grade_id
                        AND pending_adjustment.lr_status13 IN ('PENDING', 'APPROVED')
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM grade_record newer_grade
                      JOIN teaching_class newer_class ON newer_class.teaching_class_id = newer_grade.teaching_class_id
                      JOIN term newer_term ON newer_term.term_id = newer_class.term_id
                      WHERE newer_grade.student_id = gr.student_id AND newer_class.course_id = tc.course_id
                        AND newer_grade.final_score < 60 AND newer_grade.submitted = TRUE
                        AND (newer_term.start_date > source_term.start_date
                             OR (newer_term.start_date = source_term.start_date AND newer_grade.grade_id > gr.grade_id))
                        AND EXISTS (SELECT 1 FROM sht_grade_workflow_batches13 newer_batch
                                    WHERE newer_batch.lr_teaching_class_id13 = newer_grade.teaching_class_id
                                      AND newer_batch.lr_status13 = 'approved')
                  )
                ORDER BY source_term.start_date DESC, c.course_code
                """, new MapSqlParameterSource("studentId", studentId));
    }

    private void createRetakeAdjustment(Long studentId, Map<String, Object> body) {
        Long sourceGradeId = requiredLong(body, "sourceGradeId");
        Long targetTermId = requiredLong(body, "targetTermId");
        Map<String, Object> failed = one("""
                SELECT gr.grade_id, gr.final_score, tc.course_id, tc.term_id AS source_term_id,
                       source_term.end_date AS source_end_date,
                       (SELECT tp.plan_id FROM student plan_student
                        JOIN admin_class plan_class ON plan_class.admin_class_id = plan_student.admin_class_id
                        JOIN teaching_plan tp ON tp.major_id = plan_class.major_id
                         AND tp.grade_year = plan_student.grade_year AND tp.course_id = tc.course_id
                        WHERE plan_student.student_id = gr.student_id
                        ORDER BY CASE WHEN tp.term_id = tc.term_id THEN 0 ELSE 1 END, tp.plan_id LIMIT 1) AS source_plan_id,
                       COALESCE(
                         (SELECT tp.course_nature FROM student plan_student
                          JOIN admin_class plan_class ON plan_class.admin_class_id = plan_student.admin_class_id
                          JOIN teaching_plan tp ON tp.major_id = plan_class.major_id
                           AND tp.grade_year = plan_student.grade_year AND tp.course_id = tc.course_id
                          WHERE plan_student.student_id = gr.student_id
                          ORDER BY CASE WHEN tp.term_id = tc.term_id THEN 0 ELSE 1 END, tp.plan_id LIMIT 1),
                         (SELECT old_adjustment.lr_course_nature13
                          FROM sht_student_plan_adjustments13 old_adjustment
                          WHERE old_adjustment.lr_student_id13 = gr.student_id
                            AND old_adjustment.lr_course_id13 = tc.course_id
                            AND old_adjustment.lr_status13 = 'APPROVED'
                          ORDER BY old_adjustment.lr_created_at13 DESC LIMIT 1),
                         'major_required') AS course_nature
                FROM grade_record gr JOIN teaching_class tc ON tc.teaching_class_id = gr.teaching_class_id
                JOIN term source_term ON source_term.term_id = tc.term_id
                WHERE gr.grade_id = :gradeId AND gr.student_id = :studentId
                  AND gr.final_score < 60 AND gr.submitted = TRUE
                  AND EXISTS (SELECT 1 FROM sht_grade_workflow_batches13 approved_batch
                              WHERE approved_batch.lr_teaching_class_id13 = gr.teaching_class_id
                                AND approved_batch.lr_status13 = 'approved')
                  AND NOT EXISTS (
                      SELECT 1 FROM grade_record passed_grade
                      JOIN teaching_class passed_class ON passed_class.teaching_class_id = passed_grade.teaching_class_id
                      WHERE passed_grade.student_id = gr.student_id AND passed_class.course_id = tc.course_id
                        AND passed_grade.final_score >= 60 AND passed_grade.submitted = TRUE
                        AND EXISTS (SELECT 1 FROM sht_grade_workflow_batches13 passed_batch
                                    WHERE passed_batch.lr_teaching_class_id13 = passed_grade.teaching_class_id
                                      AND passed_batch.lr_status13 = 'approved')
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM grade_record newer_grade
                      JOIN teaching_class newer_class ON newer_class.teaching_class_id = newer_grade.teaching_class_id
                      JOIN term newer_term ON newer_term.term_id = newer_class.term_id
                      WHERE newer_grade.student_id = gr.student_id AND newer_class.course_id = tc.course_id
                        AND newer_grade.final_score < 60 AND newer_grade.submitted = TRUE
                        AND (newer_term.start_date > source_term.start_date
                             OR (newer_term.start_date = source_term.start_date AND newer_grade.grade_id > gr.grade_id))
                        AND EXISTS (SELECT 1 FROM sht_grade_workflow_batches13 newer_batch
                                    WHERE newer_batch.lr_teaching_class_id13 = newer_grade.teaching_class_id
                                      AND newer_batch.lr_status13 = 'approved')
                  )
                """, Map.of("gradeId", sourceGradeId, "studentId", studentId), "只能对已审批且未及格的本人课程申请重修");
        Long targetValid = jdbc.queryForObject("""
                SELECT COUNT(*) FROM term target_term
                WHERE target_term.term_id = :targetTermId
                  AND target_term.end_date > :sourceEndDate
                  AND target_term.end_date >= CURRENT_DATE
                  AND target_term.term_id <> :sourceTermId
                """, new MapSqlParameterSource().addValue("targetTermId", targetTermId)
                .addValue("sourceEndDate", failed.get("source_end_date"))
                .addValue("sourceTermId", failed.get("source_term_id")), Long.class);
        if (targetValid == null || targetValid == 0) throw new BusinessException("重修目标学期必须晚于原修读学期");
        Long duplicate = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sht_student_plan_adjustments13
                WHERE lr_source_grade_id13 = :gradeId AND lr_status13 IN ('PENDING', 'APPROVED')
                """, new MapSqlParameterSource("gradeId", sourceGradeId), Long.class);
        if (duplicate != null && duplicate > 0) throw new BusinessException("该挂科成绩已有待审批或已通过的重修申请");
        jdbc.update("""
                INSERT INTO sht_student_plan_adjustments13(
                    lr_student_id13, lr_source_plan_id13, lr_course_id13, lr_target_term_id13,
                    lr_course_nature13, lr_adjustment_type13, lr_source_grade_id13, lr_reason13
                ) VALUES (:studentId, :sourcePlanId, :courseId, :targetTermId,
                          :courseNature, 'RETAKE', :sourceGradeId, :reason)
                """, new MapSqlParameterSource().addValue("studentId", studentId)
                .addValue("sourcePlanId", failed.get("source_plan_id"), Types.BIGINT)
                .addValue("courseId", failed.get("course_id")).addValue("targetTermId", targetTermId)
                .addValue("courseNature", failed.get("course_nature")).addValue("sourceGradeId", sourceGradeId)
                .addValue("reason", requiredText(body, "reason")));
    }

    @Transactional
    public void decidePlanAdjustment(Long adjustmentId, Long approverUserId, Map<String, Object> body) {
        String status = decisionStatus(body);
        Map<String, Object> request = one("""
                SELECT lr_student_id13 AS student_id, lr_course_id13 AS course_id,
                       lr_adjustment_type13 AS adjustment_type, lr_status13 AS status
                FROM sht_student_plan_adjustments13 WHERE lr_adjustment_id13 = :id
                """, Map.of("id", adjustmentId), "培养方案调整申请不存在");
        if (!"PENDING".equals(request.get("status"))) throw new BusinessException("该申请已经处理");
        if ("APPROVED".equals(status) && "RETAKE".equals(request.get("adjustment_type"))) {
            Long passed = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM grade_record gr
                    JOIN teaching_class tc ON tc.teaching_class_id = gr.teaching_class_id
                    WHERE gr.student_id = :studentId AND tc.course_id = :courseId
                      AND gr.final_score >= 60 AND gr.submitted = TRUE
                      AND EXISTS (SELECT 1 FROM sht_grade_workflow_batches13 approved_batch
                                  WHERE approved_batch.lr_teaching_class_id13 = gr.teaching_class_id
                                    AND approved_batch.lr_status13 = 'approved')
                    """, new MapSqlParameterSource().addValue("studentId", request.get("student_id"))
                    .addValue("courseId", request.get("course_id")), Long.class);
            if (passed != null && passed > 0) throw new BusinessException("该学生已经通过此课程，无需再批准重修");
        }
        ensureUpdated(jdbc.update("""
                UPDATE sht_student_plan_adjustments13
                SET lr_status13 = :status, lr_approved_by13 = :approver,
                    lr_approved_at13 = CURRENT_TIMESTAMP, lr_updated_at13 = CURRENT_TIMESTAMP
                WHERE lr_adjustment_id13 = :id AND lr_status13 = 'PENDING'
                """, new MapSqlParameterSource()
                .addValue("status", status)
                .addValue("approver", approverUserId)
                .addValue("id", adjustmentId)));
    }

    public List<Map<String, Object>> programChanges(Long studentId) {
        return jdbc.queryForList("""
                SELECT pc.lr_program_change_id13 AS program_change_id,
                       pc.lr_student_id13 AS student_id,
                       pc.lr_from_admin_class_id13 AS from_admin_class_id,
                       pc.lr_to_admin_class_id13 AS to_admin_class_id,
                       pc.lr_effective_term_id13 AS effective_term_id,
                       pc.lr_status13 AS status, pc.lr_reason13 AS reason,
                       pc.lr_approved_by13 AS approved_by, pc.lr_approved_at13 AS approved_at,
                       pc.lr_created_at13 AS created_at, s.student_no, s.student_name,
                       from_ac.class_name AS from_class_name, to_ac.class_name AS to_class_name,
                       t.academic_year, t.semester, ua.display_name AS approver_name
                FROM sht_student_program_changes13 pc
                JOIN student s ON s.student_id = pc.lr_student_id13
                JOIN admin_class from_ac ON from_ac.admin_class_id = pc.lr_from_admin_class_id13
                JOIN admin_class to_ac ON to_ac.admin_class_id = pc.lr_to_admin_class_id13
                JOIN term t ON t.term_id = pc.lr_effective_term_id13
                LEFT JOIN user_account ua ON ua.user_id = pc.lr_approved_by13
                WHERE (:studentId IS NULL OR pc.lr_student_id13 = :studentId)
                ORDER BY pc.lr_created_at13 DESC, pc.lr_program_change_id13 DESC
                """, new MapSqlParameterSource().addValue("studentId", studentId, Types.BIGINT));
    }

    public void createProgramChange(Long studentId, Map<String, Object> body) {
        Long fromClassId = jdbc.queryForObject("SELECT admin_class_id FROM student WHERE student_id = :studentId",
                new MapSqlParameterSource("studentId", studentId), Long.class);
        if (fromClassId == null) {
            throw new BusinessException("学生不存在");
        }
        jdbc.update("""
                INSERT INTO sht_student_program_changes13(
                    lr_student_id13, lr_from_admin_class_id13, lr_to_admin_class_id13,
                    lr_effective_term_id13, lr_reason13
                ) VALUES (:studentId, :fromClassId, :toClassId, :termId, :reason)
                """, new MapSqlParameterSource()
                .addValue("studentId", studentId)
                .addValue("fromClassId", fromClassId)
                .addValue("toClassId", requiredLong(body, "toAdminClassId"))
                .addValue("termId", requiredLong(body, "effectiveTermId"))
                .addValue("reason", requiredText(body, "reason")));
    }

    @Transactional
    public void decideProgramChange(Long requestId, Long approverUserId, Map<String, Object> body) {
        Map<String, Object> request = one("""
                SELECT lr_student_id13 AS student_id, lr_from_admin_class_id13 AS from_class_id,
                       lr_to_admin_class_id13 AS to_class_id, lr_status13 AS status
                FROM sht_student_program_changes13
                WHERE lr_program_change_id13 = :id
                """, Map.of("id", requestId), "转专业/转班申请不存在");
        if (!"PENDING".equals(request.get("status"))) {
            throw new BusinessException("该申请已经处理");
        }
        String status = decisionStatus(body);
        ensureUpdated(jdbc.update("""
                UPDATE sht_student_program_changes13
                SET lr_status13 = :status, lr_approved_by13 = :approver,
                    lr_approved_at13 = CURRENT_TIMESTAMP, lr_updated_at13 = CURRENT_TIMESTAMP
                WHERE lr_program_change_id13 = :id AND lr_status13 = 'PENDING'
                """, new MapSqlParameterSource()
                .addValue("status", status).addValue("approver", approverUserId).addValue("id", requestId)));
        if ("APPROVED".equals(status)) {
            ensureUpdated(jdbc.update("""
                    UPDATE student SET admin_class_id = :toClassId
                    WHERE student_id = :studentId AND admin_class_id = :fromClassId
                    """, new MapSqlParameterSource()
                    .addValue("toClassId", request.get("to_class_id"))
                    .addValue("studentId", request.get("student_id"))
                    .addValue("fromClassId", request.get("from_class_id"))));
        }
    }

    public List<Map<String, Object>> gradeBatches(Long teacherId) {
        return jdbc.queryForList("""
                SELECT b.lr_batch_id13 AS batch_id, b.lr_teaching_class_id13 AS teaching_class_id,
                       b.lr_submission_no13 AS submission_no, b.lr_status13 AS status,
                       b.lr_submitted_by13 AS submitted_by, b.lr_submitted_at13 AS submitted_at,
                       b.lr_reviewed_by13 AS reviewed_by, b.lr_review_reason13 AS review_reason,
                       b.lr_reviewed_at13 AS reviewed_at, b.lr_record_version13 AS record_version,
                       tc.class_code, tc.class_name, c.course_code, c.course_name,
                       teacher.teacher_no, teacher.teacher_name, term.academic_year, term.semester,
                       (SELECT COUNT(*) FROM student_course_selection scs
                        WHERE scs.teaching_class_id = tc.teaching_class_id AND scs.status = 'selected') AS student_count,
                       (SELECT COUNT(*) FROM grade_record gr
                        WHERE gr.teaching_class_id = tc.teaching_class_id AND gr.final_score IS NOT NULL) AS graded_count,
                       (SELECT COUNT(*) FROM student_course_selection scs
                        LEFT JOIN grade_record gr ON gr.selection_id = scs.selection_id
                        WHERE scs.teaching_class_id = tc.teaching_class_id AND scs.status = 'selected'
                          AND gr.final_score IS NULL) AS missing_grade_count,
                       submitter.display_name AS submitter_name, reviewer.display_name AS reviewer_name
                FROM sht_grade_workflow_batches13 b
                JOIN teaching_class tc ON tc.teaching_class_id = b.lr_teaching_class_id13
                JOIN course c ON c.course_id = tc.course_id
                JOIN teacher ON teacher.teacher_id = tc.teacher_id
                JOIN term ON term.term_id = tc.term_id
                LEFT JOIN user_account submitter ON submitter.user_id = b.lr_submitted_by13
                LEFT JOIN user_account reviewer ON reviewer.user_id = b.lr_reviewed_by13
                WHERE (:teacherId IS NULL OR tc.teacher_id = :teacherId)
                  AND b.lr_submission_no13 = (
                      SELECT MAX(latest_batch.lr_submission_no13)
                      FROM sht_grade_workflow_batches13 latest_batch
                      WHERE latest_batch.lr_teaching_class_id13 = b.lr_teaching_class_id13
                  )
                ORDER BY b.lr_submitted_at13 DESC, b.lr_batch_id13 DESC
                """, new MapSqlParameterSource().addValue("teacherId", teacherId, Types.BIGINT));
    }

    @Transactional
    public void submitGradeBatch(Long teacherId, Long teachingClassId) {
        Long userId = teacherUserId(teacherId);
        Long owned = jdbc.queryForObject("""
                SELECT COUNT(*) FROM teaching_class
                WHERE teaching_class_id = :classId AND teacher_id = :teacherId
                """, new MapSqlParameterSource().addValue("classId", teachingClassId).addValue("teacherId", teacherId), Long.class);
        if (owned == null || owned == 0) {
            throw new BusinessException("教学班不属于当前教师");
        }
        Long locked = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sht_grade_workflow_batches13
                WHERE lr_teaching_class_id13 = :classId AND lr_status13 IN ('submitted', 'approved')
                """, new MapSqlParameterSource("classId", teachingClassId), Long.class);
        if (locked != null && locked > 0) throw new BusinessException("该教学班已有待审批或已通过的成绩批次");
        Integer nextNo = jdbc.queryForObject("""
                SELECT COALESCE(MAX(lr_submission_no13), 0) + 1
                FROM sht_grade_workflow_batches13
                WHERE lr_teaching_class_id13 = :classId
                """, new MapSqlParameterSource("classId", teachingClassId), Integer.class);
        jdbc.update("""
                INSERT INTO sht_grade_workflow_batches13(
                    lr_teaching_class_id13, lr_submission_no13, lr_status13, lr_submitted_by13
                ) VALUES (:classId, :submissionNo, 'submitted', :userId)
                """, new MapSqlParameterSource()
                .addValue("classId", teachingClassId)
                .addValue("submissionNo", nextNo)
                .addValue("userId", userId));
    }

    @Transactional
    public void reviewGradeBatch(Long batchId, Long reviewerUserId, Map<String, Object> body) {
        String decision = requiredText(body, "decision");
        if (!List.of("approved", "returned").contains(decision)) {
            throw new BusinessException("成绩审核只能通过或退回");
        }
        if ("returned".equals(decision) && !StringUtils.hasText(text(body.get("reason")))) {
            throw new BusinessException("退回成绩时必须填写原因");
        }
        Map<String, Object> batch = one("""
                SELECT lr_teaching_class_id13 AS teaching_class_id, lr_status13 AS status
                FROM sht_grade_workflow_batches13
                WHERE lr_batch_id13 = :id
                """, Map.of("id", batchId), "成绩批次不存在");
        if (!"submitted".equals(batch.get("status"))) {
            throw new BusinessException("成绩批次已经处理");
        }
        if ("approved".equals(decision)) {
            Long missing = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM student_course_selection scs
                    LEFT JOIN grade_record gr ON gr.selection_id = scs.selection_id
                    WHERE scs.teaching_class_id = :teachingClassId AND scs.status = 'selected'
                      AND gr.final_score IS NULL
                    """, new MapSqlParameterSource("teachingClassId", batch.get("teaching_class_id")), Long.class);
            if (missing != null && missing > 0) throw new BusinessException("成绩单仍有 " + missing + " 名学生无成绩，不能通过审批");
        }
        ensureUpdated(jdbc.update("""
                UPDATE sht_grade_workflow_batches13
                SET lr_status13 = :decision, lr_reviewed_by13 = :reviewer,
                    lr_review_reason13 = :reason, lr_reviewed_at13 = CURRENT_TIMESTAMP,
                    lr_record_version13 = lr_record_version13 + 1
                WHERE lr_batch_id13 = :id AND lr_status13 = 'submitted'
                """, new MapSqlParameterSource()
                .addValue("decision", decision)
                .addValue("reviewer", reviewerUserId)
                .addValue("reason", text(body.get("reason")))
                .addValue("id", batchId)));
        if ("returned".equals(decision)) {
            jdbc.update("""
                    UPDATE grade_record
                    SET submitted = FALSE, submitted_at = NULL, updated_at = CURRENT_TIMESTAMP
                    WHERE teaching_class_id = :teachingClassId
                    """, new MapSqlParameterSource("teachingClassId", batch.get("teaching_class_id")));
        } else {
            jdbc.update("""
                    UPDATE grade_record SET submitted = TRUE, updated_at = CURRENT_TIMESTAMP
                    WHERE teaching_class_id = :teachingClassId
                    """, new MapSqlParameterSource("teachingClassId", batch.get("teaching_class_id")));
        }
    }

    @Transactional
    public Long publishClassNotice(Long teacherId, Long teachingClassId, Map<String, Object> body) {
        Long userId = teacherUserId(teacherId);
        one("""
                SELECT teaching_class_id
                FROM teaching_class
                WHERE teaching_class_id = :classId AND teacher_id = :teacherId
                """, Map.of("classId", teachingClassId, "teacherId", teacherId), "教学班不属于当前教师");
        Long recipientCount = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT student_id)
                FROM student_course_selection
                WHERE teaching_class_id = :classId AND status = 'selected'
                """, new MapSqlParameterSource("classId", teachingClassId), Long.class);
        if (recipientCount == null || recipientCount == 0) {
            throw new BusinessException("当前教学班没有已选学生，无法发布通知");
        }
        String noticeType = StringUtils.hasText(text(body.get("noticeType"))) ? text(body.get("noticeType")) : "normal";
        if (!List.of("normal", "important").contains(noticeType)) {
            throw new BusinessException("通知级别只能是普通或重要");
        }
        String title = requiredText(body, "title");
        String content = requiredText(body, "content");
        if (title.length() > 200) throw new BusinessException("通知标题不能超过 200 个字符");
        if (content.length() > 2000) throw new BusinessException("通知正文不能超过 2000 个字符");
        Long noticeId = jdbc.queryForObject("""
                INSERT INTO notice(user_id, notice_type, title, content, read_flag)
                VALUES (:userId, :noticeType, :title, :content, FALSE)
                RETURNING notice_id
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("noticeType", noticeType)
                .addValue("title", title)
                .addValue("content", content), Long.class);
        if (noticeId == null) {
            throw new BusinessException("通知创建失败");
        }
        jdbc.update("""
                INSERT INTO sht_teacher_class_notice_recipients13(
                    lr_teacher_class_notice_id13, lr_student_id13
                )
                SELECT :noticeId, scs.student_id
                FROM student_course_selection scs
                WHERE scs.teaching_class_id = :classId AND scs.status = 'selected'
                GROUP BY scs.student_id
                """, new MapSqlParameterSource().addValue("noticeId", noticeId).addValue("classId", teachingClassId));
        return noticeId;
    }

    public List<Map<String, Object>> studentNotices(Long studentId) {
        return jdbc.queryForList("""
                SELECT n.notice_id, n.notice_type, n.title, n.content, n.created_at,
                       r.lr_read_flag13 AS read_flag, r.lr_read_at13 AS read_at,
                       ua.display_name AS publisher_name
                FROM sht_teacher_class_notice_recipients13 r
                JOIN notice n ON n.notice_id = r.lr_teacher_class_notice_id13
                LEFT JOIN user_account ua ON ua.user_id = n.user_id
                WHERE r.lr_student_id13 = :studentId
                ORDER BY n.created_at DESC, n.notice_id DESC
                """, new MapSqlParameterSource("studentId", studentId));
    }

    public void markNoticeRead(Long studentId, Long noticeId) {
        ensureUpdated(jdbc.update("""
                UPDATE sht_teacher_class_notice_recipients13
                SET lr_read_flag13 = TRUE, lr_read_at13 = COALESCE(lr_read_at13, CURRENT_TIMESTAMP)
                WHERE lr_student_id13 = :studentId AND lr_teacher_class_notice_id13 = :noticeId
                """, new MapSqlParameterSource().addValue("studentId", studentId).addValue("noticeId", noticeId)));
    }

    public Map<String, Object> rbacOverview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("roles", list("SELECT lr_role_id13 AS role_id, lr_role_code13 AS role_code, lr_role_name13 AS role_name, lr_description13 AS description, lr_is_system13 AS is_system, lr_status13 AS status, lr_scope_types_text13 AS scope_types_text FROM sht_system_roles13 ORDER BY lr_role_code13", Map.of()));
        result.put("permissions", list("SELECT lr_permission_id13 AS permission_id, lr_permission_code13 AS permission_code, lr_permission_name13 AS permission_name, lr_resource_type13 AS resource_type, lr_action_code13 AS action_code, lr_risk_level13 AS risk_level, lr_description13 AS description FROM sht_system_permissions13 ORDER BY lr_resource_type13, lr_action_code13", Map.of()));
        result.put("userRoles", list("SELECT ur.lr_user_id13 AS user_id, ur.lr_role_id13 AS role_id, ur.lr_assigned_by13 AS assigned_by, ur.lr_assigned_at13 AS assigned_at, ua.username, ua.display_name, r.lr_role_code13 AS role_code, r.lr_role_name13 AS role_name FROM sht_user_roles13 ur JOIN user_account ua ON ua.user_id = ur.lr_user_id13 JOIN sht_system_roles13 r ON r.lr_role_id13 = ur.lr_role_id13 ORDER BY ua.username, r.lr_role_code13", Map.of()));
        result.put("rolePermissions", list("SELECT lr_role_id13 AS role_id, lr_permission_id13 AS permission_id FROM sht_role_permissions13 ORDER BY lr_role_id13, lr_permission_id13", Map.of()));
        result.put("dataScopes", list("SELECT ds.lr_scope_id13 AS scope_id, ds.lr_user_id13 AS user_id, ds.lr_scope_type13 AS scope_type, ds.lr_scope_value13 AS scope_value, ds.lr_created_by13 AS created_by, ds.lr_created_at13 AS created_at, ua.username, ua.display_name FROM sht_user_data_scopes13 ds JOIN user_account ua ON ua.user_id = ds.lr_user_id13 ORDER BY ua.username, ds.lr_scope_type13", Map.of()));
        return result;
    }

    @Transactional
    public Long createRole(Map<String, Object> body) {
        String code = requiredText(body, "roleCode").toUpperCase();
        if (code.length() > 20) {
            throw new BusinessException("角色代码不能超过 20 个字符");
        }
        jdbc.update("""
                INSERT INTO sht_system_roles13(
                    lr_role_code13, lr_role_name13, lr_description13,
                    lr_is_system13, lr_status13, lr_scope_types_text13
                ) VALUES (:code, :name, :description, :systemRole, :status, :scopeTypes)
                """, new MapSqlParameterSource()
                .addValue("code", code)
                .addValue("name", requiredText(body, "roleName"))
                .addValue("description", text(body.get("description")))
                .addValue("systemRole", Boolean.TRUE.equals(body.get("systemRole")))
                .addValue("status", StringUtils.hasText(text(body.get("status"))) ? text(body.get("status")) : "enabled")
                .addValue("scopeTypes", StringUtils.hasText(text(body.get("scopeTypesText"))) ? text(body.get("scopeTypesText")) : "[]"));
        Long roleId = jdbc.queryForObject("SELECT lr_role_id13 FROM sht_system_roles13 WHERE lr_role_code13 = :code",
                new MapSqlParameterSource("code", code), Long.class);
        replaceRolePermissions(roleId, body.get("permissionIds"));
        return roleId;
    }

    @Transactional
    public void updateRole(Long roleId, Map<String, Object> body) {
        Map<String, Object> role = one("SELECT lr_is_system13 AS is_system FROM sht_system_roles13 WHERE lr_role_id13 = :id",
                Map.of("id", roleId), "角色不存在");
        if (Boolean.TRUE.equals(role.get("is_system"))) {
            throw new BusinessException("管理员、教师和学生为系统内置角色，不能修改");
        }
        jdbc.update("""
                UPDATE sht_system_roles13
                SET lr_role_name13 = :name, lr_description13 = :description, lr_status13 = :status
                WHERE lr_role_id13 = :id
                """, new MapSqlParameterSource()
                .addValue("id", roleId)
                .addValue("name", requiredText(body, "roleName"))
                .addValue("description", text(body.get("description")))
                .addValue("status", StringUtils.hasText(text(body.get("status"))) ? text(body.get("status")) : "enabled"));
        replaceRolePermissions(roleId, body.get("permissionIds"));
    }

    private void replaceRolePermissions(Long roleId, Object rawPermissionIds) {
        jdbc.update("DELETE FROM sht_role_permissions13 WHERE lr_role_id13 = :roleId", new MapSqlParameterSource("roleId", roleId));
        if (!(rawPermissionIds instanceof List<?> permissionIds)) return;
        for (Object rawId : permissionIds) {
            Long permissionId = longValue(rawId);
            if (permissionId == null) continue;
            jdbc.update("INSERT INTO sht_role_permissions13(lr_role_id13, lr_permission_id13) VALUES (:roleId, :permissionId)",
                    new MapSqlParameterSource().addValue("roleId", roleId).addValue("permissionId", permissionId));
        }
    }

    public void createPermission(Map<String, Object> body) {
        jdbc.update("""
                INSERT INTO sht_system_permissions13(
                    lr_permission_code13, lr_permission_name13, lr_resource_type13,
                    lr_action_code13, lr_risk_level13, lr_description13
                ) VALUES (:code, :name, :resourceType, :actionCode, :riskLevel, :description)
                """, new MapSqlParameterSource()
                .addValue("code", requiredText(body, "permissionCode"))
                .addValue("name", requiredText(body, "permissionName"))
                .addValue("resourceType", requiredText(body, "resourceType"))
                .addValue("actionCode", requiredText(body, "actionCode"))
                .addValue("riskLevel", StringUtils.hasText(text(body.get("riskLevel"))) ? text(body.get("riskLevel")) : "low")
                .addValue("description", text(body.get("description"))));
    }

    public void assignUserRole(Map<String, Object> body) {
        jdbc.update("""
                INSERT INTO sht_user_roles13(lr_user_id13, lr_role_id13, lr_assigned_by13)
                SELECT :userId, :roleId, :assignedBy
                WHERE NOT EXISTS (
                    SELECT 1 FROM sht_user_roles13
                    WHERE lr_user_id13 = :userId AND lr_role_id13 = :roleId
                )
                """, new MapSqlParameterSource()
                .addValue("userId", requiredLong(body, "userId"))
                .addValue("roleId", requiredLong(body, "roleId"))
                .addValue("assignedBy", longValue(body.get("assignedBy"))));
    }

    public void assignRolePermission(Map<String, Object> body) {
        jdbc.update("""
                INSERT INTO sht_role_permissions13(lr_role_id13, lr_permission_id13)
                SELECT :roleId, :permissionId
                WHERE NOT EXISTS (
                    SELECT 1 FROM sht_role_permissions13
                    WHERE lr_role_id13 = :roleId AND lr_permission_id13 = :permissionId
                )
                """, new MapSqlParameterSource()
                .addValue("roleId", requiredLong(body, "roleId"))
                .addValue("permissionId", requiredLong(body, "permissionId")));
    }

    public void createDataScope(Map<String, Object> body) {
        jdbc.update("""
                INSERT INTO sht_user_data_scopes13(
                    lr_user_id13, lr_scope_type13, lr_scope_value13, lr_created_by13
                ) VALUES (:userId, :scopeType, :scopeValue, :createdBy)
                """, new MapSqlParameterSource()
                .addValue("userId", requiredLong(body, "userId"))
                .addValue("scopeType", requiredText(body, "scopeType"))
                .addValue("scopeValue", longValue(body.get("scopeValue")))
                .addValue("createdBy", longValue(body.get("createdBy"))));
    }

    public List<Map<String, Object>> operationPlans() {
        return list("""
                SELECT p.lr_plan_id13 AS plan_id, p.lr_user_id13 AS user_id,
                       p.lr_operation_type13 AS operation_type, p.lr_request_hash13 AS request_hash,
                       p.lr_request_payload13 AS request_payload, p.lr_effect_summary13 AS effect_summary,
                       p.lr_resource_versions13 AS resource_versions, p.lr_risk_level13 AS risk_level,
                       p.lr_status13 AS status, p.lr_expires_at13 AS expires_at,
                       p.lr_consumed_at13 AS consumed_at, p.lr_created_at13 AS created_at,
                       ua.display_name AS requester_name
                FROM sht_operation_plans13 p
                JOIN user_account ua ON ua.user_id = p.lr_user_id13
                ORDER BY p.lr_created_at13 DESC
                """, Map.of());
    }

    public String createOperationPlan(Long userId, Map<String, Object> body) {
        String planId = UUID.randomUUID().toString();
        String payload = json(body.get("requestPayload"));
        String hash = sha256(payload);
        jdbc.update("""
                INSERT INTO sht_operation_plans13(
                    lr_plan_id13, lr_user_id13, lr_operation_type13, lr_request_hash13,
                    lr_request_payload13, lr_effect_summary13, lr_resource_versions13,
                    lr_risk_level13, lr_expires_at13
                ) VALUES (
                    :planId, :userId, :operationType, :requestHash,
                    :requestPayload, :effectSummary, :resourceVersions,
                    :riskLevel, :expiresAt
                )
                """, new MapSqlParameterSource()
                .addValue("planId", planId)
                .addValue("userId", userId)
                .addValue("operationType", requiredText(body, "operationType"))
                .addValue("requestHash", hash)
                .addValue("requestPayload", payload)
                .addValue("effectSummary", requiredText(body, "effectSummary"))
                .addValue("resourceVersions", text(body.get("resourceVersions")))
                .addValue("riskLevel", requiredText(body, "riskLevel"))
                .addValue("expiresAt", LocalDateTime.now().plusMinutes(30)));
        return planId;
    }

    @Transactional
    public void decideOperationPlan(String planId, Long approverUserId, Map<String, Object> body) {
        Map<String, Object> plan = one("""
                SELECT lr_user_id13 AS requester_id, lr_request_hash13 AS request_hash,
                       lr_status13 AS status, lr_expires_at13 AS expires_at
                FROM sht_operation_plans13 WHERE lr_plan_id13 = :id
                """, Map.of("id", planId), "操作计划不存在");
        if (!"pending".equals(plan.get("status"))) {
            throw new BusinessException("操作计划已经处理");
        }
        if (((Number) plan.get("requester_id")).longValue() == approverUserId) {
            throw new BusinessException("高风险操作不能由发起人自行审批");
        }
        if (asLocalDateTime(plan.get("expires_at")).isBefore(LocalDateTime.now())) {
            jdbc.update("UPDATE sht_operation_plans13 SET lr_status13 = 'expired' WHERE lr_plan_id13 = :id",
                    new MapSqlParameterSource("id", planId));
            throw new BusinessException("操作计划已经过期");
        }
        String decision = requiredText(body, "decision");
        if (!List.of("approved", "rejected").contains(decision)) {
            throw new BusinessException("审批结果不正确");
        }
        jdbc.update("""
                INSERT INTO sht_operation_approvals13(
                    lr_approval_id13, lr_plan_id13, lr_approver_user_id13,
                    lr_decision13, lr_comment_text13, lr_approved_request_hash13
                ) VALUES (:approvalId, :planId, :approver, :decision, :comment, :requestHash)
                """, new MapSqlParameterSource()
                .addValue("approvalId", UUID.randomUUID().toString())
                .addValue("planId", planId)
                .addValue("approver", approverUserId)
                .addValue("decision", decision)
                .addValue("comment", text(body.get("comment")))
                .addValue("requestHash", plan.get("request_hash")));
        jdbc.update("UPDATE sht_operation_plans13 SET lr_status13 = :status WHERE lr_plan_id13 = :id",
                new MapSqlParameterSource().addValue("status", decision).addValue("id", planId));
    }

    private Long teacherUserId(Long teacherId) {
        List<Long> ids = jdbc.queryForList("""
                SELECT user_id FROM user_account
                WHERE role_code = 'TEACHER' AND related_id = :teacherId AND status = 'enabled'
                """, new MapSqlParameterSource("teacherId", teacherId), Long.class);
        if (ids.isEmpty()) {
            throw new BusinessException("教师账号不存在或已停用");
        }
        return ids.get(0);
    }

    private void ensurePending(String table, String idColumn, Long id, String statusColumn) {
        String sql = "SELECT " + statusColumn + " FROM " + table + " WHERE " + idColumn + " = :id";
        List<String> statuses = jdbc.queryForList(sql, new MapSqlParameterSource("id", id), String.class);
        if (statuses.isEmpty()) {
            throw new BusinessException("申请不存在");
        }
        if (!"PENDING".equals(statuses.get(0))) {
            throw new BusinessException("申请已经处理");
        }
    }

    private String decisionStatus(Map<String, Object> body) {
        String decision = requiredText(body, "decision").toUpperCase();
        if (!List.of("APPROVED", "REJECTED").contains(decision)) {
            throw new BusinessException("审批结果只能是通过或拒绝");
        }
        return decision;
    }

    private List<Map<String, Object>> list(String sql, Map<String, ?> params) {
        return jdbc.queryForList(sql, new MapSqlParameterSource(params));
    }

    private Map<String, Object> one(String sql, Map<String, ?> params, String message) {
        List<Map<String, Object>> rows = list(sql, params);
        if (rows.isEmpty()) {
            throw new BusinessException(message);
        }
        return rows.get(0);
    }

    private void ensureUpdated(int count) {
        if (count == 0) {
            throw new BusinessException("记录不存在或状态已经变化");
        }
    }

    private String json(Object value) {
        if (value == null) {
            return "{}";
        }
        if (value instanceof String string) {
            return string;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("请求内容无法序列化");
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String requiredText(Map<String, Object> body, String key) {
        String value = text(body.get(key));
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(key + " 不能为空");
        }
        return value;
    }

    private Long requiredLong(Map<String, Object> body, String key) {
        Long value = longValue(body.get(key));
        if (value == null) {
            throw new BusinessException(key + " 不能为空");
        }
        return value;
    }

    private Integer requiredInteger(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value instanceof Number number) {
            int result = number.intValue();
            if ("maxCoursesPerTerm".equals(key) && result <= 0) {
                throw new BusinessException(key + " 必须大于 0");
            }
            return result;
        }
        try {
            if (value == null || !StringUtils.hasText(value.toString())) {
                throw new BusinessException(key + " 不能为空");
            }
            int result = Integer.valueOf(value.toString());
            if ("maxCoursesPerTerm".equals(key) && result <= 0) {
                throw new BusinessException(key + " 必须大于 0");
            }
            return result;
        } catch (NumberFormatException ex) {
            throw new BusinessException(key + " 格式不正确");
        }
    }

    private Double requiredDecimal(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value instanceof Number number) {
            double result = number.doubleValue();
            if (result < 0) {
                throw new BusinessException(key + " 不能小于 0");
            }
            return result;
        }
        try {
            if (value == null || !StringUtils.hasText(value.toString())) {
                throw new BusinessException(key + " 不能为空");
            }
            double result = Double.valueOf(value.toString());
            if (result < 0) {
                throw new BusinessException(key + " 不能小于 0");
            }
            return result;
        } catch (NumberFormatException ex) {
            throw new BusinessException(key + " 格式不正确");
        }
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null || !StringUtils.hasText(value.toString()) ? null : Long.valueOf(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String text(Object value) {
        return value == null ? null : value.toString().trim();
    }

    private LocalDateTime asLocalDateTime(Object value) {
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof java.util.Date date) {
            return date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
        }
        return LocalDateTime.parse(value.toString().replace(' ', 'T'));
    }
}

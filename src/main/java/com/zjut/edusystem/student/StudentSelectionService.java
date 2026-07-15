package com.zjut.edusystem.student;

import com.zjut.edusystem.common.BusinessException;
import com.zjut.edusystem.selection.MajorCourseCacheService;
import com.zjut.edusystem.selection.SelectionQueueService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class StudentSelectionService {
    private static final Logger log = LoggerFactory.getLogger(StudentSelectionService.class);
    private static final String ELECTIVE_SQL =
            "'general_elective', 'discipline_elective', 'major_elective'";
    private static final String OPEN_ROUND_CACHE_KEY = "selection:open-round:v1";

    private final NamedParameterJdbcTemplate jdbc;
    private final SelectionQueueService queueService;
    private final MajorCourseCacheService majorCourseCacheService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final boolean availableCacheEnabled;
    private final Duration openRoundCacheTtl;

    public StudentSelectionService(NamedParameterJdbcTemplate jdbc,
                                   SelectionQueueService queueService,
                                   MajorCourseCacheService majorCourseCacheService,
                                   StringRedisTemplate redisTemplate,
                                   ObjectMapper objectMapper,
                                   @Value("${edu-system.selection.available-cache.enabled:true}") boolean availableCacheEnabled,
                                   @Value("${edu-system.selection.round-ttl-seconds:5}") long openRoundCacheTtlSeconds) {
        this.jdbc = jdbc;
        this.queueService = queueService;
        this.majorCourseCacheService = majorCourseCacheService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.availableCacheEnabled = availableCacheEnabled;
        this.openRoundCacheTtl = Duration.ofSeconds(Math.max(1L, openRoundCacheTtlSeconds));
    }

    public List<Map<String, Object>> availableCourses(Long studentId) {
        Map<String, Object> round = currentOpenSelectionRoundCached();
        if (round == null) {
            return List.of();
        }
        Long termId = ((Number) round.get("term_id")).longValue();
        List<Map<String, Object>> rows = majorCourseCacheService.availableCourseRows(studentId, round);
        enrichAvailableCourseRows(rows, studentId, termId);
        return groupAvailableCourses(rows);
    }

    public List<Map<String, Object>> mySelections(Long studentId) {
        return jdbc.queryForList("""
                SELECT scs.*, c.course_code, c.course_name, c.credit, c.hours, tc.class_name, t.teacher_name,
                       cs.weekday, cs.start_period, cs.end_period, cs.start_week, cs.end_week, cs.week_pattern, cs.classroom, cs.weeks
                FROM student_course_selection scs
                JOIN teaching_class tc ON tc.teaching_class_id = scs.teaching_class_id
                JOIN course c ON c.course_id = tc.course_id
                JOIN teacher t ON t.teacher_id = tc.teacher_id
                LEFT JOIN class_schedule cs ON cs.teaching_class_id = tc.teaching_class_id
                WHERE scs.student_id = :studentId
                  AND scs.status = 'selected'
                ORDER BY scs.created_at DESC
                """, new MapSqlParameterSource("studentId", studentId));
    }

    public List<Map<String, Object>> mySchedule(Long studentId, String academicYear, Integer semester) {
        MapSqlParameterSource params = new MapSqlParameterSource("studentId", studentId)
                .addValue("academicYear", StringUtils.hasText(academicYear) ? academicYear : null)
                .addValue("semester", semester);
        return jdbc.queryForList("""
                SELECT v.*
                FROM v_student_schedule v
                WHERE v.student_id = :studentId
                  AND (:academicYear IS NULL OR regexp_replace(v.academic_year, '[^0-9]', '', 'g') = regexp_replace(:academicYear, '[^0-9]', '', 'g'))
                  AND (:semester IS NULL OR v.semester = :semester)
                ORDER BY weekday, start_period
                """, params);
    }

    public List<Map<String, Object>> myGrades(Long studentId, String academicYear, Integer semester, Long courseId, String courseType) {
        Map<String, Object> defaultTerm = null;
        if (!StringUtils.hasText(academicYear) && semester == null) {
            defaultTerm = currentTermByUtc8Date();
            if (defaultTerm != null) {
                academicYear = String.valueOf(defaultTerm.get("academic_year"));
                semester = ((Number) defaultTerm.get("semester")).intValue();
            }
        }

        MapSqlParameterSource params = new MapSqlParameterSource("studentId", studentId)
                .addValue("academicYear", StringUtils.hasText(academicYear) ? academicYear : null)
                .addValue("semester", semester);

        StringBuilder sql = new StringBuilder("""
                SELECT grade_rows.*
                FROM (
                    SELECT gr.*, c.course_id, c.course_code, c.course_name, c.credit,
                           COALESCE(
                               (
                                   SELECT tp.course_nature
                                   FROM student plan_student
                                   JOIN admin_class plan_class ON plan_class.admin_class_id = plan_student.admin_class_id
                                   JOIN teaching_plan tp
                                     ON tp.major_id = plan_class.major_id
                                    AND tp.grade_year = plan_student.grade_year
                                    AND tp.course_id = c.course_id
                                   JOIN term plan_term ON plan_term.term_id = tp.term_id
                                   WHERE plan_student.student_id = gr.student_id
                                     AND regexp_replace(plan_term.academic_year, '[^0-9]', '', 'g') = regexp_replace(t.academic_year, '[^0-9]', '', 'g')
                                     AND plan_term.semester = t.semester
                                   LIMIT 1
                               ),
                               (
                                   SELECT adjustment.lr_course_nature13
                                   FROM sht_student_plan_adjustments13 adjustment
                                   WHERE adjustment.lr_student_id13 = gr.student_id
                                     AND adjustment.lr_course_id13 = c.course_id
                                     AND adjustment.lr_status13 = 'APPROVED'
                                     AND (adjustment.lr_target_term_id13 IS NULL OR adjustment.lr_target_term_id13 = t.term_id)
                                   LIMIT 1
                               ),
                               'catalog'
                           ) AS course_type,
                           tc.class_name, t.academic_year, t.semester
                    FROM grade_record gr
                    JOIN teaching_class tc ON tc.teaching_class_id = gr.teaching_class_id
                    JOIN course c ON c.course_id = tc.course_id
                    JOIN term t ON t.term_id = tc.term_id
                    WHERE gr.student_id = :studentId
                      AND gr.submitted = TRUE
                      AND EXISTS (
                          SELECT 1 FROM sht_grade_workflow_batches13 approved_batch
                          WHERE approved_batch.lr_teaching_class_id13 = gr.teaching_class_id
                            AND approved_batch.lr_status13 = 'approved'
                      )
                ) grade_rows
                WHERE 1 = 1
                """);
        if (StringUtils.hasText(academicYear)) {
            sql.append(" AND regexp_replace(grade_rows.academic_year, '[^0-9]', '', 'g') = regexp_replace(:academicYear, '[^0-9]', '', 'g')");
        }
        if (semester != null) {
            sql.append(" AND grade_rows.semester = :semester");
        }
        if (courseId != null) {
            sql.append(" AND grade_rows.course_id = :courseId");
            params.addValue("courseId", courseId);
        }
        if (StringUtils.hasText(courseType)) {
            sql.append(" AND grade_rows.course_type = :courseType");
            params.addValue("courseType", courseType);
        }
        sql.append(" ORDER BY grade_rows.academic_year DESC, grade_rows.semester DESC, grade_rows.course_code");

        return jdbc.queryForList(sql.toString(), params);
    }

    public List<Map<String, Object>> myTrainingPlan(Long studentId, String academicYear, Integer semester) {
        MapSqlParameterSource params = new MapSqlParameterSource("studentId", studentId)
                .addValue("academicYear", StringUtils.hasText(academicYear) ? academicYear : null)
                .addValue("semester", semester);

        StringBuilder sql = new StringBuilder("""
                SELECT
                    tp.plan_id,
                    tp.course_nature AS course_type,
                    tp.grade_year,
                    term.term_id,
                    term.academic_year,
                    term.semester,
                    c.course_id,
                    c.course_code,
                    c.course_name,
                    c.credit,
                    c.hours,
                    c.exam_type
                FROM student s
                JOIN admin_class ac ON ac.admin_class_id = s.admin_class_id
                JOIN teaching_plan tp ON tp.major_id = ac.major_id AND tp.grade_year = s.grade_year
                JOIN term term ON term.term_id = tp.term_id
                JOIN course c ON c.course_id = tp.course_id
                WHERE s.student_id = :studentId
                  AND tp.course_nature IN (
                      'general_required',
                      'general_elective',
                      'discipline_required',
                      'discipline_elective',
                      'major_required',
                      'major_elective',
                      'prerequisite',
                      'practice'
                  )
                """);
        if (StringUtils.hasText(academicYear)) {
            sql.append(" AND regexp_replace(term.academic_year, '[^0-9]', '', 'g') = regexp_replace(:academicYear, '[^0-9]', '', 'g')");
        }
        if (semester != null) {
            sql.append(" AND term.semester = :semester");
        }
        sql.append(" ORDER BY term.academic_year, term.semester, tp.course_nature, c.course_code");

        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), params);
        StringBuilder adjustmentSql = new StringBuilder("""
                SELECT
                    NULL AS plan_id,
                    adjustment.lr_course_nature13 AS course_type,
                    s.grade_year,
                    term.term_id,
                    term.academic_year,
                    term.semester,
                    c.course_id,
                    c.course_code,
                    c.course_name,
                    c.credit,
                    c.hours,
                    c.exam_type
                FROM sht_student_plan_adjustments13 adjustment
                JOIN student s ON s.student_id = adjustment.lr_student_id13
                JOIN term term ON term.term_id = adjustment.lr_target_term_id13
                JOIN course c ON c.course_id = adjustment.lr_course_id13
                WHERE adjustment.lr_student_id13 = :studentId
                  AND adjustment.lr_status13 = 'APPROVED'
                """);
        if (StringUtils.hasText(academicYear)) {
            adjustmentSql.append(" AND regexp_replace(term.academic_year, '[^0-9]', '', 'g') = regexp_replace(:academicYear, '[^0-9]', '', 'g')");
        }
        if (semester != null) {
            adjustmentSql.append(" AND term.semester = :semester");
        }
        rows.addAll(jdbc.queryForList(adjustmentSql.toString(), params));
        rows.sort((left, right) -> {
            String leftKey = left.get("academic_year") + "-" + left.get("semester") + "-" + left.get("course_code");
            String rightKey = right.get("academic_year") + "-" + right.get("semester") + "-" + right.get("course_code");
            return leftKey.compareTo(rightKey);
        });
        return rows;
    }

    public List<Map<String, Object>> myTrainingPlanTerms(Long studentId) {
        return jdbc.queryForList("""
                SELECT DISTINCT term.term_id,
                       term.academic_year,
                       term.semester,
                       term.start_date,
                       term.end_date,
                       term.is_current
                FROM student s
                JOIN grade_year gy ON gy.grade_year = s.grade_year
                JOIN term term ON CAST(SUBSTRING(regexp_replace(term.academic_year, '[^0-9]', '', 'g'), 1, 4) AS INT)
                    BETWEEN CAST(SUBSTRING(regexp_replace(gy.admission_academic_year, '[^0-9]', '', 'g'), 1, 4) AS INT)
                    AND CAST(SUBSTRING(regexp_replace(gy.graduation_academic_year, '[^0-9]', '', 'g'), 1, 4) AS INT)
                WHERE s.student_id = :studentId
                  AND term.semester IN (1, 2, 3)
                  AND (
                      regexp_replace(term.academic_year, '[^0-9]', '', 'g') <> regexp_replace(gy.graduation_academic_year, '[^0-9]', '', 'g')
                      OR term.semester <= 2
                  )
                ORDER BY term.academic_year DESC, term.semester DESC, term.term_id DESC
                """, new MapSqlParameterSource("studentId", studentId));
    }

    @Transactional
    public Map<String, Object> selectCourse(Long studentId, Long teachingClassId, Long roundId) {
        Map<String, Object> round = validateRound(roundId);
        Map<String, Object> target = targetClass(teachingClassId);
        ensureRoundMatchesTerm(round, ((Number) target.get("term_id")).longValue());
        Long courseId = ((Number) target.get("course_id")).longValue();
        Long termId = ((Number) target.get("term_id")).longValue();
        validateTeachingPlan(studentId, courseId, termId);
        ensureNoSelectedSameCourse(studentId, courseId, termId);
        ensureNoTimeConflict(studentId, teachingClassId);
        Map<String, Object> selectionLimit = selectionLimitWarning(studentId, courseId, termId);

        int updated = jdbc.update("""
                UPDATE teaching_class
                SET selected_count = selected_count + 1
                WHERE teaching_class_id = :teachingClassId
                  AND status = 'open'
                  AND selected_count < capacity
                """, new MapSqlParameterSource("teachingClassId", teachingClassId));
        if (updated == 0) {
            throw new BusinessException("教学班容量不足，可进入候补队列");
        }

        String requestId = queueService.acceptSelectionRequest(studentId, teachingClassId, roundId);
        try {
            jdbc.update("""
                    INSERT INTO student_course_selection(request_id, student_id, teaching_class_id, round_id, status, selected_at)
                    VALUES(:requestId, :studentId, :teachingClassId, :roundId, 'selected', :now)
                    """, new MapSqlParameterSource()
                    .addValue("requestId", requestId)
                    .addValue("studentId", studentId)
                    .addValue("teachingClassId", teachingClassId)
                    .addValue("roundId", roundId)
                    .addValue("now", LocalDateTime.now()));
            jdbc.update("""
                    INSERT INTO selection_request_log(request_id, student_id, teaching_class_id, round_id, request_status, mq_status)
                    VALUES(:requestId, :studentId, :teachingClassId, :roundId, 'success', 'reserved')
                    """, new MapSqlParameterSource()
                    .addValue("requestId", requestId)
                    .addValue("studentId", studentId)
                    .addValue("teachingClassId", teachingClassId)
                    .addValue("roundId", roundId));
        } catch (RuntimeException ex) {
            jdbc.update("""
                    UPDATE teaching_class SET selected_count = selected_count - 1
                    WHERE teaching_class_id = :teachingClassId AND selected_count > 0
                    """, new MapSqlParameterSource("teachingClassId", teachingClassId));
            throw ex;
        }
        majorCourseCacheService.refreshTeachingClassStateAfterCommit(roundId, teachingClassId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestId", requestId);
        result.put("selectionLimitWarning", Boolean.TRUE.equals(selectionLimit.get("warning")));
        result.put("selectedCourseCount", selectionLimit.get("selectedCount"));
        result.put("maxCoursesPerTerm", selectionLimit.get("maxCourses"));
        return result;
    }

    private Map<String, Object> selectionLimitWarning(Long studentId, Long courseId, Long termId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                WITH course_nature AS (
                    SELECT tp.course_nature
                    FROM student s
                    JOIN admin_class ac ON ac.admin_class_id = s.admin_class_id
                    JOIN teaching_plan tp ON tp.major_id = ac.major_id
                                           AND tp.grade_year = s.grade_year
                                           AND tp.course_id = :courseId
                    WHERE s.student_id = :studentId AND tp.term_id = :termId
                    UNION
                    SELECT adjustment.lr_course_nature13
                    FROM sht_student_plan_adjustments13 adjustment
                    WHERE adjustment.lr_student_id13 = :studentId
                      AND adjustment.lr_course_id13 = :courseId
                      AND adjustment.lr_status13 = 'APPROVED'
                      AND (adjustment.lr_target_term_id13 IS NULL OR adjustment.lr_target_term_id13 = :termId)
                )
                SELECT rule.lr_max_courses_per_term13 AS max_courses,
                       (
                           SELECT COUNT(DISTINCT selected_tc.course_id)
                           FROM student_course_selection selected_scs
                           JOIN teaching_class selected_tc ON selected_tc.teaching_class_id = selected_scs.teaching_class_id
                           WHERE selected_scs.student_id = :studentId
                             AND selected_scs.status IN ('processing', 'selected')
                             AND selected_tc.term_id = :termId
                       ) AS selected_count
                FROM student s
                JOIN admin_class ac ON ac.admin_class_id = s.admin_class_id
                JOIN course_nature nature ON TRUE
                JOIN sht_major_category_selection_rules13 rule
                  ON rule.lr_major_id13 = ac.major_id
                 AND rule.lr_grade_year13 = s.grade_year
                 AND rule.lr_category_code13 = nature.course_nature
                WHERE s.student_id = :studentId
                LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("studentId", studentId)
                .addValue("courseId", courseId)
                .addValue("termId", termId));
        Map<String, Object> result = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            result.put("warning", false);
            result.put("selectedCount", 0);
            result.put("maxCourses", null);
            return result;
        }
        Map<String, Object> row = rows.get(0);
        int selectedCount = ((Number) row.get("selected_count")).intValue();
        int maxCourses = ((Number) row.get("max_courses")).intValue();
        result.put("warning", selectedCount >= maxCourses);
        result.put("selectedCount", selectedCount);
        result.put("maxCourses", maxCourses);
        return result;
    }

    @Transactional
    public void dropSelection(Long studentId, Long selectionId) {
        Map<String, Object> selection = queryOne("""
                SELECT * FROM student_course_selection
                WHERE selection_id = :selectionId AND student_id = :studentId AND status = 'selected'
                """, Map.of("selectionId", selectionId, "studentId", studentId), "未找到可退选记录");
        Long teachingClassId = ((Number) selection.get("teaching_class_id")).longValue();
        jdbc.update("""
                UPDATE student_course_selection
                SET status = 'dropped', dropped_at = :now, updated_at = :now
                WHERE selection_id = :selectionId
                """, new MapSqlParameterSource()
                .addValue("selectionId", selectionId)
                .addValue("now", LocalDateTime.now()));
        jdbc.update("""
                UPDATE teaching_class
                SET selected_count = selected_count - 1
                WHERE teaching_class_id = :teachingClassId AND selected_count > 0
                """, new MapSqlParameterSource("teachingClassId", teachingClassId));
        promoteWaitlist(teachingClassId);
        Long roundId = selection.get("round_id") == null ? null : ((Number) selection.get("round_id")).longValue();
        majorCourseCacheService.refreshTeachingClassStateAfterCommit(roundId, teachingClassId);
    }

    @Transactional
    public void joinWaitlist(Long studentId, Long teachingClassId, Long roundId) {
        Map<String, Object> round = validateRound(roundId);
        if (Boolean.FALSE.equals(round.get("waitlist_enabled"))) {
            throw new BusinessException("当前轮次未开启候补");
        }
        Map<String, Object> target = targetClass(teachingClassId);
        ensureRoundMatchesTerm(round, ((Number) target.get("term_id")).longValue());
        Long courseId = ((Number) target.get("course_id")).longValue();
        Long termId = ((Number) target.get("term_id")).longValue();
        validateTeachingPlan(studentId, courseId, termId);
        ensureNoSelectedSameCourse(studentId, courseId, termId);
        ensureNoWaitingSameCourse(studentId, courseId, termId);
        ensureNoTimeConflict(studentId, teachingClassId);
        int queueNo = jdbc.queryForObject("""
                SELECT COALESCE(MAX(queue_no), 0) + 1 FROM selection_waitlist WHERE teaching_class_id = :teachingClassId
                """, new MapSqlParameterSource("teachingClassId", teachingClassId), Integer.class);
        jdbc.update("""
                INSERT INTO selection_waitlist(student_id, teaching_class_id, round_id, queue_no, status)
                VALUES(:studentId, :teachingClassId, :roundId, :queueNo, 'waiting')
                """, new MapSqlParameterSource()
                .addValue("studentId", studentId)
                .addValue("teachingClassId", teachingClassId)
                .addValue("roundId", roundId)
                .addValue("queueNo", queueNo));
        jdbc.update("UPDATE teaching_class SET waitlist_count = waitlist_count + 1 WHERE teaching_class_id = :id",
                new MapSqlParameterSource("id", teachingClassId));
        majorCourseCacheService.refreshTeachingClassStateAfterCommit(roundId, teachingClassId);
    }

    public List<Map<String, Object>> myWaitlist(Long studentId) {
        return jdbc.queryForList("""
                SELECT sw.*, c.course_name, tc.class_name, t.teacher_name, tc.capacity, tc.selected_count
                FROM selection_waitlist sw
                JOIN teaching_class tc ON tc.teaching_class_id = sw.teaching_class_id
                JOIN course c ON c.course_id = tc.course_id
                JOIN teacher t ON t.teacher_id = tc.teacher_id
                WHERE sw.student_id = :studentId
                ORDER BY sw.waited_at DESC
                """, new MapSqlParameterSource("studentId", studentId));
    }

    private void enrichAvailableCourseRows(List<Map<String, Object>> rows, Long studentId, Long termId) {
        if (rows.isEmpty()) {
            return;
        }
        List<Long> teachingClassIds = rows.stream()
                .map(row -> ((Number) row.get("teaching_class_id")).longValue())
                .distinct()
                .toList();
        MapSqlParameterSource params = new MapSqlParameterSource("studentId", studentId)
                .addValue("termId", termId)
                .addValue("teachingClassIds", teachingClassIds);

        List<Map<String, Object>> personalRows = jdbc.queryForList("""
                SELECT tc.teaching_class_id,
                       CASE WHEN tcac.admin_class_id IS NOT NULL THEN TRUE ELSE FALSE END AS default_class,
                       scs.selection_id,
                       scs.status AS selection_status,
                       sw.status AS waitlist_status
                FROM teaching_class tc
                JOIN student s ON s.student_id = :studentId
                LEFT JOIN teaching_class_admin_class tcac
                       ON tcac.teaching_class_id = tc.teaching_class_id
                      AND tcac.admin_class_id = s.admin_class_id
                LEFT JOIN student_course_selection scs
                       ON scs.student_id = s.student_id
                      AND scs.teaching_class_id = tc.teaching_class_id
                      AND scs.status IN ('processing', 'selected')
                LEFT JOIN selection_waitlist sw
                       ON sw.student_id = s.student_id
                      AND sw.teaching_class_id = tc.teaching_class_id
                      AND sw.status = 'waiting'
                WHERE tc.teaching_class_id IN (:teachingClassIds)
                """, params);
        Map<Long, Map<String, Object>> personalByClass = new LinkedHashMap<>();
        for (Map<String, Object> personalRow : personalRows) {
            personalByClass.put(((Number) personalRow.get("teaching_class_id")).longValue(), personalRow);
        }

        List<Map<String, Object>> selectedRows = jdbc.queryForList("""
                SELECT tc.course_id, tc.teaching_class_id, tc.class_name
                FROM student_course_selection scs
                JOIN teaching_class tc ON tc.teaching_class_id = scs.teaching_class_id
                WHERE scs.student_id = :studentId
                  AND scs.status IN ('processing', 'selected')
                  AND tc.term_id = :termId
                ORDER BY scs.selected_at DESC, scs.selection_id DESC
                """, params);
        Map<Long, Map<String, Object>> selectedByCourse = new LinkedHashMap<>();
        for (Map<String, Object> selectedRow : selectedRows) {
            selectedByCourse.putIfAbsent(
                    ((Number) selectedRow.get("course_id")).longValue(), selectedRow);
        }
        int selectedCourseCount = selectedByCourse.size();

        for (Map<String, Object> row : rows) {
            Long teachingClassId = ((Number) row.get("teaching_class_id")).longValue();
            Map<String, Object> personal = personalByClass.get(teachingClassId);
            row.put("default_class", personal != null && Boolean.TRUE.equals(personal.get("default_class")));
            row.put("selection_id", personal == null ? null : personal.get("selection_id"));
            row.put("selection_status", personal == null ? null : personal.get("selection_status"));
            row.put("waitlist_status", personal == null ? null : personal.get("waitlist_status"));

            Long courseId = ((Number) row.get("course_id")).longValue();
            Map<String, Object> selected = selectedByCourse.get(courseId);
            row.put("selected_course_teaching_class_id",
                    selected == null ? null : selected.get("teaching_class_id"));
            row.put("selected_course_class_name", selected == null ? null : selected.get("class_name"));
            row.put("selected_course_count", selectedCourseCount);

            Object maxCoursesValue = row.get("selection_max_courses_per_term");
            boolean limitWarning = maxCoursesValue instanceof Number maxCourses
                    && selectedCourseCount >= maxCourses.intValue();
            row.put("selection_limit_warning", limitWarning);
        }
    }

    private List<Map<String, Object>> groupAvailableCourses(List<Map<String, Object>> rows) {
        Map<Long, Map<String, Object>> courseMap = new LinkedHashMap<>();
        Map<Long, Map<String, Object>> classMap = new LinkedHashMap<>();
        Map<Long, List<Map<String, Object>>> courseClassLists = new LinkedHashMap<>();
        Map<Long, List<Map<String, Object>>> classScheduleLists = new LinkedHashMap<>();

        for (Map<String, Object> row : rows) {
            Long courseId = ((Number) row.get("course_id")).longValue();
            Map<String, Object> course = courseMap.computeIfAbsent(courseId, id -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("course_id", row.get("course_id"));
                item.put("course_code", row.get("course_code"));
                item.put("course_name", row.get("course_name"));
                item.put("credit", row.get("credit"));
                item.put("hours", row.get("hours"));
                item.put("course_type", row.get("course_type"));
                item.put("round_id", row.get("round_id"));
                item.put("round_name", row.get("round_name"));
                item.put("academic_year", row.get("academic_year"));
                item.put("semester", row.get("semester"));
                item.put("selection_max_courses_per_term", row.get("selection_max_courses_per_term"));
                item.put("selected_course_count", row.get("selected_course_count"));
                item.put("selection_limit_warning", row.get("selection_limit_warning"));
                item.put("selected_course_teaching_class_id", row.get("selected_course_teaching_class_id"));
                item.put("selected_course_class_name", row.get("selected_course_class_name"));
                List<Map<String, Object>> teachingClasses = new ArrayList<>();
                item.put("teachingClasses", teachingClasses);
                courseClassLists.put(id, teachingClasses);
                return item;
            });

            Long teachingClassId = ((Number) row.get("teaching_class_id")).longValue();
            Map<String, Object> teachingClass = classMap.computeIfAbsent(teachingClassId, id -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("teaching_class_id", row.get("teaching_class_id"));
                item.put("class_code", row.get("class_code"));
                item.put("class_name", row.get("class_name"));
                item.put("capacity", row.get("capacity"));
                item.put("selected_count", row.get("selected_count"));
                item.put("waitlist_count", row.get("waitlist_count"));
                item.put("remaining_count", row.get("remaining_count"));
                item.put("teaching_class_status", row.get("teaching_class_status"));
                item.put("teacher_name", row.get("teacher_name"));
                item.put("round_id", row.get("round_id"));
                item.put("round_name", row.get("round_name"));
                item.put("academic_year", row.get("academic_year"));
                item.put("semester", row.get("semester"));
                item.put("selection_max_courses_per_term", row.get("selection_max_courses_per_term"));
                item.put("selected_course_count", row.get("selected_course_count"));
                item.put("selection_limit_warning", row.get("selection_limit_warning"));
                item.put("default_class", row.get("default_class"));
                item.put("selection_id", row.get("selection_id"));
                item.put("selection_status", row.get("selection_status"));
                item.put("waitlist_status", row.get("waitlist_status"));
                item.put("selected_course_teaching_class_id", row.get("selected_course_teaching_class_id"));
                item.put("selected_course_class_name", row.get("selected_course_class_name"));
                List<Map<String, Object>> schedules = new ArrayList<>();
                item.put("schedules", schedules);
                classScheduleLists.put(id, schedules);
                courseClassLists.get(courseId).add(item);
                return item;
            });

            if (row.get("schedule_id") != null) {
                Map<String, Object> schedule = new LinkedHashMap<>();
                schedule.put("schedule_id", row.get("schedule_id"));
                schedule.put("weekday", row.get("weekday"));
                schedule.put("start_period", row.get("start_period"));
                schedule.put("end_period", row.get("end_period"));
                schedule.put("start_week", row.get("start_week"));
                schedule.put("end_week", row.get("end_week"));
                schedule.put("week_pattern", row.get("week_pattern"));
                schedule.put("weeks", row.get("weeks"));
                schedule.put("classroom", row.get("classroom"));
                classScheduleLists.get(teachingClassId).add(schedule);
            }
        }
        return new ArrayList<>(courseMap.values());
    }

    private Map<String, Object> currentTermByUtc8Date() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        List<Map<String, Object>> byDate = jdbc.queryForList("""
                SELECT term_id, academic_year, semester
                FROM term
                WHERE :today BETWEEN start_date AND end_date
                ORDER BY start_date DESC
                LIMIT 1
                """, new MapSqlParameterSource("today", today));
        if (!byDate.isEmpty()) {
            return byDate.get(0);
        }

        List<Map<String, Object>> byFlag = jdbc.queryForList("""
                SELECT term_id, academic_year, semester
                FROM term
                WHERE is_current = TRUE
                ORDER BY start_date DESC
                LIMIT 1
                """, new MapSqlParameterSource());
        return byFlag.isEmpty() ? null : byFlag.get(0);
    }

    private Map<String, Object> currentOpenSelectionRound() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT csr.*, term.academic_year, term.semester
                FROM course_selection_round csr
                JOIN term ON term.term_id = csr.term_id
                WHERE COALESCE(csr.status, '') <> 'closed'
                  AND CURRENT_TIMESTAMP BETWEEN csr.start_time AND csr.end_time
                ORDER BY csr.start_time DESC, csr.round_id DESC
                LIMIT 1
                """, new MapSqlParameterSource());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> currentOpenSelectionRoundCached() {
        if (!availableCacheEnabled) {
            return currentOpenSelectionRound();
        }
        try {
            String cached = redisTemplate.opsForValue().get(OPEN_ROUND_CACHE_KEY);
            if (StringUtils.hasText(cached)) {
                return objectMapper.readValue(cached, new TypeReference<Map<String, Object>>() { });
            }
        } catch (Exception ex) {
            log.debug("Unable to read the open-round cache; using the database", ex);
        }
        Map<String, Object> round = currentOpenSelectionRound();
        if (round == null) {
            return null;
        }
        Map<String, Object> compact = new LinkedHashMap<>();
        compact.put("round_id", round.get("round_id"));
        compact.put("term_id", round.get("term_id"));
        compact.put("round_name", round.get("round_name"));
        compact.put("academic_year", round.get("academic_year"));
        compact.put("semester", round.get("semester"));
        try {
            redisTemplate.opsForValue().set(
                    OPEN_ROUND_CACHE_KEY, objectMapper.writeValueAsString(compact), openRoundCacheTtl);
        } catch (Exception ex) {
            log.debug("Unable to write the open-round cache", ex);
        }
        return compact;
    }

    private Map<String, Object> validateRound(Long roundId) {
        return queryOne("""
                SELECT * FROM course_selection_round
                WHERE round_id = :roundId
                  AND COALESCE(status, '') <> 'closed'
                  AND CURRENT_TIMESTAMP BETWEEN start_time AND end_time
                """, Map.of("roundId", roundId), "当前不在选课开放时间内");
    }

    private void ensureRoundMatchesTerm(Map<String, Object> round, Long termId) {
        Long roundTermId = ((Number) round.get("term_id")).longValue();
        if (!roundTermId.equals(termId)) {
            throw new BusinessException("教学班不属于当前选课轮次学期");
        }
    }

    private Map<String, Object> targetClass(Long teachingClassId) {
        return queryOne("SELECT * FROM teaching_class WHERE teaching_class_id = :id AND status = 'open'",
                Map.of("id", teachingClassId), "教学班不存在或未开放");
    }

    private void validateTeachingPlan(Long studentId, Long courseId, Long termId) {
        queryOne("""
                SELECT eligible_id
                FROM (
                    SELECT tp.plan_id AS eligible_id
                    FROM student s
                    JOIN admin_class ac ON ac.admin_class_id = s.admin_class_id
                    JOIN teaching_plan tp ON tp.major_id = ac.major_id AND tp.grade_year = s.grade_year
                    JOIN term plan_term ON plan_term.term_id = tp.term_id
                    JOIN term class_term ON class_term.term_id = :termId
                    WHERE s.student_id = :studentId AND tp.course_id = :courseId
                      AND regexp_replace(plan_term.academic_year, '[^0-9]', '', 'g') = regexp_replace(class_term.academic_year, '[^0-9]', '', 'g')
                      AND plan_term.semester = class_term.semester
                      AND tp.course_nature IN ('general_elective', 'discipline_elective', 'major_elective')
                    UNION ALL
                    SELECT -adjustment.lr_adjustment_id13 AS eligible_id
                    FROM sht_student_plan_adjustments13 adjustment
                    WHERE adjustment.lr_student_id13 = :studentId
                      AND adjustment.lr_course_id13 = :courseId
                      AND adjustment.lr_status13 = 'APPROVED'
                      AND (adjustment.lr_target_term_id13 IS NULL OR adjustment.lr_target_term_id13 = :termId)
                ) eligible
                LIMIT 1
                """, Map.of("studentId", studentId, "courseId", courseId, "termId", termId), "该课程不在当前学生培养计划内");
    }

    private void ensureNoSelectedSameCourse(Long studentId, Long courseId, Long termId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT scs.selection_id, tc.class_name
                FROM student_course_selection scs
                JOIN teaching_class tc ON tc.teaching_class_id = scs.teaching_class_id
                WHERE scs.student_id = :studentId
                  AND tc.course_id = :courseId
                  AND tc.term_id = :termId
                  AND scs.status IN ('processing', 'selected')
                """, new MapSqlParameterSource()
                .addValue("studentId", studentId)
                .addValue("courseId", courseId)
                .addValue("termId", termId));
        if (!rows.isEmpty()) {
            throw new BusinessException("同一课程当前学期已有有效教学班，请先退选后再改选");
        }
    }

    private void ensureNoWaitingSameCourse(Long studentId, Long courseId, Long termId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT sw.waitlist_id, tc.class_name
                FROM selection_waitlist sw
                JOIN teaching_class tc ON tc.teaching_class_id = sw.teaching_class_id
                WHERE sw.student_id = :studentId
                  AND tc.course_id = :courseId
                  AND tc.term_id = :termId
                  AND sw.status = 'waiting'
                """, new MapSqlParameterSource()
                .addValue("studentId", studentId)
                .addValue("courseId", courseId)
                .addValue("termId", termId));
        if (!rows.isEmpty()) {
            throw new BusinessException("同一课程已在候补队列中");
        }
    }

    private void ensureNoSelectedSameCourseLegacy(Long studentId, Long courseId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT scs.selection_id, tc.class_name
                FROM student_course_selection scs
                JOIN teaching_class tc ON tc.teaching_class_id = scs.teaching_class_id
                WHERE scs.student_id = :studentId AND tc.course_id = :courseId AND scs.status IN ('processing', 'selected')
                """, new MapSqlParameterSource().addValue("studentId", studentId).addValue("courseId", courseId));
        if (!rows.isEmpty()) {
            throw new BusinessException("同一课程已存在有效教学班，请先退选原教学班后再改选");
        }
    }

    private void ensureNoTimeConflict(Long studentId, Long teachingClassId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT c.course_name, tc.class_name, selected_schedule.weekday,
                       GREATEST(selected_schedule.start_period, target.start_period) AS conflict_start_period,
                       LEAST(selected_schedule.end_period, target.end_period) AS conflict_end_period,
                       GREATEST(selected_schedule.start_week, target.start_week) AS conflict_start_week,
                       LEAST(selected_schedule.end_week, target.end_week) AS conflict_end_week
                FROM class_schedule target
                JOIN teaching_class target_tc ON target_tc.teaching_class_id = target.teaching_class_id
                JOIN class_schedule selected_schedule ON selected_schedule.weekday = target.weekday
                    AND selected_schedule.start_period <= target.end_period
                    AND selected_schedule.end_period >= target.start_period
                    AND selected_schedule.start_week <= target.end_week
                    AND selected_schedule.end_week >= target.start_week
                    AND (
                        selected_schedule.week_pattern = 'all'
                        OR target.week_pattern = 'all'
                        OR selected_schedule.week_pattern = target.week_pattern
                    )
                JOIN student_course_selection scs ON scs.teaching_class_id = selected_schedule.teaching_class_id
                JOIN teaching_class tc ON tc.teaching_class_id = selected_schedule.teaching_class_id
                    AND tc.term_id = target_tc.term_id
                JOIN course c ON c.course_id = tc.course_id
                WHERE target.teaching_class_id = :teachingClassId
                  AND scs.student_id = :studentId
                  AND scs.status = 'selected'
                LIMIT 1
                """, new MapSqlParameterSource().addValue("studentId", studentId).addValue("teachingClassId", teachingClassId));
        if (!rows.isEmpty()) {
            Map<String, Object> conflict = rows.get(0);
            throw new BusinessException("当前课表存在冲突：" + conflict.get("course_name") + " " + conflict.get("class_name")
                    + "，周" + conflict.get("weekday")
                    + " 第" + conflict.get("conflict_start_period") + "-" + conflict.get("conflict_end_period") + "节"
                    + "，第" + conflict.get("conflict_start_week") + "-" + conflict.get("conflict_end_week") + "周");
        }
    }

    private void promoteWaitlist(Long teachingClassId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT * FROM selection_waitlist
                WHERE teaching_class_id = :teachingClassId AND status = 'waiting'
                ORDER BY queue_no
                LIMIT 1
                """, new MapSqlParameterSource("teachingClassId", teachingClassId));
        if (rows.isEmpty()) {
            return;
        }
        Map<String, Object> wait = rows.get(0);
        Long waitlistId = ((Number) wait.get("waitlist_id")).longValue();
        Long studentId = ((Number) wait.get("student_id")).longValue();
        Long roundId = ((Number) wait.get("round_id")).longValue();
        try {
            ensureNoTimeConflict(studentId, teachingClassId);
            String requestId = "PROMOTE-" + waitlistId;
            jdbc.update("""
                    INSERT INTO student_course_selection(request_id, student_id, teaching_class_id, round_id, status, selected_at)
                    VALUES(:requestId, :studentId, :teachingClassId, :roundId, 'selected', :now)
                    """, new MapSqlParameterSource()
                    .addValue("requestId", requestId)
                    .addValue("studentId", studentId)
                    .addValue("teachingClassId", teachingClassId)
                    .addValue("roundId", roundId)
                    .addValue("now", LocalDateTime.now()));
            jdbc.update("UPDATE teaching_class SET selected_count = selected_count + 1 WHERE teaching_class_id = :id",
                    new MapSqlParameterSource("id", teachingClassId));
            jdbc.update("""
                    UPDATE selection_waitlist
                    SET status = 'promoted', promoted_at = :now
                    WHERE waitlist_id = :waitlistId
                    """, new MapSqlParameterSource().addValue("waitlistId", waitlistId).addValue("now", LocalDateTime.now()));
        } catch (RuntimeException ex) {
            jdbc.update("""
                    UPDATE selection_waitlist
                    SET status = 'expired'
                    WHERE waitlist_id = :waitlistId
                    """, new MapSqlParameterSource("waitlistId", waitlistId));
        } finally {
            jdbc.update("""
                    UPDATE teaching_class
                    SET waitlist_count = CASE WHEN waitlist_count > 0 THEN waitlist_count - 1 ELSE 0 END
                    WHERE teaching_class_id = :id
                    """, new MapSqlParameterSource("id", teachingClassId));
        }
    }

    private Map<String, Object> queryOne(String sql, Map<String, ?> params, String errorMessage) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, new MapSqlParameterSource(params));
        if (rows.isEmpty()) {
            throw new BusinessException(errorMessage);
        }
        return rows.get(0);
    }
}

package com.zjut.edusystem.student;

import com.zjut.edusystem.common.BusinessException;
import com.zjut.edusystem.selection.SelectionQueueService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class StudentSelectionService {
    private final NamedParameterJdbcTemplate jdbc;
    private final SelectionQueueService queueService;

    public StudentSelectionService(NamedParameterJdbcTemplate jdbc, SelectionQueueService queueService) {
        this.jdbc = jdbc;
        this.queueService = queueService;
    }

    public List<Map<String, Object>> availableCourses(Long studentId) {
        MapSqlParameterSource params = new MapSqlParameterSource("studentId", studentId);
        return jdbc.queryForList("""
                SELECT
                    c.course_id,
                    c.course_code,
                    c.course_name,
                    c.credit,
                    c.course_type,
                    tc.teaching_class_id,
                    tc.class_code,
                    tc.class_name,
                    tc.capacity,
                    tc.selected_count,
                    tc.waitlist_count,
                    tc.status AS teaching_class_status,
                    t.teacher_name,
                    cs.weekday,
                    cs.start_period,
                    cs.end_period,
                    cs.classroom,
                    cs.weeks,
                    CASE WHEN tcac.admin_class_id IS NOT NULL THEN TRUE ELSE FALSE END AS default_class,
                    scs.selection_id,
                    scs.status AS selection_status,
                    sw.status AS waitlist_status,
                    CASE WHEN tc.capacity - tc.selected_count > 0 THEN tc.capacity - tc.selected_count ELSE 0 END AS remaining_count
                FROM student s
                JOIN admin_class ac ON ac.admin_class_id = s.admin_class_id
                JOIN teaching_plan tp ON tp.major_id = ac.major_id AND tp.grade_year = ac.grade_year
                JOIN course c ON c.course_id = tp.course_id
                JOIN teaching_class tc ON tc.course_id = c.course_id AND tc.term_id = tp.term_id
                JOIN teacher t ON t.teacher_id = tc.teacher_id
                LEFT JOIN class_schedule cs ON cs.teaching_class_id = tc.teaching_class_id
                LEFT JOIN teaching_class_admin_class tcac ON tcac.teaching_class_id = tc.teaching_class_id AND tcac.admin_class_id = s.admin_class_id
                LEFT JOIN student_course_selection scs ON scs.student_id = s.student_id
                    AND scs.teaching_class_id = tc.teaching_class_id
                    AND scs.status IN ('processing', 'selected')
                LEFT JOIN selection_waitlist sw ON sw.student_id = s.student_id
                    AND sw.teaching_class_id = tc.teaching_class_id
                    AND sw.status = 'waiting'
                WHERE s.student_id = :studentId
                ORDER BY c.course_code, tc.class_code
                """, params);
    }

    public List<Map<String, Object>> mySelections(Long studentId) {
        return jdbc.queryForList("""
                SELECT scs.*, c.course_code, c.course_name, c.credit, tc.class_name, t.teacher_name,
                       cs.weekday, cs.start_period, cs.end_period, cs.classroom, cs.weeks
                FROM student_course_selection scs
                JOIN teaching_class tc ON tc.teaching_class_id = scs.teaching_class_id
                JOIN course c ON c.course_id = tc.course_id
                JOIN teacher t ON t.teacher_id = tc.teacher_id
                LEFT JOIN class_schedule cs ON cs.teaching_class_id = tc.teaching_class_id
                WHERE scs.student_id = :studentId
                ORDER BY scs.created_at DESC
                """, new MapSqlParameterSource("studentId", studentId));
    }

    public List<Map<String, Object>> mySchedule(Long studentId) {
        return jdbc.queryForList("""
                SELECT * FROM v_student_schedule
                WHERE student_id = :studentId
                ORDER BY weekday, start_period
                """, new MapSqlParameterSource("studentId", studentId));
    }

    public List<Map<String, Object>> myGrades(Long studentId) {
        return jdbc.queryForList("""
                SELECT gr.*, c.course_code, c.course_name, c.credit, tc.class_name, t.academic_year, t.semester
                FROM grade_record gr
                JOIN teaching_class tc ON tc.teaching_class_id = gr.teaching_class_id
                JOIN course c ON c.course_id = tc.course_id
                JOIN term t ON t.term_id = tc.term_id
                WHERE gr.student_id = :studentId AND gr.submitted = TRUE
                ORDER BY t.academic_year DESC, t.semester DESC, c.course_code
                """, new MapSqlParameterSource("studentId", studentId));
    }

    @Transactional
    public String selectCourse(Long studentId, Long teachingClassId, Long roundId) {
        validateRound(roundId);
        Map<String, Object> target = targetClass(teachingClassId);
        Long courseId = ((Number) target.get("course_id")).longValue();
        validateTeachingPlan(studentId, courseId, ((Number) target.get("term_id")).longValue());
        ensureNoSelectedSameCourse(studentId, courseId);
        ensureNoTimeConflict(studentId, teachingClassId);

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
        return requestId;
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
    }

    @Transactional
    public void joinWaitlist(Long studentId, Long teachingClassId, Long roundId) {
        validateRound(roundId);
        Map<String, Object> target = targetClass(teachingClassId);
        Long courseId = ((Number) target.get("course_id")).longValue();
        validateTeachingPlan(studentId, courseId, ((Number) target.get("term_id")).longValue());
        ensureNoSelectedSameCourse(studentId, courseId);
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

    private void validateRound(Long roundId) {
        queryOne("""
                SELECT * FROM course_selection_round
                WHERE round_id = :roundId
                  AND status = 'open'
                  AND CURRENT_TIMESTAMP BETWEEN start_time AND end_time
                """, Map.of("roundId", roundId), "当前不在选课开放时间内");
    }

    private Map<String, Object> targetClass(Long teachingClassId) {
        return queryOne("SELECT * FROM teaching_class WHERE teaching_class_id = :id AND status = 'open'",
                Map.of("id", teachingClassId), "教学班不存在或未开放");
    }

    private void validateTeachingPlan(Long studentId, Long courseId, Long termId) {
        queryOne("""
                SELECT tp.plan_id
                FROM student s
                JOIN admin_class ac ON ac.admin_class_id = s.admin_class_id
                JOIN teaching_plan tp ON tp.major_id = ac.major_id AND tp.grade_year = ac.grade_year
                WHERE s.student_id = :studentId AND tp.course_id = :courseId AND tp.term_id = :termId
                """, Map.of("studentId", studentId, "courseId", courseId, "termId", termId), "该课程不在当前学生培养计划内");
    }

    private void ensureNoSelectedSameCourse(Long studentId, Long courseId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT scs.selection_id
                FROM student_course_selection scs
                JOIN teaching_class tc ON tc.teaching_class_id = scs.teaching_class_id
                WHERE scs.student_id = :studentId AND tc.course_id = :courseId AND scs.status IN ('processing', 'selected')
                """, new MapSqlParameterSource().addValue("studentId", studentId).addValue("courseId", courseId));
        if (!rows.isEmpty()) {
            throw new BusinessException("同一课程已存在有效教学班，需先退选原教学班");
        }
    }

    private void ensureNoTimeConflict(Long studentId, Long teachingClassId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT 1
                FROM class_schedule target
                JOIN class_schedule selected_schedule ON selected_schedule.weekday = target.weekday
                    AND selected_schedule.start_period <= target.end_period
                    AND selected_schedule.end_period >= target.start_period
                JOIN student_course_selection scs ON scs.teaching_class_id = selected_schedule.teaching_class_id
                WHERE target.teaching_class_id = :teachingClassId
                  AND scs.student_id = :studentId
                  AND scs.status = 'selected'
                LIMIT 1
                """, new MapSqlParameterSource().addValue("studentId", studentId).addValue("teachingClassId", teachingClassId));
        if (!rows.isEmpty()) {
            throw new BusinessException("与已选课程上课时间冲突");
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

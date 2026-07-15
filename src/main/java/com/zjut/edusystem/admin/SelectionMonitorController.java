package com.zjut.edusystem.admin;

import com.zjut.edusystem.common.ApiResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/selection-monitor")
public class SelectionMonitorController {
    private final NamedParameterJdbcTemplate jdbc;

    public SelectionMonitorController(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", jdbc.queryForMap("""
                SELECT
                  (SELECT COUNT(*) FROM selection_request_log) AS total_requests,
                  (SELECT COUNT(*) FROM student_course_selection WHERE status = 'selected') AS success_count,
                  (SELECT COUNT(*) FROM selection_waitlist WHERE status = 'waiting') AS waitlist_count,
                  (SELECT COUNT(*) FROM selection_request_log WHERE request_status IN ('failed', 'fail', 'error')) AS failed_count,
                  (SELECT COUNT(*) FROM teaching_class WHERE selected_count >= capacity) AS full_class_count
                """, Map.of()));
        result.put("rounds", jdbc.queryForList("""
                SELECT csr.round_id, csr.term_id, csr.round_name, csr.start_time, csr.end_time, csr.waitlist_enabled,
                       CASE
                         WHEN csr.status = 'closed' THEN 'closed'
                         WHEN CURRENT_TIMESTAMP < csr.start_time THEN 'not_started'
                         WHEN CURRENT_TIMESTAMP <= csr.end_time THEN 'open'
                         ELSE 'ended'
                       END AS status,
                       term.academic_year, term.semester
                FROM course_selection_round csr
                JOIN term ON term.term_id = csr.term_id
                ORDER BY CASE
                           WHEN csr.status = 'closed' THEN 2
                           WHEN CURRENT_TIMESTAMP BETWEEN csr.start_time AND csr.end_time THEN 0
                           WHEN CURRENT_TIMESTAMP < csr.start_time THEN 1
                           ELSE 3
                         END, csr.start_time DESC, csr.round_id DESC
                """, Map.of()));
        result.put("statusDistribution", jdbc.queryForList("""
                SELECT status, COUNT(*) AS count
                FROM student_course_selection
                GROUP BY status
                UNION ALL
                SELECT 'waiting' AS status, COUNT(*) AS count
                FROM selection_waitlist
                WHERE status = 'waiting'
                UNION ALL
                SELECT 'failed' AS status, COUNT(*) AS count
                FROM selection_request_log
                WHERE request_status IN ('failed', 'fail', 'error')
                """, Map.of()));
        result.put("classes", jdbc.queryForList("""
                SELECT tc.teaching_class_id,
                       tc.class_code,
                       tc.class_name,
                       tc.term_id,
                       tc.capacity,
                       tc.selected_count,
                       GREATEST(tc.capacity - tc.selected_count, 0) AS remaining_count,
                       tc.waitlist_count,
                       GREATEST(tc.capacity - tc.selected_count, 0) AS redis_remaining,
                       tc.status,
                       c.course_code,
                       c.course_name,
                       t.teacher_name,
                       term.academic_year,
                       term.semester
                FROM teaching_class tc
                JOIN course c ON c.course_id = tc.course_id
                JOIN teacher t ON t.teacher_id = tc.teacher_id
                JOIN term ON term.term_id = tc.term_id
                ORDER BY tc.waitlist_count DESC, tc.selected_count DESC, tc.teaching_class_id DESC
                """, Map.of()));
        result.put("hotClasses", jdbc.queryForList("""
                SELECT tc.teaching_class_id, tc.class_code, c.course_name, t.teacher_name, tc.selected_count
                FROM teaching_class tc
                JOIN course c ON c.course_id = tc.course_id
                JOIN teacher t ON t.teacher_id = tc.teacher_id
                ORDER BY tc.selected_count DESC, tc.teaching_class_id DESC
                LIMIT 10
                """, Map.of()));
        result.put("waitlistTop", jdbc.queryForList("""
                SELECT tc.teaching_class_id, tc.class_code, c.course_name, t.teacher_name, tc.waitlist_count
                FROM teaching_class tc
                JOIN course c ON c.course_id = tc.course_id
                JOIN teacher t ON t.teacher_id = tc.teacher_id
                ORDER BY tc.waitlist_count DESC, tc.teaching_class_id DESC
                LIMIT 10
                """, Map.of()));
        return ApiResponse.ok(result);
    }

    @GetMapping("/classes/{classId}/selections")
    public ApiResponse<List<Map<String, Object>>> selections(@PathVariable Long classId) {
        return ApiResponse.ok(jdbc.queryForList("""
                SELECT scs.selection_id,
                       scs.request_id,
                       scs.status,
                       scs.selected_at,
                       scs.dropped_at,
                       scs.fail_reason,
                       s.student_no,
                       s.student_name,
                       ac.class_name AS admin_class_name
                FROM student_course_selection scs
                JOIN student s ON s.student_id = scs.student_id
                LEFT JOIN admin_class ac ON ac.admin_class_id = s.admin_class_id
                WHERE scs.teaching_class_id = :classId
                ORDER BY scs.selected_at DESC, scs.selection_id DESC
                """, new MapSqlParameterSource("classId", classId)));
    }

    @GetMapping("/classes/{classId}/waitlist")
    public ApiResponse<List<Map<String, Object>>> waitlist(@PathVariable Long classId) {
        return ApiResponse.ok(jdbc.queryForList("""
                SELECT sw.waitlist_id,
                       sw.queue_no,
                       sw.status,
                       sw.waited_at,
                       sw.promoted_at,
                       s.student_no,
                       s.student_name,
                       ac.class_name AS admin_class_name
                FROM selection_waitlist sw
                JOIN student s ON s.student_id = sw.student_id
                LEFT JOIN admin_class ac ON ac.admin_class_id = s.admin_class_id
                WHERE sw.teaching_class_id = :classId
                ORDER BY sw.queue_no ASC, sw.waited_at ASC
                """, new MapSqlParameterSource("classId", classId)));
    }

    @GetMapping("/classes/{classId}/logs")
    public ApiResponse<List<Map<String, Object>>> logs(@PathVariable Long classId) {
        return ApiResponse.ok(jdbc.queryForList("""
                SELECT srl.*,
                       s.student_no,
                       s.student_name
                FROM selection_request_log srl
                LEFT JOIN student s ON s.student_id = srl.student_id
                WHERE srl.teaching_class_id = :classId
                ORDER BY srl.request_id DESC
                """, new MapSqlParameterSource("classId", classId)));
    }
}

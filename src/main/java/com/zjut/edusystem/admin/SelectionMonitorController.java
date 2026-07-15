package com.zjut.edusystem.admin;

import com.zjut.edusystem.common.ApiResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    public ApiResponse<Map<String, Object>> overview(
            @RequestParam(defaultValue = "") String capacityKeyword,
            @RequestParam(defaultValue = "") String waitlistKeyword) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> currentRounds = jdbc.queryForList("""
                SELECT csr.round_id, csr.term_id, csr.round_name, csr.start_time, csr.end_time, csr.waitlist_enabled,
                       term.academic_year, term.semester
                FROM course_selection_round csr
                JOIN term ON term.term_id = csr.term_id
                WHERE csr.status <> 'closed'
                  AND CURRENT_TIMESTAMP BETWEEN csr.start_time AND csr.end_time
                ORDER BY csr.start_time DESC, csr.round_id DESC
                LIMIT 1
                """, Map.of());

        if (currentRounds.isEmpty()) {
            result.put("round", null);
            result.put("classes", List.of());
            return ApiResponse.ok(result);
        }

        Map<String, Object> currentRound = currentRounds.get(0);
        result.put("round", currentRound);
        result.put("classes", jdbc.queryForList("""
                SELECT tc.teaching_class_id,
                       tc.class_code,
                       tc.class_name,
                       tc.course_id,
                       tc.term_id,
                       tc.capacity,
                       COALESCE(selection_stats.selected_count, 0) AS selected_count,
                       GREATEST(tc.capacity - COALESCE(selection_stats.selected_count, 0), 0) AS remaining_count,
                       COALESCE(waitlist_stats.waitlist_count, 0) AS waitlist_count,
                       tc.status,
                       c.course_code,
                       c.course_name,
                       c.credit,
                       c.hours,
                       t.teacher_name,
                       term.academic_year,
                       term.semester,
                       CASE
                         WHEN :capacityKeyword = '' THEN 1
                         WHEN LOWER(c.course_name) LIKE :capacityLike THEN 1
                         WHEN EXISTS (
                           SELECT 1
                           FROM student_course_selection search_scs
                           JOIN student search_student ON search_student.student_id = search_scs.student_id
                           WHERE search_scs.teaching_class_id = tc.teaching_class_id
                             AND LOWER(search_student.student_no) LIKE :capacityLike
                         ) THEN 1
                         ELSE 0
                       END AS capacity_match,
                       CASE
                         WHEN :waitlistKeyword = '' THEN 1
                         WHEN LOWER(c.course_name) LIKE :waitlistLike THEN 1
                         WHEN EXISTS (
                           SELECT 1
                           FROM selection_waitlist search_waitlist
                           JOIN student waitlist_student ON waitlist_student.student_id = search_waitlist.student_id
                           WHERE search_waitlist.teaching_class_id = tc.teaching_class_id
                             AND search_waitlist.round_id = :roundId
                             AND search_waitlist.status = 'waiting'
                             AND LOWER(waitlist_student.student_no) LIKE :waitlistLike
                         ) THEN 1
                         ELSE 0
                       END AS waitlist_match
                FROM teaching_class tc
                JOIN course c ON c.course_id = tc.course_id
                JOIN teacher t ON t.teacher_id = tc.teacher_id
                JOIN term ON term.term_id = tc.term_id
                LEFT JOIN (
                  SELECT teaching_class_id, COUNT(*) AS selected_count
                  FROM student_course_selection
                  WHERE status IN ('processing', 'selected')
                  GROUP BY teaching_class_id
                ) selection_stats ON selection_stats.teaching_class_id = tc.teaching_class_id
                LEFT JOIN (
                  SELECT teaching_class_id, COUNT(*) AS waitlist_count
                  FROM selection_waitlist
                  WHERE round_id = :roundId AND status = 'waiting'
                  GROUP BY teaching_class_id
                ) waitlist_stats ON waitlist_stats.teaching_class_id = tc.teaching_class_id
                WHERE tc.term_id = :termId
                ORDER BY c.course_code, tc.class_code, tc.teaching_class_id
                """, new MapSqlParameterSource()
                .addValue("roundId", currentRound.get("round_id"))
                .addValue("termId", currentRound.get("term_id"))
                .addValue("capacityKeyword", normalizedKeyword(capacityKeyword))
                .addValue("capacityLike", likeKeyword(capacityKeyword))
                .addValue("waitlistKeyword", normalizedKeyword(waitlistKeyword))
                .addValue("waitlistLike", likeKeyword(waitlistKeyword))));
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
                JOIN course_selection_round csr ON csr.round_id = sw.round_id
                WHERE sw.teaching_class_id = :classId
                  AND sw.status = 'waiting'
                  AND csr.status <> 'closed'
                  AND CURRENT_TIMESTAMP BETWEEN csr.start_time AND csr.end_time
                ORDER BY sw.queue_no ASC, sw.waited_at ASC
                """, new MapSqlParameterSource("classId", classId)));
    }

    private String normalizedKeyword(String keyword) {
        return keyword == null ? "" : keyword.trim().toLowerCase();
    }

    private String likeKeyword(String keyword) {
        return "%" + normalizedKeyword(keyword) + "%";
    }
}

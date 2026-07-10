package com.zjut.edusystem.admin;

import com.zjut.edusystem.common.ApiResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/room-schedules")
public class AdminRoomScheduleController {
    private final NamedParameterJdbcTemplate jdbc;

    public AdminRoomScheduleController(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(required = false) Long termId,
                                                       @RequestParam(required = false) String academicYear,
                                                       @RequestParam(required = false) Integer semester,
                                                       @RequestParam(required = false) Integer weekday,
                                                       @RequestParam(required = false) Integer startPeriod,
                                                       @RequestParam(required = false) Integer endPeriod,
                                                       @RequestParam(required = false) Long campusId) {
        int periodStart = startPeriod == null ? 1 : startPeriod;
        int periodEnd = endPeriod == null ? 12 : endPeriod;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("startPeriod", periodStart)
                .addValue("endPeriod", periodEnd);

        StringBuilder scheduleWhere = new StringBuilder("""
                WHERE cs.start_period <= :endPeriod
                  AND cs.end_period >= :startPeriod
                """);
        if (termId != null) {
            scheduleWhere.append(" AND term.term_id = :termId\n");
            params.addValue("termId", termId);
        }
        if (StringUtils.hasText(academicYear)) {
            scheduleWhere.append(" AND regexp_replace(term.academic_year, '[^0-9]', '', 'g') = regexp_replace(CAST(:academicYear AS TEXT), '[^0-9]', '', 'g')\n");
            params.addValue("academicYear", academicYear);
        }
        if (semester != null) {
            scheduleWhere.append(" AND term.semester = :semester\n");
            params.addValue("semester", semester);
        }
        if (weekday != null) {
            scheduleWhere.append(" AND cs.weekday = :weekday\n");
            params.addValue("weekday", weekday);
        }

        StringBuilder outerWhere = new StringBuilder("""
                WHERE cr.status = 'enabled'
                  AND b.status = 'enabled'
                """);
        if (campusId != null) {
            outerWhere.append(" AND b.campus_id = :campusId\n");
            params.addValue("campusId", campusId);
        }
        String sql = """
                SELECT
                    campus.campus_id,
                    campus.campus_name,
                    b.building_id,
                    b.building_name,
                    cr.classroom_id,
                    cr.room_code,
                    cr.room_name,
                    cr.floor_no,
                    cr.room_no,
                    cr.room_type,
                    cr.capacity,
                    occ.schedule_id,
                    occ.weekday,
                    occ.start_period,
                    occ.end_period,
                    occ.start_week,
                    occ.end_week,
                    occ.week_pattern,
                    occ.weeks,
                    occ.academic_year,
                    occ.semester,
                    occ.course_code,
                    occ.course_name,
                    occ.class_code,
                    occ.class_name,
                    occ.teacher_name,
                    CASE WHEN occ.schedule_id IS NULL THEN 'free' ELSE 'occupied' END AS room_status
                FROM classroom_resource cr
                JOIN teaching_building b ON b.building_id = cr.building_id
                JOIN campus ON campus.campus_id = b.campus_id
                LEFT JOIN (
                    SELECT
                        cs.classroom_id,
                        cs.schedule_id,
                        cs.weekday,
                        cs.start_period,
                        cs.end_period,
                        cs.start_week,
                        cs.end_week,
                        cs.week_pattern,
                        cs.weeks,
                        term.academic_year,
                        term.semester,
                        c.course_code,
                        c.course_name,
                        tc.class_code,
                        tc.class_name,
                        te.teacher_name
                    FROM class_schedule cs
                    JOIN teaching_class tc ON tc.teaching_class_id = cs.teaching_class_id
                    JOIN term ON term.term_id = tc.term_id
                    JOIN course c ON c.course_id = tc.course_id
                    JOIN teacher te ON te.teacher_id = tc.teacher_id
                """ + scheduleWhere + """
                ) occ ON occ.classroom_id = cr.classroom_id
                """ + outerWhere + """
                ORDER BY b.building_name, cr.floor_no, cr.room_no, occ.weekday, occ.start_period
                """;

        return ApiResponse.ok(jdbc.queryForList(sql, params));
    }
}

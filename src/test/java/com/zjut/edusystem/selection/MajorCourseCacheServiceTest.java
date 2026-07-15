package com.zjut.edusystem.selection;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MajorCourseCacheServiceTest {

    @Test
    void databaseFallbackLoadsOnlyTheMajorTrainingPlanCatalog() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        List<String> executedSql = new ArrayList<>();

        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            executedSql.add(sql);
            if (sql.contains("SELECT ac.major_id, s.grade_year")) {
                return List.of(Map.of("major_id", 7L, "grade_year", 2024));
            }
            if (sql.contains("FROM teaching_plan tp")) {
                Map<String, Object> catalogRow = new LinkedHashMap<>();
                catalogRow.put("course_id", 11L);
                catalogRow.put("course_name", "Database Systems");
                catalogRow.put("teaching_class_id", 21L);
                catalogRow.put("teaching_class_status", "open");
                catalogRow.put("capacity", 50);
                catalogRow.put("selected_count", 20);
                catalogRow.put("waitlist_count", 0);
                catalogRow.put("remaining_count", 30);
                return List.of(catalogRow);
            }
            if (sql.contains("tc.teaching_class_id IN (:teachingClassIds)")) {
                return List.of(new LinkedHashMap<>(Map.of(
                        "teaching_class_id", 21L,
                        "teaching_class_status", "open",
                        "capacity", 50,
                        "selected_count", 22,
                        "waitlist_count", 1,
                        "remaining_count", 28
                )));
            }
            return List.of();
        });

        MajorCourseCacheService service = new MajorCourseCacheService(
                jdbc, redis, new ObjectMapper(), false, 5, 3600);
        Map<String, Object> round = Map.of(
                "round_id", 31L,
                "term_id", 41L,
                "round_name", "First round",
                "academic_year", "2026-2027",
                "semester", 1
        );

        List<Map<String, Object>> rows = service.availableCourseRows(101L, round);

        assertEquals(1, rows.size());
        assertEquals(22, rows.get(0).get("selected_count"));
        assertEquals(28, rows.get(0).get("remaining_count"));
        assertTrue(executedSql.stream().anyMatch(sql -> sql.contains("tp.major_id = :majorId")));
        assertTrue(executedSql.stream().anyMatch(sql -> sql.contains("tp.grade_year = :gradeYear")));
        assertFalse(executedSql.stream().anyMatch(sql -> sql.contains("sht_student_plan_adjustments13")));
        verifyNoInteractions(redis);
    }
}

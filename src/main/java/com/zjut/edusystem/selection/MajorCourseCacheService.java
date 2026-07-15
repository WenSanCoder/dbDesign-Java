package com.zjut.edusystem.selection;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjut.edusystem.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class MajorCourseCacheService {
    private static final Logger log = LoggerFactory.getLogger(MajorCourseCacheService.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String CATALOG_KEY_PREFIX = "selection:major-catalog:v2";
    private static final String CLASS_STATE_KEY_PREFIX = "selection:class-state:v2";
    private static final String PREHEAT_MARKER_KEY_PREFIX = "selection:preheated:v2";

    private final NamedParameterJdbcTemplate jdbc;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final int preheatMinutes;
    private final Duration cacheTtl;

    public MajorCourseCacheService(
            NamedParameterJdbcTemplate jdbc,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${edu-system.selection.available-cache.enabled:true}") boolean enabled,
            @Value("${edu-system.selection.available-cache.preheat-minutes:5}") int preheatMinutes,
            @Value("${edu-system.selection.available-cache.catalog-ttl-seconds:604800}") long cacheTtlSeconds
    ) {
        this.jdbc = jdbc;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.preheatMinutes = Math.max(0, preheatMinutes);
        this.cacheTtl = Duration.ofSeconds(Math.max(60L, cacheTtlSeconds));
    }

    public List<Map<String, Object>> availableCourseRows(Long studentId, Map<String, Object> round) {
        StudentScope scope = studentScope(studentId);
        String cacheKey = catalogKey(roundId(round), termId(round), scope);
        List<Map<String, Object>> rows = enabled ? readCatalog(cacheKey) : null;
        if (rows == null) {
            rows = loadCatalogRows(scope, round);
            if (enabled) {
                writeCatalog(cacheKey, rows);
            }
        }
        applyClassStates(roundId(round), rows);
        rows.removeIf(row -> !"open".equals(String.valueOf(row.get("teaching_class_status"))));
        return rows;
    }

    public void refreshTeachingClassStateAfterCommit(Long roundId, Long teachingClassId) {
        if (!enabled || roundId == null || teachingClassId == null) {
            return;
        }
        Runnable refresh = () -> refreshTeachingClassState(roundId, teachingClassId);
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    refresh.run();
                }
            });
            return;
        }
        refresh.run();
    }

    @Scheduled(
            fixedDelayString = "${edu-system.selection.available-cache.scan-interval-ms:15000}",
            initialDelayString = "${edu-system.selection.available-cache.initial-delay-ms:5000}"
    )
    public void preheatUpcomingRounds() {
        if (!enabled) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now(BUSINESS_ZONE).plusMinutes(preheatMinutes);
        List<Map<String, Object>> rounds = jdbc.queryForList("""
                SELECT csr.round_id,
                       csr.term_id,
                       csr.round_name,
                       csr.start_time,
                       csr.end_time,
                       term.academic_year,
                       term.semester
                FROM course_selection_round csr
                JOIN term ON term.term_id = csr.term_id
                WHERE COALESCE(csr.status, '') <> 'closed'
                  AND csr.end_time >= CURRENT_TIMESTAMP
                  AND csr.start_time <= :cutoff
                ORDER BY csr.start_time, csr.round_id
                """, new MapSqlParameterSource("cutoff", cutoff));
        for (Map<String, Object> round : rounds) {
            try {
                preheatRound(round);
            } catch (Exception ex) {
                log.warn("Unable to preheat Redis for selection round {}", round.get("round_id"), ex);
            }
        }
    }

    private void preheatRound(Map<String, Object> round) {
        Long roundId = roundId(round);
        String markerKey = PREHEAT_MARKER_KEY_PREFIX + ":round:" + roundId;
        boolean catalogReady = Boolean.TRUE.equals(redisTemplate.hasKey(markerKey));
        if (!catalogReady) {
            List<StudentScope> scopes = planScopes(round);
            boolean allWritten = true;
            for (StudentScope scope : scopes) {
                List<Map<String, Object>> rows = loadCatalogRows(scope, round);
                allWritten &= writeCatalog(catalogKey(roundId, termId(round), scope), rows);
            }
            if (allWritten) {
                redisTemplate.opsForValue().set(markerKey, "1", cacheTtl);
                log.info("Preheated {} major/grade course catalogs for selection round {}",
                        scopes.size(), roundId);
            }
        }
        refreshRoundClassStates(roundId);
    }

    private StudentScope studentScope(Long studentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT ac.major_id, s.grade_year
                FROM student s
                JOIN admin_class ac ON ac.admin_class_id = s.admin_class_id
                WHERE s.student_id = :studentId
                """, new MapSqlParameterSource("studentId", studentId));
        if (rows.isEmpty()) {
            throw new BusinessException("学生不存在或未关联专业");
        }
        Map<String, Object> row = rows.get(0);
        return new StudentScope(number(row.get("major_id")), number(row.get("grade_year")));
    }

    private List<StudentScope> planScopes(Map<String, Object> round) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT DISTINCT tp.major_id, tp.grade_year
                FROM teaching_plan tp
                JOIN term plan_term ON plan_term.term_id = tp.term_id
                WHERE regexp_replace(plan_term.academic_year, '[^0-9]', '', 'g') =
                      regexp_replace(:academicYear, '[^0-9]', '', 'g')
                  AND plan_term.semester = :semester
                  AND tp.course_nature IN ('general_elective', 'discipline_elective', 'major_elective')
                  AND EXISTS (
                      SELECT 1
                      FROM teaching_class tc
                      WHERE tc.course_id = tp.course_id
                        AND tc.term_id = :termId
                        AND tc.status = 'open'
                  )
                ORDER BY tp.major_id, tp.grade_year
                """, roundParams(round));
        List<StudentScope> scopes = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            scopes.add(new StudentScope(number(row.get("major_id")), number(row.get("grade_year"))));
        }
        return scopes;
    }

    private List<Map<String, Object>> loadCatalogRows(StudentScope scope, Map<String, Object> round) {
        MapSqlParameterSource params = roundParams(round)
                .addValue("majorId", scope.majorId())
                .addValue("gradeYear", scope.gradeYear());
        return jdbc.queryForList("""
                SELECT c.course_id,
                       c.course_code,
                       c.course_name,
                       c.credit,
                       c.hours,
                       tp.course_nature AS course_type,
                       :roundId AS round_id,
                       :roundName AS round_name,
                       term.academic_year,
                       term.semester,
                       tc.teaching_class_id,
                       tc.class_code,
                       tc.class_name,
                       tc.capacity,
                       tc.selected_count,
                       tc.waitlist_count,
                       tc.status AS teaching_class_status,
                       t.teacher_name,
                       rule.lr_max_courses_per_term13 AS selection_max_courses_per_term,
                       cs.schedule_id,
                       cs.weekday,
                       cs.start_period,
                       cs.end_period,
                       cs.start_week,
                       cs.end_week,
                       cs.week_pattern,
                       cs.classroom,
                       cs.weeks,
                       GREATEST(tc.capacity - tc.selected_count, 0) AS remaining_count
                FROM teaching_plan tp
                JOIN term plan_term ON plan_term.term_id = tp.term_id
                JOIN course c ON c.course_id = tp.course_id
                JOIN teaching_class tc ON tc.course_id = c.course_id
                                      AND tc.term_id = :termId
                                      AND tc.status = 'open'
                JOIN term ON term.term_id = tc.term_id
                JOIN teacher t ON t.teacher_id = tc.teacher_id
                LEFT JOIN sht_major_category_selection_rules13 rule
                       ON rule.lr_major_id13 = tp.major_id
                      AND rule.lr_grade_year13 = tp.grade_year
                      AND rule.lr_category_code13 = tp.course_nature
                LEFT JOIN class_schedule cs ON cs.teaching_class_id = tc.teaching_class_id
                WHERE tp.major_id = :majorId
                  AND tp.grade_year = :gradeYear
                  AND regexp_replace(plan_term.academic_year, '[^0-9]', '', 'g') =
                      regexp_replace(:academicYear, '[^0-9]', '', 'g')
                  AND plan_term.semester = :semester
                  AND tp.course_nature IN ('general_elective', 'discipline_elective', 'major_elective')
                ORDER BY c.course_code, tc.class_code, cs.weekday, cs.start_period
                """, params);
    }

    private void applyClassStates(Long roundId, List<Map<String, Object>> rows) {
        Set<Long> teachingClassIds = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            teachingClassIds.add(number(row.get("teaching_class_id")));
        }
        Map<Long, Map<String, Object>> states = classStates(roundId, teachingClassIds);
        for (Map<String, Object> row : rows) {
            Map<String, Object> state = states.get(number(row.get("teaching_class_id")));
            if (state == null) {
                continue;
            }
            row.put("capacity", state.get("capacity"));
            row.put("selected_count", state.get("selected_count"));
            row.put("waitlist_count", state.get("waitlist_count"));
            row.put("teaching_class_status", state.get("teaching_class_status"));
            row.put("remaining_count", state.get("remaining_count"));
        }
    }

    private Map<Long, Map<String, Object>> classStates(Long roundId, Collection<Long> teachingClassIds) {
        Map<Long, Map<String, Object>> states = new LinkedHashMap<>();
        if (teachingClassIds.isEmpty()) {
            return states;
        }
        Set<Long> missing = new LinkedHashSet<>(teachingClassIds);
        if (enabled) {
            try {
                List<Object> fields = teachingClassIds.stream()
                        .map(teachingClassId -> (Object) String.valueOf(teachingClassId))
                        .toList();
                List<Object> cached = redisTemplate.opsForHash().multiGet(classStateKey(roundId), fields);
                int index = 0;
                for (Long teachingClassId : teachingClassIds) {
                    Object value = cached == null || index >= cached.size() ? null : cached.get(index);
                    index++;
                    if (value == null || !StringUtils.hasText(String.valueOf(value))) {
                        continue;
                    }
                    try {
                        Map<String, Object> state = objectMapper.readValue(
                                String.valueOf(value), new TypeReference<Map<String, Object>>() { });
                        states.put(teachingClassId, state);
                        missing.remove(teachingClassId);
                    } catch (Exception ex) {
                        log.debug("Unable to parse Redis class state for teaching class {}", teachingClassId, ex);
                    }
                }
            } catch (Exception ex) {
                log.debug("Unable to read Redis class states for selection round {}", roundId, ex);
            }
        }
        if (!missing.isEmpty()) {
            Map<Long, Map<String, Object>> databaseStates = queryClassStates(roundId, missing);
            states.putAll(databaseStates);
            cacheClassStates(roundId, databaseStates);
        }
        return states;
    }

    private Map<Long, Map<String, Object>> queryClassStates(Long roundId, Collection<Long> teachingClassIds) {
        if (teachingClassIds.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT tc.teaching_class_id,
                       tc.capacity,
                       tc.selected_count,
                       tc.waitlist_count,
                       tc.status AS teaching_class_status,
                       GREATEST(tc.capacity - tc.selected_count, 0) AS remaining_count
                FROM teaching_class tc
                JOIN course_selection_round csr ON csr.term_id = tc.term_id
                WHERE csr.round_id = :roundId
                  AND tc.teaching_class_id IN (:teachingClassIds)
                """, new MapSqlParameterSource("roundId", roundId)
                .addValue("teachingClassIds", teachingClassIds));
        Map<Long, Map<String, Object>> states = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            states.put(number(row.get("teaching_class_id")), row);
        }
        return states;
    }

    private void refreshRoundClassStates(Long roundId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT tc.teaching_class_id,
                       tc.capacity,
                       tc.selected_count,
                       tc.waitlist_count,
                       tc.status AS teaching_class_status,
                       GREATEST(tc.capacity - tc.selected_count, 0) AS remaining_count
                FROM teaching_class tc
                JOIN course_selection_round csr ON csr.term_id = tc.term_id
                WHERE csr.round_id = :roundId
                """, new MapSqlParameterSource("roundId", roundId));
        Map<Long, Map<String, Object>> states = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            states.put(number(row.get("teaching_class_id")), row);
        }
        cacheClassStates(roundId, states);
    }

    private void refreshTeachingClassState(Long roundId, Long teachingClassId) {
        try {
            Map<Long, Map<String, Object>> states = queryClassStates(roundId, List.of(teachingClassId));
            if (states.isEmpty()) {
                redisTemplate.opsForHash().delete(classStateKey(roundId), String.valueOf(teachingClassId));
                return;
            }
            cacheClassStates(roundId, states);
        } catch (Exception ex) {
            log.warn("Unable to refresh Redis state for teaching class {}", teachingClassId, ex);
        }
    }

    private void cacheClassStates(Long roundId, Map<Long, Map<String, Object>> states) {
        if (!enabled || states.isEmpty()) {
            return;
        }
        try {
            Map<String, String> payload = new LinkedHashMap<>();
            for (Map.Entry<Long, Map<String, Object>> entry : states.entrySet()) {
                payload.put(String.valueOf(entry.getKey()), objectMapper.writeValueAsString(entry.getValue()));
            }
            String key = classStateKey(roundId);
            redisTemplate.opsForHash().putAll(key, payload);
            redisTemplate.expire(key, cacheTtl);
        } catch (Exception ex) {
            log.debug("Unable to write Redis class states for selection round {}", roundId, ex);
        }
    }

    private List<Map<String, Object>> readCatalog(String key) {
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (!StringUtils.hasText(cached)) {
                return null;
            }
            return objectMapper.readValue(cached, new TypeReference<List<Map<String, Object>>>() { });
        } catch (Exception ex) {
            log.debug("Unable to read major course catalog from Redis key {}", key, ex);
            return null;
        }
    }

    private boolean writeCatalog(String key, List<Map<String, Object>> rows) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(rows), cacheTtl);
            return true;
        } catch (Exception ex) {
            log.debug("Unable to write major course catalog to Redis key {}", key, ex);
            return false;
        }
    }

    private MapSqlParameterSource roundParams(Map<String, Object> round) {
        return new MapSqlParameterSource("roundId", roundId(round))
                .addValue("termId", termId(round))
                .addValue("roundName", round.get("round_name"))
                .addValue("academicYear", round.get("academic_year"))
                .addValue("semester", number(round.get("semester")).intValue());
    }

    private String catalogKey(Long roundId, Long termId, StudentScope scope) {
        return CATALOG_KEY_PREFIX + ":round:" + roundId
                + ":term:" + termId
                + ":major:" + scope.majorId()
                + ":grade:" + scope.gradeYear();
    }

    private String classStateKey(Long roundId) {
        return CLASS_STATE_KEY_PREFIX + ":round:" + roundId;
    }

    private Long roundId(Map<String, Object> round) {
        return number(round.get("round_id"));
    }

    private Long termId(Map<String, Object> round) {
        return number(round.get("term_id"));
    }

    private Long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(String.valueOf(value));
    }

    private record StudentScope(Long majorId, Long gradeYear) {
    }
}

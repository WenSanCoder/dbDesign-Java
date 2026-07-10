package com.zjut.edusystem.admin;

import com.zjut.edusystem.common.ApiResponse;
import com.zjut.edusystem.common.BusinessException;
import com.zjut.edusystem.common.CrudDefinition;
import com.zjut.edusystem.common.CrudService;
import jakarta.validation.Valid;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminCrudController {
    private final CrudService crudService;
    private final NamedParameterJdbcTemplate jdbc;
    private final Map<String, CrudDefinition> definitions = new LinkedHashMap<>();

    public AdminCrudController(CrudService crudService, NamedParameterJdbcTemplate jdbc) {
        this.crudService = crudService;
        this.jdbc = jdbc;
        registerDefinitions();
    }

    @GetMapping("/{resource}")
    public ApiResponse<List<Map<String, Object>>> list(@PathVariable String resource, @RequestParam Map<String, String> filters) {
        return ApiResponse.ok(crudService.list(definition(resource), filters));
    }

    @GetMapping("/{resource}/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable String resource, @PathVariable Long id) {
        return ApiResponse.ok(crudService.get(definition(resource), id));
    }

    @PostMapping("/{resource}")
    @Transactional
    public ApiResponse<Void> create(@PathVariable String resource, @Valid @RequestBody Map<String, Object> body) {
        normalizeNoticePayload(resource, body, true);
        normalizeCoursePayload(resource, body);
        normalizeTeachingClassPayload(resource, body, false);
        validateSchedulePayload(resource, body);
        validateTrainingPlanPayload(resource, body);
        validateMajorGradePayload(resource, body);
        validateTermPayload(resource, body);
        validateGradeYearPayload(resource, body);
        crudService.create(definition(resource), body);
        if ("students".equals(resource)) {
            Long studentId = findIdByCode("student", "student_id", "student_no", text(body.get("student_no")));
            syncAccount("STUDENT", studentId, text(body.get("student_name")), text(body.get("student_no")), body, true);
        } else if ("teachers".equals(resource)) {
            Long teacherId = findIdByCode("teacher", "teacher_id", "teacher_no", text(body.get("teacher_no")));
            syncAccount("TEACHER", teacherId, text(body.get("teacher_name")), text(body.get("teacher_no")), body, true);
        } else if ("class-default-classes".equals(resource)) {
            applyDefaultClassSelection(body);
        }
        return ApiResponse.ok("新增成功", null);
    }

    @PutMapping("/{resource}/{id}")
    @Transactional
    public ApiResponse<Void> update(@PathVariable String resource, @PathVariable Long id, @RequestBody Map<String, Object> body) {
        CrudDefinition definition = definition(resource);
        normalizeNoticePayload(resource, body, false);
        normalizeCoursePayload(resource, body);
        normalizeTeachingClassPayload(resource, body, true);
        validateSchedulePayload(resource, body);
        validateTrainingPlanPayload(resource, body);
        validateMajorGradePayload(resource, body);
        validateTermPayload(resource, body);
        validateGradeYearPayload(resource, body);
        boolean accountResource = "students".equals(resource) || "teachers".equals(resource);
        boolean hasWritableFields = hasWritableFields(definition, body);
        if (hasWritableFields) {
            crudService.update(definition, id, body);
        } else if (!accountResource) {
            throw new BusinessException("没有可更新字段");
        }

        if ("students".equals(resource)) {
            Map<String, Object> student = crudService.get(definition, id);
            syncAccount("STUDENT", id, text(student.get("student_name")), text(student.get("student_no")), body, false);
        } else if ("teachers".equals(resource)) {
            Map<String, Object> teacher = crudService.get(definition, id);
            syncAccount("TEACHER", id, text(teacher.get("teacher_name")), text(teacher.get("teacher_no")), body, false);
        }
        return ApiResponse.ok("更新成功", null);
    }

    @DeleteMapping("/{resource}/{id}")
    @Transactional
    public ApiResponse<Void> delete(@PathVariable String resource, @PathVariable Long id) {
        if ("students".equals(resource)) {
            deleteAccount("STUDENT", id);
        } else if ("teachers".equals(resource)) {
            deleteAccount("TEACHER", id);
        } else if ("grade-years".equals(resource)) {
            validateGradeYearDelete(id);
        }
        crudService.delete(definition(resource), id);
        return ApiResponse.ok("删除成功", null);
    }

    @GetMapping("/teaching-classes/{id}/schedules")
    public ApiResponse<List<Map<String, Object>>> teachingClassSchedules(@PathVariable Long id) {
        return ApiResponse.ok(jdbc.queryForList("""
                SELECT cs.*, cr.room_name, cr.room_code, cr.capacity AS room_capacity, b.building_name,
                       b.campus_id, campus.campus_name
                FROM class_schedule cs
                LEFT JOIN classroom_resource cr ON cr.classroom_id = cs.classroom_id
                LEFT JOIN teaching_building b ON b.building_id = cr.building_id
                LEFT JOIN campus ON campus.campus_id = b.campus_id
                WHERE cs.teaching_class_id = :teachingClassId
                ORDER BY cs.weekday, cs.start_period, cs.schedule_id
                """, new MapSqlParameterSource("teachingClassId", id)));
    }

    @PostMapping("/teaching-classes/{id}/schedules")
    @Transactional
    public ApiResponse<Void> saveTeachingClassSchedules(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Map<String, Object> teachingClass = queryOne("""
                SELECT tc.teaching_class_id, tc.campus_id, campus.campus_name, c.credit
                FROM teaching_class tc
                JOIN course c ON c.course_id = tc.course_id
                JOIN campus ON campus.campus_id = tc.campus_id
                WHERE tc.teaching_class_id = :teachingClassId
                """, new MapSqlParameterSource("teachingClassId", id), "教学班不存在");

        Integer startWeek = integer(body.get("startWeek"));
        Integer endWeek = integer(body.get("endWeek"));
        String weekPattern = text(body.get("weekPattern"));
        if (!StringUtils.hasText(weekPattern)) {
            weekPattern = "all";
        }
        if (startWeek == null || endWeek == null || startWeek < 1 || endWeek > 16 || startWeek > endWeek) {
            throw new BusinessException("上课周次必须在 1-16 周内，且开始周不能晚于结束周");
        }
        if (!List.of("all", "odd", "even").contains(weekPattern)) {
            throw new BusinessException("周次模式只能是每周、单周或双周");
        }

        List<Map<String, Object>> sessions = scheduleSessions(body.get("sessions"));
        if (sessions.isEmpty()) {
            throw new BusinessException("至少需要维护一节课的上课时间");
        }

        int weeklyPeriods = 0;
        for (Map<String, Object> session : sessions) {
            Integer weekday = integer(session.get("weekday"));
            Integer startPeriod = integer(session.get("startPeriod"));
            Integer endPeriod = integer(session.get("endPeriod"));
            Long classroomId = longValue(session.get("classroomId"));
            if (weekday == null || weekday < 1 || weekday > 7) {
                throw new BusinessException("每节课必须选择星期");
            }
            if (startPeriod == null || endPeriod == null) {
                throw new BusinessException("每节课必须填写开始课次和结束课次");
            }
            int periodLength = endPeriod - startPeriod + 1;
            if (startPeriod < 1 || endPeriod > 12 || periodLength < 1 || periodLength > 4) {
                throw new BusinessException("一节课必须由 1-4 个连续课次组成，且课次范围为 1-12");
            }
            if (classroomId == null) {
                throw new BusinessException("每节课必须选择教室");
            }
            assertClassroomInTeachingClassCampus(classroomId, longValue(teachingClass.get("campus_id")), text(teachingClass.get("campus_name")));
            weeklyPeriods += periodLength;
        }

        Double credit = decimal(teachingClass.get("credit"));
        int expectedHours = (int) Math.round((credit == null ? 0 : credit) * 16);
        int scheduledHours = effectiveWeekCount(startWeek, endWeek, weekPattern) * weeklyPeriods;
        if (scheduledHours != expectedHours) {
            throw new BusinessException("排课总学时 " + scheduledHours + " 与课程要求 " + expectedHours + " 不一致，不能保存");
        }

        jdbc.update("DELETE FROM class_schedule WHERE teaching_class_id = :teachingClassId",
                new MapSqlParameterSource("teachingClassId", id));

        for (Map<String, Object> session : sessions) {
            jdbc.update("""
                    INSERT INTO class_schedule(
                        teaching_class_id, weekday, start_period, end_period,
                        start_week, end_week, week_pattern, classroom_id, weeks
                    )
                    VALUES (
                        :teachingClassId, :weekday, :startPeriod, :endPeriod,
                        :startWeek, :endWeek, :weekPattern, :classroomId, :weeks
                    )
                    """, new MapSqlParameterSource()
                    .addValue("teachingClassId", id)
                    .addValue("weekday", integer(session.get("weekday")))
                    .addValue("startPeriod", integer(session.get("startPeriod")))
                    .addValue("endPeriod", integer(session.get("endPeriod")))
                    .addValue("startWeek", startWeek)
                    .addValue("endWeek", endWeek)
                    .addValue("weekPattern", weekPattern)
                    .addValue("classroomId", longValue(session.get("classroomId")))
                    .addValue("weeks", weekText(startWeek, endWeek, weekPattern)));
        }

        return ApiResponse.ok("排课保存成功", null);
    }

    private CrudDefinition definition(String resource) {
        CrudDefinition definition = definitions.get(resource);
        if (definition == null) {
            throw new BusinessException("未知管理资源：" + resource);
        }
        return definition;
    }

    private void registerDefinitions() {
        definitions.put("campuses", new CrudDefinition(
                "campus",
                "campus_id",
                List.of("campus_code", "campus_name", "status", "remark"),
                "SELECT * FROM campus",
                "campus_id"
        ));
        definitions.put("colleges", new CrudDefinition(
                "college",
                "college_id",
                List.of("college_code", "college_name", "campus_id", "contact_phone", "status"),
                "SELECT college.*, campus.campus_name FROM college JOIN campus ON campus.campus_id = college.campus_id",
                "college_id DESC"
        ));
        definitions.put("majors", new CrudDefinition(
                "major",
                "major_id",
                List.of("major_code", "major_name", "college_id", "campus_id", "duration_years", "degree_type", "min_graduate_credit", "status"),
                "SELECT major.*, college.college_name, campus.campus_name FROM major JOIN college ON college.college_id = major.college_id JOIN campus ON campus.campus_id = major.campus_id",
                "major.major_id DESC"
        ));
        definitions.put("admin-classes", new CrudDefinition(
                "admin_class",
                "admin_class_id",
                List.of("class_code", "class_name", "major_id", "grade_year", "head_teacher_id", "status"),
                "SELECT ac.*, m.major_name, c.college_id, c.college_name, t.teacher_name AS head_teacher_name FROM admin_class ac JOIN major m ON m.major_id = ac.major_id JOIN college c ON c.college_id = m.college_id LEFT JOIN teacher t ON t.teacher_id = ac.head_teacher_id",
                "ac.admin_class_id DESC"
        ));
        definitions.put("grade-years", new CrudDefinition(
                "grade_year",
                "grade_year_id",
                List.of("grade_year", "admission_academic_year", "graduation_academic_year", "status", "remark"),
                "SELECT * FROM grade_year",
                "grade_year DESC"
        ));
        definitions.put("notices", new CrudDefinition(
                "notice",
                "notice_id",
                List.of("notice_type", "title", "content"),
                "SELECT n.*, COALESCE(ua.display_name, '管理员') AS publisher_name FROM notice n LEFT JOIN user_account ua ON ua.user_id = n.user_id",
                "n.created_at DESC, n.notice_id DESC"
        ));
        definitions.put("students", new CrudDefinition(
                "student",
                "student_id",
                List.of("student_no", "student_name", "gender", "age", "phone", "admin_class_id", "grade_year", "region_id", "status"),
                "SELECT s.*, ac.class_name, m.major_name, c.college_name, r.region_name, ua.user_id AS account_user_id, ua.username, ua.role_code, ua.status AS account_status, ua.last_login_at, ua.avatar_path FROM student s JOIN admin_class ac ON ac.admin_class_id = s.admin_class_id JOIN major m ON m.major_id = ac.major_id JOIN college c ON c.college_id = m.college_id LEFT JOIN region r ON r.region_id = s.region_id LEFT JOIN user_account ua ON ua.role_code = 'STUDENT' AND ua.related_id = s.student_id",
                "s.student_id DESC"
        ));
        definitions.put("teachers", new CrudDefinition(
                "teacher",
                "teacher_id",
                List.of("teacher_no", "teacher_name", "gender", "age", "title", "phone", "college_id", "status"),
                "SELECT teacher.*, college.college_name, ua.user_id AS account_user_id, ua.username, ua.role_code, ua.status AS account_status, ua.last_login_at, ua.avatar_path FROM teacher JOIN college ON college.college_id = teacher.college_id LEFT JOIN user_account ua ON ua.role_code = 'TEACHER' AND ua.related_id = teacher.teacher_id",
                "teacher.teacher_id DESC"
        ));
        definitions.put("courses", new CrudDefinition(
                "course",
                "course_id",
                List.of("course_code", "course_name", "college_id", "credit", "hours", "max_session_periods", "exam_type", "description", "status"),
                "SELECT course.*, college.college_name FROM course JOIN college ON college.college_id = course.college_id",
                "course.course_id DESC"
        ));
        definitions.put("training-requirements", new CrudDefinition(
                "major_training_requirement",
                "requirement_id",
                List.of("major_id", "grade_year", "course_type", "min_credit", "remark"),
                "SELECT mtr.*, m.major_code, m.major_name FROM major_training_requirement mtr JOIN major m ON m.major_id = mtr.major_id",
                "mtr.major_id DESC, mtr.grade_year DESC, mtr.course_type"
        ));
        definitions.put("teaching-plans", new CrudDefinition(
                "teaching_plan",
                "plan_id",
                List.of("major_id", "grade_year", "term_id", "course_id", "course_nature"),
                "SELECT tp.plan_id, tp.major_id, tp.grade_year, tp.term_id, tp.course_id, tp.course_nature, m.major_code, m.major_name, term.academic_year, term.semester, c.course_code, c.course_name, c.credit FROM teaching_plan tp JOIN major m ON m.major_id = tp.major_id JOIN term ON term.term_id = tp.term_id JOIN course c ON c.course_id = tp.course_id",
                "tp.major_id DESC, tp.grade_year DESC, term.start_date DESC, c.course_code"
        ));
        definitions.put("teaching-classes", new CrudDefinition(
                "teaching_class",
                "teaching_class_id",
                List.of("class_code", "class_name", "course_id", "teacher_id", "term_id", "campus_id", "capacity", "selected_count", "waitlist_count", "status"),
                "SELECT tc.*, c.course_code, c.course_name, c.credit, c.hours, t.teacher_name, term.academic_year, term.semester, campus.campus_name FROM teaching_class tc JOIN course c ON c.course_id = tc.course_id JOIN teacher t ON t.teacher_id = tc.teacher_id JOIN term ON term.term_id = tc.term_id JOIN campus ON campus.campus_id = tc.campus_id",
                "tc.teaching_class_id DESC"
        ));
        definitions.put("class-schedules", new CrudDefinition(
                "class_schedule",
                "schedule_id",
                List.of("teaching_class_id", "weekday", "start_period", "end_period", "start_week", "end_week", "week_pattern", "classroom_id", "classroom", "weeks"),
                "SELECT cs.*, cr.room_name, cr.room_code, cr.capacity AS room_capacity, b.building_name, b.campus_id, campus.campus_name, tc.class_code, tc.class_name, c.course_code, c.course_name, t.teacher_name, term.academic_year, term.semester FROM class_schedule cs JOIN teaching_class tc ON tc.teaching_class_id = cs.teaching_class_id JOIN course c ON c.course_id = tc.course_id JOIN teacher t ON t.teacher_id = tc.teacher_id JOIN term ON term.term_id = tc.term_id LEFT JOIN classroom_resource cr ON cr.classroom_id = cs.classroom_id LEFT JOIN teaching_building b ON b.building_id = cr.building_id LEFT JOIN campus ON campus.campus_id = b.campus_id",
                "cs.schedule_id DESC"
        ));
        definitions.put("class-default-classes", new CrudDefinition(
                "teaching_class_admin_class",
                "assignment_id",
                List.of("teaching_class_id", "admin_class_id"),
                "SELECT tcac.assignment_id, tcac.teaching_class_id, tcac.admin_class_id, tc.class_code, tc.class_name, c.course_code, c.course_name, ac.class_name AS admin_class_name, ac.grade_year FROM teaching_class_admin_class tcac JOIN teaching_class tc ON tc.teaching_class_id = tcac.teaching_class_id JOIN course c ON c.course_id = tc.course_id JOIN admin_class ac ON ac.admin_class_id = tcac.admin_class_id",
                "tcac.assignment_id DESC"
        ));
        definitions.put("rounds", new CrudDefinition(
                "course_selection_round",
                "round_id",
                List.of("term_id", "round_name", "start_time", "end_time", "status", "waitlist_enabled"),
                "SELECT csr.*, term.academic_year, term.semester FROM course_selection_round csr JOIN term ON term.term_id = csr.term_id",
                "csr.round_id DESC"
        ));
        definitions.put("terms", new CrudDefinition(
                "term",
                "term_id",
                List.of("academic_year", "semester", "start_date", "end_date", "is_current"),
                "SELECT * FROM term",
                "term_id DESC"
        ));
        definitions.put("buildings", new CrudDefinition(
                "teaching_building",
                "building_id",
                List.of("building_code", "building_name", "campus_id", "floor_count", "rooms_per_floor", "large_room_count_per_floor", "small_room_capacity", "large_room_capacity", "status", "remark"),
                "SELECT b.*, campus.campus_name FROM teaching_building b JOIN campus ON campus.campus_id = b.campus_id",
                "campus.campus_name, b.building_name"
        ));
        definitions.put("classrooms", new CrudDefinition(
                "classroom_resource",
                "classroom_id",
                List.of("building_id", "room_code", "room_name", "floor_no", "room_no", "room_type", "capacity", "status", "remark"),
                "SELECT cr.*, b.building_code, b.building_name, b.campus_id, campus.campus_name FROM classroom_resource cr JOIN teaching_building b ON b.building_id = cr.building_id JOIN campus ON campus.campus_id = b.campus_id",
                "campus.campus_name, b.building_name, cr.floor_no, cr.room_no"
        ));
    }

    private Long findIdByCode(String table, String idColumn, String codeColumn, String code) {
        if (!StringUtils.hasText(code)) {
            throw new BusinessException("编号不能为空，无法创建关联账号");
        }
        String sql = "SELECT " + idColumn + " FROM " + table + " WHERE " + codeColumn + " = :code ORDER BY " + idColumn + " DESC LIMIT 1";
        List<Long> ids = jdbc.queryForList(sql, new MapSqlParameterSource("code", code), Long.class);
        if (ids.isEmpty()) {
            throw new BusinessException("创建成功但未找到关联记录，无法创建登录账号");
        }
        return ids.get(0);
    }

    private void syncAccount(String roleCode, Long relatedId, String displayName, String defaultUsername, Map<String, Object> body, boolean createMode) {
        String username = text(body.get("username"));
        String password = text(body.get("password_text"));
        String accountStatus = text(body.get("account_status"));

        MapSqlParameterSource queryParams = new MapSqlParameterSource()
                .addValue("roleCode", roleCode)
                .addValue("relatedId", relatedId);
        List<Long> accountIds = jdbc.queryForList(
                "SELECT user_id FROM user_account WHERE role_code = :roleCode AND related_id = :relatedId",
                queryParams,
                Long.class
        );

        if (accountIds.isEmpty()) {
            if (!StringUtils.hasText(username)) {
                username = defaultUsername;
            }
            if (!StringUtils.hasText(accountStatus)) {
                accountStatus = "enabled";
            }
            String initialPassword = StringUtils.hasText(password) ? password : "123456";
            jdbc.update("""
                    INSERT INTO user_account (username, password_text, role_code, display_name, related_id, status)
                    VALUES (:username, :passwordText, :roleCode, :displayName, :relatedId, :status)
                    """,
                    new MapSqlParameterSource()
                            .addValue("username", username)
                            .addValue("passwordText", initialPassword)
                            .addValue("roleCode", roleCode)
                            .addValue("displayName", displayName)
                            .addValue("relatedId", relatedId)
                            .addValue("status", accountStatus)
            );
            return;
        }

        Map<String, Object> currentAccount = jdbc.queryForMap(
                "SELECT username, status FROM user_account WHERE user_id = :userId",
                new MapSqlParameterSource("userId", accountIds.get(0))
        );
        if (!StringUtils.hasText(username)) {
            username = text(currentAccount.get("username"));
        }
        if (!StringUtils.hasText(accountStatus)) {
            accountStatus = text(currentAccount.get("status"));
        }

        MapSqlParameterSource updateParams = new MapSqlParameterSource()
                .addValue("userId", accountIds.get(0))
                .addValue("username", username)
                .addValue("roleCode", roleCode)
                .addValue("displayName", displayName)
                .addValue("relatedId", relatedId)
                .addValue("status", accountStatus);
        String sql = """
                UPDATE user_account
                SET username = :username,
                    role_code = :roleCode,
                    display_name = :displayName,
                    related_id = :relatedId,
                    status = :status
                """;
        if (StringUtils.hasText(password)) {
            sql += ", password_text = :passwordText";
            updateParams.addValue("passwordText", password);
        }
        sql += " WHERE user_id = :userId";
        jdbc.update(sql, updateParams);
    }

    private void deleteAccount(String roleCode, Long relatedId) {
        jdbc.update(
                "DELETE FROM user_account WHERE role_code = :roleCode AND related_id = :relatedId",
                new MapSqlParameterSource()
                        .addValue("roleCode", roleCode)
                        .addValue("relatedId", relatedId)
        );
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private void normalizeCoursePayload(String resource, Map<String, Object> body) {
        if (!"courses".equals(resource)) {
            return;
        }
        body.putIfAbsent("max_session_periods", 4);
        if (!body.containsKey("hours") || body.get("hours") == null || !StringUtils.hasText(text(body.get("hours")))) {
            Double credit = decimal(body.get("credit"));
            if (credit != null) {
                body.put("hours", (int) Math.round(credit * 16));
            }
        }
    }

    private void normalizeTeachingClassPayload(String resource, Map<String, Object> body, boolean updateMode) {
        if (!"teaching-classes".equals(resource)) {
            return;
        }
        body.remove("selected_count");
        body.remove("waitlist_count");
        Long courseId = longValue(body.get("course_id"));
        Long termId = longValue(body.get("term_id"));
        String classCode = text(body.get("class_code"));
        if (body.get("campus_id") == null && courseId != null && termId != null) {
            List<Long> campusIds = jdbc.queryForList("""
                    SELECT CASE WHEN SUBSTR(term.academic_year, 1, 4) = CAST(tp.grade_year AS TEXT)
                                THEN first_year_campus.campus_id
                                ELSE m.campus_id
                           END AS campus_id
                    FROM teaching_plan tp
                    JOIN major m ON m.major_id = tp.major_id
                    JOIN term ON term.term_id = tp.term_id
                    JOIN campus first_year_campus ON first_year_campus.campus_code = 'ZHAOHUI'
                    WHERE tp.course_id = :courseId
                      AND tp.term_id = :termId
                    ORDER BY tp.plan_id
                    LIMIT 1
                    """, new MapSqlParameterSource()
                    .addValue("courseId", courseId)
                    .addValue("termId", termId), Long.class);
            if (campusIds.isEmpty()) {
                campusIds = jdbc.queryForList("""
                        SELECT COALESCE(m.campus_id, col.campus_id)
                        FROM course c
                        JOIN college col ON col.college_id = c.college_id
                        LEFT JOIN major m ON m.college_id = col.college_id
                        WHERE c.course_id = :courseId
                        ORDER BY m.major_id
                        LIMIT 1
                        """, new MapSqlParameterSource("courseId", courseId), Long.class);
            }
            if (!campusIds.isEmpty()) {
                body.put("campus_id", campusIds.get(0));
            }
        }
        if (courseId == null || termId == null || !StringUtils.hasText(classCode)) {
            return;
        }

        Map<String, Object> row = queryOne("""
                SELECT c.course_name, term.academic_year, term.semester
                FROM course c
                CROSS JOIN term
                WHERE c.course_id = :courseId
                  AND term.term_id = :termId
                """, new MapSqlParameterSource()
                .addValue("courseId", courseId)
                .addValue("termId", termId), "课程或学期不存在");
        String academicYear = text(row.get("academic_year"));
        String yearPrefix = academicYear != null && academicYear.length() >= 4 ? academicYear.substring(0, 4) : "";
        String semester = String.format("%02d", integer(row.get("semester")) == null ? 0 : integer(row.get("semester")));
        body.put("class_name", yearPrefix + semester + text(row.get("course_name")) + classCode + "班");
    }

    private void validateSchedulePayload(String resource, Map<String, Object> body) {
        if (!"class-schedules".equals(resource)) {
            return;
        }
        Integer startPeriod = integer(body.get("start_period"));
        Integer endPeriod = integer(body.get("end_period"));
        Integer startWeek = integer(body.get("start_week"));
        Integer endWeek = integer(body.get("end_week"));
        if (startPeriod != null && endPeriod != null) {
            int length = endPeriod - startPeriod + 1;
            if (startPeriod < 1 || endPeriod > 12 || length < 1 || length > 4) {
                throw new BusinessException("一次课必须由 1-4 个连续课次组成，且课次范围为 1-12");
            }
        }
        if (startWeek != null && endWeek != null && (startWeek < 1 || endWeek > 16 || startWeek > endWeek)) {
            throw new BusinessException("上课周次必须在 1-16 周内，且开始周不能晚于结束周");
        }
        Long classroomId = longValue(body.get("classroom_id"));
        if (classroomId == null && !StringUtils.hasText(text(body.get("classroom")))) {
            throw new BusinessException("上课安排必须选择教室");
        }
    }

    private void normalizeNoticePayload(String resource, Map<String, Object> body, boolean defaultMissing) {
        if (!"notices".equals(resource)) {
            return;
        }
        String noticeType = text(body.get("notice_type"));
        if (!StringUtils.hasText(noticeType)) {
            if (defaultMissing) {
                body.put("notice_type", "normal");
            }
            return;
        }
        if (!List.of("normal", "important").contains(noticeType)) {
            throw new BusinessException("公告只能设置为普通或重要");
        }
    }

    private void applyDefaultClassSelection(Map<String, Object> body) {
        Long teachingClassId = longValue(body.get("teaching_class_id"));
        Long adminClassId = longValue(body.get("admin_class_id"));
        if (teachingClassId == null || adminClassId == null) {
            throw new BusinessException("默认班级分配必须选择教学班和行政班");
        }

        Map<String, Object> teachingClass = queryOne("""
                SELECT tc.teaching_class_id, tc.course_id, tc.term_id, tc.campus_id, campus.campus_name,
                       tc.capacity, tc.selected_count, c.course_name
                FROM teaching_class tc
                JOIN course c ON c.course_id = tc.course_id
                JOIN campus ON campus.campus_id = tc.campus_id
                WHERE tc.teaching_class_id = :teachingClassId
                """, new MapSqlParameterSource("teachingClassId", teachingClassId), "教学班不存在");

        Map<String, Object> adminClassCampus = queryOne("""
                SELECT ac.class_name AS admin_class_name,
                       CASE WHEN SUBSTR(term.academic_year, 1, 4) = CAST(ac.grade_year AS TEXT)
                            THEN first_year_campus.campus_id
                            ELSE major_campus.campus_id
                       END AS expected_campus_id,
                       CASE WHEN SUBSTR(term.academic_year, 1, 4) = CAST(ac.grade_year AS TEXT)
                            THEN first_year_campus.campus_name
                            ELSE major_campus.campus_name
                       END AS expected_campus_name
                FROM admin_class ac
                JOIN major m ON m.major_id = ac.major_id
                JOIN campus major_campus ON major_campus.campus_id = m.campus_id
                JOIN campus first_year_campus ON first_year_campus.campus_code = 'ZHAOHUI'
                JOIN grade_year gy ON gy.grade_year = ac.grade_year
                JOIN term ON term.term_id = :termId
                WHERE ac.admin_class_id = :adminClassId
                """, new MapSqlParameterSource()
                .addValue("adminClassId", adminClassId)
                .addValue("termId", teachingClass.get("term_id")), "行政班不存在");

        Long classCampusId = longValue(teachingClass.get("campus_id"));
        Long expectedCampusId = longValue(adminClassCampus.get("expected_campus_id"));
        if (classCampusId != null && expectedCampusId != null && !classCampusId.equals(expectedCampusId)) {
            throw new BusinessException("默认行政班分配校区不符："
                    + text(adminClassCampus.get("admin_class_name"))
                    + " 当前学期应在 " + text(adminClassCampus.get("expected_campus_name"))
                    + "，教学班开班校区为 " + text(teachingClass.get("campus_name")));
        }

        Map<String, Object> round = queryOne("""
                SELECT round_id
                FROM course_selection_round
                WHERE term_id = :termId
                ORDER BY start_time ASC, round_id ASC
                LIMIT 1
                """, new MapSqlParameterSource("termId", teachingClass.get("term_id")), "该教学班所属学期尚未维护选课轮次，不能执行默认分配");

        Integer targetStudentCount = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM student s
                WHERE s.admin_class_id = :adminClassId
                  AND s.status = 'active'
                  AND NOT EXISTS (
                      SELECT 1
                      FROM student_course_selection scs
                      WHERE scs.student_id = s.student_id
                        AND scs.teaching_class_id = :teachingClassId
                        AND scs.status IN ('processing', 'selected')
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM student_course_selection scs2
                      JOIN teaching_class tc2 ON tc2.teaching_class_id = scs2.teaching_class_id
                      WHERE scs2.student_id = s.student_id
                        AND tc2.course_id = :courseId
                        AND tc2.term_id = :termId
                        AND scs2.status IN ('processing', 'selected')
                  )
                """, new MapSqlParameterSource()
                .addValue("adminClassId", adminClassId)
                .addValue("teachingClassId", teachingClassId)
                .addValue("courseId", teachingClass.get("course_id"))
                .addValue("termId", teachingClass.get("term_id")), Integer.class);

        int capacity = integer(teachingClass.get("capacity")) == null ? 0 : integer(teachingClass.get("capacity"));
        int selectedCount = integer(teachingClass.get("selected_count")) == null ? 0 : integer(teachingClass.get("selected_count"));
        int available = capacity - selectedCount;
        int need = targetStudentCount == null ? 0 : targetStudentCount;
        if (need > available) {
            throw new BusinessException("默认分配人数 " + need + " 超过教学班剩余容量 " + available);
        }
        if (need == 0) {
            return;
        }

        jdbc.update("""
                INSERT INTO student_course_selection(request_id, student_id, teaching_class_id, round_id, status, selected_at, selection_source)
                SELECT
                    'DEFAULT-' || CAST(:teachingClassId AS TEXT) || '-' || CAST(s.student_id AS TEXT),
                    s.student_id,
                    :teachingClassId,
                    :roundId,
                    'selected',
                    CURRENT_TIMESTAMP,
                    'default'
                FROM student s
                WHERE s.admin_class_id = :adminClassId
                  AND s.status = 'active'
                  AND NOT EXISTS (
                      SELECT 1
                      FROM student_course_selection scs
                      WHERE scs.student_id = s.student_id
                        AND scs.teaching_class_id = :teachingClassId
                        AND scs.status IN ('processing', 'selected')
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM student_course_selection scs2
                      JOIN teaching_class tc2 ON tc2.teaching_class_id = scs2.teaching_class_id
                      WHERE scs2.student_id = s.student_id
                        AND tc2.course_id = :courseId
                        AND tc2.term_id = :termId
                        AND scs2.status IN ('processing', 'selected')
                  )
                """, new MapSqlParameterSource()
                .addValue("teachingClassId", teachingClassId)
                .addValue("roundId", round.get("round_id"))
                .addValue("adminClassId", adminClassId)
                .addValue("courseId", teachingClass.get("course_id"))
                .addValue("termId", teachingClass.get("term_id")));

        jdbc.update("""
                UPDATE teaching_class
                SET selected_count = selected_count + :count
                WHERE teaching_class_id = :teachingClassId
                """, new MapSqlParameterSource()
                .addValue("count", need)
                .addValue("teachingClassId", teachingClassId));
    }

    private void validateTrainingPlanPayload(String resource, Map<String, Object> body) {
        if (!"teaching-plans".equals(resource)) {
            return;
        }
        String courseNature = text(body.get("course_nature"));
        if (!StringUtils.hasText(courseNature)) {
            throw new BusinessException("培养方案课程必须选择课程性质");
        }
    }

    private void validateMajorGradePayload(String resource, Map<String, Object> body) {
        if (!"teaching-plans".equals(resource) && !"training-requirements".equals(resource)) {
            return;
        }
        Long majorId = longValue(body.get("major_id"));
        Integer gradeYear = integer(body.get("grade_year"));
        if (majorId == null || gradeYear == null) {
            throw new BusinessException("培养方案必须选择专业和年级");
        }
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM grade_year
                WHERE grade_year = :gradeYear AND status = 'enabled'
                """, new MapSqlParameterSource()
                .addValue("gradeYear", gradeYear), Integer.class);
        if (count == null || count == 0) {
            throw new BusinessException("该入学年级不存在或未启用，不能维护培养方案");
        }
    }

    private void validateTermPayload(String resource, Map<String, Object> body) {
        if (!"terms".equals(resource)) {
            return;
        }
        String academicYear = text(body.get("academic_year"));
        if (StringUtils.hasText(academicYear)) {
            String normalized = normalizeAcademicYear(academicYear);
            if (!StringUtils.hasText(normalized)) {
                throw new BusinessException("学年格式应为 2025/2026");
            }
            body.put("academic_year", normalized);
        }
        Integer semester = integer(body.get("semester"));
        if (semester != null && (semester < 1 || semester > 3)) {
            throw new BusinessException("一个学年最多维护 3 个学期，学期值必须为 1、2 或 3");
        }
    }

    private void validateGradeYearPayload(String resource, Map<String, Object> body) {
        if (!"grade-years".equals(resource)) {
            return;
        }
        Integer gradeYear = integer(body.get("grade_year"));
        if (gradeYear == null || gradeYear < 2000 || gradeYear > 2100) {
            throw new BusinessException("入学年级必须在 2000-2100 范围内");
        }
        String admissionAcademicYear = normalizeAcademicYear(text(body.get("admission_academic_year")));
        String graduationAcademicYear = normalizeAcademicYear(text(body.get("graduation_academic_year")));
        if (StringUtils.hasText(admissionAcademicYear)) {
            body.put("admission_academic_year", admissionAcademicYear);
        }
        if (StringUtils.hasText(graduationAcademicYear)) {
            body.put("graduation_academic_year", graduationAcademicYear);
        }
        if (!StringUtils.hasText(admissionAcademicYear) || !StringUtils.hasText(graduationAcademicYear)) {
            throw new BusinessException("入学年级必须维护入学学年和毕业学年");
        }
        ensureAcademicYearExists(admissionAcademicYear, "入学学年");
        ensureAcademicYearExists(graduationAcademicYear, "毕业学年");

        Integer admissionStartYear = academicYearStart(admissionAcademicYear);
        Integer graduationStartYear = academicYearStart(graduationAcademicYear);
        if (admissionStartYear == null || graduationStartYear == null) {
            throw new BusinessException("学年格式应为 2024/2025");
        }
        if (graduationStartYear < admissionStartYear) {
            throw new BusinessException("毕业学年不能早于入学学年");
        }
    }

    private void ensureAcademicYearExists(String academicYear, String label) {
        String normalized = normalizeAcademicYear(academicYear);
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM term
                WHERE academic_year = :academicYear
                """, new MapSqlParameterSource("academicYear", normalized), Integer.class);
        if (count == null || count == 0) {
            throw new BusinessException(label + "必须先在学年学期维护中创建：" + academicYear);
        }
    }

    private Integer academicYearStart(String academicYear) {
        String normalized = normalizeAcademicYear(academicYear);
        if (!StringUtils.hasText(normalized) || normalized.length() < 4) {
            return null;
        }
        try {
            return Integer.valueOf(normalized.substring(0, 4));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String normalizeAcademicYear(String academicYear) {
        if (!StringUtils.hasText(academicYear)) {
            return null;
        }
        String digits = academicYear.trim().replaceAll("[^0-9]", "");
        if (digits.length() != 8) {
            return null;
        }
        return digits.substring(0, 4) + "/" + digits.substring(4, 8);
    }

    private void validateGradeYearDelete(Long gradeYearId) {
        Map<String, Object> grade = crudService.get(definition("grade-years"), gradeYearId);
        Integer gradeYear = integer(grade.get("grade_year"));
        if (gradeYear == null) {
            return;
        }
        Integer classCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM admin_class
                WHERE grade_year = :gradeYear
                """, new MapSqlParameterSource("gradeYear", gradeYear), Integer.class);
        if (classCount != null && classCount > 0) {
            throw new BusinessException("该年级已存在行政班，不能直接删除");
        }
        jdbc.update("DELETE FROM teaching_plan WHERE grade_year = :gradeYear", new MapSqlParameterSource("gradeYear", gradeYear));
        jdbc.update("DELETE FROM major_training_requirement WHERE grade_year = :gradeYear", new MapSqlParameterSource("gradeYear", gradeYear));
    }

    private Map<String, Object> queryOne(String sql, MapSqlParameterSource params, String message) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, params);
        if (rows.isEmpty()) {
            throw new BusinessException(message);
        }
        return rows.get(0);
    }

    private Integer integer(Object value) {
        if (value == null || !StringUtils.hasText(text(value))) {
            return null;
        }
        return Double.valueOf(text(value)).intValue();
    }

    private Long longValue(Object value) {
        if (value == null || !StringUtils.hasText(text(value))) {
            return null;
        }
        return Double.valueOf(text(value)).longValue();
    }

    private Double decimal(Object value) {
        if (value == null || !StringUtils.hasText(text(value))) {
            return null;
        }
        return Double.valueOf(text(value));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> scheduleSessions(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> sessions = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                sessions.add((Map<String, Object>) map);
            }
        }
        return sessions;
    }

    private int effectiveWeekCount(int startWeek, int endWeek, String weekPattern) {
        int count = 0;
        for (int week = startWeek; week <= endWeek; week++) {
            if ("odd".equals(weekPattern) && week % 2 == 0) {
                continue;
            }
            if ("even".equals(weekPattern) && week % 2 != 0) {
                continue;
            }
            count++;
        }
        return count;
    }

    private String weekText(int startWeek, int endWeek, String weekPattern) {
        String text = startWeek + "-" + endWeek + "周";
        if ("odd".equals(weekPattern)) {
            return text + "(单周)";
        }
        if ("even".equals(weekPattern)) {
            return text + "(双周)";
        }
        return text;
    }

    private void assertClassroomInTeachingClassCampus(Long classroomId, Long teachingClassCampusId, String teachingClassCampusName) {
        Map<String, Object> room = queryOne("""
                SELECT cr.classroom_id, cr.room_name, b.campus_id, campus.campus_name
                FROM classroom_resource cr
                JOIN teaching_building b ON b.building_id = cr.building_id
                JOIN campus ON campus.campus_id = b.campus_id
                WHERE cr.classroom_id = :classroomId
                """, new MapSqlParameterSource("classroomId", classroomId), "教室不存在");
        Long roomCampusId = longValue(room.get("campus_id"));
        if (teachingClassCampusId != null && roomCampusId != null && !teachingClassCampusId.equals(roomCampusId)) {
            throw new BusinessException("排课校区不符：教学班校区为 "
                    + teachingClassCampusName
                    + "，教室 " + text(room.get("room_name"))
                    + " 属于 " + text(room.get("campus_name")));
        }
    }

    private boolean hasWritableFields(CrudDefinition definition, Map<String, Object> body) {
        return definition.writableColumns().stream().anyMatch(body::containsKey);
    }
}

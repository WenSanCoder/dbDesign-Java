package com.zjut.edusystem.admin;

import com.zjut.edusystem.common.BusinessException;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AdminBatchImportService {
    private static final int MAX_ROWS = 1000;
    private static final Map<String, List<String>> HEADERS = Map.of(
            "admin-classes", List.of("专业编码", "入学年级", "班号", "班主任工号", "状态"),
            "users", List.of("账号", "初始密码", "显示姓名", "角色代码", "关联身份编号", "账号状态"),
            "buildings", List.of("楼宇编码", "教学楼名称", "校区编码", "楼层数", "每层教室数", "每层大教室数", "小教室容量", "大教室容量", "状态", "备注"),
            "teaching-classes", List.of("课程代码", "学年", "学期", "教学班编号", "任课教师工号", "校区编码", "容量", "状态"),
            "program-changes", List.of("学号", "目标行政班编码", "生效学年", "生效学期", "申请原因")
    );

    private final NamedParameterJdbcTemplate jdbc;

    public AdminBatchImportService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Map<String, Object> importXlsx(String type, MultipartFile file, Long operatorUserId) {
        List<String> expectedHeaders = HEADERS.get(type);
        if (expectedHeaders == null) throw new BusinessException("不支持的导入类型：" + type);
        List<ImportRow> rows = parse(file, expectedHeaders);
        Set<String> uniqueKeys = new HashSet<>();
        for (ImportRow row : rows) {
            try {
                switch (type) {
                    case "admin-classes" -> importAdminClass(row, uniqueKeys);
                    case "users" -> importUser(row, uniqueKeys, operatorUserId);
                    case "buildings" -> importBuilding(row, uniqueKeys);
                    case "teaching-classes" -> importTeachingClass(row, uniqueKeys);
                    case "program-changes" -> importProgramChange(row, uniqueKeys);
                    default -> throw new BusinessException("不支持的导入类型：" + type);
                }
            } catch (BusinessException ex) {
                throw new BusinessException("第 " + row.rowNumber() + " 行：" + ex.getMessage());
            } catch (DataAccessException ex) {
                throw new BusinessException("第 " + row.rowNumber() + " 行：数据库校验失败，可能存在重复编号或不符合约束的数据");
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        result.put("fileName", file.getOriginalFilename());
        result.put("importedCount", rows.size());
        return result;
    }

    private List<ImportRow> parse(MultipartFile file, List<String> expectedHeaders) {
        if (file == null || file.isEmpty()) throw new BusinessException("请选择 XLSX 文件");
        String filename = file.getOriginalFilename();
        if (!StringUtils.hasText(filename) || !filename.toLowerCase().endsWith(".xlsx")) {
            throw new BusinessException("仅支持 .xlsx 文件");
        }
        try (InputStream input = file.getInputStream(); Workbook workbook = WorkbookFactory.create(input)) {
            if (workbook.getNumberOfSheets() == 0) throw new BusinessException("工作簿没有工作表");
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) throw new BusinessException("第一行必须是模板表头");
            DataFormatter formatter = new DataFormatter();
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            for (int column = 0; column < expectedHeaders.size(); column++) {
                String actual = cellText(headerRow, column, formatter, evaluator);
                if (!expectedHeaders.get(column).equals(actual)) {
                    throw new BusinessException("第 " + (column + 1) + " 列表头必须为“" + expectedHeaders.get(column) + "”");
                }
            }
            if (StringUtils.hasText(cellText(headerRow, expectedHeaders.size(), formatter, evaluator))) {
                throw new BusinessException("模板包含未定义的额外列");
            }
            List<ImportRow> rows = new ArrayList<>();
            for (int rowIndex = headerRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row source = sheet.getRow(rowIndex);
                if (source == null) continue;
                Map<String, String> values = new LinkedHashMap<>();
                boolean hasValue = false;
                for (int column = 0; column < expectedHeaders.size(); column++) {
                    String value = cellText(source, column, formatter, evaluator);
                    values.put(expectedHeaders.get(column), value);
                    hasValue |= StringUtils.hasText(value);
                }
                if (hasValue) rows.add(new ImportRow(rowIndex + 1, values));
                if (rows.size() > MAX_ROWS) throw new BusinessException("单次最多导入 " + MAX_ROWS + " 行数据");
            }
            if (rows.isEmpty()) throw new BusinessException("文件中没有可导入的数据行");
            return rows;
        } catch (BusinessException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new BusinessException("XLSX 文件无法读取，请确认文件未损坏且格式正确");
        }
    }

    private void importAdminClass(ImportRow row, Set<String> uniqueKeys) {
        String majorCode = required(row, "专业编码", 32);
        int gradeYear = integer(row, "入学年级", 2000, 2100);
        int classNo = integer(row, "班号", 1, 99);
        String teacherNo = optional(row, "班主任工号", 32);
        String status = enabledStatus(required(row, "状态", 20));
        Map<String, Object> major = one("SELECT major_id, major_code, major_name FROM major WHERE major_code = :value AND status = 'enabled'",
                majorCode, "专业编码不存在或已停用");
        one("SELECT grade_year FROM grade_year WHERE grade_year = :value AND status = 'enabled'", gradeYear, "入学年级不存在或已停用");
        String classCode = majorCode + "-" + gradeYear + "-" + String.format("%02d", classNo);
        unique(uniqueKeys, "admin-class:" + classCode, "文件内行政班重复：" + classCode);
        ensureAbsent("admin_class", "class_code", classCode, "行政班编码已存在：" + classCode);
        Long headTeacherId = null;
        if (StringUtils.hasText(teacherNo)) {
            headTeacherId = number(one("SELECT teacher_id FROM teacher WHERE teacher_no = :value AND status = 'active'",
                    teacherNo, "班主任工号不存在或教师已停用").get("teacher_id"));
        }
        String className = gradeYear + "级" + major.get("major_name") + classNo + "班";
        jdbc.update("""
                INSERT INTO admin_class(class_code, class_name, major_id, grade_year, head_teacher_id, status)
                VALUES (:code, :name, :majorId, :gradeYear, :teacherId, :status)
                """, new MapSqlParameterSource().addValue("code", classCode).addValue("name", className)
                .addValue("majorId", major.get("major_id")).addValue("gradeYear", gradeYear)
                .addValue("teacherId", headTeacherId, Types.BIGINT).addValue("status", status));
    }

    private void importUser(ImportRow row, Set<String> uniqueKeys, Long operatorUserId) {
        String username = required(row, "账号", 64);
        String password = required(row, "初始密码", 128);
        String displayName = required(row, "显示姓名", 100);
        String roleCode = required(row, "角色代码", 20);
        String relatedCode = optional(row, "关联身份编号", 32);
        String status = enabledStatus(required(row, "账号状态", 20));
        if (password.length() < 6) throw new BusinessException("初始密码至少需要 6 位");
        unique(uniqueKeys, "user:" + username.toLowerCase(), "文件内账号重复：" + username);
        ensureAbsent("user_account", "username", username, "账号已存在：" + username);
        Map<String, Object> role = one("""
                SELECT lr_role_id13 AS role_id, lr_role_code13 AS role_code
                FROM sht_system_roles13 WHERE lr_role_code13 = :value AND lr_status13 = 'enabled'
                """, roleCode, "角色代码不存在或已停用");
        Long relatedId = null;
        if ("STUDENT".equals(roleCode)) {
            if (!StringUtils.hasText(relatedCode)) throw new BusinessException("学生角色必须填写关联学号");
            relatedId = number(one("SELECT student_id FROM student WHERE student_no = :value", relatedCode, "关联学号不存在").get("student_id"));
        } else if ("TEACHER".equals(roleCode)) {
            if (!StringUtils.hasText(relatedCode)) throw new BusinessException("教师角色必须填写关联工号");
            relatedId = number(one("SELECT teacher_id FROM teacher WHERE teacher_no = :value", relatedCode, "关联工号不存在").get("teacher_id"));
        } else if (StringUtils.hasText(relatedCode)) {
            throw new BusinessException("管理员或自定义角色的关联身份编号必须留空");
        }
        if (relatedId != null) {
            Long used = jdbc.queryForObject("SELECT COUNT(*) FROM user_account WHERE role_code = :roleCode AND related_id = :relatedId",
                    new MapSqlParameterSource().addValue("roleCode", roleCode).addValue("relatedId", relatedId), Long.class);
            if (used != null && used > 0) throw new BusinessException("该学生或教师身份已经绑定账号");
        }
        jdbc.update("""
                INSERT INTO user_account(username, password_text, role_code, display_name, related_id, status)
                VALUES (:username, :password, :roleCode, :displayName, :relatedId, :status)
                """, new MapSqlParameterSource().addValue("username", username).addValue("password", password)
                .addValue("roleCode", roleCode).addValue("displayName", displayName)
                .addValue("relatedId", relatedId, Types.BIGINT).addValue("status", status));
        Long userId = jdbc.queryForObject("SELECT user_id FROM user_account WHERE username = :username",
                new MapSqlParameterSource("username", username), Long.class);
        jdbc.update("""
                INSERT INTO sht_user_roles13(lr_user_id13, lr_role_id13, lr_assigned_by13)
                VALUES (:userId, :roleId, :assignedBy)
                """, new MapSqlParameterSource().addValue("userId", userId).addValue("roleId", role.get("role_id"))
                .addValue("assignedBy", operatorUserId, Types.BIGINT));
    }

    private void importBuilding(ImportRow row, Set<String> uniqueKeys) {
        String code = required(row, "楼宇编码", 32);
        String name = required(row, "教学楼名称", 80);
        String campusCode = required(row, "校区编码", 32);
        int floors = integer(row, "楼层数", 1, 99);
        int rooms = integer(row, "每层教室数", 1, 999);
        int largeRooms = integer(row, "每层大教室数", 0, rooms);
        int smallCapacity = integer(row, "小教室容量", 1, 9999);
        int largeCapacity = integer(row, "大教室容量", smallCapacity, 9999);
        String status = enabledStatus(required(row, "状态", 20));
        String remark = optional(row, "备注", 500);
        unique(uniqueKeys, "building-code:" + code.toLowerCase(), "文件内楼宇编码重复：" + code);
        unique(uniqueKeys, "building-name:" + name, "文件内教学楼名称重复：" + name);
        ensureAbsent("teaching_building", "building_code", code, "楼宇编码已存在：" + code);
        ensureAbsent("teaching_building", "building_name", name, "教学楼名称已存在：" + name);
        Long campusId = number(one("SELECT campus_id FROM campus WHERE campus_code = :value AND status = 'enabled'",
                campusCode, "校区编码不存在或已停用").get("campus_id"));
        jdbc.update("""
                INSERT INTO teaching_building(building_code, building_name, campus_id, floor_count,
                    rooms_per_floor, large_room_count_per_floor, small_room_capacity,
                    large_room_capacity, status, remark)
                VALUES (:code, :name, :campusId, :floors, :rooms, :largeRooms,
                        :smallCapacity, :largeCapacity, :status, :remark)
                """, new MapSqlParameterSource().addValue("code", code).addValue("name", name)
                .addValue("campusId", campusId).addValue("floors", floors).addValue("rooms", rooms)
                .addValue("largeRooms", largeRooms).addValue("smallCapacity", smallCapacity)
                .addValue("largeCapacity", largeCapacity).addValue("status", status).addValue("remark", remark));
    }

    private void importTeachingClass(ImportRow row, Set<String> uniqueKeys) {
        String courseCode = required(row, "课程代码", 32);
        String academicYear = required(row, "学年", 20);
        int semester = integer(row, "学期", 1, 3);
        String classCode = required(row, "教学班编号", 32);
        String teacherNo = required(row, "任课教师工号", 32);
        String campusCode = required(row, "校区编码", 32);
        int capacity = integer(row, "容量", 1, 9999);
        String status = classStatus(required(row, "状态", 20));
        Map<String, Object> course = one("SELECT course_id, course_name FROM course WHERE course_code = :value AND status = 'enabled'",
                courseCode, "课程代码不存在或已停用");
        Map<String, Object> term = one("SELECT term_id, academic_year, semester FROM term WHERE academic_year = :year AND semester = :semester",
                new MapSqlParameterSource().addValue("year", academicYear).addValue("semester", semester), "学年学期不存在");
        Long teacherId = number(one("SELECT teacher_id FROM teacher WHERE teacher_no = :value AND status = 'active'",
                teacherNo, "任课教师工号不存在或已停用").get("teacher_id"));
        Long campusId = number(one("SELECT campus_id FROM campus WHERE campus_code = :value AND status = 'enabled'",
                campusCode, "校区编码不存在或已停用").get("campus_id"));
        String key = term.get("term_id") + ":" + course.get("course_id") + ":" + classCode.toLowerCase();
        unique(uniqueKeys, "teaching-class:" + key, "文件内同一课程、学期的教学班编号重复");
        Long exists = jdbc.queryForObject("""
                SELECT COUNT(*) FROM teaching_class
                WHERE term_id = :termId AND course_id = :courseId AND class_code = :classCode
                """, new MapSqlParameterSource().addValue("termId", term.get("term_id"))
                .addValue("courseId", course.get("course_id")).addValue("classCode", classCode), Long.class);
        if (exists != null && exists > 0) throw new BusinessException("同一课程、学期的教学班编号已存在");
        String yearPrefix = academicYear.length() >= 4 ? academicYear.substring(0, 4) : academicYear;
        String className = yearPrefix + String.format("%02d", semester) + course.get("course_name") + classCode + "班";
        jdbc.update("""
                INSERT INTO teaching_class(class_code, class_name, course_id, teacher_id, term_id,
                                           campus_id, capacity, selected_count, waitlist_count, status)
                VALUES (:classCode, :className, :courseId, :teacherId, :termId,
                        :campusId, :capacity, 0, 0, :status)
                """, new MapSqlParameterSource().addValue("classCode", classCode).addValue("className", className)
                .addValue("courseId", course.get("course_id")).addValue("teacherId", teacherId)
                .addValue("termId", term.get("term_id")).addValue("campusId", campusId)
                .addValue("capacity", capacity).addValue("status", status));
    }

    private void importProgramChange(ImportRow row, Set<String> uniqueKeys) {
        String studentNo = required(row, "学号", 32);
        String targetClassCode = required(row, "目标行政班编码", 32);
        String academicYear = required(row, "生效学年", 20);
        int semester = integer(row, "生效学期", 1, 3);
        String reason = required(row, "申请原因", 500);
        unique(uniqueKeys, "program-change:" + studentNo, "文件内同一学生存在多条转专业/转班申请");
        Map<String, Object> student = one("SELECT student_id, admin_class_id, grade_year FROM student WHERE student_no = :value AND status = 'active'",
                studentNo, "学号不存在或学生不是在读状态");
        Map<String, Object> targetClass = one("SELECT admin_class_id, grade_year FROM admin_class WHERE class_code = :value AND status = 'enabled'",
                targetClassCode, "目标行政班编码不存在或已停用");
        if (number(student.get("admin_class_id")).equals(number(targetClass.get("admin_class_id")))) {
            throw new BusinessException("目标行政班不能与当前行政班相同");
        }
        if (!String.valueOf(student.get("grade_year")).equals(String.valueOf(targetClass.get("grade_year")))) {
            throw new BusinessException("目标行政班必须与学生入学年级一致");
        }
        Map<String, Object> term = one("SELECT term_id FROM term WHERE academic_year = :year AND semester = :semester",
                new MapSqlParameterSource().addValue("year", academicYear).addValue("semester", semester), "生效学年学期不存在");
        Long pending = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sht_student_program_changes13
                WHERE lr_student_id13 = :studentId AND lr_status13 = 'PENDING'
                """, new MapSqlParameterSource("studentId", student.get("student_id")), Long.class);
        if (pending != null && pending > 0) throw new BusinessException("该学生已经有待审批的转专业/转班申请");
        jdbc.update("""
                INSERT INTO sht_student_program_changes13(
                    lr_student_id13, lr_from_admin_class_id13, lr_to_admin_class_id13,
                    lr_effective_term_id13, lr_reason13)
                VALUES (:studentId, :fromClassId, :toClassId, :termId, :reason)
                """, new MapSqlParameterSource().addValue("studentId", student.get("student_id"))
                .addValue("fromClassId", student.get("admin_class_id")).addValue("toClassId", targetClass.get("admin_class_id"))
                .addValue("termId", term.get("term_id")).addValue("reason", reason));
    }

    private Map<String, Object> one(String sql, Object value, String message) {
        return one(sql, new MapSqlParameterSource("value", value), message);
    }

    private Map<String, Object> one(String sql, MapSqlParameterSource params, String message) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, params);
        if (rows.isEmpty()) throw new BusinessException(message);
        return rows.get(0);
    }

    private void ensureAbsent(String table, String column, String value, String message) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = :value",
                new MapSqlParameterSource("value", value), Long.class);
        if (count != null && count > 0) throw new BusinessException(message);
    }

    private void unique(Set<String> keys, String key, String message) {
        if (!keys.add(key)) throw new BusinessException(message);
    }

    private String required(ImportRow row, String column, int maxLength) {
        String value = row.values().get(column);
        if (!StringUtils.hasText(value)) throw new BusinessException(column + "不能为空");
        if (value.length() > maxLength) throw new BusinessException(column + "不能超过 " + maxLength + " 个字符");
        return value;
    }

    private String optional(ImportRow row, String column, int maxLength) {
        String value = row.values().get(column);
        if (value != null && value.length() > maxLength) throw new BusinessException(column + "不能超过 " + maxLength + " 个字符");
        return StringUtils.hasText(value) ? value : null;
    }

    private int integer(ImportRow row, String column, int minimum, int maximum) {
        String value = required(row, column, 20);
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) throw new BusinessException(column + "必须在 " + minimum + " 到 " + maximum + " 之间");
            return parsed;
        } catch (NumberFormatException ex) {
            throw new BusinessException(column + "必须是整数");
        }
    }

    private String enabledStatus(String value) {
        return switch (value) {
            case "启用", "enabled" -> "enabled";
            case "停用", "disabled" -> "disabled";
            default -> throw new BusinessException("状态只能填写启用或停用");
        };
    }

    private String classStatus(String value) {
        return switch (value) {
            case "草稿", "draft" -> "draft";
            case "开放", "open" -> "open";
            case "关闭", "closed" -> "closed";
            default -> throw new BusinessException("状态只能填写草稿、开放或关闭");
        };
    }

    private String cellText(Row row, int column, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (row.getCell(column) == null) return "";
        return formatter.formatCellValue(row.getCell(column), evaluator).trim();
    }

    private Long number(Object value) {
        return value instanceof Number number ? number.longValue() : Long.valueOf(String.valueOf(value));
    }

    private record ImportRow(int rowNumber, Map<String, String> values) {}
}

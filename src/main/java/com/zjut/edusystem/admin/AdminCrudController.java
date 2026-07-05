package com.zjut.edusystem.admin;

import com.zjut.edusystem.common.ApiResponse;
import com.zjut.edusystem.common.BusinessException;
import com.zjut.edusystem.common.CrudDefinition;
import com.zjut.edusystem.common.CrudService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminCrudController {
    private final CrudService crudService;
    private final Map<String, CrudDefinition> definitions = new LinkedHashMap<>();

    public AdminCrudController(CrudService crudService) {
        this.crudService = crudService;
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
    public ApiResponse<Void> create(@PathVariable String resource, @Valid @RequestBody Map<String, Object> body) {
        crudService.create(definition(resource), body);
        return ApiResponse.ok("新增成功", null);
    }

    @PutMapping("/{resource}/{id}")
    public ApiResponse<Void> update(@PathVariable String resource, @PathVariable Long id, @RequestBody Map<String, Object> body) {
        crudService.update(definition(resource), id, body);
        return ApiResponse.ok("更新成功", null);
    }

    @DeleteMapping("/{resource}/{id}")
    public ApiResponse<Void> delete(@PathVariable String resource, @PathVariable Long id) {
        crudService.delete(definition(resource), id);
        return ApiResponse.ok("删除成功", null);
    }

    private CrudDefinition definition(String resource) {
        CrudDefinition definition = definitions.get(resource);
        if (definition == null) {
            throw new BusinessException("未知管理资源：" + resource);
        }
        return definition;
    }

    private void registerDefinitions() {
        definitions.put("colleges", new CrudDefinition(
                "college",
                "college_id",
                List.of("college_code", "college_name", "contact_phone", "status"),
                "SELECT * FROM college",
                "college_id DESC"
        ));
        definitions.put("majors", new CrudDefinition(
                "major",
                "major_id",
                List.of("major_code", "major_name", "college_id", "duration_years", "degree_type", "min_graduate_credit", "status"),
                "SELECT major.*, college.college_name FROM major JOIN college ON college.college_id = major.college_id",
                "major.major_id DESC"
        ));
        definitions.put("admin-classes", new CrudDefinition(
                "admin_class",
                "admin_class_id",
                List.of("class_code", "class_name", "major_id", "grade_year", "head_teacher_id", "status"),
                "SELECT ac.*, m.major_name, c.college_id, c.college_name, t.teacher_name AS head_teacher_name FROM admin_class ac JOIN major m ON m.major_id = ac.major_id JOIN college c ON c.college_id = m.college_id LEFT JOIN teacher t ON t.teacher_id = ac.head_teacher_id",
                "ac.admin_class_id DESC"
        ));
        definitions.put("students", new CrudDefinition(
                "student",
                "student_id",
                List.of("student_no", "student_name", "gender", "age", "phone", "admin_class_id", "region_id", "status"),
                "SELECT s.*, ac.class_name, m.major_name, c.college_name, r.region_name FROM student s JOIN admin_class ac ON ac.admin_class_id = s.admin_class_id JOIN major m ON m.major_id = ac.major_id JOIN college c ON c.college_id = m.college_id LEFT JOIN region r ON r.region_id = s.region_id",
                "s.student_id DESC"
        ));
        definitions.put("teachers", new CrudDefinition(
                "teacher",
                "teacher_id",
                List.of("teacher_no", "teacher_name", "gender", "age", "title", "phone", "college_id", "status"),
                "SELECT teacher.*, college.college_name FROM teacher JOIN college ON college.college_id = teacher.college_id",
                "teacher.teacher_id DESC"
        ));
        definitions.put("courses", new CrudDefinition(
                "course",
                "course_id",
                List.of("course_code", "course_name", "college_id", "credit", "hours", "exam_type", "course_type", "description", "status"),
                "SELECT course.*, college.college_name FROM course JOIN college ON college.college_id = course.college_id",
                "course.course_id DESC"
        ));
        definitions.put("teaching-classes", new CrudDefinition(
                "teaching_class",
                "teaching_class_id",
                List.of("class_code", "class_name", "course_id", "teacher_id", "term_id", "capacity", "selected_count", "waitlist_count", "status"),
                "SELECT tc.*, c.course_name, t.teacher_name, term.academic_year, term.semester FROM teaching_class tc JOIN course c ON c.course_id = tc.course_id JOIN teacher t ON t.teacher_id = tc.teacher_id JOIN term ON term.term_id = tc.term_id",
                "tc.teaching_class_id DESC"
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
    }
}

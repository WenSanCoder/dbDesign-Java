package com.zjut.edusystem.teacher;

import com.zjut.edusystem.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/teacher")
public class TeacherController {
    private final TeacherService teacherService;

    public TeacherController(TeacherService teacherService) {
        this.teacherService = teacherService;
    }

    @GetMapping("/{teacherId}/classes")
    public ApiResponse<List<Map<String, Object>>> classes(@PathVariable Long teacherId) {
        return ApiResponse.ok(teacherService.classes(teacherId));
    }

    @GetMapping("/{teacherId}/classes/{teachingClassId}/students")
    public ApiResponse<List<Map<String, Object>>> students(@PathVariable Long teacherId, @PathVariable Long teachingClassId) {
        return ApiResponse.ok(teacherService.students(teacherId, teachingClassId));
    }

    @GetMapping("/{teacherId}/classes/{teachingClassId}/grades")
    public ApiResponse<List<Map<String, Object>>> grades(@PathVariable Long teacherId, @PathVariable Long teachingClassId) {
        return ApiResponse.ok(teacherService.grades(teacherId, teachingClassId));
    }

    @PostMapping("/{teacherId}/classes/{teachingClassId}/grades")
    public ApiResponse<Void> saveGrades(@PathVariable Long teacherId, @PathVariable Long teachingClassId, @RequestBody GradeSaveRequest request) {
        teacherService.saveGrades(teacherId, teachingClassId, request.grades(), false);
        return ApiResponse.ok("成绩草稿已保存", null);
    }

    @PostMapping("/{teacherId}/classes/{teachingClassId}/grades/submit")
    public ApiResponse<Void> submitGrades(@PathVariable Long teacherId, @PathVariable Long teachingClassId, @RequestBody GradeSaveRequest request) {
        teacherService.saveGrades(teacherId, teachingClassId, request.grades(), true);
        return ApiResponse.ok("成绩已提交", null);
    }

    @GetMapping("/{teacherId}/classes/{teachingClassId}/grade-statistics")
    public ApiResponse<Map<String, Object>> gradeStatistics(@PathVariable Long teacherId, @PathVariable Long teachingClassId) {
        return ApiResponse.ok(teacherService.gradeStatistics(teacherId, teachingClassId));
    }

    public record GradeSaveRequest(List<TeacherService.GradeInput> grades) {
    }
}

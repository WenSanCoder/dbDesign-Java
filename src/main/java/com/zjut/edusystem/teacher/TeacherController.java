package com.zjut.edusystem.teacher;

import com.zjut.edusystem.common.ApiResponse;
import com.zjut.edusystem.governance.GovernanceService;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/teacher")
public class TeacherController {
    private final TeacherService teacherService;
    private final GovernanceService governanceService;

    public TeacherController(TeacherService teacherService, GovernanceService governanceService) {
        this.teacherService = teacherService;
        this.governanceService = governanceService;
    }

    @GetMapping("/{teacherId}/classes")
    public ApiResponse<List<Map<String, Object>>> classes(
            @PathVariable Long teacherId,
            @RequestParam(required = false) String academicYear,
            @RequestParam(required = false) Integer semester
    ) {
        return ApiResponse.ok(teacherService.classes(teacherId, academicYear, semester));
    }

    @GetMapping("/{teacherId}/classes/{teachingClassId}/students")
    public ApiResponse<Map<String, Object>> students(
            @PathVariable Long teacherId,
            @PathVariable Long teachingClassId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize
    ) {
        return ApiResponse.ok(teacherService.students(teacherId, teachingClassId, keyword, page, pageSize));
    }

    @GetMapping("/{teacherId}/classes/{teachingClassId}/grades")
    public ApiResponse<Map<String, Object>> grades(
            @PathVariable Long teacherId,
            @PathVariable Long teachingClassId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize
    ) {
        return ApiResponse.ok(teacherService.grades(teacherId, teachingClassId, keyword, page, pageSize));
    }

    @PostMapping("/{teacherId}/classes/{teachingClassId}/grades")
    public ApiResponse<Void> saveGrades(@PathVariable Long teacherId, @PathVariable Long teachingClassId, @RequestBody GradeSaveRequest request) {
        teacherService.saveGrades(teacherId, teachingClassId, request.grades(), false);
        return ApiResponse.ok("成绩草稿已保存", null);
    }

    @PostMapping("/{teacherId}/classes/{teachingClassId}/grades/submit")
    @Transactional
    public ApiResponse<Void> submitGrades(@PathVariable Long teacherId, @PathVariable Long teachingClassId, @RequestBody GradeSaveRequest request) {
        teacherService.saveGrades(teacherId, teachingClassId, request.grades(), true);
        governanceService.submitGradeBatch(teacherId, teachingClassId);
        return ApiResponse.ok("成绩已提交", null);
    }

    @GetMapping("/{teacherId}/classes/{teachingClassId}/grade-statistics")
    public ApiResponse<Map<String, Object>> gradeStatistics(@PathVariable Long teacherId, @PathVariable Long teachingClassId) {
        return ApiResponse.ok(teacherService.gradeStatistics(teacherId, teachingClassId));
    }

    public record GradeSaveRequest(List<TeacherService.GradeInput> grades) {
    }
}

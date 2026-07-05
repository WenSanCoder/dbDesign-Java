package com.zjut.edusystem.student;

import com.zjut.edusystem.common.ApiResponse;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/student")
public class StudentController {
    private final StudentSelectionService selectionService;

    public StudentController(StudentSelectionService selectionService) {
        this.selectionService = selectionService;
    }

    @GetMapping("/{studentId}/available-courses")
    public ApiResponse<List<Map<String, Object>>> availableCourses(@PathVariable Long studentId) {
        return ApiResponse.ok(selectionService.availableCourses(studentId));
    }

    @GetMapping("/{studentId}/selections")
    public ApiResponse<List<Map<String, Object>>> mySelections(@PathVariable Long studentId) {
        return ApiResponse.ok(selectionService.mySelections(studentId));
    }

    @GetMapping("/{studentId}/schedule")
    public ApiResponse<List<Map<String, Object>>> mySchedule(@PathVariable Long studentId) {
        return ApiResponse.ok(selectionService.mySchedule(studentId));
    }

    @GetMapping("/{studentId}/grades")
    public ApiResponse<List<Map<String, Object>>> myGrades(@PathVariable Long studentId) {
        return ApiResponse.ok(selectionService.myGrades(studentId));
    }

    @GetMapping("/{studentId}/waitlist")
    public ApiResponse<List<Map<String, Object>>> myWaitlist(@PathVariable Long studentId) {
        return ApiResponse.ok(selectionService.myWaitlist(studentId));
    }

    @PostMapping("/{studentId}/select")
    public ApiResponse<Map<String, String>> select(@PathVariable Long studentId, @RequestBody SelectionRequest request) {
        String requestId = selectionService.selectCourse(studentId, request.teachingClassId(), request.roundId());
        return ApiResponse.ok("选课成功", Map.of("requestId", requestId));
    }

    @PostMapping("/{studentId}/drop/{selectionId}")
    public ApiResponse<Void> drop(@PathVariable Long studentId, @PathVariable Long selectionId) {
        selectionService.dropSelection(studentId, selectionId);
        return ApiResponse.ok("退课成功", null);
    }

    @PostMapping("/{studentId}/waitlist")
    public ApiResponse<Void> waitlist(@PathVariable Long studentId, @RequestBody SelectionRequest request) {
        selectionService.joinWaitlist(studentId, request.teachingClassId(), request.roundId());
        return ApiResponse.ok("已加入候补队列", null);
    }

    public record SelectionRequest(@NotNull Long teachingClassId, @NotNull Long roundId) {
    }
}

package com.zjut.edusystem.governance;

import com.zjut.edusystem.common.ApiResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/governance")
public class GovernanceController {
    private final GovernanceService service;

    public GovernanceController(GovernanceService service) {
        this.service = service;
    }

    @GetMapping("/lookups")
    public ApiResponse<Map<String, Object>> lookups() {
        return ApiResponse.ok(service.lookups());
    }

    @GetMapping("/selection-rules")
    public ApiResponse<List<Map<String, Object>>> selectionRules() {
        return ApiResponse.ok(service.selectionRules());
    }

    @PostMapping("/selection-rules")
    public ApiResponse<Void> createSelectionRule(@RequestBody Map<String, Object> body) {
        service.saveSelectionRule(null, body);
        return ApiResponse.ok("选课规则已新增", null);
    }

    @PutMapping("/selection-rules/{ruleId}")
    public ApiResponse<Void> updateSelectionRule(@PathVariable Long ruleId, @RequestBody Map<String, Object> body) {
        service.saveSelectionRule(ruleId, body);
        return ApiResponse.ok("选课规则已更新", null);
    }

    @DeleteMapping("/selection-rules/{ruleId}")
    public ApiResponse<Void> deleteSelectionRule(@PathVariable Long ruleId) {
        service.deleteSelectionRule(ruleId);
        return ApiResponse.ok("选课规则已删除", null);
    }

    @PostMapping(value = "/selection-rules/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> importSelectionRules(@RequestPart("file") MultipartFile file) {
        return ApiResponse.ok("选课规则批量导入完成", service.importSelectionRules(file));
    }

    @GetMapping("/plan-adjustments")
    public ApiResponse<List<Map<String, Object>>> planAdjustments(@RequestParam(required = false) Long studentId) {
        return ApiResponse.ok(service.planAdjustments(studentId));
    }

    @PostMapping("/students/{studentId}/plan-adjustments")
    public ApiResponse<Void> createPlanAdjustment(@PathVariable Long studentId, @RequestBody Map<String, Object> body) {
        service.createPlanAdjustment(studentId, body);
        return ApiResponse.ok("培养方案调整申请已提交", null);
    }

    @GetMapping("/students/{studentId}/retake-options")
    public ApiResponse<List<Map<String, Object>>> retakeOptions(@PathVariable Long studentId) {
        return ApiResponse.ok(service.retakeOptions(studentId));
    }

    @PutMapping("/plan-adjustments/{adjustmentId}/decision")
    public ApiResponse<Void> decidePlanAdjustment(@PathVariable Long adjustmentId,
                                                   @RequestParam Long approverUserId,
                                                   @RequestBody Map<String, Object> body) {
        service.decidePlanAdjustment(adjustmentId, approverUserId, body);
        return ApiResponse.ok("培养方案调整申请已处理", null);
    }

    @GetMapping("/program-changes")
    public ApiResponse<List<Map<String, Object>>> programChanges(@RequestParam(required = false) Long studentId) {
        return ApiResponse.ok(service.programChanges(studentId));
    }

    @PostMapping("/students/{studentId}/program-changes")
    public ApiResponse<Void> createProgramChange(@PathVariable Long studentId, @RequestBody Map<String, Object> body) {
        service.createProgramChange(studentId, body);
        return ApiResponse.ok("转专业/转班申请已提交", null);
    }

    @PutMapping("/program-changes/{requestId}/decision")
    public ApiResponse<Void> decideProgramChange(@PathVariable Long requestId,
                                                  @RequestParam Long approverUserId,
                                                  @RequestBody Map<String, Object> body) {
        service.decideProgramChange(requestId, approverUserId, body);
        return ApiResponse.ok("转专业/转班申请已处理", null);
    }

    @GetMapping("/grade-batches")
    public ApiResponse<List<Map<String, Object>>> gradeBatches(@RequestParam(required = false) Long teacherId) {
        return ApiResponse.ok(service.gradeBatches(teacherId));
    }

    @PostMapping("/teachers/{teacherId}/classes/{teachingClassId}/grade-batches")
    public ApiResponse<Void> submitGradeBatch(@PathVariable Long teacherId, @PathVariable Long teachingClassId) {
        service.submitGradeBatch(teacherId, teachingClassId);
        return ApiResponse.ok("成绩审核批次已提交", null);
    }

    @PutMapping("/grade-batches/{batchId}/review")
    public ApiResponse<Void> reviewGradeBatch(@PathVariable Long batchId,
                                               @RequestParam Long reviewerUserId,
                                               @RequestBody Map<String, Object> body) {
        service.reviewGradeBatch(batchId, reviewerUserId, body);
        return ApiResponse.ok("成绩批次已审核", null);
    }

    @PostMapping("/teachers/{teacherId}/classes/{teachingClassId}/notices")
    public ApiResponse<Map<String, Long>> publishClassNotice(@PathVariable Long teacherId,
                                                             @PathVariable Long teachingClassId,
                                                             @RequestBody Map<String, Object> body) {
        return ApiResponse.ok("教学班通知已发布", Map.of("noticeId", service.publishClassNotice(teacherId, teachingClassId, body)));
    }

    @GetMapping("/students/{studentId}/notices")
    public ApiResponse<List<Map<String, Object>>> studentNotices(@PathVariable Long studentId) {
        return ApiResponse.ok(service.studentNotices(studentId));
    }

    @PutMapping("/students/{studentId}/notices/{noticeId}/read")
    public ApiResponse<Void> markNoticeRead(@PathVariable Long studentId, @PathVariable Long noticeId) {
        service.markNoticeRead(studentId, noticeId);
        return ApiResponse.ok("通知已读", null);
    }

    @GetMapping("/rbac")
    public ApiResponse<Map<String, Object>> rbacOverview() {
        return ApiResponse.ok(service.rbacOverview());
    }

    @PostMapping("/rbac/roles")
    public ApiResponse<Map<String, Long>> createRole(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok("角色已新增", Map.of("roleId", service.createRole(body)));
    }

    @PutMapping("/rbac/roles/{roleId}")
    public ApiResponse<Void> updateRole(@PathVariable Long roleId, @RequestBody Map<String, Object> body) {
        service.updateRole(roleId, body);
        return ApiResponse.ok("角色权限已更新", null);
    }

    @PostMapping("/rbac/permissions")
    public ApiResponse<Void> createPermission(@RequestBody Map<String, Object> body) {
        service.createPermission(body);
        return ApiResponse.ok("权限点已新增", null);
    }

    @PostMapping("/rbac/user-roles")
    public ApiResponse<Void> assignUserRole(@RequestBody Map<String, Object> body) {
        service.assignUserRole(body);
        return ApiResponse.ok("用户角色已分配", null);
    }

    @PostMapping("/rbac/role-permissions")
    public ApiResponse<Void> assignRolePermission(@RequestBody Map<String, Object> body) {
        service.assignRolePermission(body);
        return ApiResponse.ok("角色权限已分配", null);
    }

    @PostMapping("/rbac/data-scopes")
    public ApiResponse<Void> createDataScope(@RequestBody Map<String, Object> body) {
        service.createDataScope(body);
        return ApiResponse.ok("数据范围已分配", null);
    }

    @GetMapping("/operation-plans")
    public ApiResponse<List<Map<String, Object>>> operationPlans() {
        return ApiResponse.ok(service.operationPlans());
    }

    @PostMapping("/operation-plans")
    public ApiResponse<Map<String, String>> createOperationPlan(@RequestParam Long userId,
                                                                @RequestBody Map<String, Object> body) {
        return ApiResponse.ok("操作计划已创建", Map.of("planId", service.createOperationPlan(userId, body)));
    }

    @PutMapping("/operation-plans/{planId}/decision")
    public ApiResponse<Void> decideOperationPlan(@PathVariable String planId,
                                                  @RequestParam Long approverUserId,
                                                  @RequestBody Map<String, Object> body) {
        service.decideOperationPlan(planId, approverUserId, body);
        return ApiResponse.ok("操作计划已审批", null);
    }
}

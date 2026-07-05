-- Demo seed data for dbDesign-Java / dbDesign-Vue
-- Target: current Spring Boot + Vue implementation
-- Notes:
-- 1. This script assumes core tables already exist.
-- 2. It prefers the column names actually referenced by the current backend code.
-- 3. Execute after your schema DDL. If a few optional columns/views/triggers differ, adjust locally.

BEGIN;

-- Clean demo data in dependency order
DELETE FROM operation_log WHERE user_id IN (1, 2, 3, 4, 5);
DELETE FROM credit_summary WHERE student_id IN (1, 2, 3);
DELETE FROM course_review WHERE review_id IN (1, 2, 3, 4);
DELETE FROM notice WHERE notice_id IN (1, 2, 3, 4, 5, 6, 7, 8);
DELETE FROM grade_record WHERE grade_id IN (1, 2, 3, 4, 5, 6);
DELETE FROM selection_request_log WHERE request_id IN (
  'REQ-001', 'REQ-002', 'REQ-003', 'REQ-004', 'REQ-005', 'REQ-006'
);
DELETE FROM selection_waitlist WHERE waitlist_id IN (1, 2, 3, 4);
DELETE FROM student_course_selection WHERE selection_id IN (1, 2, 3, 4, 5, 6);
DELETE FROM class_schedule WHERE schedule_id IN (1, 2, 3, 4, 5, 6, 7, 8);
DELETE FROM teaching_class_admin_class WHERE teaching_class_id IN (1, 2, 3, 4, 5, 6);
DELETE FROM teaching_plan WHERE plan_id IN (1, 2, 3, 4, 5, 6, 7, 8);
DELETE FROM course_selection_round WHERE round_id IN (1, 2);
DELETE FROM teaching_class WHERE teaching_class_id IN (1, 2, 3, 4, 5, 6);
DELETE FROM course WHERE course_id IN (1, 2, 3, 4, 5, 6);
DELETE FROM term WHERE term_id IN (1, 2);
DELETE FROM user_account WHERE user_id IN (1, 2, 3, 4, 5);
DELETE FROM student WHERE student_id IN (1, 2, 3);
DELETE FROM teacher WHERE teacher_id IN (1, 2, 3);
DELETE FROM admin_class WHERE admin_class_id IN (1, 2, 3);
DELETE FROM major WHERE major_id IN (1, 2, 3);
DELETE FROM college WHERE college_id IN (1, 2, 3);
DELETE FROM region WHERE region_id IN (1, 2, 3);

-- Reset sequences if they exist and are owned by these tables
ALTER SEQUENCE IF EXISTS college_college_id_seq RESTART WITH 10;
ALTER SEQUENCE IF EXISTS major_major_id_seq RESTART WITH 10;
ALTER SEQUENCE IF EXISTS admin_class_admin_class_id_seq RESTART WITH 10;
ALTER SEQUENCE IF EXISTS region_region_id_seq RESTART WITH 10;
ALTER SEQUENCE IF EXISTS teacher_teacher_id_seq RESTART WITH 10;
ALTER SEQUENCE IF EXISTS student_student_id_seq RESTART WITH 10;
ALTER SEQUENCE IF EXISTS user_account_user_id_seq RESTART WITH 10;
ALTER SEQUENCE IF EXISTS term_term_id_seq RESTART WITH 10;
ALTER SEQUENCE IF EXISTS course_course_id_seq RESTART WITH 10;
ALTER SEQUENCE IF EXISTS teaching_class_teaching_class_id_seq RESTART WITH 10;
ALTER SEQUENCE IF EXISTS class_schedule_schedule_id_seq RESTART WITH 10;
ALTER SEQUENCE IF EXISTS course_selection_round_round_id_seq RESTART WITH 10;
ALTER SEQUENCE IF EXISTS teaching_plan_plan_id_seq RESTART WITH 10;
ALTER SEQUENCE IF EXISTS student_course_selection_selection_id_seq RESTART WITH 10;
ALTER SEQUENCE IF EXISTS selection_waitlist_waitlist_id_seq RESTART WITH 10;
ALTER SEQUENCE IF EXISTS grade_record_grade_id_seq RESTART WITH 10;
ALTER SEQUENCE IF EXISTS notice_notice_id_seq RESTART WITH 10;
ALTER SEQUENCE IF EXISTS course_review_review_id_seq RESTART WITH 10;

-- Base dictionaries
INSERT INTO college (college_id, college_code, college_name, contact_phone, status)
VALUES
  (1, 'CS', '计算机科学与技术学院', '0571-88880001', 'enabled'),
  (2, 'SE', '软件学院', '0571-88880002', 'enabled'),
  (3, 'MX', '马克思主义学院', '0571-88880003', 'enabled');

INSERT INTO major (major_id, major_code, major_name, college_id, duration_years, degree_type, min_graduate_credit, status)
VALUES
  (1, 'CS2025', '计算机科学与技术', 1, 4, '本科', 160, 'enabled'),
  (2, 'SE2025', '软件工程', 2, 4, '本科', 160, 'enabled'),
  (3, 'AI2025', '人工智能', 1, 4, '本科', 160, 'enabled');

INSERT INTO region (region_id, region_code, region_name)
VALUES
  (1, '330100', '浙江省杭州市'),
  (2, '330200', '浙江省宁波市'),
  (3, '320100', '江苏省南京市');

INSERT INTO teacher (teacher_id, teacher_no, teacher_name, gender, age, title, phone, college_id, status)
VALUES
  (1, 'T2025001', '陈志强', 'male', 42, '教授', '13800000001', 1, 'active'),
  (2, 'T2025002', '王敏', 'female', 37, '副教授', '13800000002', 2, 'active'),
  (3, 'T2025003', '李思远', 'male', 35, '讲师', '13800000003', 3, 'active');

INSERT INTO admin_class (admin_class_id, class_code, class_name, major_id, grade_year, head_teacher_id, status)
VALUES
  (1, 'CS2501', '计科2501班', 1, 2025, 1, 'enabled'),
  (2, 'SE2501', '软工2501班', 2, 2025, 2, 'enabled'),
  (3, 'AI2501', '人工智能2501班', 3, 2025, 1, 'enabled');

INSERT INTO student (student_id, student_no, student_name, gender, age, phone, admin_class_id, region_id, status)
VALUES
  (1, '2025001001', '张晨', 'male', 19, '15657137860', 1, 1, 'active'),
  (2, '2025001002', '林雨欣', 'female', 19, '15657137861', 1, 2, 'active'),
  (3, '2025002001', '赵天宇', 'male', 19, '15657137862', 2, 3, 'active');

-- Demo accounts used by frontend login
INSERT INTO user_account (user_id, username, password_text, role_code, display_name, related_id, status, avatar_path, last_login_at)
VALUES
  (1, 'admin', '123456', 'ADMIN', '系统管理员', NULL, 'enabled', NULL, NULL),
  (2, 'teacher1', '123456', 'TEACHER', '陈志强', 1, 'enabled', NULL, NULL),
  (3, 'teacher2', '123456', 'TEACHER', '王敏', 2, 'enabled', NULL, NULL),
  (4, 'student1', '123456', 'STUDENT', '张晨', 1, 'enabled', NULL, NULL),
  (5, 'student2', '123456', 'STUDENT', '林雨欣', 2, 'enabled', NULL, NULL);

INSERT INTO term (term_id, academic_year, semester, start_date, end_date, is_current)
VALUES
  (1, '2025/2026', 1, DATE '2025-09-08', DATE '2026-01-18', FALSE),
  (2, '2025/2026', 2, DATE '2026-02-23', DATE '2026-06-28', TRUE);

INSERT INTO course (course_id, course_code, course_name, college_id, credit, hours, exam_type, course_type, description, status)
VALUES
  (1, 'CS101', '程序设计基础', 1, 4, 64, 'exam', 'required', '面向一年级学生的程序设计入门课程，涵盖流程控制、函数、数组与基础算法。', 'enabled'),
  (2, 'CS102', '数据结构', 1, 4, 64, 'exam', 'required', '讲授线性表、树、图、查找与排序，为后续课程打下基础。', 'enabled'),
  (3, 'SE201', '数据库系统', 2, 3, 48, 'exam', 'required', '介绍数据库设计、SQL、事务、索引和典型应用开发。', 'enabled'),
  (4, 'SE202', '软件工程导论', 2, 2, 32, 'check', 'elective', '围绕需求分析、设计建模、测试和协作开发展开。', 'enabled'),
  (5, 'MX101', '思想道德与法治', 3, 3, 48, 'check', 'required', '通识课程，帮助学生建立基础法治意识与价值观。', 'enabled'),
  (6, 'AI201', '机器学习导论', 1, 3, 48, 'exam', 'elective', '介绍监督学习、模型评估与典型机器学习算法。', 'enabled');

INSERT INTO teaching_class (teaching_class_id, class_code, class_name, course_id, teacher_id, term_id, capacity, selected_count, waitlist_count, status)
VALUES
  (1, 'CS101-01', '程序设计基础-01', 1, 1, 2, 60, 2, 0, 'open'),
  (2, 'CS102-01', '数据结构-01', 2, 1, 2, 50, 1, 0, 'open'),
  (3, 'SE201-01', '数据库系统-01', 3, 2, 2, 45, 2, 1, 'open'),
  (4, 'SE202-01', '软件工程导论-01', 4, 2, 2, 30, 1, 0, 'open'),
  (5, 'MX101-01', '思想道德与法治-01', 5, 3, 2, 80, 1, 0, 'open'),
  (6, 'AI201-01', '机器学习导论-01', 6, 1, 2, 20, 0, 0, 'open');

INSERT INTO class_schedule (schedule_id, teaching_class_id, weekday, start_period, end_period, classroom, weeks)
VALUES
  (1, 1, 1, 1, 2, '屏峰校区健A-201', '1-16'),
  (2, 2, 2, 3, 4, '屏峰校区健A-305', '1-16'),
  (3, 3, 3, 1, 2, '屏峰校区健B-402', '1-16'),
  (4, 4, 4, 5, 6, '屏峰校区健B-210', '1-16'),
  (5, 5, 5, 3, 4, '屏峰校区文科楼-101', '1-16'),
  (6, 6, 2, 7, 8, '屏峰校区健A-502', '1-16'),
  (7, 3, 3, 3, 4, '屏峰校区机房-604', '1-16'),
  (8, 1, 1, 3, 4, '屏峰校区机房-301', '1-16');

INSERT INTO teaching_class_admin_class (teaching_class_id, admin_class_id)
VALUES
  (1, 1),
  (2, 1),
  (3, 1),
  (3, 2),
  (4, 2),
  (5, 1),
  (5, 2),
  (6, 3);

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema = current_schema()
      AND table_name = 'teaching_plan'
      AND column_name = 'course_nature'
  ) THEN
    IF EXISTS (
      SELECT 1
      FROM information_schema.columns
      WHERE table_schema = current_schema()
        AND table_name = 'teaching_plan'
        AND column_name = 'assessment_type'
    ) THEN
      INSERT INTO teaching_plan (plan_id, major_id, grade_year, term_id, course_id, course_nature, assessment_type)
      VALUES
        (1, 1, 2025, 2, 1, 'required', 'exam'),
        (2, 1, 2025, 2, 2, 'required', 'exam'),
        (3, 1, 2025, 2, 3, 'required', 'exam'),
        (4, 1, 2025, 2, 5, 'required', 'check'),
        (5, 2, 2025, 2, 3, 'required', 'exam'),
        (6, 2, 2025, 2, 4, 'elective', 'check'),
        (7, 2, 2025, 2, 5, 'required', 'check'),
        (8, 3, 2025, 2, 6, 'elective', 'exam');
    ELSIF EXISTS (
      SELECT 1
      FROM information_schema.columns
      WHERE table_schema = current_schema()
        AND table_name = 'teaching_plan'
        AND column_name = 'exam_type'
    ) THEN
      INSERT INTO teaching_plan (plan_id, major_id, grade_year, term_id, course_id, course_nature, exam_type)
      VALUES
        (1, 1, 2025, 2, 1, 'required', 'exam'),
        (2, 1, 2025, 2, 2, 'required', 'exam'),
        (3, 1, 2025, 2, 3, 'required', 'exam'),
        (4, 1, 2025, 2, 5, 'required', 'check'),
        (5, 2, 2025, 2, 3, 'required', 'exam'),
        (6, 2, 2025, 2, 4, 'elective', 'check'),
        (7, 2, 2025, 2, 5, 'required', 'check'),
        (8, 3, 2025, 2, 6, 'elective', 'exam');
    ELSE
      INSERT INTO teaching_plan (plan_id, major_id, grade_year, term_id, course_id, course_nature)
      VALUES
        (1, 1, 2025, 2, 1, 'required'),
        (2, 1, 2025, 2, 2, 'required'),
        (3, 1, 2025, 2, 3, 'required'),
        (4, 1, 2025, 2, 5, 'required'),
        (5, 2, 2025, 2, 3, 'required'),
        (6, 2, 2025, 2, 4, 'elective'),
        (7, 2, 2025, 2, 5, 'required'),
        (8, 3, 2025, 2, 6, 'elective');
    END IF;
  ELSE
    INSERT INTO teaching_plan (plan_id, major_id, grade_year, term_id, course_id)
    VALUES
      (1, 1, 2025, 2, 1),
      (2, 1, 2025, 2, 2),
      (3, 1, 2025, 2, 3),
      (4, 1, 2025, 2, 5),
      (5, 2, 2025, 2, 3),
      (6, 2, 2025, 2, 4),
      (7, 2, 2025, 2, 5),
      (8, 3, 2025, 2, 6);
  END IF;
END $$;

INSERT INTO course_selection_round (round_id, term_id, round_name, start_time, end_time, status, waitlist_enabled)
VALUES
  (1, 2, '第一轮选课', TIMESTAMP '2026-06-01 08:00:00', TIMESTAMP '2026-12-31 23:59:59', 'open', TRUE),
  (2, 2, '第二轮补退选', TIMESTAMP '2026-07-01 08:00:00', TIMESTAMP '2026-12-31 23:59:59', 'not_started', TRUE);

INSERT INTO student_course_selection (
  selection_id, request_id, student_id, teaching_class_id, round_id, status,
  selected_at, dropped_at, fail_reason, created_at, updated_at
)
VALUES
  (1, 'REQ-001', 1, 1, 1, 'selected', TIMESTAMP '2026-06-02 09:10:00', NULL, NULL, TIMESTAMP '2026-06-02 09:10:00', TIMESTAMP '2026-06-02 09:10:00'),
  (2, 'REQ-002', 1, 3, 1, 'selected', TIMESTAMP '2026-06-02 09:15:00', NULL, NULL, TIMESTAMP '2026-06-02 09:15:00', TIMESTAMP '2026-06-02 09:15:00'),
  (3, 'REQ-003', 1, 5, 1, 'selected', TIMESTAMP '2026-06-02 09:20:00', NULL, NULL, TIMESTAMP '2026-06-02 09:20:00', TIMESTAMP '2026-06-02 09:20:00'),
  (4, 'REQ-004', 2, 1, 1, 'selected', TIMESTAMP '2026-06-02 10:10:00', NULL, NULL, TIMESTAMP '2026-06-02 10:10:00', TIMESTAMP '2026-06-02 10:10:00'),
  (5, 'REQ-005', 2, 2, 1, 'selected', TIMESTAMP '2026-06-02 10:15:00', NULL, NULL, TIMESTAMP '2026-06-02 10:15:00', TIMESTAMP '2026-06-02 10:15:00'),
  (6, 'REQ-006', 3, 4, 1, 'selected', TIMESTAMP '2026-06-02 11:00:00', NULL, NULL, TIMESTAMP '2026-06-02 11:00:00', TIMESTAMP '2026-06-02 11:00:00');

INSERT INTO selection_waitlist (
  waitlist_id, student_id, teaching_class_id, round_id, queue_no, status, waited_at, promoted_at
)
VALUES
  (1, 3, 3, 1, 1, 'waiting', TIMESTAMP '2026-06-02 11:10:00', NULL);

INSERT INTO selection_request_log (request_id, student_id, teaching_class_id, round_id, request_status, mq_status)
VALUES
  ('REQ-001', 1, 1, 1, 'success', 'reserved'),
  ('REQ-002', 1, 3, 1, 'success', 'reserved'),
  ('REQ-003', 1, 5, 1, 'success', 'reserved'),
  ('REQ-004', 2, 1, 1, 'success', 'reserved'),
  ('REQ-005', 2, 2, 1, 'success', 'reserved'),
  ('REQ-006', 3, 4, 1, 'success', 'reserved');

INSERT INTO grade_record (
  grade_id, selection_id, student_id, teaching_class_id,
  usual_score, exam_score, final_score, grade_point,
  submitted, submitted_at, remark
)
VALUES
  (1, 1, 1, 1, 88, 92, 90.8, 4.0, TRUE, TIMESTAMP '2026-06-25 15:00:00', '程序设计基础较扎实'),
  (2, 2, 1, 3, 90, 94, 92.8, 4.2, TRUE, TIMESTAMP '2026-06-25 15:05:00', '数据库实验完成质量高'),
  (3, 3, 1, 5, 85, 89, 87.8, 3.8, TRUE, TIMESTAMP '2026-06-25 15:10:00', '课堂表现稳定'),
  (4, 4, 2, 1, 79, 84, 82.5, 3.3, TRUE, TIMESTAMP '2026-06-25 15:12:00', '基础不错'),
  (5, 5, 2, 2, 76, 80, 78.8, 3.0, TRUE, TIMESTAMP '2026-06-25 15:15:00', '需要继续加强算法训练'),
  (6, 6, 3, 4, 82, 86, 84.8, 3.5, TRUE, TIMESTAMP '2026-06-25 15:20:00', '项目分析能力较好');

-- Some schemas maintain credit_summary via trigger after grade_record insert.
-- Clear those rows again before seeding explicit summary values.
DELETE FROM credit_summary WHERE student_id IN (1, 2, 3);

INSERT INTO credit_summary (student_id, required_credits, elective_credits, total_credits, updated_at)
VALUES
  (1, 10, 0, 10, CURRENT_TIMESTAMP),
  (2, 8, 0, 8, CURRENT_TIMESTAMP),
  (3, 2, 0, 2, CURRENT_TIMESTAMP);

INSERT INTO course_review (review_id, student_id, teaching_class_id, teacher_id, rating, difficulty, workload, content, created_at)
VALUES
  (1, 1, 1, 1, 5, 3, 3, '老师讲解清楚，实验安排合理，适合大一同学入门。', TIMESTAMP '2026-06-28 18:00:00'),
  (2, 1, 3, 2, 5, 4, 4, '数据库系统课程实践性很强，收获很大。', TIMESTAMP '2026-06-28 18:05:00'),
  (3, 2, 1, 1, 4, 3, 3, '课堂节奏紧凑，代码案例比较丰富。', TIMESTAMP '2026-06-28 18:10:00'),
  (4, 3, 4, 2, 4, 2, 2, '适合做课程设计前的工程化方法入门。', TIMESTAMP '2026-06-28 18:15:00');

INSERT INTO notice (notice_id, user_id, notice_type, title, content, read_flag, created_at)
VALUES
  (1, 1, 'system', '2025/2026-2 学期开始运行', '当前学期已切换为 2025/2026 学年第二学期，请及时检查课程与轮次配置。', FALSE, TIMESTAMP '2026-02-23 08:00:00'),
  (2, 1, 'course', '第一轮选课开放', '第一轮选课已开放，请在规定时间内完成容量检查与轮次监控。', FALSE, TIMESTAMP '2026-06-01 08:00:00'),
  (3, 2, 'grade', '数据库系统成绩提交通知', '请任课教师在 2026-06-30 前完成数据库系统课程成绩提交。', FALSE, TIMESTAMP '2026-06-20 10:00:00'),
  (4, 3, 'course', '软件工程导论课程提醒', '本周课程包含课堂讨论，请提前阅读案例材料。', TRUE, TIMESTAMP '2026-06-10 09:30:00'),
  (5, 4, 'selection', '已成功选上数据库系统', '你已成功选上 数据库系统-01 教学班。', FALSE, TIMESTAMP '2026-06-02 09:16:00'),
  (6, 4, 'grade', '程序设计基础成绩已发布', '程序设计基础成绩已发布，请前往成绩页面查看。', FALSE, TIMESTAMP '2026-06-25 16:00:00'),
  (7, 5, 'grade', '数据结构成绩已发布', '数据结构成绩已发布，请前往成绩页面查看。', FALSE, TIMESTAMP '2026-06-25 16:05:00'),
  (8, 2, 'system', '教师端成绩统计可用', '你可以在成绩录入页面查看课程成绩分布与排名。', TRUE, TIMESTAMP '2026-06-25 17:00:00');

COMMIT;

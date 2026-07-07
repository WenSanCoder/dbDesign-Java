-- 2026-07-06 用户账号关联约束与公告类型字段调整
-- Execute after the base schema and avatar_path_patch.sql.

BEGIN;

-- 公告通知不再使用公告类型字段。
ALTER TABLE notice DROP COLUMN IF EXISTS notice_type;

-- 每个教师/学生业务对象最多绑定一个登录账号。
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_account_role_related
ON user_account (role_code, related_id);

-- 每个账号用户名唯一，避免教师/学生管理中改出重复登录名。
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_account_username
ON user_account (username);

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'ck_user_account_role_code'
      AND conrelid = 'user_account'::regclass
  ) THEN
    ALTER TABLE user_account
      ADD CONSTRAINT ck_user_account_role_code
      CHECK (role_code IN ('ADMIN', 'TEACHER', 'STUDENT'));
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'ck_user_account_status'
      AND conrelid = 'user_account'::regclass
  ) THEN
    ALTER TABLE user_account
      ADD CONSTRAINT ck_user_account_status
      CHECK (status IN ('enabled', 'disabled'));
  END IF;
END $$;

CREATE OR REPLACE FUNCTION check_user_account_related_id()
RETURNS TRIGGER AS $$
BEGIN
  IF NEW.role_code = 'TEACHER' THEN
    IF NEW.related_id IS NULL OR NOT EXISTS (
      SELECT 1 FROM teacher WHERE teacher_id = NEW.related_id
    ) THEN
      RAISE EXCEPTION '教师账号必须关联存在的 teacher.teacher_id';
    END IF;
  ELSIF NEW.role_code = 'STUDENT' THEN
    IF NEW.related_id IS NULL OR NOT EXISTS (
      SELECT 1 FROM student WHERE student_id = NEW.related_id
    ) THEN
      RAISE EXCEPTION '学生账号必须关联存在的 student.student_id';
    END IF;
  ELSIF NEW.role_code = 'ADMIN' THEN
    NEW.related_id := NULL;
  END IF;

  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_user_account_related_id ON user_account;
CREATE TRIGGER trg_user_account_related_id
BEFORE INSERT OR UPDATE OF role_code, related_id ON user_account
FOR EACH ROW
EXECUTE PROCEDURE check_user_account_related_id();

CREATE OR REPLACE FUNCTION block_teacher_delete_with_account()
RETURNS TRIGGER AS $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM user_account
    WHERE role_code = 'TEACHER' AND related_id = OLD.teacher_id
  ) THEN
    RAISE EXCEPTION '该教师仍有关联登录账号，请先删除或转移账号';
  END IF;

  RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION block_student_delete_with_account()
RETURNS TRIGGER AS $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM user_account
    WHERE role_code = 'STUDENT' AND related_id = OLD.student_id
  ) THEN
    RAISE EXCEPTION '该学生仍有关联登录账号，请先删除或转移账号';
  END IF;

  RETURN OLD;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_teacher_delete_account_guard ON teacher;
CREATE TRIGGER trg_teacher_delete_account_guard
BEFORE DELETE ON teacher
FOR EACH ROW
EXECUTE PROCEDURE block_teacher_delete_with_account();

DROP TRIGGER IF EXISTS trg_student_delete_account_guard ON student;
CREATE TRIGGER trg_student_delete_account_guard
BEFORE DELETE ON student
FOR EACH ROW
EXECUTE PROCEDURE block_student_delete_with_account();

COMMIT;

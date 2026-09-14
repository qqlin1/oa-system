CREATE DATABASE IF NOT EXISTS oa_system
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE oa_system;

CREATE TABLE IF NOT EXISTS sys_department
(
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '部门ID',
    name        VARCHAR(50) NOT NULL COMMENT '部门名称',
    parent_id   BIGINT      NOT NULL DEFAULT 0 COMMENT '上级部门ID，0表示根部门',
    leader_id   BIGINT               DEFAULT NULL COMMENT '部门负责人用户ID',
    status      TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1启用，0禁用',
    sort        INT         NOT NULL DEFAULT 0 COMMENT '显示顺序',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP
                                      ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    PRIMARY KEY (id),
    UNIQUE KEY uk_department_parent_name (parent_id, name),
    KEY idx_department_parent_id (parent_id),
    KEY idx_department_leader_id (leader_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '部门表';


CREATE TABLE IF NOT EXISTS sys_user
(
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    username      VARCHAR(50)  NOT NULL COMMENT '登录账号',
    password      VARCHAR(100) NOT NULL COMMENT 'BCrypt密码哈希',
    name          VARCHAR(50)  NOT NULL COMMENT '用户姓名',
    phone         VARCHAR(20)           DEFAULT NULL COMMENT '手机号',
    department_id BIGINT       NOT NULL COMMENT '所属部门ID',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1启用，0禁用',
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER' COMMENT '角色：ADMIN/USER',
    token_version INT          NOT NULL DEFAULT 0 COMMENT 'Token版本',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                                        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    PRIMARY KEY (id),
    UNIQUE KEY uk_user_username (username),
    UNIQUE KEY uk_user_phone (phone),
    KEY idx_user_department_id (department_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '用户表';


CREATE TABLE IF NOT EXISTS sys_leave
(
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '请假单ID',
    applicant_id     BIGINT       NOT NULL COMMENT '申请人ID',
    department_id    BIGINT       NOT NULL COMMENT '申请时所在部门ID',
    leave_type       VARCHAR(20)  NOT NULL COMMENT '请假类型',
    start_time       DATETIME     NOT NULL COMMENT '请假开始时间',
    end_time         DATETIME     NOT NULL COMMENT '请假结束时间',
    reason           VARCHAR(500) NOT NULL COMMENT '请假原因',
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                                 COMMENT 'PENDING/APPROVED/REJECTED/CANCELED',
    approver_id      BIGINT                DEFAULT NULL COMMENT '审批人ID',
    approval_comment VARCHAR(500)          DEFAULT NULL COMMENT '审批意见',
    approval_time    DATETIME              DEFAULT NULL COMMENT '审批时间',
    create_time      DATETIME     NOT NULL COMMENT '创建时间',
    update_time      DATETIME     NOT NULL COMMENT '更新时间',

    PRIMARY KEY (id),
    KEY idx_leave_applicant_time (applicant_id, create_time),
    KEY idx_leave_status_time (status, create_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '请假申请表';

CREATE TABLE IF NOT EXISTS sys_meeting_room
(
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '会议室ID',
    name        VARCHAR(50) NOT NULL COMMENT '会议室名称',
    capacity    INT         NOT NULL DEFAULT 0 COMMENT '可容纳人数',
    location    VARCHAR(100)          DEFAULT NULL COMMENT '位置',
    status      TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1启用，0停用',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP
                                      ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    PRIMARY KEY (id),
    UNIQUE KEY uk_room_name (name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '会议室表';


CREATE TABLE IF NOT EXISTS sys_booking
(
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '预订记录ID',
    room_id     BIGINT      NOT NULL COMMENT '会议室ID',
    user_id     BIGINT      NOT NULL COMMENT '预订人ID',
    start_time  DATETIME    NOT NULL COMMENT '预订开始时间',
    end_time    DATETIME    NOT NULL COMMENT '预订结束时间',
    status      VARCHAR(20) NOT NULL DEFAULT 'BOOKED'
                                    COMMENT 'BOOKED已预订 / CANCELED已取消',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP
                                    ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    PRIMARY KEY (id),
    KEY idx_booking_room_time (room_id, start_time, end_time),
    KEY idx_booking_user_time (user_id, start_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '会议室预订表';

CREATE TABLE IF NOT EXISTS sys_idempotent
(
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    request_id  VARCHAR(64) NOT NULL COMMENT '幂等号，前端提交前申请，重试时复用同一个',
    biz_type    VARCHAR(32) NOT NULL DEFAULT '' COMMENT '业务类型，如 LEAVE',
    biz_id      BIGINT               DEFAULT NULL COMMENT '首次处理产生的业务单号',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP
                                    ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    PRIMARY KEY (id),
    UNIQUE KEY uk_idempotent_request_id (request_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '幂等表';

CREATE TABLE IF NOT EXISTS sys_notification
(
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    user_id     BIGINT      NOT NULL COMMENT '接收通知的用户ID',
    biz_type    VARCHAR(32) NOT NULL DEFAULT '' COMMENT '业务类型，如 LEAVE',
    biz_id      BIGINT      NOT NULL COMMENT '关联的业务单号，如请假单ID',
    content     VARCHAR(500) NOT NULL COMMENT '通知内容',
    msg_id      VARCHAR(64) NOT NULL COMMENT '消息ID，靠它做消费幂等',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_msg_id (msg_id),
    KEY idx_notification_user_time (user_id, create_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '站内通知表';

CREATE TABLE IF NOT EXISTS sys_operation_log
(
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    operation   VARCHAR(64)  NOT NULL COMMENT '操作名称，来自 @AuditLog 的 value，如 审批请假',
    operator_id BIGINT       NULL COMMENT '操作人ID',
    method      VARCHAR(200) NOT NULL DEFAULT '' COMMENT '被拦截的方法，如 LeaveService.cancelLeave(..)',
    params      VARCHAR(500) NULL COMMENT '方法入参，超长截断',
    status      VARCHAR(16)  NOT NULL COMMENT '结果：成功 / 失败 / 回滚',
    error_msg   VARCHAR(500) NULL COMMENT '失败时的异常信息，超长截断',
    cost_millis BIGINT       NOT NULL DEFAULT 0 COMMENT '方法耗时（毫秒）',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    PRIMARY KEY (id),
    KEY idx_op_log_operator_time (operator_id, create_time),
    KEY idx_op_log_operation_time (operation, create_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '操作日志表';

-- ============================================================
-- RBAC 权限模型（U4）
--
-- 设计说明：
--   用户 ──(sys_user_role)── 角色 ──(sys_role_permission)── 权限
--
--   sys_user.role 字段仍然保留，但降级为「主角色」的冗余字段，只用于列表展示，
--   不再参与任何授权判断。授权的唯一来源是 sys_user_role。
--   这么做是为了避免列表查询时每次都 join 三张表。
-- ============================================================

CREATE TABLE IF NOT EXISTS sys_role
(
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    code        VARCHAR(64)  NOT NULL COMMENT '角色编码，如 ADMIN / DEPT_MANAGER',
    name        VARCHAR(64)  NOT NULL COMMENT '角色名称',
    description VARCHAR(255) NULL COMMENT '角色说明',
    data_scope  TINYINT      NOT NULL DEFAULT 4 COMMENT '数据权限：1全部 2本部门及以下 3本部门 4仅本人',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1启用 0禁用',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    PRIMARY KEY (id),
    UNIQUE KEY uk_role_code (code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '角色表';

CREATE TABLE IF NOT EXISTS sys_permission
(
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    code        VARCHAR(64) NOT NULL COMMENT '权限编码，如 leave:approve',
    name        VARCHAR(64) NOT NULL COMMENT '权限名称',
    module      VARCHAR(32) NOT NULL COMMENT '所属模块',
    sort        INT         NOT NULL DEFAULT 0 COMMENT '排序',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    PRIMARY KEY (id),
    UNIQUE KEY uk_permission_code (code),
    KEY idx_permission_module (module)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '权限表';

CREATE TABLE IF NOT EXISTS sys_user_role
(
    id          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    user_id     BIGINT   NOT NULL COMMENT '用户ID',
    role_id     BIGINT   NOT NULL COMMENT '角色ID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    PRIMARY KEY (id),
    UNIQUE KEY uk_user_role (user_id, role_id),
    KEY idx_user_role_user (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '用户角色关联表';

CREATE TABLE IF NOT EXISTS sys_role_permission
(
    id            BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    role_id       BIGINT   NOT NULL COMMENT '角色ID',
    permission_id BIGINT   NOT NULL COMMENT '权限ID',
    create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    PRIMARY KEY (id),
    UNIQUE KEY uk_role_permission (role_id, permission_id),
    KEY idx_role_permission_role (role_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '角色权限关联表';

-- ---------- 种子数据 ----------

INSERT INTO sys_role (code, name, description, data_scope)
VALUES ('ADMIN', '系统管理员', '拥有全部权限，可管理用户、部门与会议室', 1),
       ('DEPT_MANAGER', '部门经理', '可审批本部门及下属部门的请假', 2),
       ('USER', '普通员工', '可提交和查看自己的请假，可预订会议室', 4)
ON DUPLICATE KEY UPDATE name = VALUES(name),
                        description = VALUES(description),
                        data_scope = VALUES(data_scope);

INSERT INTO sys_permission (code, name, module, sort)
VALUES ('user:list', '查看用户列表', '用户', 10),
       ('user:view', '查看用户详情', '用户', 20),
       ('user:create', '创建用户', '用户', 30),
       ('user:update-status', '启用/禁用用户', '用户', 40),
       ('department:tree', '查看组织架构', '部门', 50),
       ('department:create', '创建部门', '部门', 60),
       ('department:move', '调整部门层级', '部门', 70),
       ('department:delete', '删除部门', '部门', 80),
       ('leave:create', '提交请假', '请假', 90),
       ('leave:cancel', '撤销请假', '请假', 100),
       ('leave:approve', '审批请假', '请假', 110),
       ('leave:view-pending', '查看待审批列表', '请假', 120),
       ('meeting-room:create', '创建会议室', '会议室', 130),
       ('meeting-room:list', '查看会议室列表', '会议室', 140),
       ('booking:create', '预订会议室', '会议室', 150),
       ('role:list', '查看角色列表', '权限', 160),
       ('role:manage', '分配/移除用户角色', '权限', 170),
       ('file:upload', '上传附件', '文件', 180),
       ('file:delete', '删除附件', '文件', 190)
ON DUPLICATE KEY UPDATE name = VALUES(name),
                        module = VALUES(module),
                        sort = VALUES(sort);

-- 管理员：全部权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
         CROSS JOIN sys_permission p
WHERE r.code = 'ADMIN'
ON DUPLICATE KEY UPDATE role_id = role_id;

-- 部门经理：审批相关 + 查看组织架构与会议室
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
         JOIN sys_permission p
              ON p.code IN ('leave:create', 'leave:cancel', 'leave:approve', 'leave:view-pending',
                            'department:tree', 'user:view',
                            'meeting-room:list', 'booking:create',
                            'file:upload', 'file:delete')
WHERE r.code = 'DEPT_MANAGER'
ON DUPLICATE KEY UPDATE role_id = role_id;

-- 普通员工：自己的请假 + 会议室
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
         JOIN sys_permission p
              ON p.code IN ('leave:create', 'leave:cancel', 'department:tree', 'user:view',
                            'meeting-room:list', 'booking:create',
                            'file:upload')
WHERE r.code = 'USER'
ON DUPLICATE KEY UPDATE role_id = role_id;

-- 把 sys_user.role 上的存量数据迁移到 sys_user_role
INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id
FROM sys_user u
         JOIN sys_role r ON r.code = u.role
WHERE u.role IS NOT NULL
  AND u.role <> ''
ON DUPLICATE KEY UPDATE user_id = user_id;

-- ============================================================
-- U6 多级审批流
--
-- 核心思路：把「几级审批、每级谁审」做成【配置】，而不是写死在代码里。
-- 加一级审批 = 往 sys_approval_flow 插一行，不用改代码、不用发版。
-- ============================================================

CREATE TABLE IF NOT EXISTS sys_approval_flow
(
    id                 BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    biz_type           VARCHAR(32) NOT NULL COMMENT '业务类型，如 LEAVE',
    step               INT         NOT NULL COMMENT '第几级，从 1 开始',
    name               VARCHAR(64) NOT NULL COMMENT '节点名称，如 直属主管审批',
    approver_role_code VARCHAR(64) NOT NULL COMMENT '审批人需要具备的角色编码',
    status             TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1启用 0停用',
    create_time        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    PRIMARY KEY (id),
    UNIQUE KEY uk_flow_biz_step (biz_type, step)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '审批链配置表';

CREATE TABLE IF NOT EXISTS sys_leave_approval
(
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '流水ID',
    leave_id    BIGINT       NOT NULL COMMENT '请假单ID',
    step        INT          NOT NULL COMMENT '第几级审批，从 1 开始',
    approver_id BIGINT       NOT NULL COMMENT '审批人ID',
    decision    VARCHAR(20)  NOT NULL COMMENT 'APPROVED 同意 / REJECTED 拒绝',
    comment     VARCHAR(500) NULL COMMENT '审批意见',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '审批时间',

    PRIMARY KEY (id),
    UNIQUE KEY uk_approval_leave_step (leave_id, step),
    KEY idx_approval_approver (approver_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '请假审批流水表';

-- sys_leave 加 current_step 字段（MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，用 information_schema 判断）
SET @col_exists = (SELECT COUNT(*)
                   FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE()
                     AND TABLE_NAME = 'sys_leave'
                     AND COLUMN_NAME = 'current_step');

SET @ddl = IF(@col_exists = 0,
              'ALTER TABLE sys_leave ADD COLUMN current_step INT NOT NULL DEFAULT 1 COMMENT ''当前审批到第几级''',
              'SELECT 1');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 审批链种子数据：请假走两级
--   第 1 级：直属主管（DEPT_MANAGER 角色）
--   第 2 级：部门负责人（ADMIN 角色）
INSERT INTO sys_approval_flow (biz_type, step, name, approver_role_code)
VALUES ('LEAVE', 1, '直属主管审批', 'DEPT_MANAGER'),
       ('LEAVE', 2, '部门负责人审批', 'ADMIN')
ON DUPLICATE KEY UPDATE name               = VALUES(name),
                        approver_role_code = VALUES(approver_role_code);

-- ============================================================
-- 文件上传与附件
--
-- 为什么分两张表：
--   同一个文件（比如一份公司制度 PDF）可能被很多人上传。
--   如果只有一张表、MD5 上加唯一索引，那么第二个人上传时会「秒传」命中第一个人的记录 ——
--   结果是他看到的文件名、上传人都是别人的。
--
--   所以拆成：
--     sys_file_blob —— 物理文件，按内容 MD5 去重，磁盘上只存一份
--     sys_file      —— 逻辑文件，每次上传动作一条，记录「谁传的、叫什么名字、挂在哪张单子上」
--
--   秒传的本质就是：算出 MD5 → 发现物理文件已存在 → 不再上传，只插一条逻辑记录。
-- ============================================================

CREATE TABLE IF NOT EXISTS sys_file_blob
(
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    file_md5     CHAR(32)     NOT NULL COMMENT '文件内容的 MD5，物理去重靠它',
    file_size    BIGINT       NOT NULL COMMENT '文件大小（字节）',
    content_type VARCHAR(128) NULL COMMENT 'MIME 类型',
    storage_path VARCHAR(500) NOT NULL COMMENT '相对上传根目录的存储路径',
    ref_count    INT          NOT NULL DEFAULT 1 COMMENT '被多少条逻辑文件引用',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    PRIMARY KEY (id),
    UNIQUE KEY uk_blob_md5 (file_md5)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '文件物理表（按内容去重）';

CREATE TABLE IF NOT EXISTS sys_file
(
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    blob_id     BIGINT       NOT NULL COMMENT '指向物理文件',
    file_name   VARCHAR(255) NOT NULL COMMENT '原始文件名（用户看到的名字）',
    uploader_id BIGINT       NOT NULL COMMENT '上传人ID',
    biz_type    VARCHAR(32)  NULL COMMENT '关联业务类型，如 LEAVE',
    biz_id      BIGINT       NULL COMMENT '关联业务ID',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    PRIMARY KEY (id),
    KEY idx_file_blob (blob_id),
    KEY idx_file_biz (biz_type, biz_id),
    KEY idx_file_uploader (uploader_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '文件逻辑表（每次上传一条）';

CREATE TABLE IF NOT EXISTS sys_file_chunk
(
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    upload_id   VARCHAR(64) NOT NULL COMMENT '一次分片上传会话的ID',
    chunk_index INT         NOT NULL COMMENT '分片序号，从 0 开始',
    chunk_size  INT         NOT NULL COMMENT '这个分片的大小（字节）',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',

    PRIMARY KEY (id),
    UNIQUE KEY uk_chunk_upload_index (upload_id, chunk_index),
    KEY idx_chunk_upload (upload_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '分片上传记录表';

-- End of schema

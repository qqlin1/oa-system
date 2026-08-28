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

-- End of schema

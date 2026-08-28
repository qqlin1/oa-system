# OA System

一个基于 Spring Boot 的 OA 后端学习项目，围绕用户认证、部门管理和请假审批三个核心模块，实现完整的身份认证、权限控制和业务状态流转。

## 技术栈

- Java 21
- Spring Boot 4.0.7
- Spring MVC
- MyBatis-Plus 3.5.17
- MySQL 8.x
- JJWT 0.13.0
- BCrypt
- Jakarta Validation
- Maven

## 已实现功能

### 用户与认证

- 用户创建、查询、分页和状态管理
- BCrypt 密码哈希存储
- JWT 登录认证
- JWT 过期和签名校验
- 用户禁用后旧 Token 即时失效
- 使用 `tokenVersion` 实现退出登录
- 普通用户和管理员权限区分

### 部门管理

- 创建部门
- 查询组织架构树
- 部门迁移
- 防止部门层级成环
- 删除部门前检查子部门和关联用户

### 请假审批

- 员工提交请假申请
- 查询自己的请假记录
- 管理员查询待审批列表
- 管理员批准或拒绝申请
- 员工撤销自己的待审批申请
- 使用条件更新防止重复审批以及并发状态覆盖

## 项目结构

```text
src/main/java/com/qqlin/oa
├── common          统一响应结果和分页结果
├── config          Spring MVC、MyBatis-Plus 等配置
├── controller      接收 HTTP 请求
├── dto             接收并校验前端参数
├── entity          数据库实体
├── enums           业务状态枚举
├── exception       业务异常和全局异常处理
├── interceptor     JWT 身份认证拦截器
├── mapper          MyBatis-Plus 数据访问层
├── security        JWT 生成与解析
├── service         核心业务规则
└── vo              返回给前端的数据
```

## 核心认证流程

1. 用户使用账号和密码登录。
2. 服务端通过 BCrypt 校验密码。
3. 登录成功后生成 JWT。
4. 客户端在后续请求中携带：

```http
Authorization: Bearer <token>
```

5. JWT 拦截器完成验签和过期时间检查。
6. 从可信 Claims 中读取用户 ID 和 Token 版本。
7. 查询数据库，确认用户仍然存在、处于启用状态且 Token 版本一致。
8. 将当前用户 ID 放入 Request，供 Controller 和 Service 使用。

## 请假状态流转

```text
                 ┌── APPROVED
PENDING ─────────┤
                 ├── REJECTED
                 └── CANCELED
```

只有 `PENDING` 状态的申请可以被审批或撤销。

审批和撤销使用带状态条件的更新：

```sql
UPDATE sys_leave
SET status = ?
WHERE id = ?
  AND status = 'PENDING';
```

当两个请求同时操作同一条申请时，只有第一个请求能够更新成功，后续请求影响行数为 `0`，从而避免状态被重复覆盖。

## 环境要求

- JDK 21
- MySQL 8.x
- Git

项目自带 Maven Wrapper，不要求单独安装 Maven。

## 本地启动

### 1. 克隆项目

```bash
git clone https://github.com/qqlin1/oa-system.git
cd oa-system
```

### 2. 初始化数据库

登录 MySQL：

```bash
mysql -u root -p
```

在 MySQL 客户端中执行：

```sql
SOURCE sql/schema.sql;
```

也可以使用 IDEA 的 Database Console 执行 `sql/schema.sql`。

### 3. 创建本地私密配置

复制示例文件：

```text
src/main/resources/application-local.example.properties
```

并将副本命名为：

```text
src/main/resources/application-local.properties
```

在副本中填写本机数据库密码和 JWT 密钥。

`application-local.properties` 已被 Git 忽略，不会提交到远程仓库。JWT 密钥必须是合法的 Base64 字符串，建议使用至少 32 字节的随机密钥。

### 4. 启动项目

Windows：

```powershell
.\mvnw.cmd spring-boot:run
```

macOS 或 Linux：

```bash
./mvnw spring-boot:run
```

服务默认运行在：

```text
http://localhost:8080
```

## 主要接口

### 认证接口

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| POST | `/auth/login` | 登录并返回 JWT | 公开 |
| POST | `/auth/logout` | 退出登录并使旧 Token 失效 | 已登录 |

### 用户接口

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| GET | `/users/me` | 查询当前用户 | 已登录 |
| GET | `/users/{id}` | 查询指定用户 | 本人或管理员 |
| GET | `/users` | 分页查询用户 | 管理员 |
| POST | `/users` | 创建用户 | 管理员 |
| PATCH | `/users/{id}/status` | 启用或禁用用户 | 管理员 |

### 部门接口

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| POST | `/departments` | 创建部门 | 管理员 |
| GET | `/departments/tree` | 查询组织架构树 | 管理员 |
| PATCH | `/departments/{id}/parent` | 迁移部门 | 管理员 |
| DELETE | `/departments/{id}` | 删除空部门 | 管理员 |

### 请假接口

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| POST | `/leaves` | 提交请假申请 | 已登录 |
| GET | `/leaves/me` | 查询自己的申请 | 已登录 |
| GET | `/leaves/pending` | 查询待审批申请 | 管理员 |
| PATCH | `/leaves/{id}/approval` | 批准或拒绝申请 | 管理员 |
| PATCH | `/leaves/{id}/cancel` | 撤销自己的待审批申请 | 申请人 |

## 统一响应格式

成功响应示例：

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {}
}
```

失败响应示例：

```json
{
  "code": 401,
  "message": "请先登录",
  "data": null
}
```

主要状态码：

- `400`：请求参数错误
- `401`：未登录、Token 无效或登录状态失效
- `403`：已经登录，但没有操作权限
- `404`：目标资源不存在
- `409`：业务状态冲突

## Postman 验收

项目提供 Postman 集合：

```text
postman/OA-System-Leave-Acceptance.postman_collection.json
```

导入后配置以下变量：

- `baseUrl`
- 普通用户账号和密码
- 管理员账号和密码

登录请求会自动保存 Token，后续接口会自动携带对应身份。

## 设计要点

- 使用 DTO 接收并校验输入，避免直接暴露 Entity。
- 使用 VO 控制返回字段，避免泄露密码。
- 认证与授权分离：JWT 判断“你是谁”，Service 判断“你能做什么”。
- 使用 `tokenVersion` 实现退出后旧 Token 失效。
- 部门树一次查询后在内存组装，避免递归查询数据库。
- 请假审批使用条件更新处理并发竞争。
- 根据查询条件设计联合索引，减少全表扫描和额外排序。

# AI SaaS 平台 API 开发文档（按实际开发顺序）

> Java 21 + Spring Boot 3.x + Vue 3 + Python FastAPI + LangChain + RAG + 多租户 SaaS

本文将原有 API、架构和路由文档整合成一个文件，并按照实际工程依赖关系重新排列。建议后续开发严格按照阶段推进，每完成一个小功能就提交 Git。

## 开发阶段总览

| 阶段 | 模块 | 主要目标 |
|---|---|---|
| 1 | API 规范与系统架构 | 统一约定、服务边界、Nginx 路由 |
| 2 | 认证 | 注册、登录、JWT、Spring Security |
| 3 | 用户与多租户 | 用户、租户、RBAC、租户隔离 |
| 4 | 知识库 | 知识库 CRUD |
| 5 | 文档与 AI 处理 | 上传、解析、切分、Embedding、Qdrant |
| 6 | 对话与 RAG | 检索、SSE、引用来源 |
| 7 | 简历与 JD | 简历解析、JD、匹配分析 |
| 8 | AI 面试 | 出题、回答分析、面试报告 |
| 9 | 管理后台 | 管理、统计、审计、系统配置 |

---

## 阶段 1.1：系统架构与服务边界

# 系统架构设计

## 1. 请求链路

``` text
Vue/React
   |
   | /api/v1/**
   v
Nginx
   |
   v
Spring Boot
   |
   +-- Auth / RBAC / Tenant
   +-- User
   +-- Knowledge
   +-- Document
   +-- Chat
   +-- Resume
   +-- Job
   +-- Interview
   |
   +---> MySQL
   +---> Redis
   +---> MinIO
   +---> RabbitMQ
   |
   +-- /internal/ai/v1/**
   v
FastAPI
   |
   +-- LangChain
   +-- RAG
   +-- Agent
   +-- Qdrant
   +-- LLM
```

## 2. 职责边界

### Nginx

负责：

-   TLS
-   静态资源
-   API 反向代理
-   SSE/长连接转发
-   限制上传大小
-   基础限流

### Spring Boot

负责：

-   登录认证
-   JWT
-   RBAC
-   多租户
-   用户和权限
-   业务 CRUD
-   文件元数据
-   异步任务
-   AI 调用编排
-   审计日志

### FastAPI

负责：

-   文档解析
-   文本切分
-   Embedding
-   RAG
-   Reranker
-   Prompt
-   LLM
-   Interview Agent
-   Resume AI Analysis

## 3. 不建议

不建议：

``` text
Browser -> Python
Browser -> Java
```

两套 API 同时暴露给前端会导致：

-   Token/权限逻辑重复
-   租户隔离复杂
-   前端环境配置复杂
-   AI 服务暴露面增大

推荐：

``` text
Browser -> Nginx -> Java -> Python
```


---

## 阶段 1.2：Nginx 路由与服务访问

# Nginx 路由规范

## 推荐配置

``` nginx
server {
    listen 80;
    server_name your-domain.com;

    client_max_body_size 50m;

    location / {
        root /usr/share/nginx/html;
        try_files $uri $uri/ /index.html;
    }

    location /api/ {
        proxy_pass http://java-backend:8080;
        proxy_http_version 1.1;

        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location /api/v1/chat/stream {
        proxy_pass http://java-backend:8080;
        proxy_http_version 1.1;

        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 300s;

        proxy_set_header Connection '';
    }

    location /ws/ {
        proxy_pass http://java-backend:8080;
        proxy_http_version 1.1;

        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }

    # AI Service 不建议对公网开放。
    # 如果必须经过 Nginx，仅允许内网/IP 白名单访问。
    location /internal/ai/ {
        deny all;
        return 403;
    }
}
```

## Docker Compose 网络

建议：

``` text
frontend/nginx
      |
      +-- java-backend
               |
               +-- ai-service
               +-- mysql
               +-- redis
               +-- qdrant
               +-- rabbitmq
               +-- minio
```

Java 调 Python 使用：

`http://ai-service:8000`

而不是：

`http://localhost:8000`

## SSE 注意事项

Nginx 对流式 AI 输出必须关闭 buffering，并提高 read timeout。

前端推荐使用 `fetch()` + `ReadableStream`，因为 POST SSE 更容易携带复杂
JSON 请求体。


---

## 阶段 1.3：API 规范

# API 通用规范

## 1. Base URL

开发环境：

`http://localhost/api/v1`

生产环境：

`https://your-domain.com/api/v1`

前端代码不应该写死 Java 服务地址。

## 2. HTTP 方法

  方法     用途
  -------- --------------------------
  GET      查询
  POST     创建、执行动作、复杂查询
  PUT      整体更新
  PATCH    局部更新
  DELETE   删除

## 3. Header

``` http
Authorization: Bearer <accessToken>
Content-Type: application/json
X-Request-Id: 8f2a1c
```

`X-Request-Id` 可由前端生成；如果没有，Nginx/Java 生成 traceId。

## 4. 统一响应

成功：

``` json
{
  "code": 0,
  "message": "success",
  "data": {},
  "traceId": "7f82a1"
}
```

失败：

``` json
{
  "code": 40001,
  "message": "参数校验失败",
  "data": null,
  "traceId": "7f82a1"
}
```

## 5. 分页

请求：

``` text
GET /api/v1/users?page=1&pageSize=20
```

响应：

``` json
{
  "code": 0,
  "message": "success",
  "data": {
    "page": 1,
    "pageSize": 20,
    "total": 125,
    "records": []
  },
  "traceId": "abc123"
}
```

## 6. 时间

统一使用 ISO-8601：

`2026-09-30T14:30:00+08:00`

数据库建议保存 `DATETIME`，API 层统一序列化。

## 7. ID

数据库主键建议使用 `BIGINT` 或 UUID。API JSON 中如果使用 BIGINT，前端
TypeScript 项目建议按 `string` 接收，避免 JavaScript 精度问题。

## 8. 文件上传

使用：

``` http
Content-Type: multipart/form-data
```

示例字段：

-   `file`: MultipartFile
-   `knowledgeBaseId`: Long
-   `description`: String

## 9. SSE 流式响应

AI 对话推荐：

``` http
POST /api/v1/chat/stream
Accept: text/event-stream
```

事件：

``` text
event: token
data: {"content":"Spring"}

event: token
data: {"content":" Boot"}

event: source
data: {"documentId":"10001","pageNo":12}

event: done
data: {"messageId":"90001"}
```

## 10. 状态码

    HTTP 含义
  ------ --------------------
     200 成功
     201 创建成功
     204 删除成功且无响应体
     400 请求参数错误
     401 未认证
     403 无权限
     404 资源不存在
     409 资源冲突
     413 文件过大
     422 业务参数无法处理
     429 请求过于频繁
     500 服务内部错误
     502 AI/下游服务异常
     504 AI/下游服务超时


---

## 阶段 2：认证接口

# 认证接口

## POST /api/v1/auth/login

登录。

Request:

``` json
{
  "username": "admin",
  "password": "123456"
}
```

  字段       类型     必填
  ---------- -------- ------
  username   String   是
  password   String   是

Response:

``` json
{
  "code": 0,
  "message": "success",
  "data": {
    "accessToken": "eyJ...",
    "refreshToken": "eyJ...",
    "expiresIn": 7200,
    "user": {
      "id": "10001",
      "username": "admin",
      "nickname": "管理员",
      "role": "TENANT_ADMIN"
    }
  },
  "traceId": "abc123"
}
```

## POST /api/v1/auth/refresh

刷新 Access Token。

Request:

``` json
{
  "refreshToken": "eyJ..."
}
```

## POST /api/v1/auth/logout

退出登录。

## GET /api/v1/auth/me

获取当前登录用户。

Response:

``` json
{
  "code": 0,
  "message": "success",
  "data": {
    "id": "10001",
    "username": "admin",
    "nickname": "管理员",
    "tenantId": "20001",
    "roles": ["TENANT_ADMIN"],
    "permissions": [
      "knowledge:read",
      "knowledge:write"
    ]
  },
  "traceId": "abc123"
}
```

## POST /api/v1/auth/password

修改当前用户密码。

Request:

``` json
{
  "oldPassword": "old",
  "newPassword": "new"
}
```


---

## 阶段 3：用户与租户接口

# 用户与租户接口

## 用户

### GET /api/v1/users

查询租户用户。

Query:

  参数       类型      必填   默认
  ---------- --------- ------ ------
  page       Integer   否     1
  pageSize   Integer   否     20
  keyword    String    否     \-
  status     String    否     \-

### GET /api/v1/users/{id}

Path:

`id: String`

### POST /api/v1/users

``` json
{
  "username": "zhangsan",
  "nickname": "张三",
  "email": "test@example.com",
  "roleIds": ["3"]
}
```

### PUT /api/v1/users/{id}

更新用户。

### DELETE /api/v1/users/{id}

删除用户。

## 租户

### GET /api/v1/tenant

获取当前租户。

### PUT /api/v1/tenant

``` json
{
  "name": "AI 学习空间",
  "logo": "https://...",
  "description": "团队知识库"
}
```

### GET /api/v1/tenant/members

查询租户成员。

### POST /api/v1/tenant/members/invite

``` json
{
  "email": "user@example.com",
  "roleId": "3"
}
```


---

## 阶段 4：知识库接口

# 知识库接口

## POST /api/v1/knowledge-bases

创建知识库。

``` json
{
  "name": "Java 学习知识库",
  "description": "Java、Spring Boot、MySQL"
}
```

Response:

``` json
{
  "code": 0,
  "message": "success",
  "data": {
    "id": "30001",
    "name": "Java 学习知识库",
    "description": "Java、Spring Boot、MySQL",
    "documentCount": 0,
    "status": "ACTIVE",
    "createdAt": "2026-09-30T14:30:00+08:00"
  },
  "traceId": "abc123"
}
```

## GET /api/v1/knowledge-bases

Query:

-   `page: Integer`
-   `pageSize: Integer`
-   `keyword: String`
-   `status: String`

## GET /api/v1/knowledge-bases/{id}

获取知识库详情。

## PUT /api/v1/knowledge-bases/{id}

``` json
{
  "name": "Java 后端知识库",
  "description": "更新后的描述"
}
```

## DELETE /api/v1/knowledge-bases/{id}

删除知识库。

## GET /api/v1/knowledge-bases/{id}/stats

返回：

``` json
{
  "documentCount": 32,
  "chunkCount": 1840,
  "completedDocuments": 30,
  "failedDocuments": 2
}
```


---

## 阶段 5.1：文档接口

# 文档接口

## POST /api/v1/knowledge-bases/{knowledgeBaseId}/documents

上传文档。

Content-Type:

`multipart/form-data`

参数：

  字段          类型            必填
  ------------- --------------- ------
  file          MultipartFile   是
  description   String          否

支持：

-   PDF
-   DOCX
-   TXT
-   Markdown
-   HTML

Response:

``` json
{
  "code": 0,
  "message": "uploaded",
  "data": {
    "documentId": "40001",
    "fileName": "spring-boot.pdf",
    "status": "UPLOADED"
  },
  "traceId": "abc123"
}
```

## GET /api/v1/knowledge-bases/{knowledgeBaseId}/documents

Query：

-   `page: Integer`
-   `pageSize: Integer`
-   `keyword: String`
-   `status: String`

## GET /api/v1/documents/{id}

获取文档详情及处理状态。

## GET /api/v1/documents/{id}/status

Response:

``` json
{
  "documentId": "40001",
  "status": "VECTOR_INDEXED",
  "progress": 90,
  "errorMessage": null
}
```

状态：

`UPLOADED -> PARSING -> PARSED -> CHUNKING -> CHUNKED -> EMBEDDING -> VECTOR_INDEXED -> COMPLETED`

失败：

`FAILED`

## DELETE /api/v1/documents/{id}

删除文档及对应向量。

## POST /api/v1/documents/{id}/retry

重新处理失败文档。

## GET /api/v1/documents/{id}/download

获取临时下载地址或执行文件下载。


---

## 阶段 5.2：AI 内部服务接口

# AI 内部接口

> 这些接口供 Spring Boot 调用 FastAPI，默认不允许浏览器直接访问。

Base：

`http://ai-service:8000/internal/ai/v1`

建议通过内部网络 + `X-Service-Token` 或 mTLS 保护。

## POST /internal/ai/v1/documents/parse

Request:

``` json
{
  "documentId": "40001",
  "fileUrl": "s3://bucket/xxx.pdf",
  "fileType": "PDF"
}
```

Response:

``` json
{
  "code": 0,
  "message": "success",
  "data": {
    "text": "...",
    "pageCount": 20
  },
  "traceId": "abc123"
}
```

## POST /internal/ai/v1/documents/chunk

``` json
{
  "documentId": "40001",
  "text": "...",
  "chunkSize": 800,
  "chunkOverlap": 150
}
```

## POST /internal/ai/v1/embeddings

``` json
{
  "texts": [
    "Spring Boot 是..."
  ],
  "model": "text-embedding-model"
}
```

## POST /internal/ai/v1/rag/search

``` json
{
  "tenantId": "20001",
  "userId": "10001",
  "knowledgeBaseId": "30001",
  "query": "什么是 Spring Boot 自动配置？",
  "topK": 10,
  "enableRerank": true
}
```

返回检索结果：

``` json
{
  "results": [
    {
      "documentId": "40001",
      "chunkId": "50001",
      "content": "...",
      "score": 0.9231,
      "pageNo": 12
    }
  ]
}
```

## POST /internal/ai/v1/rag/chat

``` json
{
  "tenantId": "20001",
  "userId": "10001",
  "knowledgeBaseId": "30001",
  "conversationId": "90001",
  "query": "解释 Spring Bean 生命周期",
  "topK": 8,
  "enableRerank": true
}
```

返回：

``` json
{
  "answer": "...",
  "sources": [],
  "usage": {
    "promptTokens": 1200,
    "completionTokens": 600,
    "totalTokens": 1800
  }
}
```

## POST /internal/ai/v1/resumes/parse

输入文件 URL，输出结构化 Resume JSON。

## POST /internal/ai/v1/resumes/analyze

输入 Resume JSON + Job JSON，输出分析结果。

## POST /internal/ai/v1/interviews/generate

生成第一道或下一道面试题。

## POST /internal/ai/v1/interviews/analyze

分析用户回答并生成下一题。

## POST /internal/ai/v1/interviews/report

生成最终面试报告。

## 内部接口原则

Python 服务不得自行决定：

-   当前用户是谁
-   当前租户是谁
-   用户是否有权限
-   用户是否可以访问某知识库

这些权限判断由 Java 完成。

Python 只处理 Java 已授权的数据和 AI 任务。


---

## 阶段 6：对话与 RAG 接口

# 对话与 RAG 接口

## POST /api/v1/conversations

创建会话。

``` json
{
  "knowledgeBaseId": "30001",
  "title": "Spring Boot 学习"
}
```

## GET /api/v1/conversations

Query:

-   `page: Integer`
-   `pageSize: Integer`
-   `knowledgeBaseId: String`

## GET /api/v1/conversations/{id}

获取会话。

## DELETE /api/v1/conversations/{id}

删除会话。

## POST /api/v1/conversations/{id}/messages

非流式 AI 问答。

``` json
{
  "message": "Spring Boot 自动配置原理是什么？",
  "topK": 8,
  "enableRerank": true
}
```

Response:

``` json
{
  "code": 0,
  "message": "success",
  "data": {
    "messageId": "90001",
    "answer": "Spring Boot 自动配置主要通过条件装配机制...",
    "sources": [
      {
        "documentId": "40001",
        "documentName": "spring-boot.pdf",
        "chunkId": "50001",
        "pageNo": 12,
        "score": 0.9231
      }
    ]
  },
  "traceId": "abc123"
}
```

## POST /api/v1/conversations/{id}/messages/stream

SSE 流式回答。

Request:

``` json
{
  "message": "解释一下 Spring Bean 生命周期",
  "topK": 8,
  "enableRerank": true
}
```

事件类型：

-   `token`
-   `source`
-   `error`
-   `done`

## POST /api/v1/rag/search

仅执行检索，不调用 LLM。

``` json
{
  "knowledgeBaseId": "30001",
  "query": "Redis 缓存击穿",
  "topK": 10,
  "enableRerank": true
}
```

## POST /api/v1/rag/feedback

记录用户对回答的反馈。

``` json
{
  "messageId": "90001",
  "rating": "UP",
  "reason": "回答准确"
}
```


---

## 阶段 7.1：简历接口

# 简历接口

## POST /api/v1/resumes

上传简历。

`multipart/form-data`

字段：

-   `file: MultipartFile`
-   `resumeName: String`

## GET /api/v1/resumes

分页查询当前用户简历。

## GET /api/v1/resumes/{id}

获取简历详情。

## DELETE /api/v1/resumes/{id}

删除简历。

## POST /api/v1/resumes/{id}/parse

重新解析简历。

## GET /api/v1/resumes/{id}/parsed

获取结构化简历：

``` json
{
  "basic": {
    "name": "张三",
    "email": "test@example.com"
  },
  "education": [],
  "workExperience": [],
  "projects": [],
  "skills": [
    {
      "name": "Java",
      "level": "ADVANCED",
      "years": 3
    }
  ]
}
```

## POST /api/v1/resumes/{id}/optimize

``` json
{
  "jobId": "70001",
  "target": "PROJECT_EXPERIENCE"
}
```

返回 AI 优化建议，不直接覆盖原简历。


---

## 阶段 7.2：JD 与匹配接口

# JD 与简历匹配接口

## POST /api/v1/jobs

创建职位。

``` json
{
  "jobTitle": "Java 后端开发工程师",
  "companyName": "Example",
  "jobDescription": "负责 Spring Boot 后端开发..."
}
```

## GET /api/v1/jobs

分页查询职位。

## GET /api/v1/jobs/{id}

职位详情。

## PUT /api/v1/jobs/{id}

更新职位。

## DELETE /api/v1/jobs/{id}

删除职位。

## POST /api/v1/jobs/{id}/parse

解析 JD。

返回：

``` json
{
  "skills": [
    {
      "name": "Java",
      "priority": "REQUIRED"
    },
    {
      "name": "Spring Boot",
      "priority": "REQUIRED"
    }
  ],
  "experienceYears": 3,
  "education": "本科",
  "responsibilities": []
}
```

## POST /api/v1/resume-job-analysis

Request:

``` json
{
  "resumeId": "60001",
  "jobId": "70001"
}
```

Response:

``` json
{
  "analysisId": "80001",
  "matchedSkills": [],
  "missingSkills": [],
  "evidence": [],
  "recommendations": []
}
```

说明：系统应返回具体匹配依据和缺口，不建议仅返回一个无法解释的百分比。


---

## 阶段 8：AI 面试接口

# AI 面试接口

## POST /api/v1/interviews

创建面试。

``` json
{
  "resumeId": "60001",
  "jobId": "70001",
  "interviewType": "TECHNICAL"
}
```

## GET /api/v1/interviews

查询历史面试。

## GET /api/v1/interviews/{id}

获取面试详情。

## POST /api/v1/interviews/{id}/start

开始面试。

Response:

``` json
{
  "questionId": "81001",
  "questionNo": 1,
  "question": "请介绍一下你在项目中使用 Redis 的场景。",
  "questionType": "PROJECT"
}
```

## POST /api/v1/interviews/{id}/answers

提交回答。

``` json
{
  "questionId": "81001",
  "answer": "我们使用 Redis 做缓存..."
}
```

Response:

``` json
{
  "analysis": {
    "technical": {
      "score": 0.82,
      "strengths": [],
      "weaknesses": []
    },
    "communication": {
      "clarity": 0.78
    }
  },
  "nextQuestion": {
    "questionId": "81002",
    "question": "如果出现缓存击穿，你会如何处理？",
    "questionType": "TECHNICAL"
  }
}
```

## POST /api/v1/interviews/{id}/finish

结束面试。

## GET /api/v1/interviews/{id}/report

获取最终报告：

-   technicalAnalysis
-   projectAnalysis
-   communicationAnalysis
-   knowledgeGaps
-   recommendations


---

## 阶段 9：管理后台接口

# 管理后台接口

管理员接口统一使用：

`/api/v1/admin/**`

## Dashboard

### GET /api/v1/admin/dashboard

返回：

``` json
{
  "tenantCount": 12,
  "userCount": 356,
  "knowledgeBaseCount": 89,
  "documentCount": 2300,
  "aiRequestCount": 18500,
  "tokenUsage": 12500000
}
```

## 用户管理

-   `GET /api/v1/admin/users`
-   `GET /api/v1/admin/users/{id}`
-   `PUT /api/v1/admin/users/{id}/status`
-   `DELETE /api/v1/admin/users/{id}`

## 角色权限

-   `GET /api/v1/admin/roles`
-   `POST /api/v1/admin/roles`
-   `PUT /api/v1/admin/roles/{id}`
-   `DELETE /api/v1/admin/roles/{id}`
-   `GET /api/v1/admin/permissions`

## 租户管理

平台管理员：

-   `GET /api/v1/admin/tenants`
-   `POST /api/v1/admin/tenants`
-   `GET /api/v1/admin/tenants/{id}`
-   `PUT /api/v1/admin/tenants/{id}`
-   `PUT /api/v1/admin/tenants/{id}/status`

## AI 配置

-   `GET /api/v1/admin/ai/models`
-   `POST /api/v1/admin/ai/models`
-   `PUT /api/v1/admin/ai/models/{id}`
-   `DELETE /api/v1/admin/ai/models/{id}`

API Key 不应在列表接口中返回明文。

## Prompt 管理

-   `GET /api/v1/admin/prompts`
-   `POST /api/v1/admin/prompts`
-   `PUT /api/v1/admin/prompts/{id}`
-   `POST /api/v1/admin/prompts/{id}/publish`
-   `POST /api/v1/admin/prompts/{id}/rollback`

## AI 用量

-   `GET /api/v1/admin/usage`
-   `GET /api/v1/admin/usage/daily`
-   `GET /api/v1/admin/usage/models`

## 审计日志

`GET /api/v1/admin/audit-logs`

字段：

-   userId
-   tenantId
-   action
-   resource
-   requestId
-   ip
-   createdAt


---

## 附录：错误码

# 错误码

     Code 含义
  ------- ----------------
        0 成功
    40001 参数校验失败
    40002 请求格式错误
    40101 未登录
    40102 Token 已过期
    40301 无权限
    40302 无权访问该租户
    40401 用户不存在
    40402 资源不存在
    40403 知识库不存在
    40404 文档不存在
    40901 用户已存在
    40902 资源状态冲突
    41301 文件过大
    42201 文件类型不支持
    42901 请求过于频繁
    50001 系统内部异常
    50201 AI 服务异常
    50202 LLM 调用失败
    50203 向量数据库异常
    50401 AI 服务超时

业务错误响应：

``` json
{
  "code": 40301,
  "message": "无权访问该资源",
  "data": null,
  "traceId": "abc123"
}
```


---

# 实际编码路线图

## 第 1 步：项目初始化

- 创建 Spring Boot 多模块工程。
- 配置 MySQL、Redis、RabbitMQ、MinIO、Qdrant。
- 创建统一响应体、异常体系、日志和 traceId。
- 先让 Java 服务稳定启动，不急着开发 RAG。

## 第 2 步：认证

1. User Entity
2. 注册 API
3. 登录 API
4. JWT Access Token
5. Spring Security Filter
6. 当前用户上下文 `userId`

## 第 3 步：多租户与权限

1. Tenant Entity
2. TenantContext
3. 从认证信息获取 `tenantId` / `userId`
4. RBAC
5. 所有租户数据强制带 `tenant_id`
6. 跨租户访问测试

> **关键安全原则：** 普通前端请求不要提供 `tenantId`。Java 应从经过认证的 SecurityContext / TenantContext 获取 `tenantId`，再写入数据库或传给 Python AI 服务。

## 第 4 步：知识库

- 创建知识库
- 查询知识库
- 修改知识库
- 删除知识库
- 验证租户隔离

## 第 5 步：文档处理流水线

```text
上传文件
  ↓
MinIO / S3
  ↓
MySQL document 元数据
  ↓
RabbitMQ
  ↓
Python AI Service
  ↓
Parse → Chunk → Embedding
  ↓
Qdrant
  ↓
更新 document 状态
```

推荐状态：

```text
UPLOADED
  → PARSING
  → PARSED
  → CHUNKING
  → CHUNKED
  → EMBEDDING
  → VECTOR_INDEXED
  → COMPLETED
```

失败进入 `FAILED`，并保留失败原因。

## 第 6 步：RAG 对话

```text
前端
 ↓
Nginx
 ↓
Spring Boot
 ↓
Python AI Service
 ↓
Query Rewrite
 ↓
Embedding
 ↓
Qdrant
 ↓
Reranker（后续增强）
 ↓
Prompt
 ↓
LLM
 ↓
SSE
 ↓
前端
```

Qdrant 检索必须至少考虑：

- `tenant_id`
- `knowledge_base_id`
- 必要时 `document_id`

## 第 7 步：求职助手

```text
简历上传
 ↓
AI 解析
 ↓
结构化 Resume JSON
 ↓
MySQL

JD
 ↓
AI 解析
 ↓
技能 / 经验 / 学历 / 职责
 ↓
MySQL

Resume + JD
 ↓
AI 分析
 ↓
匹配项 / 缺失项 / 改进建议
```

## 第 8 步：AI 面试

```text
简历 + JD
 ↓
创建面试
 ↓
AI 动态生成问题
 ↓
用户回答
 ↓
AI 分析回答
 ↓
面试报告
```

## 第 9 步：管理后台

最后再集中完善：

- 用户管理
- 租户管理
- 知识库/文档管理
- AI 使用量统计
- 操作审计
- 系统配置

# 多租户开发必须遵守的规则

1. 普通前端 Request DTO 不提供 `tenantId`。
2. `tenantId` 从认证后的 SecurityContext / TenantContext 获取。
3. `userId` 从认证上下文获取，不接受前端伪造。
4. 租户数据表保存 `tenant_id`。
5. 查询、修改、删除都必须做租户范围校验。
6. Qdrant 检索必须带租户过滤。
7. Java 调 Python 时，由 Java 注入已经授权的 `tenantId` / `userId`。
8. Python AI Service 不负责决定用户是否有权限访问租户。
9. SUPER_ADMIN 的跨租户操作必须走明确的后台权限流程。
10. 编写跨租户读取、修改、删除的自动化测试。

# 推荐 Git 提交节奏

```text
chore: initialize spring boot project
chore: add mysql configuration
feat: add user entity
feat: add user registration api
feat: add login api
feat: add jwt authentication
feat: add tenant context
feat: add tenant isolation
test: add cross tenant access tests
feat: add knowledge base api
feat: add document upload
feat: add document async processing
feat: add qdrant vector storage
feat: add rag search
feat: add streaming chat
feat: add resume parser
feat: add job matching
feat: add ai interview
feat: add admin api
```

这样 GitHub 上的 commit 历史本身就是你的项目开发日志。

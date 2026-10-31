-- ============================================================
-- MindForge AI —— 数据库 schema（空库建库，唯一权威）
--
-- 【这是什么】
--   建库用的完整脚本：33 张表 + 角色权限种子数据。
--   在空库上执行一次，就能得到和当前开发库完全一致的结构。
--
-- 【用法】
--   mysql -h 127.0.0.1 -P 3306 -uroot -p < mindforge-schema.sql
--
-- 【两个文件的分工】
--   本文件        —— 只负责「空库一键建库」，整份可以直接执行
--   mindforge-增量变更.sql —— 只负责「老库升级」，按时间倒序记录每条 ALTER
--   两边不重叠：本文件里没有 ALTER，增量文件里没有建表语句（除了建表类的变更）。
--
-- 【约定】
--   · 字符集只在【库】这层指定（utf8mb4 / utf8mb4_0900_ai_ci），建表不写 CHARSET/COLLATE
--   · 所有表【没有外键】，关联全在代码里 —— 删除必须手动清理关联表，同一个事务
--   · 逻辑删除用 deleted 列；没有 deleted 列的表只能物理删
--   · 自定义 SQL 要手动过滤 deleted = 0（MyBatis-Plus 的逻辑删除只管它自己生成的 SQL）
--
-- ⚠️ 【以后改结构怎么改】
--     ① 本文件对应的 CREATE TABLE —— 保证「空库一键建」永远是对的
--     ② mindforge-增量变更.sql 追加一条 —— 写清改了什么、为什么，给落后的库对照用
--   两处都要改。只改建表语句的话，已经上线的库永远补不上；
--   只改增量的话，新库建出来就是旧结构。
--
-- 【历史文件（已被取代，仅留档，不要再用）】
--   mindforge-schema-双前端单后端-RBAC.sql   早期设计稿：用 sys_user、带物理外键，与实际库不一致
--   mindforge-业务表建表语句.sql              只含其中 11 张表
-- ============================================================

CREATE DATABASE IF NOT EXISTS `mindforge`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE `mindforge`;


-- ============================================================
-- 一、用户与权限（6 张）
-- ============================================================

CREATE TABLE `user` (
  `id` bigint NOT NULL COMMENT '用户id',
  `username` varchar(32) NOT NULL COMMENT '用户名',
  `email` varchar(64) DEFAULT NULL COMMENT '用户邮箱',
  `email_verified` tinyint NOT NULL DEFAULT '0' COMMENT '邮箱是否已验证：0未验证 1已验证',
  `phone` varchar(16) DEFAULT NULL COMMENT '手机号',
  `password_hash` varchar(255) NOT NULL COMMENT '加密后密码',
  `nickname` varchar(64) DEFAULT NULL COMMENT '用户昵称',
  `avatar_url` varchar(500) DEFAULT NULL COMMENT '头像url',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1正常 0禁用',
  `last_login_at` datetime DEFAULT NULL COMMENT '最后登陆时间',
  `last_login_ip` varchar(64) DEFAULT NULL COMMENT '最后登录IP（只记成功登录，长度对齐 sys_audit_log.ip_address）',
  `reminder_enabled` tinyint NOT NULL DEFAULT '1' COMMENT '是否接收日程邮件提醒：0关闭 1开启',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '账号是否已删除(逻辑)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_username` (`username`),
  UNIQUE KEY `uk_user_email` (`email`),
  -- 手机号只在【活跃账号】之间唯一：函数索引把已删除行映射成 NULL，
  -- NULL 不参与唯一性 → 账号注销后号码可以被新用户复用（号码是运营商资源）。
  -- 注意这和 uk_user_email 的口径刻意不同：邮箱是永久占用（防冒名）。
  UNIQUE KEY `uk_user_phone` ((CASE WHEN `deleted` = 0 THEN `phone` ELSE NULL END)),
  KEY `idx_user_status` (`status`)
) ENGINE=InnoDB COMMENT='用户表';

CREATE TABLE `sys_role` (
  `id` bigint NOT NULL COMMENT '不同角色(role)id',
  `name` varchar(64) NOT NULL COMMENT '角色(role),用户、管理员',
  `code` varchar(64) NOT NULL COMMENT '角色对应code',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1启用 0禁用',
  `description` varchar(255) DEFAULT NULL COMMENT '角色描述',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '角色是否已删除(逻辑)',
  `level` int NOT NULL DEFAULT '0' COMMENT '角色层级，数值越大权限越高。垂直越权防护：不能操作层级 >= 自己的角色/用户',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_code` (`code`),
  KEY `idx_role_status` (`status`)
) ENGINE=InnoDB COMMENT='角色表';

CREATE TABLE `sys_permission` (
  `id` bigint NOT NULL,
  `name` varchar(64) NOT NULL,
  `code` varchar(128) NOT NULL,
  `type` varchar(32) NOT NULL COMMENT 'MENU/BUTTON/API',
  `path` varchar(255) DEFAULT NULL,
  `method` varchar(16) DEFAULT NULL,
  `description` varchar(255) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_permission_code` (`code`)
) ENGINE=InnoDB COMMENT='权限表';

CREATE TABLE `sys_user_role` (
  `id` bigint NOT NULL,
  `user_id` bigint NOT NULL COMMENT '与用户表的id字段关联',
  `role_id` bigint NOT NULL COMMENT '与角色表的id字段关联',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role` (`user_id`,`role_id`),
  KEY `idx_ur_user` (`user_id`),
  KEY `idx_ur_role` (`role_id`)
) ENGINE=InnoDB COMMENT='用户角色关联表';

CREATE TABLE `sys_role_permission` (
  `id` bigint NOT NULL,
  `role_id` bigint NOT NULL COMMENT '与角色表的id字段关联',
  `permission_id` bigint NOT NULL COMMENT '与权限表的id字段关联',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_permission` (`role_id`,`permission_id`),
  KEY `idx_rp_role` (`role_id`),
  KEY `idx_rp_permission` (`permission_id`)
) ENGINE=InnoDB COMMENT='角色权限关联表';

CREATE TABLE `sys_refresh_token` (
  `id` bigint NOT NULL,
  `user_id` bigint NOT NULL COMMENT '与用户表的id字段关联',
  `token_hash` varchar(255) NOT NULL,
  `expires_at` datetime NOT NULL,
  `revoked_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `session_id` bigint DEFAULT NULL COMMENT '会话 id：一次登录一个，refresh 轮转时继承（老数据为 NULL）',
  `end_type` varchar(16) DEFAULT NULL COMMENT '登录端：APP 用户端 / ADMIN 管理端',
  `ip` varchar(64) DEFAULT NULL COMMENT '登录时的客户端 IP',
  `user_agent` varchar(512) DEFAULT NULL COMMENT 'UA 原文；展示时由 UserAgentParser 粗解析成「Chrome / Windows」',
  `last_active_at` datetime DEFAULT NULL COMMENT '最近活跃时间，每次刷新令牌时更新',
  `revoked_reason` varchar(16) DEFAULT NULL COMMENT '作废原因：ROTATED 轮转 / LOGOUT 登出 / KICKED 被踢 / REVOKE_OTHERS 退出其他设备 / REVOKE_ALL 全撤',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refresh_token_hash` (`token_hash`),
  KEY `idx_refresh_user` (`user_id`),
  KEY `idx_refresh_expire` (`expires_at`),
  KEY `idx_refresh_session` (`session_id`)
) ENGINE=InnoDB COMMENT='刷新令牌表';


-- ============================================================
-- 二、文件与知识库（4 张）
-- ============================================================

CREATE TABLE `sys_file` (
  `id` bigint NOT NULL COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '上传者用户 id，逻辑关联 user.id',
  `original_name` varchar(255) NOT NULL COMMENT '原始文件名（用户上传时的名字）',
  `object_key` varchar(500) NOT NULL COMMENT '对象存储中的 key（MinIO 内的路径）',
  `bucket_name` varchar(128) NOT NULL COMMENT '对象存储的 bucket 名',
  `content_type` varchar(128) DEFAULT NULL COMMENT 'MIME 类型，如 application/pdf',
  `file_size` bigint NOT NULL DEFAULT '0' COMMENT '文件字节数',
  `content_hash` varchar(128) DEFAULT NULL COMMENT '内容哈希（SHA-256），用于秒传与去重',
  `biz_type` varchar(32) NOT NULL DEFAULT '' COMMENT '业务类型：documents 文档 / resumes 简历 / avatars 头像。取值和 object_key 的第一段一致',
  `storage_type` varchar(32) NOT NULL DEFAULT 'MINIO' COMMENT '存储类型：MINIO / LOCAL / OSS',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除：0 未删除 / 1 已删除',
  `deleted_at` datetime DEFAULT NULL COMMENT '逻辑删除时间；未删除为 NULL。后台清理任务据此判断是否超过恢复窗口',
  PRIMARY KEY (`id`),
  KEY `idx_file_user` (`user_id`),
  KEY `idx_file_hash` (`user_id`,`content_hash`),
  KEY `idx_file_deleted_at` (`deleted`,`deleted_at`)
) ENGINE=InnoDB COMMENT='文件对象存储元数据表';

CREATE TABLE `knowledge_base` (
  `id` bigint NOT NULL COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '归属用户 id，逻辑关联 user.id',
  `name` varchar(100) NOT NULL COMMENT '知识库名称',
  `description` varchar(500) DEFAULT NULL COMMENT '描述',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：1 启用 / 0 禁用',
  `document_count` int NOT NULL DEFAULT '0' COMMENT '文档数量（冗余计数，避免每次 COUNT）',
  `is_system` tinyint NOT NULL DEFAULT '0' COMMENT '是否官方/系统全局智库：0 否(个人私域) / 1 是(官方公域，全站共享)',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除：0 未删除 / 1 已删除',
  PRIMARY KEY (`id`),
  KEY `idx_kb_user` (`user_id`),
  KEY `idx_kb_status` (`user_id`,`status`),
  KEY `idx_kb_system` (`is_system`,`status`)
) ENGINE=InnoDB COMMENT='知识库表（含个人私域与官方公域智库）';

CREATE TABLE `document` (
  `id` bigint NOT NULL,
  `user_id` bigint NOT NULL COMMENT '与用户表的id字段关联',
  `knowledge_base_id` bigint NOT NULL COMMENT '与个人知识库表的id字段关联',
  `file_id` bigint DEFAULT NULL COMMENT '与文件对象存储元数据表的id字段关联',
  `file_name` varchar(255) NOT NULL,
  `file_type` varchar(64) DEFAULT NULL,
  `file_url` varchar(1000) DEFAULT NULL,
  `file_size` bigint NOT NULL DEFAULT '0',
  `content_hash` varchar(128) DEFAULT NULL,
  `parse_status` varchar(32) NOT NULL DEFAULT 'UPLOADED',
  `chunk_status` varchar(32) NOT NULL DEFAULT 'PENDING',
  `vector_status` varchar(32) NOT NULL DEFAULT 'PENDING',
  `error_message` varchar(1000) DEFAULT NULL,
  `page_count` int DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  `vector_purged` tinyint NOT NULL DEFAULT '0' COMMENT '向量是否已从向量库清除：0=未清 1=已清（文档删除后由 AI 服务异步清理）',
  PRIMARY KEY (`id`),
  KEY `idx_doc_user_kb` (`user_id`,`knowledge_base_id`),
  KEY `idx_doc_status` (`user_id`,`parse_status`,`chunk_status`,`vector_status`),
  KEY `idx_doc_hash` (`user_id`,`content_hash`),
  KEY `idx_doc_vector_purged` (`deleted`,`vector_purged`)
) ENGINE=InnoDB COMMENT='知识库文档表';

CREATE TABLE `document_chunk` (
  `id` bigint NOT NULL,
  `user_id` bigint NOT NULL COMMENT '与用户表的id字段关联',
  `knowledge_base_id` bigint NOT NULL COMMENT '与个人知识库表的id字段关联',
  `document_id` bigint NOT NULL COMMENT '与知识库文档表的id字段关联',
  `chunk_index` int NOT NULL,
  `content` longtext NOT NULL,
  `token_count` int DEFAULT NULL,
  `page_no` int DEFAULT NULL,
  `vector_id` varchar(255) DEFAULT NULL,
  `metadata_json` json DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_doc_chunk_index` (`document_id`,`chunk_index`),
  KEY `idx_chunk_user_kb` (`user_id`,`knowledge_base_id`),
  KEY `idx_chunk_document` (`document_id`),
  KEY `idx_chunk_vector` (`vector_id`)
) ENGINE=InnoDB COMMENT='文档分块表';


-- ============================================================
-- 三、对话与 RAG（3 张）
-- ============================================================

CREATE TABLE `conversation` (
  `id` bigint NOT NULL,
  `user_id` bigint NOT NULL COMMENT '与用户表的id字段关联',
  `knowledge_base_id` bigint DEFAULT NULL COMMENT '与个人知识库表的id字段关联',
  `title` varchar(255) NOT NULL,
  `model_name` varchar(128) DEFAULT NULL,
  `status` tinyint NOT NULL DEFAULT '1',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_conv_user` (`user_id`),
  KEY `idx_conv_user_kb` (`user_id`,`knowledge_base_id`),
  KEY `idx_conv_updated` (`user_id`,`updated_at`)
) ENGINE=InnoDB COMMENT='对话表';

CREATE TABLE `chat_message` (
  `id` bigint NOT NULL,
  `conversation_id` bigint NOT NULL COMMENT '与对话表的id字段关联',
  `user_id` bigint DEFAULT NULL COMMENT '与用户表的id字段关联',
  `role` varchar(32) NOT NULL COMMENT 'SYSTEM/USER/ASSISTANT',
  `content` longtext NOT NULL,
  `token_count` int DEFAULT NULL,
  `model_name` varchar(128) DEFAULT NULL,
  `prompt_tokens` int DEFAULT NULL,
  `completion_tokens` int DEFAULT NULL,
  `finish_reason` varchar(64) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `rating` varchar(8) DEFAULT NULL COMMENT '用户反馈：UP 有帮助 / DOWN 没帮助',
  `feedback_reason` varchar(500) DEFAULT NULL COMMENT '反馈补充说明',
  `feedback_at` datetime DEFAULT NULL COMMENT '反馈时间',
  PRIMARY KEY (`id`),
  KEY `idx_msg_conv` (`conversation_id`),
  KEY `idx_msg_user` (`user_id`),
  KEY `idx_msg_conv_created` (`conversation_id`,`created_at`)
) ENGINE=InnoDB COMMENT='聊天消息表';

CREATE TABLE `chat_source` (
  `id` bigint NOT NULL,
  `message_id` bigint NOT NULL COMMENT '外键聊天消息表',
  `document_id` bigint NOT NULL COMMENT '文档表关联',
  `chunk_id` bigint NOT NULL COMMENT '文档分块表',
  `page_no` int DEFAULT NULL,
  `relevance_score` decimal(10,6) DEFAULT NULL,
  `source_title` varchar(255) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_source_message` (`message_id`),
  KEY `idx_source_document` (`document_id`),
  KEY `idx_source_chunk` (`chunk_id`)
) ENGINE=InnoDB COMMENT='RAG引用来源表';


-- ============================================================
-- 四、简历与求职（8 张）
-- ============================================================

CREATE TABLE `resume` (
  `id` bigint NOT NULL COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '归属用户 id，逻辑关联 user.id',
  `file_id` bigint DEFAULT NULL COMMENT '原始文件 id，逻辑关联 sys_file.id；手工录入时为空',
  `name` varchar(100) NOT NULL COMMENT '简历名称',
  `target_position` varchar(128) DEFAULT NULL COMMENT '目标职位',
  `summary` text COMMENT '简历摘要（AI 提炼）',
  `raw_content` longtext COMMENT '原始文本内容（解析后提取）',
  `structured_json` json DEFAULT NULL COMMENT '结构化后的简历数据（AI 解析结果）',
  `parse_status` varchar(32) NOT NULL DEFAULT 'PENDING' COMMENT '解析状态：PENDING / PARSING / SUCCESS / FAILED',
  `error_message` varchar(500) DEFAULT NULL COMMENT '解析失败原因（最近一次）',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：1 启用 / 0 禁用',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除：0 未删除 / 1 已删除',
  PRIMARY KEY (`id`),
  KEY `idx_resume_user` (`user_id`),
  KEY `idx_resume_status` (`user_id`,`parse_status`)
) ENGINE=InnoDB COMMENT='简历表';

CREATE TABLE `resume_skill` (
  `id` bigint NOT NULL COMMENT '主键',
  `resume_id` bigint NOT NULL COMMENT '所属简历 id，逻辑关联 resume.id',
  `skill_name` varchar(128) NOT NULL COMMENT '技能名称，如 Java / Spring Boot',
  `skill_type` varchar(64) DEFAULT NULL COMMENT '技能类型：LANGUAGE / FRAMEWORK / TOOL / SOFT',
  `proficiency` varchar(32) DEFAULT NULL COMMENT '熟练程度：BEGINNER / INTERMEDIATE / ADVANCED / EXPERT',
  `years_experience` decimal(4,1) DEFAULT NULL COMMENT '使用年限，如 3.5',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_resume_skill_resume` (`resume_id`),
  KEY `idx_resume_skill_name` (`skill_name`)
) ENGINE=InnoDB COMMENT='简历技能表';

CREATE TABLE `job` (
  `id` bigint NOT NULL COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '归属用户 id，逻辑关联 user.id',
  `title` varchar(255) NOT NULL COMMENT '职位名称',
  `company_name` varchar(255) DEFAULT NULL COMMENT '公司名称',
  `location` varchar(255) DEFAULT NULL COMMENT '工作地点',
  `source_url` varchar(1000) DEFAULT NULL COMMENT '职位来源链接',
  `raw_content` longtext NOT NULL COMMENT 'JD 原始文本',
  `structured_json` json DEFAULT NULL COMMENT '结构化后的 JD 数据（AI 解析结果）',
  `experience_required` varchar(128) DEFAULT NULL COMMENT '经验要求，如 3-5 年',
  `education_required` varchar(128) DEFAULT NULL COMMENT '学历要求，如 本科',
  `parse_status` varchar(32) NOT NULL DEFAULT 'PENDING' COMMENT '解析状态：PENDING / PARSING / SUCCESS / FAILED',
  `application_stage` varchar(32) NOT NULL DEFAULT 'WISHLIST' COMMENT '投递阶段：WISHLIST/APPLIED/SCREENING/INTERVIEW_1/INTERVIEW_2/HR/OFFER/REJECTED',
  `linked_resume_id` bigint DEFAULT NULL COMMENT '绑定的目标简历 id',
  `salary_offer` varchar(64) DEFAULT NULL COMMENT '薪资范围或 Offer 年薪包',
  `next_action_at` datetime DEFAULT NULL COMMENT '下一场笔试/面试提醒时间',
  `crm_notes` varchar(1000) DEFAULT NULL COMMENT '求职跟进备注',
  `error_message` varchar(500) DEFAULT NULL COMMENT '解析失败原因（最近一次）',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：1 启用 / 0 禁用',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除：0 未删除 / 1 已删除',
  PRIMARY KEY (`id`),
  KEY `idx_job_user` (`user_id`),
  KEY `idx_job_status` (`user_id`,`parse_status`),
  KEY `idx_job_stage` (`user_id`,`application_stage`)
) ENGINE=InnoDB COMMENT='职位/JD表';

CREATE TABLE `job_skill` (
  `id` bigint NOT NULL COMMENT '主键',
  `job_id` bigint NOT NULL COMMENT '所属职位 id，逻辑关联 job.id',
  `skill_name` varchar(128) NOT NULL COMMENT '技能名称',
  `skill_type` varchar(64) DEFAULT NULL COMMENT '技能类型：LANGUAGE / FRAMEWORK / TOOL / SOFT',
  `required_level` varchar(32) DEFAULT NULL COMMENT '要求程度，如 了解 / 熟悉 / 精通',
  `required` tinyint NOT NULL DEFAULT '1' COMMENT '是否硬性要求：1 必须 / 0 加分项',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_job_skill_job` (`job_id`),
  KEY `idx_job_skill_name` (`skill_name`)
) ENGINE=InnoDB COMMENT='职位技能要求表';

CREATE TABLE `job_offer` (
  `id` bigint NOT NULL COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '归属用户 id，逻辑关联 user.id',
  `job_id` bigint DEFAULT NULL COMMENT '关联的职位 id（可选），从投递看板导入时带上',
  `company_name` varchar(255) NOT NULL COMMENT '公司名',
  `position_name` varchar(255) DEFAULT NULL COMMENT '岗位名',
  `salary_annual` decimal(10,2) DEFAULT NULL COMMENT '年薪（万元），仅展示参考、不参与算分',
  `salary_score` tinyint NOT NULL COMMENT '薪资包评分 1-10',
  `tech_score` tinyint NOT NULL COMMENT '技术栈发展评分 1-10',
  `brand_score` tinyint NOT NULL COMMENT '平台背书评分 1-10',
  `wlb_score` tinyint NOT NULL COMMENT '加班强度评分 1-10（分越高越轻松）',
  `commute_score` tinyint NOT NULL COMMENT '通勤距离评分 1-10（分越高越方便）',
  `dimension_notes_json` json DEFAULT NULL COMMENT '各维度打分的理由，如 {"salary":"总包最高","wlb":"大小周"}',
  `status` varchar(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 考虑中 / ACCEPTED 已接受 / DECLINED 已拒绝',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除：0 未删除 / 1 已删除',
  PRIMARY KEY (`id`),
  KEY `idx_offer_user` (`user_id`,`status`),
  KEY `idx_offer_created` (`user_id`,`created_at`)
) ENGINE=InnoDB COMMENT='Offer 对比表（五维打分：薪资包/技术栈发展/平台背书/加班强度/通勤距离）';

-- 求职日程（面试日历）。一条日程 = 一场笔试/面试/HR面。
-- 不复用 job.next_action_at 的原因：那个字段只有一个时间点，装不下同一家公司多场次；
-- next_action_at 由本表派生（最近一场未完成日程），保证看板提醒与日历一致。
CREATE TABLE `job_event` (
  `id` bigint NOT NULL COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '归属用户 id，逻辑关联 user.id',
  `job_id` bigint NOT NULL COMMENT '关联职位 id，逻辑关联 job.id（日程必挂职位）',
  `event_type` varchar(32) NOT NULL DEFAULT 'OTHER' COMMENT '类型：WRITTEN_TEST 笔试 / INTERVIEW_1 一面 / INTERVIEW_2 二面 / HR HR面 / OFFER_TALK 谈薪 / OTHER 其他',
  `title` varchar(255) NOT NULL COMMENT '标题（如「字节跳动 技术一面」）',
  `event_at` datetime NOT NULL COMMENT '开始时间',
  `duration_minutes` int DEFAULT NULL COMMENT '预计时长（分钟），可空',
  `event_mode` varchar(32) DEFAULT NULL COMMENT '形式：ONSITE 现场 / ONLINE 线上 / PHONE 电话，可空',
  `location` varchar(500) DEFAULT NULL COMMENT '地点或会议链接，可空',
  `note` varchar(1000) DEFAULT NULL COMMENT '备注（面试官、准备要点），可空',
  `status` varchar(32) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING 待进行 / DONE 已完成 / CANCELLED 已取消',
  `reminded_at` datetime DEFAULT NULL COMMENT '已发提醒时间，NULL 表示未提醒（抢占式幂等的依据）',
  `remind_fail_count` tinyint NOT NULL DEFAULT '0' COMMENT '提醒发送失败次数，达到上限后不再重试',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除：0 未删除 / 1 已删除',
  PRIMARY KEY (`id`),
  KEY `idx_event_user_time` (`user_id`,`event_at`),
  KEY `idx_event_job` (`job_id`),
  KEY `idx_event_remind` (`status`,`event_at`,`reminded_at`)
) ENGINE=InnoDB COMMENT='求职日程表（面试日历）';

CREATE TABLE `resume_job_analysis` (
  `id` bigint NOT NULL COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '归属用户 id，逻辑关联 user.id',
  `resume_id` bigint NOT NULL COMMENT '简历 id，逻辑关联 resume.id',
  `job_id` bigint NOT NULL COMMENT '职位 id，逻辑关联 job.id',
  `overall_score` decimal(6,2) DEFAULT NULL COMMENT '综合匹配分（0-100）',
  `matched_skills_json` json DEFAULT NULL COMMENT '匹配上的技能（数组）',
  `missing_skills_json` json DEFAULT NULL COMMENT '缺失的技能（数组）',
  `matched_experience_json` json DEFAULT NULL COMMENT '匹配上的经历（数组）',
  `missing_experience_json` json DEFAULT NULL COMMENT '缺失的经历（数组）',
  `match_items_json` json DEFAULT NULL COMMENT '逐条要求的匹配结论（五类：MATCHED/EXPRESSION_GAP/INSUFFICIENT_EVIDENCE/REAL_GAP/UNCERTAIN），数组',
  `suggestions_json` json DEFAULT NULL COMMENT '改进建议（数组）',
  `analysis_text` longtext COMMENT 'AI 生成的整体分析（整体判断 + 五类统计 + 优先动作）',
  `model_name` varchar(128) DEFAULT NULL COMMENT '使用的模型名',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_analysis_user` (`user_id`),
  KEY `idx_analysis_resume_job` (`resume_id`,`job_id`),
  KEY `idx_analysis_created` (`user_id`,`created_at`)
) ENGINE=InnoDB COMMENT='简历职位匹配分析表';


CREATE TABLE `salary_estimate` (
  `id` bigint NOT NULL COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '归属用户 id，逻辑关联 user.id',
  `request_key` char(64) NOT NULL COMMENT '参数指纹 sha256(city|position|years|education|companyType|排序后的 skills)，用于 7 天内命中缓存',
  `city` varchar(64) NOT NULL COMMENT '城市（用户填写原文）',
  `position` varchar(128) NOT NULL COMMENT '岗位名称',
  `years_of_experience` int NOT NULL COMMENT '工作年限',
  `education` varchar(32) DEFAULT NULL COMMENT '学历（选填）',
  `company_type` varchar(32) DEFAULT NULL COMMENT '公司类型（选填）',
  `skills_json` json DEFAULT NULL COMMENT '技术栈（数组）',
  `current_annual_salary` decimal(12,2) DEFAULT NULL COMMENT '用户填写的当前年包（元），用于给涨幅参考',
  `monthly_p50` decimal(10,2) DEFAULT NULL COMMENT '月薪 P50（元）',
  `monthly_p75` decimal(10,2) DEFAULT NULL COMMENT '月薪 P75（元）',
  `monthly_p90` decimal(10,2) DEFAULT NULL COMMENT '月薪 P90（元）',
  `annual_p50` decimal(12,2) DEFAULT NULL COMMENT '年包 P50（元）',
  `annual_p75` decimal(12,2) DEFAULT NULL COMMENT '年包 P75（元）',
  `annual_p90` decimal(12,2) DEFAULT NULL COMMENT '年包 P90（元）',
  `months_per_year` int DEFAULT NULL COMMENT '一年发几个月（12/13/14/15/16）',
  `confidence` varchar(16) DEFAULT NULL COMMENT '置信度 LOW/MEDIUM/HIGH —— 服务端按规则钳制后的值，不是模型自评',
  `basis_json` json DEFAULT NULL COMMENT '估算依据（数组）',
  `factors_json` json DEFAULT NULL COMMENT '加减分项（数组）',
  `caveat` varchar(500) DEFAULT NULL COMMENT '免责声明 —— 服务端固定拼接，不采信模型输出',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（同一条记录也当缓存用，7 天内同参数直接复用）',
  PRIMARY KEY (`id`),
  KEY `idx_salary_user_created` (`user_id`,`created_at`),
  KEY `idx_salary_user_key` (`user_id`,`request_key`)
) ENGINE=InnoDB COMMENT='AI 薪酬估算表';


-- ============================================================
-- 五、AI 面试（3 张）
-- ============================================================

CREATE TABLE `interview` (
  `id` bigint NOT NULL COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '归属用户 id，逻辑关联 user.id',
  `resume_id` bigint DEFAULT NULL COMMENT '关联简历 id，可为空（不基于简历面试）',
  `job_id` bigint DEFAULT NULL COMMENT '关联职位 id，可为空',
  `title` varchar(255) NOT NULL COMMENT '面试标题',
  `interview_type` varchar(64) NOT NULL COMMENT '面试类型：TECHNICAL / BEHAVIORAL / MIXED',
  `difficulty` varchar(32) DEFAULT NULL COMMENT '难度：EASY / MEDIUM / HARD',
  `status` varchar(32) NOT NULL DEFAULT 'CREATED' COMMENT '状态：CREATED / IN_PROGRESS / FINISHED',
  `question_count` int NOT NULL DEFAULT '0' COMMENT '题目数量',
  `started_at` datetime DEFAULT NULL COMMENT '开始作答时间',
  `finished_at` datetime DEFAULT NULL COMMENT '结束时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除：0 未删除 / 1 已删除',
  PRIMARY KEY (`id`),
  KEY `idx_interview_user` (`user_id`),
  KEY `idx_interview_status` (`user_id`,`status`)
) ENGINE=InnoDB COMMENT='AI面试会话表';

CREATE TABLE `interview_question` (
  `id` bigint NOT NULL COMMENT '主键',
  `interview_id` bigint NOT NULL COMMENT '所属面试 id，逻辑关联 interview.id',
  `question_no` int NOT NULL COMMENT '题号（同一场面试内唯一）',
  `question_type` varchar(64) DEFAULT NULL COMMENT '题型：TECHNICAL / BEHAVIORAL / SITUATIONAL',
  `question` text NOT NULL COMMENT '题目内容',
  `expected_points` json DEFAULT NULL COMMENT '参考答案要点（数组）',
  `difficulty` varchar(32) DEFAULT NULL COMMENT '难度：EASY / MEDIUM / HARD',
  `user_answer` longtext COMMENT '用户的回答',
  `ai_analysis` longtext COMMENT 'AI 对该回答的分析',
  `score` decimal(6,2) DEFAULT NULL COMMENT '本题得分',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `answered_at` datetime DEFAULT NULL COMMENT '作答时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_interview_question_no` (`interview_id`,`question_no`),
  KEY `idx_question_interview` (`interview_id`)
) ENGINE=InnoDB COMMENT='AI面试题目表';

CREATE TABLE `interview_report` (
  `id` bigint NOT NULL COMMENT '主键',
  `interview_id` bigint NOT NULL COMMENT '所属面试 id，逻辑关联 interview.id',
  `overall_score` decimal(6,2) DEFAULT NULL COMMENT '综合得分',
  `technical_score` decimal(6,2) DEFAULT NULL COMMENT '技术能力得分',
  `communication_score` decimal(6,2) DEFAULT NULL COMMENT '沟通表达得分',
  `problem_solving_score` decimal(6,2) DEFAULT NULL COMMENT '问题解决得分',
  `engineering_score` decimal(6,2) DEFAULT NULL COMMENT '工程规范得分（2026-10-30 新增，历史为 NULL）',
  `job_fit_score` decimal(6,2) DEFAULT NULL COMMENT '岗位契合得分（未指定目标岗位时为 NULL）',
  `strengths_json` json DEFAULT NULL COMMENT '优势（数组）',
  `weaknesses_json` json DEFAULT NULL COMMENT '不足（数组）',
  `suggestions_json` json DEFAULT NULL COMMENT '改进建议（数组）',
  `report_text` longtext COMMENT 'AI 生成的完整报告',
  `model_name` varchar(128) DEFAULT NULL COMMENT '使用的模型名',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_report_interview` (`interview_id`)
) ENGINE=InnoDB COMMENT='AI面试报告表';


-- ============================================================
-- 六、AI 与运维（5 张）
-- ============================================================

CREATE TABLE `sys_ai_model` (
  `id` bigint NOT NULL COMMENT '主键',
  `name` varchar(64) NOT NULL COMMENT '配置显示名，如「对话主模型」',
  `scene` varchar(32) NOT NULL COMMENT '用途：CHAT 对话 / EMBEDDING 向量化 / RERANK 重排序',
  `provider` varchar(64) NOT NULL COMMENT '提供方：dashscope / deepseek / openai',
  `model_name` varchar(128) NOT NULL COMMENT '模型名，如 deepseek-v4-flash',
  `base_url` varchar(255) DEFAULT NULL COMMENT 'API 地址（OpenAI 兼容协议的 base_url）',
  `api_key_cipher` varchar(1024) DEFAULT NULL COMMENT 'API Key 密文（AES-GCM + Base64）。绝不返回给前端、绝不进日志',
  `api_key_hint` varchar(32) DEFAULT NULL COMMENT 'Key 尾部提示（如 ****abcd），仅供列表展示',
  `temperature` decimal(3,2) DEFAULT NULL COMMENT '采样温度',
  `max_tokens` int DEFAULT NULL COMMENT '单次最大输出 token',
  `timeout_seconds` int DEFAULT NULL COMMENT '调用超时（秒）',
  `is_default` tinyint NOT NULL DEFAULT '0' COMMENT '同 scene 下的默认配置：1 是 / 0 否',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1 启用 / 0 禁用',
  `description` varchar(255) DEFAULT NULL COMMENT '备注',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_ai_model_scene` (`scene`,`status`),
  -- 「每个用途最多一个启用中的默认」—— 用函数索引表达：
  -- 不是默认（或已禁用）的行表达式结果是 NULL，而 NULL 不参与唯一性，
  -- 所以多个「非默认」互不冲突；只有真正的默认会占坑。
  -- ⚠️ 因为唯一性是【立即】生效的（MySQL 不支持延迟约束），
  --    代码里必须【先清旧默认、再设新默认】，反过来会撞 1062。
  UNIQUE KEY `uk_ai_model_default`
    (`scene`, (CASE WHEN `is_default` = 1 AND `status` = 1 THEN 1 ELSE NULL END))
) ENGINE=InnoDB COMMENT='AI 模型配置表';

CREATE TABLE `sys_prompt` (
  `id` bigint NOT NULL COMMENT '主键',
  `scene` varchar(32) NOT NULL COMMENT '场景：RAG_CHAT 对话 / RESUME_PARSE 简历解析 等（大写，对齐 Python 侧的 prompt 文件名）',
  `name` varchar(64) NOT NULL COMMENT '变体名，如「默认」「严谨」。用户切换的单位',
  `version` int NOT NULL COMMENT '该变体的版本号，从 1 开始递增',
  `content` mediumtext NOT NULL COMMENT '提示词正文（保留 {占位符} 形式）',
  `status` varchar(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT 草稿 / PUBLISHED 已发布 / ARCHIVED 已归档',
  `is_default` tinyint NOT NULL DEFAULT '0' COMMENT '该 scene 下用户没指定时用这条：1 是 / 0 否',
  `user_selectable` tinyint NOT NULL DEFAULT '0' COMMENT '是否出现在用户的切换列表里：1 是 / 0 否',
  `label` varchar(64) DEFAULT NULL COMMENT '给用户看的选项名（如「严谨」「简洁」）。user_selectable=1 时必填',
  `description` varchar(255) DEFAULT NULL COMMENT '备注',
  `created_by` bigint DEFAULT NULL COMMENT '创建人（管理员）id',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_prompt_scene_name_version` (`scene`,`name`,`version`),
  KEY `idx_prompt_scene_status` (`scene`,`status`),
  KEY `idx_prompt_selectable` (`scene`,`user_selectable`,`status`)
) ENGINE=InnoDB COMMENT='提示词配置表（按场景 + 变体 + 版本管理）';

CREATE TABLE `ai_usage_record` (
  `id` bigint NOT NULL COMMENT '主键',
  `user_id` bigint DEFAULT NULL COMMENT '触发用户 id；系统自动调用时为空',
  `request_id` varchar(64) DEFAULT NULL COMMENT '链路 id，可与日志文件、审计日志关联',
  `scene` varchar(64) NOT NULL COMMENT '业务场景：RAG_CHAT / RESUME_PARSE / JOB_PARSE / INTERVIEW 等',
  `provider` varchar(64) DEFAULT NULL COMMENT '模型提供方，如 deepseek / openai',
  `model_name` varchar(128) DEFAULT NULL COMMENT '模型名',
  `prompt_tokens` int NOT NULL DEFAULT '0' COMMENT '输入 token 数',
  `completion_tokens` int NOT NULL DEFAULT '0' COMMENT '输出 token 数',
  `total_tokens` int NOT NULL DEFAULT '0' COMMENT '总 token 数',
  `duration_ms` bigint DEFAULT NULL COMMENT '调用耗时（毫秒）',
  `status` varchar(32) NOT NULL DEFAULT 'SUCCESS' COMMENT '调用结果：SUCCESS / FAILED',
  `error_message` varchar(1000) DEFAULT NULL COMMENT '失败原因',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_usage_created` (`created_at`),
  KEY `idx_usage_user_created` (`user_id`,`created_at`),
  KEY `idx_usage_request` (`request_id`)
) ENGINE=InnoDB COMMENT='AI调用用量记录表';

CREATE TABLE `sys_audit_log` (
  `id` bigint NOT NULL COMMENT '主键',
  `user_id` bigint DEFAULT NULL COMMENT '操作人用户 id，关联 user.id（无外键）；系统自动触发时为 NULL',
  `request_id` varchar(64) DEFAULT NULL COMMENT '链路追踪 id，对应请求头 X-Request-Id / 响应体 traceId，用于回查该次请求的日志',
  `action` varchar(128) NOT NULL COMMENT '操作动作，如 ADMIN_LOGIN / USER_ASSIGN_ROLE / ROLE_ASSIGN_PERMISSION / AI_CONFIG_UPDATE',
  `resource_type` varchar(64) DEFAULT NULL COMMENT '资源类型，如 USER / ROLE / AI_CONFIG',
  `resource_id` varchar(64) DEFAULT NULL COMMENT '资源 id，被操作对象的标识',
  `http_method` varchar(16) DEFAULT NULL COMMENT 'HTTP 方法，如 POST / PUT / DELETE',
  `request_uri` varchar(500) DEFAULT NULL COMMENT '请求路径，如 /api/admin/users/5/role',
  `ip_address` varchar(64) DEFAULT NULL COMMENT '客户端 IP',
  `user_agent` varchar(500) DEFAULT NULL COMMENT '客户端 User-Agent',
  `result` varchar(32) NOT NULL DEFAULT 'SUCCESS' COMMENT '操作结果：SUCCESS 成功 / FAIL 失败',
  `detail_json` json DEFAULT NULL COMMENT '详细信息，建议存新旧值对比，如 {"before":{"roleId":1},"after":{"roleId":2}}',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_audit_user_created` (`user_id`,`created_at`) COMMENT '按操作人+时间查：某人都做过什么',
  KEY `idx_audit_resource` (`resource_type`,`resource_id`) COMMENT '按资源查：某条记录的变更历史',
  KEY `idx_audit_created` (`created_at`) COMMENT '按时间范围查',
  KEY `idx_audit_action` (`action`,`created_at`) COMMENT '按 action 筛选'
) ENGINE=InnoDB COMMENT='系统审计日志表';

CREATE TABLE `sys_alert` (
  `id` bigint NOT NULL COMMENT '主键',
  `user_id` bigint DEFAULT NULL COMMENT '触发操作的用户 id（无外键）；系统触发时为 NULL',
  `action` varchar(128) NOT NULL COMMENT '触发告警的高危操作，取值对齐 sys_audit_log.action',
  `resource_type` varchar(64) DEFAULT NULL COMMENT '资源类型，如 USER / ROLE',
  `resource_id` varchar(64) DEFAULT NULL COMMENT '资源 id',
  `request_id` varchar(64) DEFAULT NULL COMMENT '触发请求的 traceId，用于回查审计与日志',
  `title` varchar(255) NOT NULL COMMENT '告警标题（通知渠道里展示的摘要）',
  `detail_json` json DEFAULT NULL COMMENT '操作明细，与审计日志的 detail_json 相同',
  `read_by` bigint DEFAULT NULL COMMENT '标记已读的管理员 id；未读为 NULL',
  `read_at` datetime DEFAULT NULL COMMENT '标记已读时间；未读为 NULL',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发生时间',
  PRIMARY KEY (`id`),
  KEY `idx_alert_read` (`read_at`),
  KEY `idx_alert_action` (`action`,`created_at`),
  KEY `idx_alert_created` (`created_at`)
) ENGINE=InnoDB COMMENT='高危操作告警表（有已读状态；和审计的分工：审计回答发生过什么，告警回答需要有人看）';

CREATE TABLE `recycle_bin` (
  `id` bigint NOT NULL COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '删除者；用户端为自己的 user.id（无外键）',
  `resource_type` varchar(32) NOT NULL COMMENT '资源类型：JOB / JOB_EVENT / OFFER / INTERVIEW / CONVERSATION（一期）；RESUME / DOCUMENT / KNOWLEDGE_BASE 预留',
  `resource_id` bigint NOT NULL COMMENT '原表 id —— 原行还在，只是 deleted=1',
  `title` varchar(255) NOT NULL COMMENT '展示名（简历名 / 职位名 / 公司名…），删除后原行查不到也能显示',
  `payload_json` json DEFAULT NULL COMMENT '被物理删掉、无法从原表找回的关联数据（如简历的技能与匹配分析）；一期资源都没有物理删的关联数据，全为 NULL',
  `deleted_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '删除时间',
  `expire_at` datetime NOT NULL COMMENT '到期时间 —— 必须 ≤ OSS 保留期，否则恢复出来是个空壳',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_recycle_resource` (`resource_type`,`resource_id`) COMMENT '同一资源只能有一条回收记录，防重复登记',
  KEY `idx_recycle_user_time` (`user_id`,`deleted_at`) COMMENT '最近删除列表：按人 + 时间倒序',
  KEY `idx_recycle_expire` (`expire_at`) COMMENT '过期清理任务扫这个'
) ENGINE=InnoDB COMMENT='回收站：逻辑删除后的恢复入口，恢复窗口与 OSS 保留期对齐';

-- ============================================================
-- 归档表（2026-10-22 补建）
--
-- ⚠️ 为什么之前没有：DataArchiveServiceImpl 早就写好了「把超期数据搬进归档表」
--    的逻辑，但这两张表从来没建过。之所以一直没暴露 —— sys_audit_log 攒够 90 天
--    之前 selectIdsBefore 返回空，压根不会执行 INSERT；一旦有到期数据，
--    每天 03:40 的 DataArchiveTask 就会抛 Table doesn't exist，被 catch 住只打一行
--    ERROR，表现是「归档永远不生效」，服务不会挂，所以极难发现。
--
-- ⚠️ 列必须与源表【完全一致】：归档语句是
--      INSERT INTO sys_audit_log_archive SELECT * FROM sys_audit_log WHERE id IN (...)
--    列数 / 顺序 / 类型任何一处不同都会直接报错。给源表加列时这里要同步 ALTER。
--    索引名加 _archive 后缀只是为了避免看执行计划时混淆（跨表同名本来也允许）。
-- ============================================================

CREATE TABLE `sys_audit_log_archive` (
  `id` bigint NOT NULL COMMENT '主键',
  `user_id` bigint DEFAULT NULL COMMENT '操作人用户 id，关联 user.id（无外键）；系统自动触发时为 NULL',
  `request_id` varchar(64) DEFAULT NULL COMMENT '链路追踪 id，对应请求头 X-Request-Id / 响应体 traceId，用于回查该次请求的日志',
  `action` varchar(128) NOT NULL COMMENT '操作动作，如 ADMIN_LOGIN / USER_ASSIGN_ROLE / ROLE_ASSIGN_PERMISSION / AI_CONFIG_UPDATE',
  `resource_type` varchar(64) DEFAULT NULL COMMENT '资源类型，如 USER / ROLE / AI_CONFIG',
  `resource_id` varchar(64) DEFAULT NULL COMMENT '资源 id，被操作对象的标识',
  `http_method` varchar(16) DEFAULT NULL COMMENT 'HTTP 方法，如 POST / PUT / DELETE',
  `request_uri` varchar(500) DEFAULT NULL COMMENT '请求路径，如 /api/admin/users/5/role',
  `ip_address` varchar(64) DEFAULT NULL COMMENT '客户端 IP',
  `user_agent` varchar(500) DEFAULT NULL COMMENT '客户端 User-Agent',
  `result` varchar(32) NOT NULL DEFAULT 'SUCCESS' COMMENT '操作结果：SUCCESS 成功 / FAIL 失败',
  `detail_json` json DEFAULT NULL COMMENT '详细信息，建议存新旧值对比，如 {"before":{"roleId":1},"after":{"roleId":2}}',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_audit_archive_user_created` (`user_id`,`created_at`) COMMENT '按操作人+时间查：某人都做过什么',
  KEY `idx_audit_archive_resource` (`resource_type`,`resource_id`) COMMENT '按资源查：某条记录的变更历史',
  KEY `idx_audit_archive_created` (`created_at`) COMMENT '按时间范围查',
  KEY `idx_audit_archive_action` (`action`,`created_at`) COMMENT '按 action 筛选'
) ENGINE=InnoDB COMMENT='审计日志归档表（超过保留期的历史数据，只读留档，不再参与业务查询）';

CREATE TABLE `sys_alert_archive` (
  `id` bigint NOT NULL COMMENT '主键',
  `user_id` bigint DEFAULT NULL COMMENT '触发操作的用户 id（无外键）；系统触发时为 NULL',
  `action` varchar(128) NOT NULL COMMENT '触发告警的高危操作，取值对齐 sys_audit_log.action',
  `resource_type` varchar(64) DEFAULT NULL COMMENT '资源类型，如 USER / ROLE',
  `resource_id` varchar(64) DEFAULT NULL COMMENT '资源 id',
  `request_id` varchar(64) DEFAULT NULL COMMENT '触发请求的 traceId，用于回查审计与日志',
  `title` varchar(255) NOT NULL COMMENT '告警标题（通知渠道里展示的摘要）',
  `detail_json` json DEFAULT NULL COMMENT '操作明细，与审计日志的 detail_json 相同',
  `read_by` bigint DEFAULT NULL COMMENT '标记已读的管理员 id；未读为 NULL',
  `read_at` datetime DEFAULT NULL COMMENT '标记已读时间；未读为 NULL',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发生时间',
  PRIMARY KEY (`id`),
  KEY `idx_alert_archive_read` (`read_at`),
  KEY `idx_alert_archive_action` (`action`,`created_at`),
  KEY `idx_alert_archive_created` (`created_at`)
) ENGINE=InnoDB COMMENT='告警归档表（超过保留期的已读告警，只读留档）';

-- ---------------------------------------------------------------
-- 过期删除数据归档（2026-10-26）
-- 回收站记录过期后，先把业务数据归档到这里再物理删，留痕用。
-- ★ 只留痕，不保证可还原：关联数据（技能/题目/消息）在删除那一刻就已物理删了。
-- ---------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `deleted_data_archive` (
`id`            BIGINT       NOT NULL                COMMENT '雪花 id',
`resource_type` VARCHAR(32)  NOT NULL                COMMENT '资源类型（RecycleResourceType）',
`original_id`   BIGINT       NOT NULL                COMMENT '原业务表 id',
`user_id`       BIGINT       NOT NULL                COMMENT '原数据所有者',
`title`         VARCHAR(255) DEFAULT NULL            COMMENT '原数据标题，便于人工追查',
`payload_json`  JSON         DEFAULT NULL            COMMENT '删除时的快照（可能为 null）',
`deleted_at`    DATETIME     DEFAULT NULL            COMMENT '原来什么时候被删的',
`expire_at`     DATETIME     DEFAULT NULL            COMMENT '恢复期何时结束',
`archived_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '归档时间',
PRIMARY KEY (`id`),
KEY `idx_type_original` (`resource_type`, `original_id`),
KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='过期删除数据归档';


-- ============================================================
-- 种子数据（新建库必须执行）
--
-- ⚠️ 已有库【不要重复执行】—— 主键会冲突。
--    这几张表的 id 都是写死的（角色 1-3、权限 1-59、演示用户 1-2），
--    不是自增值，所以重复建库的结果一致。
-- 用户账号见本节末尾的「开发演示账号」—— 部署到公网前必须改密码或删掉。
-- ============================================================




INSERT INTO `sys_role` (`id`, `name`, `code`, `status`, `description`, `created_at`, `updated_at`, `deleted`, `level`) VALUES (1,'普通用户','USER',1,'用户端普通用户角色','2026-10-02 16:28:36','2026-10-07 23:02:17',0,10),(2,'管理员','ADMIN',1,'管理端管理员角色','2026-10-02 16:28:36','2026-10-07 23:02:17',0,50),(3,'超级管理员','SUPER_ADMIN',1,'拥有全部权限的角色','2026-10-02 16:28:36','2026-10-07 23:02:17',0,100);

INSERT INTO `sys_permission` (`id`, `name`, `code`, `type`, `path`, `method`, `description`, `created_at`, `updated_at`) VALUES (1,'知识库查看','app:knowledge:read','API',NULL,NULL,'查看个人知识库','2026-10-03 15:53:56','2026-10-03 15:53:56'),(2,'知识库管理','app:knowledge:write','API',NULL,NULL,'创建和修改个人知识库','2026-10-03 15:53:56','2026-10-03 15:53:56'),(3,'知识库删除','app:knowledge:delete','API',NULL,NULL,'删除个人知识库','2026-10-03 15:53:56','2026-10-03 15:53:56'),(4,'文档查看','app:document:read','API',NULL,NULL,'查看知识库文档','2026-10-03 15:53:56','2026-10-03 15:53:56'),(5,'文档上传','app:document:upload','API',NULL,NULL,'上传知识库文档','2026-10-03 15:53:56','2026-10-03 15:53:56'),(6,'文档删除','app:document:delete','API',NULL,NULL,'删除知识库文档','2026-10-03 15:53:56','2026-10-03 15:53:56'),(7,'文档重试','app:document:retry','API',NULL,NULL,'重试文档处理','2026-10-03 15:53:56','2026-10-03 15:53:56'),(8,'对话查看','app:conversation:read','API',NULL,NULL,'查看对话','2026-10-03 15:53:56','2026-10-03 15:53:56'),(9,'对话管理','app:conversation:write','API',NULL,NULL,'创建和发送对话消息','2026-10-03 15:53:56','2026-10-03 15:53:56'),(10,'RAG检索','app:rag:search','API',NULL,NULL,'执行RAG检索','2026-10-03 15:53:56','2026-10-03 15:53:56'),(11,'RAG反馈','app:rag:feedback','API',NULL,NULL,'提交RAG回答反馈','2026-10-03 15:53:56','2026-10-03 15:53:56'),(12,'简历查看','app:resume:read','API',NULL,NULL,'查看个人简历','2026-10-03 15:53:56','2026-10-03 15:53:56'),(13,'简历管理','app:resume:write','API',NULL,NULL,'管理个人简历','2026-10-03 15:53:56','2026-10-03 15:53:56'),(14,'简历解析','app:resume:parse','API',NULL,NULL,'解析个人简历','2026-10-03 15:53:56','2026-10-03 15:53:56'),(15,'简历优化','app:resume:optimize','API',NULL,NULL,'AI简历优化','2026-10-03 15:53:56','2026-10-03 15:53:56'),(16,'职位查看','app:job:read','API',NULL,NULL,'查看职位/JD','2026-10-03 15:53:56','2026-10-03 15:53:56'),(17,'职位管理','app:job:write','API',NULL,NULL,'管理职位/JD','2026-10-03 15:53:56','2026-10-03 15:53:56'),(18,'职位解析','app:job:parse','API',NULL,NULL,'解析职位/JD','2026-10-03 15:53:56','2026-10-03 15:53:56'),(19,'面试查看','app:interview:read','API',NULL,NULL,'查看AI面试','2026-10-03 15:53:56','2026-10-03 15:53:56'),(20,'面试管理','app:interview:write','API',NULL,NULL,'创建AI面试','2026-10-03 15:53:56','2026-10-03 15:53:56'),(21,'面试开始','app:interview:start','API',NULL,NULL,'开始AI面试','2026-10-03 15:53:56','2026-10-03 15:53:56'),(22,'面试回答','app:interview:answer','API',NULL,NULL,'提交面试回答','2026-10-03 15:53:56','2026-10-03 15:53:56'),(23,'面试报告','app:interview:report','API',NULL,NULL,'查看面试报告','2026-10-03 15:53:56','2026-10-03 15:53:56'),(24,'后台首页','admin:dashboard:read','API',NULL,NULL,'查看管理后台首页','2026-10-03 15:53:56','2026-10-03 15:53:56'),(25,'用户列表','admin:user:list','API',NULL,NULL,'查看用户列表','2026-10-03 15:53:56','2026-10-03 15:53:56'),(26,'用户详情','admin:user:detail','API',NULL,NULL,'查看用户详情','2026-10-03 15:53:56','2026-10-03 15:53:56'),(27,'用户修改','admin:user:update','API',NULL,NULL,'修改用户状态或资料','2026-10-03 15:53:56','2026-10-03 15:53:56'),(28,'用户禁用','admin:user:disable','API',NULL,NULL,'禁用用户','2026-10-03 15:53:56','2026-10-03 15:53:56'),(29,'用户分配角色','admin:user:assign-role','API',NULL,NULL,'给用户分配角色','2026-10-03 15:53:56','2026-10-03 15:53:56'),(30,'角色列表','admin:role:list','API',NULL,NULL,'查看角色','2026-10-03 15:53:56','2026-10-03 15:53:56'),(31,'角色创建','admin:role:create','API',NULL,NULL,'创建角色','2026-10-03 15:53:56','2026-10-03 15:53:56'),(32,'角色修改','admin:role:update','API',NULL,NULL,'修改角色','2026-10-03 15:53:56','2026-10-03 15:53:56'),(33,'角色删除','admin:role:delete','API',NULL,NULL,'删除角色','2026-10-03 15:53:56','2026-10-03 15:53:56'),(34,'角色分配权限','admin:role:assign-permission','API',NULL,NULL,'给角色分配权限','2026-10-03 15:53:56','2026-10-03 15:53:56'),(35,'权限列表','admin:permission:list','API',NULL,NULL,'查看权限','2026-10-03 15:53:56','2026-10-03 15:53:56'),(36,'知识库管理','admin:knowledge:list','API',NULL,NULL,'管理所有知识库','2026-10-03 15:53:56','2026-10-03 15:53:56'),(37,'文档管理','admin:document:list','API',NULL,NULL,'管理所有文档','2026-10-03 15:53:56','2026-10-03 15:53:56'),(38,'文档重试管理','admin:document:retry','API',NULL,NULL,'管理员重试文档','2026-10-03 15:53:56','2026-10-03 15:53:56'),(39,'文档删除管理','admin:document:delete','API',NULL,NULL,'管理员删除文档','2026-10-03 15:53:56','2026-10-03 15:53:56'),(40,'审计日志','admin:audit:list','API',NULL,NULL,'查看审计日志','2026-10-03 15:53:56','2026-10-03 15:53:56'),(41,'AI用量','admin:usage:read','API',NULL,NULL,'查看AI用量','2026-10-03 15:53:56','2026-10-03 15:53:56'),(42,'AI配置','admin:ai:manage','API',NULL,NULL,'管理AI模型配置','2026-10-03 15:53:56','2026-10-03 15:53:56'),(43,'Prompt管理','admin:prompt:manage','API',NULL,NULL,'管理Prompt模板','2026-10-03 15:53:56','2026-10-03 15:53:56'),(44,'创建用户','admin:user:create','API',NULL,NULL,'管理端创建用户','2026-10-08 15:38:00','2026-10-08 15:38:00'),(45,'删除用户','admin:user:delete','API',NULL,NULL,'管理端删除用户','2026-10-08 15:38:00','2026-10-08 15:38:00'),(46,'知识库删除管理','admin:knowledge:delete','API',NULL,NULL,'管理端删除任意用户的知识库','2026-10-08 19:48:44','2026-10-08 19:48:44'),(47,'文档状态管理','admin:document:update','API',NULL,NULL,'管理端修改文档处理状态','2026-10-08 19:48:44','2026-10-08 19:48:44'),(48,'简历匹配','app:resume:match','API',NULL,NULL,'简历与职位匹配分析','2026-10-10 13:50:00','2026-10-10 13:50:00'),(49,'提示词查看','app:prompt:read','API',NULL,NULL,'用户端查看可选的回答风格','2026-10-10 19:00:00','2026-10-10 19:00:00'),(50,'高危操作告警','admin:alert:list','API',NULL,NULL,'查看高危操作告警并标记已读','2026-10-11 10:10:00','2026-10-11 10:10:00'),(51,'Offer查看','app:offer:read','API',NULL,NULL,'查看与对比 Offer','2026-10-14 22:00:00','2026-10-14 22:00:00'),(52,'Offer管理','app:offer:write','API',NULL,NULL,'录入和管理 Offer','2026-10-14 22:00:00','2026-10-14 22:00:00'),(53,'薪酬估算查看','app:salary:read','API',NULL,NULL,'查看 AI 薪酬估算历史','2026-10-17 14:00:00','2026-10-17 14:00:00'),(54,'薪酬估算','app:salary:write','API',NULL,NULL,'发起 AI 薪酬估算','2026-10-17 14:00:00','2026-10-17 14:00:00'),(55,'回收站查看','app:recycle:read','API',NULL,NULL,'查看最近删除的 Offer 与面试日程','2026-10-22 16:40:00','2026-10-22 16:40:00'),(56,'回收站管理','app:recycle:write','API',NULL,NULL,'从回收站恢复或彻底删除','2026-10-22 16:40:00','2026-10-22 16:40:00'),(57,'知识库创建管理','admin:knowledge:create','API',NULL,NULL,'管理端新建官方知识库','2026-10-22 17:10:00','2026-10-22 17:10:00'),(58,'知识库修改管理','admin:knowledge:update','API',NULL,NULL,'管理端修改官方知识库','2026-10-22 17:10:00','2026-10-22 17:10:00'),(59,'文档上传管理','admin:document:upload','API',NULL,NULL,'管理端上传或按 URL 导入文档','2026-10-22 17:10:00','2026-10-22 17:10:00');

INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`, `created_at`) VALUES (1,1,8,'2026-10-03 15:57:55'),(2,1,9,'2026-10-03 15:57:55'),(3,1,6,'2026-10-03 15:57:55'),(4,1,4,'2026-10-03 15:57:55'),(5,1,7,'2026-10-03 15:57:55'),(6,1,5,'2026-10-03 15:57:55'),(7,1,22,'2026-10-03 15:57:55'),(8,1,19,'2026-10-03 15:57:55'),(9,1,23,'2026-10-03 15:57:55'),(10,1,21,'2026-10-03 15:57:55'),(11,1,20,'2026-10-03 15:57:55'),(12,1,18,'2026-10-03 15:57:55'),(13,1,16,'2026-10-03 15:57:55'),(14,1,17,'2026-10-03 15:57:55'),(15,1,3,'2026-10-03 15:57:55'),(16,1,1,'2026-10-03 15:57:55'),(17,1,2,'2026-10-03 15:57:55'),(18,1,11,'2026-10-03 15:57:55'),(19,1,10,'2026-10-03 15:57:55'),(20,1,15,'2026-10-03 15:57:55'),(21,1,14,'2026-10-03 15:57:55'),(22,1,12,'2026-10-03 15:57:55'),(23,1,13,'2026-10-03 15:57:55'),(32,2,40,'2026-10-03 15:59:04'),(33,2,24,'2026-10-03 15:59:04'),(34,2,37,'2026-10-03 15:59:04'),(35,2,38,'2026-10-03 15:59:04'),(36,2,36,'2026-10-03 15:59:04'),(37,2,41,'2026-10-03 15:59:04'),(39,2,26,'2026-10-03 15:59:04'),(40,2,28,'2026-10-03 15:59:04'),(41,2,25,'2026-10-03 15:59:04'),(42,2,27,'2026-10-03 15:59:04'),(47,3,42,'2026-10-03 15:59:35'),(48,3,40,'2026-10-03 15:59:35'),(49,3,24,'2026-10-03 15:59:35'),(50,3,39,'2026-10-03 15:59:35'),(51,3,37,'2026-10-03 15:59:35'),(52,3,38,'2026-10-03 15:59:35'),(53,3,36,'2026-10-03 15:59:35'),(54,3,35,'2026-10-03 15:59:35'),(55,3,43,'2026-10-03 15:59:35'),(56,3,34,'2026-10-03 15:59:35'),(57,3,31,'2026-10-03 15:59:35'),(58,3,33,'2026-10-03 15:59:35'),(59,3,30,'2026-10-03 15:59:35'),(60,3,32,'2026-10-03 15:59:35'),(61,3,41,'2026-10-03 15:59:35'),(62,3,29,'2026-10-03 15:59:35'),(63,3,26,'2026-10-03 15:59:35'),(64,3,28,'2026-10-03 15:59:35'),(65,3,25,'2026-10-03 15:59:35'),(66,3,27,'2026-10-03 15:59:35'),(67,3,8,'2026-10-03 15:59:35'),(68,3,9,'2026-10-03 15:59:35'),(69,3,6,'2026-10-03 15:59:35'),(70,3,4,'2026-10-03 15:59:35'),(71,3,7,'2026-10-03 15:59:35'),(72,3,5,'2026-10-03 15:59:35'),(73,3,22,'2026-10-03 15:59:35'),(74,3,19,'2026-10-03 15:59:35'),(75,3,23,'2026-10-03 15:59:35'),(76,3,21,'2026-10-03 15:59:35'),(77,3,20,'2026-10-03 15:59:35'),(78,3,18,'2026-10-03 15:59:35'),(79,3,16,'2026-10-03 15:59:35'),(80,3,17,'2026-10-03 15:59:35'),(81,3,3,'2026-10-03 15:59:35'),(82,3,1,'2026-10-03 15:59:35'),(83,3,2,'2026-10-03 15:59:35'),(84,3,11,'2026-10-03 15:59:35'),(85,3,10,'2026-10-03 15:59:35'),(86,3,15,'2026-10-03 15:59:35'),(87,3,14,'2026-10-03 15:59:35'),(88,3,12,'2026-10-03 15:59:35'),(89,3,13,'2026-10-03 15:59:35'),(118,3,44,'2026-10-08 15:38:00'),(119,3,45,'2026-10-08 15:38:00'),(120,3,47,'2026-10-08 19:48:44'),(121,3,46,'2026-10-08 19:48:44'),(122,1,48,'2026-10-10 13:50:00'),(123,3,48,'2026-10-10 13:50:00'),(124,1,49,'2026-10-10 19:00:00'),(125,3,49,'2026-10-10 19:00:00'),(126,2,50,'2026-10-11 10:10:00'),(127,3,50,'2026-10-11 10:10:00'),(128,1,51,'2026-10-14 22:00:00'),(129,3,51,'2026-10-14 22:00:00'),(130,1,52,'2026-10-14 22:00:00'),(131,3,52,'2026-10-14 22:00:00'),(132,1,53,'2026-10-17 14:00:00'),(133,3,53,'2026-10-17 14:00:00'),(134,1,54,'2026-10-17 14:00:00'),(135,3,54,'2026-10-17 14:00:00'),(136,1,55,'2026-10-22 16:40:00'),(137,1,56,'2026-10-22 16:40:00'),(138,3,55,'2026-10-22 16:40:00'),(139,3,56,'2026-10-22 16:40:00'),(140,2,57,'2026-10-22 17:10:00'),(141,2,58,'2026-10-22 17:10:00'),(142,2,59,'2026-10-22 17:10:00'),(143,3,57,'2026-10-22 17:10:00'),(144,3,58,'2026-10-22 17:10:00'),(145,3,59,'2026-10-22 17:10:00');


-- ---------------------------------------------------------------
-- 开发演示账号
--
-- ⚠️ 这两个账号的密码是公开的（写在 README 里），任何人都知道。
--    部署到公网前【必须】改密码，或者直接把这一段删掉。
--
--    admin    / Admin@2026   超级管理员（角色 3），可进管理端 /admin/
--    zhangsan / User@2026    普通用户（角色 1），只能进用户端
--
-- 哈希是 BCrypt(cost=10)，与后端 PasswordEncoder 的默认强度一致。
-- email 留空：演示账号不该指向任何真实邮箱（否则日程提醒会往那儿发信）。
-- ---------------------------------------------------------------
INSERT INTO `user` (`id`, `username`, `email`, `password_hash`, `nickname`,
                    `status`, `created_at`, `updated_at`, `deleted`)
VALUES (1, 'admin', NULL, '$2a$10$b3O9NKcciR6634YE25VP0ufhGb5PZv9pTPGOTprU6VN5b9UUeSOyi', '超级管理员', 1, NOW(), NOW(), 0),
       (2, 'zhangsan', NULL, '$2a$10$EJ31do2ijlCn4ze/Dt4auOLTAxFdCHJ7RZxJm/W0ftdL4i0Wy6C2S', 'zhangsan', 1, NOW(), NOW(), 0);

INSERT INTO `sys_user_role` (`id`, `user_id`, `role_id`) VALUES (1, 1, 3), (2, 2, 1);


-- ============================================================
-- 变更记录（时间倒序）
--
-- ⚠️ 这一段是【历史留档，不要在新库上执行】——
--    上面的建表语句已经是改完之后的最终状态，这里再跑一遍会重复改（列已存在）。
--    它的用途：给「已经建好但落后了」的库对照，知道该补哪些 ALTER。
--
-- ★ 所以下面整段被【块注释】包起来了。这样整份文件可以直接喂给空库：
--     mysql -h 127.0.0.1 -P 3306 -uroot -p < mindforge-schema.sql
--   不加这层注释的话，脚本会在第一条 CREATE TABLE 上就报「表已存在」而中断 ——
--   「空库一键建库」这个承诺就不成立了。
--   要把某条变更补到落后的库上，把对应的 SQL 复制出来单独执行即可。
-- ============================================================

-- ============================================================
-- 变更历史不在这里 —— 见 mindforge-增量变更.sql
--
-- 本文件只负责【空库一键建库】，可整份直接执行：
--   mysql -h 127.0.0.1 -P 3306 -uroot -p < mindforge-schema.sql
--
-- 老库升级（补齐后期新增的列 / 索引 / 权限码）看 mindforge-增量变更.sql：
-- 那边按时间倒序列出每条变更，并写明「为什么改」。
--
-- 为什么不放在本文件里：ALTER 在空库上跑会报「列已存在」而中断，
-- 「整份文件喂给空库」这个承诺就不成立了。
-- ============================================================

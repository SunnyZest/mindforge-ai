-- ============================================================
-- MindForge AI —— 增量变更记录（全项目唯一一份）
--
-- 用途：给「已经建好但落后了」的库对照，知道该补哪些 ALTER / 索引 / 权限码。
--
-- ⚠️ 空库建库【不要】执行本文件 —— 用 mindforge-schema.sql，
--    那边的建表语句已经是改完之后的最终状态。
--
-- 约定：按时间倒序（新的在上面），每条写清「为什么改」，
--      而不是只写「改了什么」—— 半年后看「为什么」才知道能不能动。
--
-- 字符集：库级已统一 utf8mb4 / utf8mb4_0900_ai_ci，这里【不写】CHARSET/COLLATE。
-- ============================================================


-- ============================================================
-- 2026-10-30  面试报告补「工程规范 / 岗位契合」两个维度
--
-- 背景：前端报告页的能力雷达图原本画 5 个角，但后端只给 3 个分数 ——
--       另外两个是前端自己编的（工程规范 = 技术分与问题解决分的平均，岗位契合直接复用总分）。
--       图上五个角看起来都评估过，实际只有三个是模型给的，会误导用户判断自己哪块弱。
--
-- 两个字段都可空，且允许只给其中一个：
--   engineering_score  工程规范：代码组织、边界与异常处理、可维护性、测试意识。
--                      刻意和技术分分开 —— 技术分看「会不会」，工程分看「做得规不规范」。
--   job_fit_score      岗位契合：与目标岗位的匹配度。
--                      ★ 没指定目标岗位时必须为 NULL —— 没有岗位就没有契合度，给个数字等于编。
--
-- ⚠️ 不做历史回填：旧报告没有这两个分项，补分只能重跑模型（成本高、结果不稳定）。
--    旧报告保持 3 维展示 + 原 overall_score 值，不重算。
--
-- ⚠️ overall_score 的口径也变了（三维加权 → 五维加权，且由 Python 侧算而不是模型算），
--    但那是 Python 的事，Java 侧字段和类型都没变。
-- ============================================================

ALTER TABLE `interview_report`
    ADD COLUMN `engineering_score` decimal(6,2) DEFAULT NULL
        COMMENT '工程规范得分（历史数据为 NULL）'
        AFTER `problem_solving_score`,
    ADD COLUMN `job_fit_score` decimal(6,2) DEFAULT NULL
        COMMENT '岗位契合得分（未指定目标岗位时为 NULL）'
        AFTER `engineering_score`;


-- ============================================================
-- 2026-10-30  sys_refresh_token 补会话字段（登录设备管理）
--
-- 背景：要给用户一个「我的登录设备」列表，能看有哪些设备登着、能踢掉可疑的。
--       原来这张表只存 token 哈希，分不清「哪台设备」——
--       而且每次刷新都轮转（旧的作废、新的插入），一个设备登一天会产生 48 条记录
--       （access token 30 分钟一刷），直接拿来展示会是一堆重复项。
--
-- 设计：
--   session_id    一次登录 = 一个会话，refresh 轮转时【继承】同一个 session_id。
--                 列表按 session_id 聚合，只显示每个会话最新的一条。
--                 雪花 id，登录时生成。
--   end_type      登录端（APP 用户端 / ADMIN 管理端）。会话天然属于某一端。
--   ip / user_agent   登录那一刻的客户端信息，用于展示「在哪登的」。
--   last_active_at    最近一次刷新令牌的时间 —— 比 created_at 更能反映「还活着」。
--
-- ⚠️ 五列都允许 NULL：存量 token 没有这些信息，而且 14 天内自然过期，不值得迁移。
--    代码里遇到 session_id 为 NULL 的老 token 会当成「新会话」重新生成，
--    所以老用户刷新一次就自动补上了。
--
-- ★ 为什么还要 revoked_reason：
--   原来「token 被作废」只有一种含义 —— 轮转（用过一次就该失效），
--   所以 refresh() 一见到已撤销的 token 就判定为泄露，直接撤销该用户【全部】会话。
--   加了「踢设备」之后这个假设不成立了：
--   用户从 A 设备踢掉 B，B 的客户端并不知道，下次刷新仍会用那个已撤销的 token ——
--   如果还按「泄露」处理，就会把 A 也一起撤销，等于「踢别人把自己踢下线」。
--   所以作废原因要分开：只有 ROTATED 才触发重放检测，其余（登出/被踢）只是拒绝这一次。
--
-- ⚠️ 部署顺序：先跑本段 DDL，再启服务。实体里已有这些字段，
--    库缺列会直接 Unknown column 'session_id'。
-- ============================================================

ALTER TABLE `sys_refresh_token`
    ADD COLUMN `session_id` bigint DEFAULT NULL
        COMMENT '会话 id：一次登录一个，refresh 轮转时继承（老数据为 NULL）'
        AFTER `created_at`,
    ADD COLUMN `end_type` varchar(16) DEFAULT NULL
        COMMENT '登录端：APP 用户端 / ADMIN 管理端'
        AFTER `session_id`,
    ADD COLUMN `ip` varchar(64) DEFAULT NULL
        COMMENT '登录时的客户端 IP'
        AFTER `end_type`,
    ADD COLUMN `user_agent` varchar(512) DEFAULT NULL
        COMMENT 'UA 原文；展示时由 UserAgentParser 粗解析'
        AFTER `ip`,
    ADD COLUMN `last_active_at` datetime DEFAULT NULL
        COMMENT '最近活跃时间，每次刷新令牌时更新'
        AFTER `user_agent`,
    ADD COLUMN `revoked_reason` varchar(16) DEFAULT NULL
        COMMENT '作废原因：ROTATED / LOGOUT / KICKED / REVOKE_OTHERS / REVOKE_ALL'
        AFTER `revoked_at`,
    ADD INDEX `idx_refresh_session` (`session_id`);


-- ============================================================
-- 2026-10-28  用户表补「最后登录 IP」
--
-- 背景：管理端用户详情要能看出「这个人从哪登的」，
--       而 user 表只有 last_login_at，没有 IP。
--       审计表 sys_audit_log 也帮不上忙 —— 用户端登录成功【刻意不记审计】
--       （正常登录频率高，全记会把审计表淹掉），所以成功登录的 IP 哪都查不到。
-- 设计：加一列，登录成功时与 last_login_at 一起写。
--       长度 64 对齐 sys_audit_log.ip_address（IPv6 最长 45，留余量）。
-- 注意：只记【成功】登录。失败登录的 IP 在审计里（USER_LOGIN / FAIL）。
-- ============================================================

ALTER TABLE `user`
    ADD COLUMN `last_login_ip` varchar(64) DEFAULT NULL
        COMMENT '最后登录IP（只记成功登录，长度对齐 sys_audit_log.ip_address）'
        AFTER `last_login_at`;


-- ============================================================
-- 2026-10-28  日程主动提醒（面试 / 笔试到点前发邮件）
--
-- 背景：job_event 里明明有待办时间，前端日历也看得到，
--       但系统从不主动告诉用户 —— 全靠自己想起来打开日历。
-- 设计：不新建 reminder 表。提醒的事实来源就是 job_event 本身，
--       另建一张表要维护两份数据，必然不同步。
--
-- 两个字段的分工：
--   reminded_at        已发提醒的时间。NULL = 还没提醒过。
--                      ★ 幂等靠【抢占】而不是靠「定时任务只跑一次」：
--                        UPDATE ... SET reminded_at = ? WHERE id = ? AND reminded_at IS NULL
--                        受影响行数 = 1 才发信。多实例、任务重跑、手动触发都不会重复发。
--   remind_fail_count  发信失败的次数。失败时把 reminded_at 置回 NULL 并 +1，
--                      达到上限（默认 3）就不再重试 —— 否则一个永久失效的邮箱会被每小时重试到天荒地老。
--
-- 索引为什么是 (status, event_at, reminded_at)：
--   扫描条件是 status = 'PENDING' AND event_at BETWEEN ? AND ? AND reminded_at IS NULL，
--   三列顺序与查询条件一致，能直接走索引区间扫描，不用回表过滤。
--
-- ⚠️ 部署顺序：先跑本段 DDL，再启服务。实体里已有这两个字段，
--    库缺列会直接 Unknown column 'reminded_at'。
-- ============================================================

ALTER TABLE `job_event`
    ADD COLUMN `reminded_at` datetime DEFAULT NULL
        COMMENT '已发提醒时间，NULL 表示未提醒（抢占式幂等的依据）'
        AFTER `status`,
    ADD COLUMN `remind_fail_count` tinyint NOT NULL DEFAULT '0'
        COMMENT '提醒发送失败次数，达到上限后不再重试'
        AFTER `reminded_at`,
    ADD INDEX `idx_event_remind` (`status`,`event_at`,`reminded_at`);


-- 提醒开关（默认开启）。
-- ★ 为什么默认 1 而不是 0：这是「本该有」的功能，不是营销邮件。
--   但必须给出口 —— 没有退订开关的定时邮件，等于给用户一个投诉理由。
ALTER TABLE `user`
    ADD COLUMN `reminder_enabled` tinyint NOT NULL DEFAULT '1'
        COMMENT '是否接收日程邮件提醒：0关闭 1开启'
        AFTER `last_login_ip`;




-- ============================================================
-- 2026-10-18  user 加 email_verified + 手机号函数唯一索引 uk_user_phone
--
-- 背景：接邮箱验证码（注册 / 找回密码）。验证码只证明「此刻你拥有这个邮箱」，
--       「已验证」是长期状态 —— 不落标记的话每次登录、每次找回密码都要重新发码。
--
-- ① 手机号的唯一性口径【刻意和邮箱不同】：
--    邮箱 / 用户名是永久占用（唯一索引不管逻辑删除，注销后也不能再用，防冒名）；
--    手机号是运营商资源，账号注销后应当能被新机主使用。
--    所以用函数索引，把「已删除」的行映射成 NULL —— NULL 不参与唯一性：
--      多个已注销账号用同一个号码 → 全是 NULL → 不冲突 ✓
--      两个活跃账号用同一个号码   → 都是号码 → 撞 1062 ✓
--    这和 2026-10-16 给 sys_ai_model 加的 uk_ai_model_default 是同一个套路。
--
-- ② 配套的代码改动（不做会撞约束或误判）：
--    - UserMapper.countByPhoneActive 只查 deleted = 0，与索引口径一致；
--    - AdminUserServiceImpl.updateProfile 里空白手机号归一成 NULL
--      （'' 也是值，两个「清空手机号」的账号会互相撞）。
--
-- 应用到落后的库：
--   ALTER TABLE `user`
--     ADD COLUMN `email_verified` tinyint NOT NULL DEFAULT 0
--       COMMENT '邮箱是否已验证：0未验证 1已验证' AFTER `email`,
--     ADD UNIQUE KEY `uk_user_phone` ((CASE WHEN `deleted` = 0 THEN `phone` ELSE NULL END));
--   UPDATE `user` SET `email_verified` = 1 WHERE `email` IS NOT NULL;  -- 存量按「能登录即可信」处理
-- ============================================================

-- ============================================================
-- 2026-10-17  新增第 29 张表 salary_estimate + 权限码 app:salary:read / app:salary:write
--
-- 背景：ROADMAP 二-5「市场薪酬测算」的后端落地。刻意做成「AI 估算」而不是
--       「市场行情」—— 我们没有真实薪酬样本，任何让人以为有数据源的字段
--       （样本量、数据来源）都不加。
--
-- ① 为什么估算结果要落表：
--    一行同时当【缓存】和【历史】。request_key 是参数指纹，
--    同一用户 + 同参数 7 天内直接复用旧记录，避免连点几次重复烧 token。
-- ② 为什么不存 model_name：
--    用户端一律不暴露模型（与 /app/ai/status 同一口径），存了只会诱使前端展示。
-- ③ confidence 存的是【服务端按规则钳制后】的值，不是模型自评 ——
--    模型普遍过于自信，城市/岗位/年限不在常见组合里时必须降档。
--
-- 应用到落后的库：
--   CREATE TABLE `salary_estimate` ( ... 见上文第四节 ... );
--   INSERT INTO `sys_permission` (...) VALUES
--     (53,'薪酬估算查看','app:salary:read','API',NULL,NULL,'查看 AI 薪酬估算历史',NOW(),NOW()),
--     (54,'薪酬估算','app:salary:write','API',NULL,NULL,'发起 AI 薪酬估算',NOW(),NOW());
--   INSERT INTO `sys_role_permission` (id, role_id, permission_id, created_at) VALUES
--     (132,1,53,NOW()),(133,3,53,NOW()),(134,1,54,NOW()),(135,3,54,NOW());
-- ============================================================

-- ============================================================
-- 2026-10-16  给 sys_ai_model 加函数唯一索引 uk_ai_model_default
--
-- 背景：「同一用途只有一个默认」原本只在代码里保证
--       （AdminAiModelServiceImpl.clearOtherDefaults）。但代码约束挡不住
--       手工改库、脚本导入、以后新写的入口 —— 一旦出现两个默认，
--       Python 侧 ai_config 取哪条就看运气，而且不报错。
--
-- ① 为什么不是普通唯一索引 (scene, is_default)：
--    那样多个 is_default=0 会互相冲突（0 也参与唯一性），
--    而一个 scene 下本来就允许多条非默认。原代码注释里已经踩过这个坑。
--
-- ② 用函数索引表达：把「非默认 / 已禁用」映射成 NULL，
--    SQL 里 NULL 不参与唯一性，于是
--      多个「非默认」→ 全是 NULL → 不冲突 ✓
--      两个「启用中的默认」→ 都是 1 → 撞 1062 ✓
--    实测：UPDATE 第二条为默认时报
--      ERROR 1062 Duplicate entry 'CHAT-1' for key 'sys_ai_model.uk_ai_model_default'
--
-- ③ ★ 配套的代码改动（必须一起做，否则应用自己会撞约束）：
--    create/update 里的 clearOtherDefaults 从「先设再清」改成【先清再设】。
--    MySQL 不支持延迟约束，唯一性在语句执行时立即检查 ——
--    先设新默认时旧默认还在，直接 1062。
--    两个操作在同一事务里，清了之后插入失败会一起回滚，不会留下「没有默认」。
-- ============================================================

ALTER TABLE `sys_ai_model`
  ADD UNIQUE KEY `uk_ai_model_default`
  (`scene`, (CASE WHEN `is_default` = 1 AND `status` = 1 THEN 1 ELSE NULL END));

-- ============================================================
-- 2026-10-16  新增 job_event 表（面试日历 / 求职日程）
--
-- 背景：ROADMAP 二-4 提到的「面试日历提醒」只做了一半 —— 看板卡片上有
--       job.next_action_at 的待办提醒，但没有日历视图，也装不下多家公司多场次。
--
-- ① 为什么不复用 job.next_action_at：
--    那个字段只有一个时间点，装不下「周三笔试 + 下周一技术一面 + 下周五 HR 面」
--    这种真实情况，也记不了形式（现场/线上）、地点、时长、备注。
--    日历要的是「时间轴上的多个事件」，必须是独立实体。
--
-- ② 两者的关系：job_event 是日程的【事实来源】，job.next_action_at 变成派生值
--    （= 最近一场未完成的日程），由 JobEventServiceImpl 在日程增删改后回写 ——
--    这样看板卡片的「待办提醒」和日历里看到的永远是同一件事。
--    ⚠️ 没有未来日程时【不回写空值】，避免把用户手填的提醒清掉。
--
-- ③ 权限码复用 app:job:read / app:job:write，不新开码：
--    日程是职位的子资源（每条必挂一个职位），能看职位就该能看自己的日程。
--
-- ④ 删除职位时级联【逻辑删】其日程（job_event 有 deleted 列，走 MP 通用删除）。
--    留下的行引用着已删职位，但查询都带 deleted=0 过滤，日历里看不到
--    —— 与 document_chunk 的孤儿数据是同一套已知约定。
--
-- ⑤ 索引 (user_id, event_at)：日历的查询就是「某用户某时间段」，天然贴合。
-- ============================================================

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
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除：0 未删除 / 1 已删除',
  PRIMARY KEY (`id`),
  KEY `idx_event_user_time` (`user_id`,`event_at`),
  KEY `idx_event_job` (`job_id`)
) ENGINE=InnoDB COMMENT='求职日程表（面试日历）';

-- ============================================================
-- 2026-10-14  新增 job_offer 表 + 权限码 app:offer:read / app:offer:write
--
-- 背景：ROADMAP 二-5「Offer 对比器（五维打分表）」落地。
--       新表主键同样不带 AUTO_INCREMENT（沿用 10-13 起的雪花 ID 约定）。
--
-- ① 为什么五个维度都由用户打分，而不是按薪资自动算分：
--    自动按「最高薪归一化」会让最高薪那家永远满分，而用户对薪资的满意度是非线性的
--    （30 万可能已经够了，35 万不会让他更满意多少）。打分表的价值就在用户的主观权衡。
--    salary_annual 只用于展示参考，不参与计算。
-- ② 为什么权重不落库、由对比请求带过来：
--    权重表达的是「你现在最看重什么」，会随心境变。落库就要设计「改权重」的接口
--    和并发问题；放在请求里则拖一下滑块就能实时看排名变化。
-- ③ 权限码 app:offer:read(51) / app:offer:write(52)：绑 USER(1) + SUPER_ADMIN(3)，
--    与 app:job:* 的绑定方式一致（ADMIN 只有管理端权限，不给用户端业务码）。
-- ============================================================

CREATE TABLE `job_offer` ( ... 见上面的建表语句 ... );

INSERT INTO `sys_permission`
  (`id`, `name`, `code`, `type`, `path`, `method`, `description`, `created_at`, `updated_at`)
VALUES
  (51,'Offer查看','app:offer:read','API',NULL,NULL,'查看与对比 Offer','2026-10-14 22:00:00','2026-10-14 22:00:00'),
  (52,'Offer管理','app:offer:write','API',NULL,NULL,'录入和管理 Offer','2026-10-14 22:00:00','2026-10-14 22:00:00');

INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`, `created_at`) VALUES
  (128,1,51,'2026-10-14 22:00:00'),(129,3,51,'2026-10-14 22:00:00'),
  (130,1,52,'2026-10-14 22:00:00'),(131,3,52,'2026-10-14 22:00:00');

-- ============================================================
-- 2026-10-13  全库 26 张表主键去掉 AUTO_INCREMENT，切换为雪花算法（ASSIGN_ID）
-- ============================================================

ALTER TABLE `user` MODIFY COLUMN `id` bigint NOT NULL COMMENT '用户id';
ALTER TABLE `sys_role` MODIFY COLUMN `id` bigint NOT NULL COMMENT '不同角色(role)id';
ALTER TABLE `sys_permission` MODIFY COLUMN `id` bigint NOT NULL;
ALTER TABLE `sys_user_role` MODIFY COLUMN `id` bigint NOT NULL;
ALTER TABLE `sys_role_permission` MODIFY COLUMN `id` bigint NOT NULL;
ALTER TABLE `sys_refresh_token` MODIFY COLUMN `id` bigint NOT NULL;
ALTER TABLE `sys_file` MODIFY COLUMN `id` bigint NOT NULL COMMENT '主键';
ALTER TABLE `knowledge_base` MODIFY COLUMN `id` bigint NOT NULL COMMENT '主键';
ALTER TABLE `document` MODIFY COLUMN `id` bigint NOT NULL;
ALTER TABLE `document_chunk` MODIFY COLUMN `id` bigint NOT NULL;
ALTER TABLE `conversation` MODIFY COLUMN `id` bigint NOT NULL;
ALTER TABLE `chat_message` MODIFY COLUMN `id` bigint NOT NULL;
ALTER TABLE `chat_source` MODIFY COLUMN `id` bigint NOT NULL;
ALTER TABLE `resume` MODIFY COLUMN `id` bigint NOT NULL COMMENT '主键';
ALTER TABLE `resume_skill` MODIFY COLUMN `id` bigint NOT NULL COMMENT '主键';
ALTER TABLE `job` MODIFY COLUMN `id` bigint NOT NULL COMMENT '主键';
ALTER TABLE `job_skill` MODIFY COLUMN `id` bigint NOT NULL COMMENT '主键';
ALTER TABLE `resume_job_analysis` MODIFY COLUMN `id` bigint NOT NULL COMMENT '主键';
ALTER TABLE `interview` MODIFY COLUMN `id` bigint NOT NULL COMMENT '主键';
ALTER TABLE `interview_question` MODIFY COLUMN `id` bigint NOT NULL COMMENT '主键';
ALTER TABLE `interview_report` MODIFY COLUMN `id` bigint NOT NULL COMMENT '主键';
ALTER TABLE `sys_ai_model` MODIFY COLUMN `id` bigint NOT NULL COMMENT '主键';
ALTER TABLE `sys_prompt` MODIFY COLUMN `id` bigint NOT NULL COMMENT '主键';
ALTER TABLE `ai_usage_record` MODIFY COLUMN `id` bigint NOT NULL COMMENT '主键';
ALTER TABLE `sys_audit_log` MODIFY COLUMN `id` bigint NOT NULL COMMENT '主键';
ALTER TABLE `sys_alert` MODIFY COLUMN `id` bigint NOT NULL COMMENT '主键';

-- ============================================================
-- 2026-10-11  新增 sys_alert 表 + 权限码 admin:alert:list
--
-- 背景：安全待办第一条「高危操作告警」。审计只是被动记录，没人看等于没有 ——
-- 现在改权限 / 分配角色 / 删用户 / 改 AI 配置 / 改提示词这些高危操作发生时，
-- 除了审计留痕，还会生成一条带「已读状态」的告警，管理端能直接看到未读角标。
--
-- ① 为什么单独一张表而不是在审计上打「已读」标记：
--    审计是「发生过什么」，只读、永久留痕（连删除接口都没有）；
--    告警是「需要有人看」，读完就完成使命，需要可变的已读状态。
--    把可变状态塞进只读表会破坏审计「不可篡改」的语义。
-- ② 成功和失败的高危操作都告警 —— 失败往往更可疑（有权限没层级、在试探）。
--    ACCESS_DENIED 和登录失败刻意不告警：可能被攻击探测刷屏，告警疲劳会淹没真告警。
-- ③ webhook 外推（企业微信 / 钉钉 text 格式）走配置项 mindforge.alert.webhook-url，
--    留空 = 只入库不外推；推送失败只打 WARNING，绝不影响业务和审计。
-- ④ 权限码 admin:alert:list(50)：绑 ADMIN(2) + SUPER_ADMIN(3)。
--    看告警的人越多越好，ADMIN 看到高层级的操作记录没有安全问题。
-- ============================================================

CREATE TABLE `sys_alert` ( ... 见上面的建表语句 ... );

INSERT INTO `sys_permission`
 (`id`, `name`, `code`, `type`, `path`, `method`, `description`, `created_at`, `updated_at`)
VALUES
 (50,'高危操作告警','admin:alert:list','API',NULL,NULL,'查看高危操作告警并标记已读','2026-10-11 10:10:00','2026-10-11 10:10:00');

INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`, `created_at`) VALUES
 (126,2,50,'2026-10-11 10:10:00'),(127,3,50,'2026-10-11 10:10:00');

-- ============================================================
-- 2026-10-10  sys_file 加 biz_type + 新增头像上传接口
--
-- 背景：用户个人中心要能上传头像（原先只能填一个外部图片 URL）。
--
-- ① 为什么加 biz_type 这一列：
--    头像要能被 <img src> 直接加载，所以读取接口必须【公开】
--    （img 标签带不上 Authorization 头）。公开读就有一个安全边界：
--    只能读头像，绝不能让别人顺着同一个接口把简历 / 文档也拉走
--    （sys_file.id 是可枚举的）。
--
--    本来想靠 object_key 的前缀（avatars/…）判断，但不行 ——
--    FileServiceImpl 的秒传按 (userId, contentHash) 去重、【不区分业务】，
--    同一份字节先当简历传、再当头像是，头像记录的 objectKey 会指向 resumes/…。
--    所以必须让 sys_file 自己记住「我属于哪个业务」。
--
-- ② 回填：老数据按 object_key 的第一段推（秒传跨业务复用的那几条会推错，
--    但那是开发期数据，且读取看的是 biz_type 而不是前缀）。
--
-- ③ 接口（权限按项目默认：个人资料类接口不挂权限码，登录即可）：
--      POST /api/app/user/avatar        上传头像（multipart，≤2MB，仅图片）
--      GET  /api/app/avatars/{fileId}   读头像（★ 公开，302 跳预签名 URL）
--    ⚠️ 公开读的边界完全由 AvatarController 自己兜（只放 biz_type=avatars），
--       SecurityConfig 里放行 /app/avatars/** 之后它没有任何鉴权兜底。
-- ============================================================

ALTER TABLE `sys_file`
 ADD COLUMN `biz_type` varchar(32) NOT NULL DEFAULT '' COMMENT '业务类型：documents / resumes / avatars' AFTER `content_hash`;

UPDATE `sys_file` SET `biz_type` = SUBSTRING_INDEX(`object_key`, '/', 1) WHERE `biz_type` = '';

-- ============================================================
-- 2026-10-10  新增 sys_prompt 表 + 权限码 app:prompt:read
--
-- 背景：阶段 18 Prompt 管理落地（原先用户 2026-10-08 决定暂缓，
-- 后来因为「管理端加 prompt 供用户切换」这个需求又开了）。
--
-- ① 为什么是「scene + name + version」三层，而不是原设计的「name + version」：
--    原设计里一个 prompt 只有版本历史（发布/回滚）。但「供用户切换」意味着
--    同一个场景下要【多个并存的变体】—— 那是不同的变体，不是同一个的旧版本。
--    所以拆成：
--      scene + name → 一个变体（用户切换的单位）
--      version      → 该变体的历史（发布/回滚的单位）
--    UNIQUE (scene, name, version) 保证同一变体下版本号不重复。
--
-- ② 为什么需要 user_selectable / is_default 两个标记：
--    user_selectable=1 才出现在用户的切换列表里 —— 因为 8 个 prompt 里有 7 个
--    的输出格式是【内部契约】（resume_parse 必须吐特定 JSON 等），
--    让用户随便切会直接让解析/出题挂掉。由管理员逐条决定哪些能暴露给用户。
--    is_default=1 是「用户没指定时用哪条」，一个 scene 下应当只有一条。
--
-- ③ 权限码 app:prompt:read(49)：用户端要能列出「有哪些回答风格可选」。
--    admin:prompt:manage(43) 早就种好了，管理端不用加。
-- ============================================================

CREATE TABLE `sys_prompt` ( ... 见上面的建表语句 ... );

INSERT INTO `sys_permission` (`id`, `name`, `code`, `type`, `path`, `method`, `description`, `created_at`, `updated_at`) VALUES
 (49,'提示词查看','app:prompt:read','API',NULL,NULL,'用户端查看可选的回答风格','2026-10-10 19:00:00','2026-10-10 19:00:00');

INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`, `created_at`) VALUES
 (124,1,49,'2026-10-10 19:00:00'),(125,3,49,'2026-10-10 19:00:00');

-- ============================================================
-- 2026-10-10  resume_job_analysis 加 match_items_json + 新增权限码 app:resume:match
--
-- 背景：阶段 13 简历-JD 匹配接上了（Java 调 Python 的 /resumes/match）。
--
-- ① 为什么加 match_items_json：
--    匹配的结论刻意【不用百分比】，用五类 ——
--      已匹配 / 表达缺口 / 证据不足 / 真实缺口 / 待确认
--    因为百分比没法行动：「73%」既不告诉他差在哪，也不告诉他去改什么；
--    而「表达缺口：把『负责订单系统开发』改成带量级的说法」可以直接照着改。
--    原有的四个列（matched/missing × skills/experience）表达不了五类，
--    硬塞进去会让列名和内容对不上，所以单开一列存完整的逐条结论。
--    那四个列保留，继续放「已匹配 / 真实缺口」的摘要（前端表格列要用）。
--
-- ② 为什么顺带改 analysis_text 的注释：
--    它原来写的是「完整分析文本」，但实际存的是一段 200 字以内的整体判断
--    （整体结论 + 五类统计 + 优先动作）。刻意不生成一份 Markdown 长报告 ——
--    五类细节已经在 match_items_json 里了，再写一份等于把同样内容输出两遍，
--    实测那样单次要 200 秒，离模型 300 秒超时就没多少余量了。注释要跟代码一致。
--
-- ③ 为什么单独加权限码：
--    匹配是一次真实的模型调用（烧 token），和「看简历」不是一回事，
--    不能复用 app:resume:read。前端也用它决定「开始匹配」按钮显不显示。
--    ⚠️ 只绑 USER(1) 和 SUPER_ADMIN(3) —— ADMIN(2) 只有管理端权限。
--
-- ⚠️ 同一对（简历 + 职位）只保留一条结果，重跑覆盖。
--    表上的 idx_analysis_resume_job 【不是唯一索引】，这个约束靠代码保证
--    （见 ResumeJobAnalysisServiceImpl#persist）。
-- ============================================================

ALTER TABLE `resume_job_analysis`
 ADD COLUMN `match_items_json` json DEFAULT NULL
     COMMENT '逐条要求的匹配结论（五类：MATCHED/EXPRESSION_GAP/INSUFFICIENT_EVIDENCE/REAL_GAP/UNCERTAIN），数组'
     AFTER `missing_experience_json`,
 MODIFY COLUMN `analysis_text` longtext
     COMMENT 'AI 生成的整体分析（整体判断 + 五类统计 + 优先动作）';

INSERT INTO `sys_permission`
 (`id`, `name`, `code`, `type`, `path`, `method`, `description`, `created_at`, `updated_at`)
VALUES
 (48,'简历匹配','app:resume:match','API',NULL,NULL,'简历与职位匹配分析','2026-10-10 13:50:00','2026-10-10 13:50:00');

INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`, `created_at`) VALUES
 (122,1,48,'2026-10-10 13:50:00'),(123,3,48,'2026-10-10 13:50:00');

-- ============================================================
-- 2026-10-09  resume / job 加 error_message（解析失败原因）
--
-- 背景：简历和 JD 的 AI 解析接上了（Java 调 Python 的 /resumes/parse、/jobs/parse）。
-- 解析会失败 —— 文件是扫描件 PDF 没有文本层、模型输出不是 JSON、AI 服务没起来。
-- 失败后前端要能告诉用户「为什么失败」，而不是只有一个 FAILED。
--
-- 为什么是 varchar(500) 而不是 text：
--   存的是给人看的一句话，不是日志。超长的原始异常在写入前会截断
--   （见 ResumeServiceImpl#truncateError），完整堆栈在服务日志里。
--
-- 为什么不像 document 表那样分阶段记：
--   document 的处理是【异步流水线】（Python 轮询认领、分三步推进），
--   每一步都可能单独失败，所以要三个 status + 一个 error_message。
--   简历 / JD 是【同步调用】，一次请求要么成要么败，一个状态就够。
-- ============================================================

ALTER TABLE `resume` ADD COLUMN `error_message` varchar(500) DEFAULT NULL COMMENT '解析失败原因（最近一次）' AFTER `parse_status`;
ALTER TABLE `job`    ADD COLUMN `error_message` varchar(500) DEFAULT NULL COMMENT '解析失败原因（最近一次）' AFTER `parse_status`;

-- ============================================================
-- 2026-10-08  新建 sys_ai_model（AI 模型配置，阶段 17）
--
-- 背景：管理端要维护 AI 模型配置（提供方 / 模型名 / base_url / API Key）。
-- 为什么单独一张表而不是塞进配置文件：
--   配置要能被管理员在界面上改、能有多套（对话 / 向量化 / 重排序），
--   而且 API Key 需要按配置加密存储 —— 这些都不是 application.yml 能承担的。
--
-- 设计要点：
--   1. api_key_cipher 存 AES-GCM 密文（Base64(IV‖密文+标签)），
--      加密密钥在 mindforge.ai-config.secret-key，不进版本库。
--      api_key_hint 只存尾部 4 位（如 ****abcd），供列表展示 ——
--      对外永远不返回明文，也不返回密文。
--   2. 【没有 deleted 列】：删除是物理删，连带清掉 Key 密文。
--      对这张表来说这是好事 —— 不留一份没人管、却还能解开的凭据。
--      （和 sys_permission 等其它配置表一致）
--   3. is_default 用「同 scene 只有一个默认」的业务规则保证，没建唯一索引 ——
--      MySQL 唯一索引对「多行 is_default=0」不友好，而条件唯一索引本项目没用过。
--   4. scene 是 CHAT / EMBEDDING / RERANK —— 代码里按它分支，所以做了取值校验。
--
-- 权限：复用已有的 admin:ai:manage(42)，只给 SUPER_ADMIN，不用新增权限码。
-- ============================================================

CREATE TABLE sys_ai_model (
 id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
 name            VARCHAR(64)  NOT NULL COMMENT '配置显示名，如「对话主模型」',
 scene           VARCHAR(32)  NOT NULL COMMENT '用途：CHAT 对话 / EMBEDDING 向量化 / RERANK 重排序',
 provider        VARCHAR(64)  NOT NULL COMMENT '提供方：dashscope / deepseek / openai',
 model_name      VARCHAR(128) NOT NULL COMMENT '模型名，如 deepseek-v4-flash',
 base_url        VARCHAR(255) DEFAULT NULL COMMENT 'API 地址（OpenAI 兼容协议的 base_url）',
 api_key_cipher  VARCHAR(1024) DEFAULT NULL COMMENT 'API Key 密文（AES-GCM + Base64）。绝不返回给前端、绝不进日志',
 api_key_hint    VARCHAR(32)  DEFAULT NULL COMMENT 'Key 尾部提示（如 ****abcd），仅供列表展示',
 temperature     DECIMAL(3,2) DEFAULT NULL COMMENT '采样温度',
 max_tokens      INT          DEFAULT NULL COMMENT '单次最大输出 token',
 timeout_seconds INT          DEFAULT NULL COMMENT '调用超时（秒）',
 is_default      TINYINT      NOT NULL DEFAULT 0 COMMENT '同 scene 下的默认配置：1 是 / 0 否',
 status          TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用 / 0 禁用',
 description     VARCHAR(255) DEFAULT NULL COMMENT '备注',
 created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
 updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
 PRIMARY KEY (id),
 KEY idx_ai_model_scene (scene, status)
) COMMENT='AI 模型配置表';

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
-- 2026-10-08  新增「知识库删除 / 文档状态修改」两个权限码
--
-- 背景：阶段 16 补上管理端知识库和文档管理，其中两个操作没有对应权限码。
-- 为什么不复用现成的：
--   · 删知识库 —— 现有只有 admin:knowledge:list（「能看」和「能删别人的数据」
--     不该是同一个权限）
--   · 改文档状态 —— 现有 admin:document:retry 只把状态重置为 PENDING（方向安全），
--     而改状态能直接把文档标成 SUCCESS，会让检索拿到不完整的内容，是能破坏数据的操作
-- 授权范围：只给 SUPER_ADMIN。
--   对齐 admin:document:delete —— ADMIN 能「看」和「重试」，但不能「删」和「改状态」。
-- ============================================================

INSERT INTO sys_permission (id, name, code, type, description, created_at, updated_at) VALUES
 (46, '知识库删除管理', 'admin:knowledge:delete', 'API', '管理端删除任意用户的知识库', NOW(), NOW()),
 (47, '文档状态管理',   'admin:document:update',  'API', '管理端修改文档处理状态', NOW(), NOW());

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
CROSS JOIN sys_permission p
WHERE r.code = 'SUPER_ADMIN'
AND p.code IN ('admin:knowledge:delete', 'admin:document:update');


-- ============================================================
-- 2026-10-08  新增「创建用户 / 删除用户」两个权限码
--
-- 背景：阶段 5 补上 POST /api/admin/users 和 DELETE /api/admin/users/{id}，
--       但 sys_permission 里没有对应的权限码。
--       （其他 user 相关权限都是 admin:user:update / :disable / :assign-role，
--         语义都对不上，不能复用 —— 用错权限码会让「谁能做什么」变得说不清。）
-- 授权范围：只给 SUPER_ADMIN。
--       ADMIN 现有的 10 个权限全是「只读 + 禁用 + 重试文档」，
--       建号和删号比禁用重得多，和 assign-role 同级，不该下放。
-- ============================================================

INSERT INTO sys_permission (id, name, code, type, description, created_at, updated_at) VALUES
 (44, '创建用户', 'admin:user:create', 'API', '管理端创建用户', NOW(), NOW()),
 (45, '删除用户', 'admin:user:delete', 'API', '管理端删除用户', NOW(), NOW());

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
CROSS JOIN sys_permission p
WHERE r.code = 'SUPER_ADMIN'
AND p.code IN ('admin:user:create', 'admin:user:delete');


-- ============================================================
-- 2026-10-08  对话反馈
--
-- 背景：文档定义了 POST /api/app/rag/feedback 接口，
--       但 chat_message 表没有存反馈的地方。
-- 设计：一条消息只保留一个反馈，重复提交覆盖（不加历史表）。
-- ============================================================

ALTER TABLE chat_message
 ADD COLUMN rating VARCHAR(8) NULL
     COMMENT '用户反馈：UP 有帮助 / DOWN 没帮助',
 ADD COLUMN feedback_reason VARCHAR(500) NULL
     COMMENT '反馈补充说明',
 ADD COLUMN feedback_at DATETIME NULL
     COMMENT '反馈时间';


-- ============================================================
-- 2026-10-07  角色层级（垂直越权防护）
--
-- 背景：光靠 @PreAuthorize 只能判断「有没有某个权限」，
--       判断不了「能不能操作这个对象」。
--       比如 ADMIN 有 admin:user:disable 权限，那他能禁用 SUPER_ADMIN 吗？
--       权限码层面是「有权限」的，但从层级上讲不该动比自己高的人。
-- 规则：只能操作层级【严格小于】自己的角色 / 用户。
--       「严格小于」不是「小于等于」—— 防止超管误操作把自己权限清空、
--       导致系统失去管理员。
-- 内置层级：USER = 10、ADMIN = 50、SUPER_ADMIN = 100
-- ============================================================

ALTER TABLE sys_role
 ADD COLUMN level INT NOT NULL DEFAULT 0
     COMMENT '角色层级，数值越大权限越高。垂直越权防护：不能操作层级 >= 自己的角色/用户';

UPDATE sys_role SET level = 10  WHERE code = 'USER';
UPDATE sys_role SET level = 50  WHERE code = 'ADMIN';
UPDATE sys_role SET level = 100 WHERE code = 'SUPER_ADMIN';



-- ============================================================
-- 后续变更追加在这里（放在最上面，保持时间倒序）
-- ============================================================

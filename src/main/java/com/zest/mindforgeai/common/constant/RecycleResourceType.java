package com.zest.mindforgeai.common.constant;

import lombok.Getter;

/**
 * 回收站支持的资源类型。
 *
 * <p>★ <b>表名在这里写死，绝不拼请求参数</b>：恢复与彻底删除必须用原生 SQL
 * 绕过 MyBatis-Plus 的逻辑删除拦截（MP 会给 {@code updateById} 自动补
 * {@code deleted = 0}，永远改不到一条 {@code deleted = 1} 的记录），
 * 而表名一旦来自用户输入就是 SQL 注入点。白名单是这里唯一可信的来源。
 *
 * <p>★ <b>只放「删除时没有任何物理清理」的类型</b>：删的时候只把 {@code deleted} 置 1，
 * 恢复就是改回 0，完整且零副作用。
 *
 * <p>⚠️ 一开始以为「职位 / 面试 / 对话」也属于这一类，把五个 delete 方法读完才发现不是：
 * <ul>
 *   <li>职位：{@code job_skill} 与 {@code resume_job_analysis} 是<b>物理删</b>；</li>
 *   <li>面试：{@code interview_question} 与 {@code interview_report} 是<b>物理删</b>；</li>
 *   <li>对话：{@code chat_message} 与 {@code chat_source} 是<b>物理删</b> —— 恢复出来是个空壳。</li>
 * </ul>
 * 这三种要么补 payload 快照（删除时把将被物理删的关联数据序列化进 recycle_bin，
 * 恢复时反序列化写回），要么恢复后重新解析 / 重新生成，属于下一期。
 * 简历与文档 / 知识库同理（技能、向量 chunk 都会被清）。
 *
 * @author crx
 * @since 2026-10-22
 */
@Getter
public enum RecycleResourceType {

    OFFER("job_offer", "Offer"),
    JOB_EVENT("job_event", "面试日程"),
    /**
     * 职位（二期）。
     *
     * <p>⚠️ 它**不是**纯逻辑删除：删职位时会物理删掉 {@code job_skill} 与
     * {@code resume_job_analysis}（两张表都没有 deleted 列），并级联逻辑删
     * {@code job_event}。所以恢复必须靠 {@code payload_json} 里的快照重建，
     * 见 {@code JobRecycleHandler}。
     */
    JOB("job", "职位"),
    /**
     * 模拟面试（二期）。
     *
     * <p>⚠️ 同样不是纯逻辑删除：删面试会物理删 {@code interview_question} 与
     * {@code interview_report}（都没有 deleted 列）。这两样是 AI 生成的，丢了要重新烧 token，
     * 所以必须靠 {@code payload_json} 快照恢复，见 {@code InterviewRecycleHandler}。
     */
    INTERVIEW("interview", "模拟面试"),
    /**
     * 对话（二期）。
     *
     * <p>⚠️ 删对话会物理删 {@code chat_message} 与 {@code chat_source}。
     * 恢复时 {@code chat_source.message_id} 要重映射到新雪花，见
     * {@code ConversationRecycleHandler} —— 这是三种资源里唯一有「子表引用子表」的。
     */
    CONVERSATION("conversation", "对话"),
    /**
     * 简历（三期）。
     *
     * <p>⚠️ 删简历会物理删 {@code resume_skill} 与 {@code resume_job_analysis}，
     * 但**不需要重新解析** —— 正文与解析结果在主表的 {@code structured_json} 里，
     * 逻辑删不碰它。见 {@code ResumeRecycleHandler}。
     */
    RESUME("resume", "简历"),
    /**
     * 知识库（三期）。
     *
     * <p>★ 它**几乎全是逻辑删**（`knowledge_base` / `document` / `conversation` 置 deleted，
     * `document_chunk` 压根不动），所以 payload 只记「删除那一刻被级联删掉了哪些 id」，
     * 恢复时精确改回 0。唯一的坑是向量可能已被 Python 清掉，见
     * {@code KnowledgeBaseRecycleHandler}。
     */
    KNOWLEDGE_BASE("knowledge_base", "知识库"),
    /**
     * 文档（三期）。
     *
     * <p>★ 最简单的一个：删除只逻辑删 {@code document} + 软删 {@code sys_file}，
     * **{@code document_chunk} 完全不动**。恢复只要把 sys_file 的 deleted_at 清空。
     * 唯一的坑是向量可能已被 Python 清掉，见 {@code DocumentRecycleHandler}。
     */
    DOCUMENT("document", "文档");

    /** 原表名 —— 必须是本枚举里写死的字面量 */
    private final String table;

    /** 中文名，给前端列表展示 */
    private final String label;

    RecycleResourceType(String table, String label) {
        this.table = table;
        this.label = label;
    }

    /**
     * 按名字查类型，找不到返回 {@code null}。
     *
     * <p>刻意不抛异常：这是工具方法，报错文案与错误码属于调用方的业务判断
     * （比如列表接口遇到不认识的类型可以直接忽略筛选条件，而不是 500）。
     */
    public static RecycleResourceType of(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String normalized = name.trim().toUpperCase();
        for (RecycleResourceType type : values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        return null;
    }
}

package com.zest.mindforgeai.service;

/**
 * 知识库级联清理。
 * <p>
 * 「删知识库时连带清理它下面的文档和对话」是一条业务规则，
 * 用户端删自己的库、管理端删别人的库，走的是<b>同一套规则</b>。
 * <p>
 * 为什么单独抽出来：本项目<b>所有表都没有外键</b>，关联清理全靠代码手动做，
 * 漏一处就是孤儿数据。这条规则如果两边各写一遍，将来加一张关联表时
 * 很容易只改一处 —— 那正是「删了库但数据还在」这类问题的来源。
 * <p>
 * ⚠️ 本组件<b>不做任何权限校验</b>。调用方必须自己保证
 * 「当前用户有权删这个知识库」（用户端靠归属校验，管理端靠权限码）。
 *
 * @author crx
 * @since 2026-10-08
 */
public interface KnowledgeBaseCascadeService {

    /**
     * 级联删除：文档 + 对话一起清理，最后逻辑删除知识库本身。
     * <p>
     * 必须在事务里调用 —— 三步要么全成，要么全不成。
     *
     * @param knowledgeBaseId 知识库 id
     * @return 各关联表的删除条数，供审计记录
     */
    CascadeResult deleteCascade(Long knowledgeBaseId);

    /**
     * 级联删除的结果。
     *
     * @param documentsRemoved     连带逻辑删除的文档数
     * @param conversationsRemoved 连带逻辑删除的对话数
     */
    record CascadeResult(int documentsRemoved, int conversationsRemoved) {
    }
}

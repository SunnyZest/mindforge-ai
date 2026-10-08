package com.zest.mindforgeai.service;

import com.zest.mindforgeai.pojo.dto.AiModelCreateDTO;
import com.zest.mindforgeai.pojo.dto.AiModelUpdateDTO;
import com.zest.mindforgeai.pojo.vo.AiModelVO;

import java.util.List;

/**
 * 管理端 AI 模型配置，文档 §二十五。
 *
 * <h3>三条硬性约束（文档明确要求）</h3>
 * <ul>
 *   <li>API Key <b>加密存储</b> —— 见 {@code AesCipher}（AES-GCM）</li>
 *   <li><b>列表接口不得返回明文</b> —— 对外 VO 里压根没有 apiKey 字段，
 *       只给 {@code apiKeyHint}（形如 ****abcd）</li>
 *   <li><b>日志里不得打印 API Key</b> —— 所有日志与审计明细都只记 hint</li>
 * </ul>
 *
 * @author crx
 * @since 2026-10-08
 */
public interface AdminAiModelService {

    /**
     * 配置列表。
     *
     * @param scene 按用途过滤（CHAT / EMBEDDING / RERANK），可空
     */
    List<AiModelVO> listModels(String scene);

    /** 新增配置 */
    AiModelVO create(AiModelCreateDTO dto);

    /**
     * 修改配置。
     * <p>
     * {@code apiKey} 传 null 表示<b>保持原 Key 不变</b>，不是清空 ——
     * 列表不返回明文，前端编辑表单里本来就没有原 Key，
     * 把 null 当清空的话，管理员改个备注就会把 Key 弄丢。
     */
    AiModelVO update(Long id, AiModelUpdateDTO dto);

    /**
     * 删除配置。
     * <p>
     * <b>物理删除</b>（表里没有 deleted 列）—— 对这张表来说是好事：
     * 删掉配置的同时也删掉了里面的 Key 密文，不留一份还能解开的凭据。
     */
    void delete(Long id);
}

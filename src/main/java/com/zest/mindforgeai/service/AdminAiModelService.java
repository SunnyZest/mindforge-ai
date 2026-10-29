package com.zest.mindforgeai.service;

import com.zest.mindforgeai.pojo.dto.AiModelCreateDTO;
import com.zest.mindforgeai.pojo.dto.AiModelUpdateDTO;
import com.zest.mindforgeai.pojo.vo.AiEffectiveConfigVO;
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

    /**
     * 当前<b>真正生效</b>的配置。
     * <p>
     * ★ <b>转发 Python 的内存快照，不在 Java 侧重算</b>。
     * 生效规则是「缓存优先、没有就回退 .env」，而 .env 的模型名只有 Python 知道 ——
     * Java 拿 {@code sys_ai_model} 重算一遍必然漂移，这种漂移最难查（两边都觉得自己对）。
     * <p>
     * 为什么列表接口不够用：某个用途有多条但都没勾默认时，生效的是 id 最大的那条；
     * 某个用途一条都没配时走 .env，库里根本没有这条记录。这两种情况下
     * {@link #listModels(String)} 都答不出「到底在用什么」。
     * <p>
     * ⚠️ Python 不可达时<b>抛异常</b>，不要返回空列表 ——
     * 空列表的意思是「没有任何生效配置」，与「查不到」是两件事，
     * 混在一起前端就没法给出正确提示了。
     */
    AiEffectiveConfigVO effectiveConfig();
}

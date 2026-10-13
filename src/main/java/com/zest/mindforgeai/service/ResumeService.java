package com.zest.mindforgeai.service;

import com.zest.mindforgeai.pojo.dto.ResumeOptimizeDTO;
import com.zest.mindforgeai.pojo.dto.ResumeUpdateDTO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.pojo.vo.ResumeDetailVO;
import com.zest.mindforgeai.pojo.vo.ResumeOptimizeVO;
import com.zest.mindforgeai.pojo.vo.ResumeParsedVO;
import com.zest.mindforgeai.pojo.vo.ResumeVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 简历服务（用户端）。
 * <p>
 * <b>所有方法都只操作当前登录用户自己的简历</b>：列表查询直接带 user_id 条件，
 * 按 id 操作的一律先走归属校验。
 * <p>
 * ⚠️ {@code POST /resumes/{id}/optimize}（AI 优化建议）也走这里 —— 它和解析共用
 * 同一个前置条件（{@code raw_content}），放在一起能少一次「原文从哪来」的疑问。
 *
 * @author crx
 * @since 2026-10-08
 */
public interface ResumeService {

    /** 分页列表（关键词匹配简历名称 / 目标职位） */
    PageResultVO<ResumeVO> pageResumes(long page, long pageSize, String keyword,
                                       String sortBy, String sortOrder);

    /** 详情（含解析全文与结构化数据） */
    ResumeDetailVO getDetail(Long id);

    /**
     * 上传简历。
     * <p>
     * 流程：校验文件类型 → 上传对象存储 → 落 {@code sys_file} → 落 {@code resume}。
     *
     * @param name           简历名称，空则取原文件名
     * @param targetPosition 目标职位，可空
     */
    ResumeVO upload(MultipartFile file, String name, String targetPosition);

    /**
     * 解析简历（同步）。
     * <p>
     * 流程：取预签名下载地址 → 交给 Python AI 服务（下载 / 提取文本 / 结构化）→
     * 结果落 {@code raw_content} / {@code summary} / {@code structured_json}，
     * 并把技能重写进 {@code resume_skill}。
     * <p>
     * ⚠️ <b>这是个慢接口</b>：下载 + 文本提取 + 模型生成，一次可能几十秒。
     * 期间 {@code parse_status} 是 {@code PARSING}，前端可以轮询
     * {@link #getParsed(Long)} 看进度。
     * <p>
     * 失败时把 {@code parse_status} 置为 {@code FAILED} 并把原因写进
     * {@code error_message}，然后<b>照常把异常抛出去</b> ——
     * 前端既要能从响应里知道失败，也要能在详情页看到上次失败的原因。
     * <p>
     * ⚠️ <b>失败【不清空】已有的解析结果</b>（{@code raw_content} / {@code structured_json} /
     * {@code resume_skill} 都保持原样）。所以这两个字段的语义是分开的：
     * <ul>
     *   <li>{@code structuredJson} —— <b>最近一次成功</b>解析的结果</li>
     *   <li>{@code parseStatus} —— <b>最近一次尝试</b>的结果</li>
     * </ul>
     * 于是可能出现 {@code parseStatus = FAILED} 而 {@code structuredJson} 非空。
     * 这是刻意的：正文没变，旧结果依然是对的，不该因为一次重试失败就把它删掉。
     * 前端判断「有没有可用的解析结果」应该看 {@code structuredJson} 是否为空，
     * 而不是看 {@code parseStatus} 是不是 SUCCESS。
     *
     * @throws com.zest.mindforgeai.common.exception.BizException
     *         简历不存在 / 没有文件 / 正在解析中（40902）/ AI 服务不可用
     */
    ResumeParsedVO parse(Long id);

    /** 只取解析结果（前端轮询「解析好了没」用，响应体比详情小） */
    ResumeParsedVO getParsed(Long id);

    /**
     * AI 优化建议（同步，慢）。
     * <p>
     * ⚠️ <b>只返回建议，不落库、不改简历</b>（文档 §二十一 明确要求
     * 「AI 优化只返回建议，不直接覆盖原始简历」）。所以这个方法是只读的，
     * 同样的输入可以反复调 —— 不会产生任何副作用，也不会烧掉已有的解析结果。
     * <p>
     * 前置条件：简历<b>已经解析过</b>（{@code raw_content} 非空）。
     * 没解析就没什么可分析的 —— 原文都在 PDF 里，AI 读不到。
     * <p>
     * ★ 返回值里 {@code needsFact = true} 的条目表示「这里缺事实，要用户自己补」，
     * 此时 {@code rewritten} 为 null。调用方（前端）必须把这类和普通改写建议
     * <b>分开展示</b>，否则用户会以为 AI 已经替他把经历写好了。
     *
     * @param dto 目标岗位（可空）+ 重点分析的部分（可空 = 整体）
     * @throws com.zest.mindforgeai.common.exception.BizException
     *         简历不存在（404）/ 还没解析过（400）/ 目标岗位不存在或没解析（400、404）/
     *         AI 服务不可用（502、504）
     */
    ResumeOptimizeVO optimize(Long id, ResumeOptimizeDTO dto);

    /**
     * 在线精修或采纳 AI 优化建议后更新简历内容。
     */
    ResumeDetailVO update(Long id, ResumeUpdateDTO dto);

    /**
     * 删除简历（逻辑删除 {@code resume} + 软删 {@code sys_file}）。
     * <p>
     * 不动 OSS 对象 —— 留恢复窗口，到期由 {@code FilePurgeTask} 判断引用计数后清理。
     */
    void delete(Long id);
}

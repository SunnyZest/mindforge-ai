package com.zest.mindforgeai.service;

import com.zest.mindforgeai.pojo.vo.PageResultVO;
import com.zest.mindforgeai.pojo.vo.ResumeDetailVO;
import com.zest.mindforgeai.pojo.vo.ResumeParsedVO;
import com.zest.mindforgeai.pojo.vo.ResumeVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 简历服务（用户端）。
 * <p>
 * <b>所有方法都只操作当前登录用户自己的简历</b>：列表查询直接带 user_id 条件，
 * 按 id 操作的一律先走归属校验。
 * <p>
 * ⚠️ 解析相关的接口（{@code POST /resumes/{id}/parse}、{@code /optimize}）
 * 还没实现 —— 要等 Python AI 服务。目前上传后 {@code parseStatus} 停在 {@code PENDING}。
 *
 * @author crx
 * @since 2026-10-08
 */
public interface ResumeService {

    /** 分页列表（关键词匹配简历名称 / 目标职位） */
    PageResultVO<ResumeVO> pageResumes(long page, long pageSize, String keyword);

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

    /** 只取解析结果（前端轮询「解析好了没」用，响应体比详情小） */
    ResumeParsedVO getParsed(Long id);

    /**
     * 删除简历（逻辑删除 {@code resume} + 软删 {@code sys_file}）。
     * <p>
     * 不动 OSS 对象 —— 留恢复窗口，到期由 {@code FilePurgeTask} 判断引用计数后清理。
     */
    void delete(Long id);
}

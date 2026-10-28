package com.zest.mindforgeai.pojo.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 面试邀约抽取请求。
 * <p>
 * ★ <b>二选一</b>：要么传 {@code text}（粘贴的微信邀面词 / 邮件正文），
 * 要么传文件（前端读成 base64）。文件由<b>后端</b>解析（PDF / DOCX / 图片 OCR）——
 * 前端<b>不解析文件内容</b>，它只负责把字节读成 base64。
 * <p>
 * ★ <b>为什么不复用文档上传那条链路</b>：那条链路是「先落库、再异步解析、再轮询状态」，
 * 而邀约抽取是用户要<b>当场核对结果</b>才能确认的同步场景，落库反而多一份垃圾数据
 * （用户点「取消」就留下一堆没人认领的文档）。所以这里 base64 直传，不落 OSS、不建文档。
 *
 * @author crx
 * @since 2026-10-28
 */
@Schema(description = "面试邀约抽取请求")
@Data
public class InvitationExtractDTO {

    @Schema(description = "输入方式：TEXT 粘贴文本 / FILE 上传文件",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "TEXT")
    @NotBlank(message = "请选择输入方式")
    @Pattern(regexp = "^(TEXT|FILE)$", message = "输入方式只能是 TEXT 或 FILE")
    private String source;

    /**
     * 上限 20000 字符。
     * <p>
     * 实测一封邀约邮件正文 300~800 字，微信邀面词更短。给 20000 是为了容纳
     * 「整个邮件线程都粘进来」这种用法，再长就不是邀约了 —— 而模型输入越长，
     * 抽错和超时的概率越高，不如让用户自己裁一下。
     */
    @Schema(description = "邀约原文，source=TEXT 时必填")
    @Size(max = 20000, message = "内容最长 20000 个字符，请只粘贴邀约相关的部分")
    private String text;

    @Schema(description = "文件类型：PDF / DOCX / TXT / MD / PNG / JPG，source=FILE 时必填", example = "PDF")
    @Size(max = 16, message = "文件类型不合法")
    private String fileType;

    /**
     * 纯 base64，<b>不带</b> {@code data:xxx;base64,} 前缀。
     * <p>
     * ⚠️ 这里刻意<b>不加</b> {@code @Size}：大小上限要按<b>解码后的字节数</b>判断
     * （见 {@code InvitationExtractServiceImpl#checkFileSize}），
     * 在 DTO 上按字符数卡会出现「10MB 的 PDF 被判超限、9.9MB 的又刚好卡在边界」这种
     * 说不清的现象，报错文案也没法告诉用户到底多大。
     */
    @Schema(description = "文件内容的纯 base64（不带 data: 前缀），source=FILE 时必填")
    private String contentBase64;

    /**
     * 条件必填的校验。
     * <p>
     * ★ 用 {@code @AssertTrue} 而不是在 service 里手写 if：字段级校验的错误会走
     * 同一个参数校验异常处理器，返回的 {@code code} 和字段名与其他接口一致，
     * 前端不用为这个接口单独写一套错误处理。
     * <p>
     * {@code @JsonIgnore} 是为了不让这两个方法变成响应字段 ——
     * 它们只服务于入参校验，不是数据。
     */
    @AssertTrue(message = "粘贴文本时内容不能为空")
    @JsonIgnore
    public boolean isTextProvided() {
        return !"TEXT".equalsIgnoreCase(source) || (text != null && !text.isBlank());
    }

    @AssertTrue(message = "上传文件时请提供文件内容")
    @JsonIgnore
    public boolean isFileProvided() {
        return !"FILE".equalsIgnoreCase(source)
                || (contentBase64 != null && !contentBase64.isBlank());
    }
}

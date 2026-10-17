package com.zest.mindforgeai.controller.internal;

import com.zest.mindforgeai.common.result.Result;
import com.zest.mindforgeai.pojo.dto.ChunkBatchDTO;
import com.zest.mindforgeai.pojo.dto.DocumentStatusUpdateDTO;
import com.zest.mindforgeai.pojo.vo.DocumentClaimVO;
import com.zest.mindforgeai.pojo.vo.DocumentVectorPurgeVO;
import com.zest.mindforgeai.service.InternalDocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 内部文档接口 —— <b>只给 Python AI 服务调</b>。
 * <p>
 * <b>路径前缀说明</b>（容易混）：
 * <ul>
 *   <li>{@code /internal/ai/v1/**} —— Java <b>调</b> Python，路径在 Python 那边（文档 §十七）</li>
 *   <li>{@code /internal/documents/**} —— Python <b>调</b> Java，就是本类</li>
 * </ul>
 * <p>
 * <b>认证</b>：不走 JWT（Python 没有用户身份，也不该有），
 * 由 {@code InternalTokenFilter} 校验 {@code X-Service-Token} 请求头。
 * <p>
 * <b>权限</b>：这里<b>不做归属校验</b> —— 调用方是服务不是用户，
 * 没有「当前登录用户」这个概念。安全性由「服务令牌 + 网络隔离」保证。
 * <p>
 * ⚠️ 生产环境务必用 Nginx / Docker 内网把 {@code /internal/**} 挡在外面，不能直接通过url访问
 * 单靠一个静态 token 不够。
 *
 * @author crx
 * @since 2026-10-06
 */
@Slf4j
@Tag(name = "内部接口-文档（仅 Python AI 服务）")
@RestController
@RequestMapping("/internal/documents")
public class InternalDocumentController {

    private final InternalDocumentService internalDocumentService;

    public InternalDocumentController(InternalDocumentService internalDocumentService) {
        this.internalDocumentService = internalDocumentService;
    }

    /**
     * 认领待处理文档。
     * <p>
     * Python 的轮询循环调用它 —— 拿到任务就处理，没拿到就等下一轮。
     */
    @Operation(summary = "认领待处理文档")
    @PostMapping("/claim")
    public Result<List<DocumentClaimVO>> claim(@RequestParam(defaultValue = "10") int limit) {
        return Result.success(internalDocumentService.claimPending(limit));
    }

    /**
     * 回写处理状态。
     * <p>
     * 可以分多次调用：解析完先报 {@code parseStatus}，分块完再报 {@code chunkStatus}……
     * 没轮到的阶段传 null 即可。
     */
    @Operation(summary = "回写文档处理状态")
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id,
                                     @Valid @RequestBody DocumentStatusUpdateDTO dto) {
        internalDocumentService.updateStatus(id, dto);
        return Result.success();
    }

    /**
     * 批量写入分块。
     * <p>
     * 幂等：内部先删该文档的旧分块再插入，重复调用不会产生重复数据。
     */
    @Operation(summary = "批量写入文档分块")
    @PostMapping("/{id}/chunks")
    public Result<Integer> saveChunks(@PathVariable Long id,
                                      @Valid @RequestBody ChunkBatchDTO dto) {
        return Result.success(internalDocumentService.saveChunks(id, dto));
    }

    /**
     * 查出「已删除但向量未清」的文档。
     * <p>
     * <b>为什么需要这个</b>：删除文档只做逻辑删除，向量库里的向量不会自动消失，
     * 会留下「孤儿向量」—— 检索时可能召回已被删除的文档内容。
     * AI 服务定期调这个接口拿到列表，去向量库删掉，再回写「已清理」。
     * <p>
     * 用「AI 服务来拉」而不是「Java 主动通知 AI 服务」：本服务将来可能跑在
     * NAT 后的虚拟机里，Java 主动调它需要端口转发，而这个方向天然可达。
     */
    @Operation(summary = "查询待清理向量的文档")
    @GetMapping("/pending-vector-purge")
    public Result<List<DocumentVectorPurgeVO>> pendingVectorPurge(
            @RequestParam(defaultValue = "100") int limit) {
        return Result.success(internalDocumentService.listPendingVectorPurge(limit));
    }

    /**
     * 标记某个文档的向量已清除。
     * <p>
     * 幂等：重复调用不会出错。
     */
    @Operation(summary = "标记向量已清除")
    @PutMapping("/{id}/vector-purged")
    public Result<Void> markVectorPurged(@PathVariable Long id) {
        internalDocumentService.markVectorPurged(id);
        return Result.success();
    }
}

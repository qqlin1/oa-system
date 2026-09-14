package com.qqlin.oa.controller;

import com.qqlin.oa.common.Result;
import com.qqlin.oa.dto.MultipartUploadDTO;
import com.qqlin.oa.entity.FileRecord;
import com.qqlin.oa.exception.InvalidFileException;
import com.qqlin.oa.service.FileService;
import com.qqlin.oa.vo.FileDownloadVO;
import com.qqlin.oa.vo.FileUploadVO;
import com.qqlin.oa.vo.MultipartInitVO;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 附件接口。
 *
 * 直传（小文件）：
 *   POST /files/upload                     multipart/form-data，一个请求搞定
 *
 * 分片上传（大文件）—— 三步：
 *   POST /files/multipart/init             拿 uploadId + 已传分片列表（断点续传）
 *   POST /files/multipart/chunk            传一个分片（可并发、可重复）
 *   POST /files/multipart/complete         分片齐了，合并成正式文件
 *
 * 其他：
 *   GET    /files/{id}                     查文件元信息
 *   GET    /files/{id}/download            下载
 *   DELETE /files/{id}                     删除
 */
@RestController
@RequestMapping("/files")
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    /** 小文件直传。10MB 以内走这个，一个请求就完了。 */
    @PostMapping("/upload")
    public Result<FileUploadVO> upload(@RequestAttribute("currentUserId") Long currentUserId,
                                       @RequestParam("file") MultipartFile file,
                                       @RequestParam(value = "bizType", required = false) String bizType,
                                       @RequestParam(value = "bizId", required = false) Long bizId) {
        return Result.success(fileService.upload(currentUserId, file, bizType, bizId));
    }

    /**
     * 初始化分片上传。
     *
     * 前端先算好整个文件的 MD5 传过来：
     *   服务端已有这个文件 → 秒传，直接返回 fileId
     *   没有 → 返回 uploadId 和已传分片，前端补传缺的那些
     */
    @PostMapping("/multipart/init")
    public Result<MultipartInitVO> initMultipart(@RequestAttribute("currentUserId") Long currentUserId,
                                                 @Valid @RequestBody MultipartUploadDTO dto) {
        return Result.success(fileService.initMultipart(
                currentUserId, dto.getFileName(), dto.getFileMd5(),
                dto.getFileSize(), dto.getTotalChunks(), dto.getBizType(), dto.getBizId()));
    }

    /** 上传一个分片。可以并发传，也可以重复传（幂等）。 */
    @PostMapping("/multipart/chunk")
    public Result<Void> uploadChunk(@RequestAttribute("currentUserId") Long currentUserId,
                                    @RequestParam("uploadId") String uploadId,
                                    @RequestParam("chunkIndex") int chunkIndex,
                                    @RequestParam("chunk") MultipartFile chunk) {
        byte[] data;
        try {
            data = chunk.getBytes();
        } catch (Exception e) {
            throw new InvalidFileException("读取分片内容失败");
        }
        fileService.uploadChunk(currentUserId, uploadId, chunkIndex, data);
        return Result.success();
    }

    /** 分片传齐后合并成正式文件。 */
    @PostMapping("/multipart/complete")
    public Result<FileUploadVO> completeMultipart(@RequestAttribute("currentUserId") Long currentUserId,
                                                  @Valid @RequestBody MultipartUploadDTO dto) {
        if (dto.getUploadId() == null || dto.getUploadId().isBlank()) {
            throw new InvalidFileException("uploadId 不能为空");
        }
        return Result.success(fileService.completeMultipart(
                currentUserId, dto.getUploadId(), dto.getFileName(), dto.getFileMd5(),
                dto.getFileSize(), dto.getTotalChunks(), dto.getBizType(), dto.getBizId()));
    }

    @GetMapping("/{id}")
    public Result<FileRecord> getFile(@RequestAttribute("currentUserId") Long currentUserId,
                                      @PathVariable("id") Long fileId) {
        return Result.success(fileService.getFile(currentUserId, fileId));
    }

    /**
     * 下载。
     *
     * 返回的不是 JSON，而是原始字节流，所以要单独用 ResponseEntity 而不是 Result 包装。
     * 文件名要 URL 编码，否则中文名会在响应头里乱码。
     *
     * 用 Content-Disposition: attachment 让浏览器走「下载」而不是「直接打开」——
     * 这是防止 XSS 的一道防线：如果用户上传了一个 .html 文件，
     * 不加这个头的话浏览器会当成网页渲染，里面的脚本就能在别人的会话里执行。
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@RequestAttribute("currentUserId") Long currentUserId,
                                           @PathVariable("id") Long fileId) {
        FileDownloadVO download = fileService.download(currentUserId, fileId);

        String encodedName = URLEncoder.encode(download.fileName(), StandardCharsets.UTF_8)
                .replace("+", "%20");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + encodedName + "\"; filename*=UTF-8''" + encodedName);
        headers.setContentLength(download.content().length);

        return ResponseEntity.ok().headers(headers).body(download.content());
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteFile(@RequestAttribute("currentUserId") Long currentUserId,
                                   @PathVariable("id") Long fileId) {
        fileService.deleteFile(currentUserId, fileId);
        return Result.success();
    }
}

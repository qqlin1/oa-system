package com.qqlin.oa.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qqlin.oa.annotation.AuditLog;
import com.qqlin.oa.annotation.RequiresPermission;
import com.qqlin.oa.entity.FileBlob;
import com.qqlin.oa.entity.FileChunk;
import com.qqlin.oa.entity.FileRecord;
import com.qqlin.oa.exception.FileNotFoundException;
import com.qqlin.oa.exception.ForbiddenException;
import com.qqlin.oa.exception.InvalidFileException;
import com.qqlin.oa.mapper.FileBlobMapper;
import com.qqlin.oa.mapper.FileChunkMapper;
import com.qqlin.oa.mapper.FileRecordMapper;
import com.qqlin.oa.vo.FileDownloadVO;
import com.qqlin.oa.vo.FileUploadVO;
import com.qqlin.oa.vo.MultipartInitVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 附件上传与下载。
 *
 * 三件事：直传小文件、分片传大文件、秒传。
 *
 * 【秒传是怎么实现的】
 * 前端在上传前先用 JS 算出整个文件的 MD5 传过来。服务端拿这个 MD5 去
 * sys_file_blob 里查：
 *   查到了 → 说明这份内容磁盘上已经有了，不用再传一个字节，直接建一条逻辑记录。
 *   没查到 → 老老实实上传。
 * 所以「秒传」不是黑科技，就是「按内容去重 + 客户端提前算好内容指纹」。
 *
 * 【断点续传是怎么实现的】
 * uploadId 由 fileMd5 和 userId 算出来，是【确定的】——
 * 同一个人重传同一个文件，拿到的 uploadId 一定一样。
 * 这样 init 接口就能查出上次已经传了哪些分片，前端只补缺失的。
 * 如果 uploadId 用随机 UUID，那每次重传都是新会话，已传的分片就找不回来了。
 */
@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    /** 直传的单个文件上限：10 MB */
    private static final long MAX_DIRECT_UPLOAD_SIZE = 10L * 1024 * 1024;

    /** 分片上传的总大小上限：200 MB */
    private static final long MAX_MULTIPART_SIZE = 200L * 1024 * 1024;

    /** 单个分片上限：5 MB */
    private static final int MAX_CHUNK_SIZE = 5 * 1024 * 1024;

    private final FileBlobMapper fileBlobMapper;
    private final FileRecordMapper fileRecordMapper;
    private final FileChunkMapper fileChunkMapper;
    private final FileStorageService storageService;
    private final PermissionService permissionService;

    public FileService(FileBlobMapper fileBlobMapper,
                       FileRecordMapper fileRecordMapper,
                       FileChunkMapper fileChunkMapper,
                       FileStorageService storageService,
                       PermissionService permissionService) {
        this.fileBlobMapper = fileBlobMapper;
        this.fileRecordMapper = fileRecordMapper;
        this.fileChunkMapper = fileChunkMapper;
        this.storageService = storageService;
        this.permissionService = permissionService;
    }

    // =================================================================
    // 一、小文件直传
    // =================================================================

    @AuditLog("上传附件")
    @RequiresPermission("file:upload")
    @Transactional
    public FileUploadVO upload(Long currentUserId, MultipartFile file, String bizType, Long bizId) {
        if (file == null || file.isEmpty()) {
            throw new InvalidFileException("上传的文件为空");
        }
        if (file.getSize() > MAX_DIRECT_UPLOAD_SIZE) {
            throw new InvalidFileException("文件超过直传上限 "
                    + (MAX_DIRECT_UPLOAD_SIZE / 1024 / 1024) + "MB，请改用分片上传");
        }

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new InvalidFileException("读取上传内容失败");
        }

        String md5 = md5Hex(content);
        String fileName = sanitizeFileName(file.getOriginalFilename());

        FileBlob blob = fileBlobMapper.selectOne(
                new LambdaQueryWrapper<FileBlob>().eq(FileBlob::getFileMd5, md5));

        boolean instant;
        if (blob == null) {
            // 没传过：写盘 + 建物理记录
            String storagePath = storageService.buildStoragePath(md5);
            storageService.save(storagePath, content);
            blob = insertBlob(md5, content.length, file.getContentType(), storagePath);
            instant = false;
        } else {
            // 秒传：磁盘上已经有这份内容了，只加一次引用
            checkSizeMatches(blob, content.length);
            fileBlobMapper.increaseRefCount(blob.getId());
            instant = true;
        }

        FileRecord record = createRecord(blob.getId(), fileName, currentUserId, bizType, bizId);
        log.info("附件上传完成。fileId={}, md5={}, 秒传={}", record.getId(), md5, instant);
        return toVO(record, blob, instant);
    }

    // =================================================================
    // 二、分片上传
    // =================================================================

    /**
     * 初始化分片上传。
     *
     * 返回两种结果：
     *   秒传命中 → instant=true，前端直接显示完成
     *   需要上传 → 返回 uploadId 和【已传分片列表】，前端只补缺的
     */
    @RequiresPermission("file:upload")
    public MultipartInitVO initMultipart(Long currentUserId, String fileName, String fileMd5,
                                         long fileSize, int totalChunks,
                                         String bizType, Long bizId) {
        validateMultipartParams(fileMd5, fileSize, totalChunks);

        // 先看能不能秒传
        FileBlob blob = fileBlobMapper.selectOne(
                new LambdaQueryWrapper<FileBlob>().eq(FileBlob::getFileMd5, fileMd5));
        if (blob != null) {
            checkSizeMatches(blob, fileSize);
            fileBlobMapper.increaseRefCount(blob.getId());
            FileRecord record = createRecord(blob.getId(), sanitizeFileName(fileName),
                    currentUserId, bizType, bizId);

            MultipartInitVO vo = new MultipartInitVO();
            vo.setInstant(true);
            vo.setFileId(record.getId());
            vo.setUploadedChunks(List.of());
            log.info("命中秒传。md5={}, fileId={}", fileMd5, record.getId());
            return vo;
        }

        // 需要真传：uploadId 是确定的，所以能续传
        String uploadId = buildUploadId(fileMd5, currentUserId);
        List<Integer> uploaded = fileChunkMapper.selectUploadedIndexes(uploadId);

        MultipartInitVO vo = new MultipartInitVO();
        vo.setInstant(false);
        vo.setUploadId(uploadId);
        vo.setUploadedChunks(uploaded);
        log.info("分片上传初始化。uploadId={}, 共 {} 片, 已传 {} 片",
                uploadId, totalChunks, uploaded.size());
        return vo;
    }

    /**
     * 上传一个分片。
     *
     * 幂等：同一个分片重复上传（网络重试、用户刷新页面）不会出错，
     * 也不会在磁盘上留下两份。
     */
    @RequiresPermission("file:upload")
    public void uploadChunk(Long currentUserId, String uploadId, int chunkIndex, byte[] data) {
        if (uploadId == null || uploadId.isBlank()) {
            throw new InvalidFileException("uploadId 不能为空");
        }
        if (chunkIndex < 0) {
            throw new InvalidFileException("分片序号不能为负");
        }
        if (data == null || data.length == 0) {
            throw new InvalidFileException("分片内容为空");
        }
        if (data.length > MAX_CHUNK_SIZE) {
            throw new InvalidFileException("分片超过 " + (MAX_CHUNK_SIZE / 1024 / 1024) + "MB");
        }

        Long alreadyUploaded = fileChunkMapper.selectCount(
                new LambdaQueryWrapper<FileChunk>()
                        .eq(FileChunk::getUploadId, uploadId)
                        .eq(FileChunk::getChunkIndex, chunkIndex));
        if (alreadyUploaded > 0) {
            // 已经传过了，直接返回。重复上传是正常情况（重试），不该报错
            return;
        }

        // 先写盘再插库：最坏情况是盘上多一份同样的分片（覆盖写，无害）；
        // 反过来先插库再写盘的话，写盘失败会留下「库里说有、盘上没有」的假记录，
        // 合并时才发现分片缺失，排查起来很麻烦。
        storageService.saveChunk(uploadId, chunkIndex, data);

        FileChunk chunk = new FileChunk();
        chunk.setUploadId(uploadId);
        chunk.setChunkIndex(chunkIndex);
        chunk.setChunkSize(data.length);
        try {
            fileChunkMapper.insert(chunk);
        } catch (DuplicateKeyException e) {
            // 并发下两个请求同时传同一片，唯一索引会挡掉一个。属于正常情况，忽略
            log.debug("分片已被并发上传，忽略。uploadId={}, index={}", uploadId, chunkIndex);
        }
    }

    /**
     * 合并分片，生成正式的文件记录。
     *
     * 合并前先确认分片齐了 —— 不齐就报错，让前端继续传，而不是合并出一个残缺文件。
     */
    @AuditLog("上传附件")
    @RequiresPermission("file:upload")
    @Transactional
    public FileUploadVO completeMultipart(Long currentUserId, String uploadId, String fileName,
                                          String fileMd5, long fileSize, int totalChunks,
                                          String bizType, Long bizId) {
        validateMultipartParams(fileMd5, fileSize, totalChunks);

        List<Integer> uploaded = fileChunkMapper.selectUploadedIndexes(uploadId);
        if (uploaded.size() != totalChunks) {
            throw new InvalidFileException("分片不完整：已传 " + uploaded.size()
                    + " 片，需要 " + totalChunks + " 片");
        }

        FileBlob blob = fileBlobMapper.selectOne(
                new LambdaQueryWrapper<FileBlob>().eq(FileBlob::getFileMd5, fileMd5));

        boolean instant;
        if (blob == null) {
            String storagePath = storageService.buildStoragePath(fileMd5);
            storageService.mergeChunks(uploadId, totalChunks, storagePath, fileSize);
            blob = insertBlob(fileMd5, fileSize, null, storagePath);
            instant = false;
        } else {
            // 传的过程中别人已经传过同一个文件了，合并出来的内容是一样的，直接复用
            checkSizeMatches(blob, fileSize);
            fileBlobMapper.increaseRefCount(blob.getId());
            instant = true;
        }

        // 合并成功，分片没用了，清理掉释放磁盘
        fileChunkMapper.delete(new LambdaQueryWrapper<FileChunk>()
                .eq(FileChunk::getUploadId, uploadId));
        storageService.cleanChunks(uploadId);

        FileRecord record = createRecord(blob.getId(), sanitizeFileName(fileName),
                currentUserId, bizType, bizId);
        log.info("分片合并完成。fileId={}, md5={}, 共 {} 片", record.getId(), fileMd5, totalChunks);
        return toVO(record, blob, instant);
    }

    // =================================================================
    // 三、查询、下载、删除
    // =================================================================

    public FileRecord getFile(Long currentUserId, Long fileId) {
        FileRecord record = fileRecordMapper.selectById(fileId);
        if (record == null) {
            throw new FileNotFoundException("文件不存在");
        }
        return record;
    }

    /**
     * 下载文件。
     *
     * 权限：上传者本人，或管理员。
     *
     * 这里是个简化版。真实系统还要看业务归属 —— 比如经理要能下载
     * 下属请假单上的病历附件，那就要顺着 biz_type/biz_id 找到那张单子，
     * 再用数据权限判断他能不能看。
     */
    public FileDownloadVO download(Long currentUserId, Long fileId) {
        FileRecord record = fileRecordMapper.selectById(fileId);
        if (record == null) {
            throw new FileNotFoundException("文件不存在");
        }
        if (!Objects.equals(record.getUploaderId(), currentUserId)
                && !permissionService.isAdmin(currentUserId)) {
            throw new ForbiddenException("无权下载该文件");
        }

        FileBlob blob = fileBlobMapper.selectById(record.getBlobId());
        if (blob == null) {
            throw new FileNotFoundException("文件数据已丢失");
        }

        byte[] content = storageService.read(blob.getStoragePath());
        return new FileDownloadVO(record.getFileName(), blob.getContentType(), content);
    }

    /**
     * 删除文件。
     *
     * 【权限分两种，别混在一起】
     *   删【自己传的】文件 —— 这是所有权问题。用户当然能删自己刚传错的东西，
     *                       不应该要求他有「删除附件」这个管理权限。
     *   删【别人传的】文件 —— 这才是权限问题，需要 file:delete。
     *
     * 所以这里用 @RequiresPermission("file:upload") 做入口（登录且能上传就能进），
     * 再在方法体里判断「是不是本人」。如果只挂 file:delete，普通员工就删不掉自己的附件了。
     *
     * 【为什么不能直接删磁盘文件】
     * 同一个物理文件可能被多条逻辑记录引用（秒传产生的）。
     * 删掉一条记录后，别人可能还在用这份文件。
     * 所以先把引用数减一，只有减到 0 才真正删磁盘。
     */
    @AuditLog("删除附件")
    @RequiresPermission("file:upload")
    @Transactional
    public void deleteFile(Long currentUserId, Long fileId) {
        FileRecord record = fileRecordMapper.selectById(fileId);
        if (record == null) {
            throw new FileNotFoundException("文件不存在");
        }

        boolean isOwner = Objects.equals(record.getUploaderId(), currentUserId);
        if (!isOwner && !permissionService.hasPermission(currentUserId, "file:delete")) {
            throw new ForbiddenException("只能删除自己上传的附件");
        }

        fileRecordMapper.deleteById(fileId);
        fileBlobMapper.decreaseRefCount(record.getBlobId());

        FileBlob blob = fileBlobMapper.selectById(record.getBlobId());
        if (blob != null && blob.getRefCount() != null && blob.getRefCount() <= 0) {
            // 引用数归零，说明没有任何逻辑记录再用它了，可以安全删盘
            storageService.delete(blob.getStoragePath());
            fileBlobMapper.deleteById(blob.getId());
            log.info("物理文件已无引用，已删除。md5={}", blob.getFileMd5());
        }
    }

    // =================================================================
    // 内部方法
    // =================================================================

    /**
     * 插入物理文件记录，处理并发。
     *
     * 两个请求同时上传同一份内容时，都会发现 blob 不存在、都去插 ——
     * 唯一索引会挡住一个。被挡住的那个重新查一次就能拿到对方插好的记录。
     */
    private FileBlob insertBlob(String md5, long size, String contentType, String storagePath) {
        FileBlob blob = new FileBlob();
        blob.setFileMd5(md5);
        blob.setFileSize(size);
        blob.setContentType(contentType);
        blob.setStoragePath(storagePath);
        blob.setRefCount(1);
        try {
            fileBlobMapper.insert(blob);
            return blob;
        } catch (DuplicateKeyException e) {
            log.debug("物理文件已被并发创建，复用已有记录。md5={}", md5);
            return fileBlobMapper.selectOne(
                    new LambdaQueryWrapper<FileBlob>().eq(FileBlob::getFileMd5, md5));
        }
    }

    private FileRecord createRecord(Long blobId, String fileName, Long uploaderId,
                                    String bizType, Long bizId) {
        FileRecord record = new FileRecord();
        record.setBlobId(blobId);
        record.setFileName(fileName);
        record.setUploaderId(uploaderId);
        record.setBizType(bizType);
        record.setBizId(bizId);
        fileRecordMapper.insert(record);
        return record;
    }

    private void checkSizeMatches(FileBlob blob, long size) {
        if (blob.getFileSize() != null && blob.getFileSize() != size) {
            // 同一个 MD5 对应不同大小，只可能是客户端算错了 MD5。
            // 真发生 MD5 碰撞的概率低到可以忽略，但不能默默接受 ——
            // 否则会把错误的文件当成秒传命中，用户下到的是别的文件。
            throw new InvalidFileException("MD5 相同但文件大小不一致，请重新计算文件指纹");
        }
    }

    private void validateMultipartParams(String fileMd5, long fileSize, int totalChunks) {
        if (fileMd5 == null || fileMd5.length() != 32) {
            throw new InvalidFileException("fileMd5 必须是 32 位十六进制字符串");
        }
        if (fileSize <= 0) {
            throw new InvalidFileException("文件大小必须大于 0");
        }
        if (fileSize > MAX_MULTIPART_SIZE) {
            throw new InvalidFileException("文件超过分片上传上限 "
                    + (MAX_MULTIPART_SIZE / 1024 / 1024) + "MB");
        }
        if (totalChunks <= 0) {
            throw new InvalidFileException("分片数必须大于 0");
        }
    }

    /**
     * 由文件 MD5 和上传人算出确定的上传会话 ID。
     *
     * 用确定值而不是随机 UUID，是为了让「重传同一个文件」能拿到同一个 uploadId，
     * 从而查出上次传了哪些分片 —— 这是断点续传能成立的前提。
     *
     * 带上 userId 是为了让不同用户的分片互不干扰（虽然内容一样，
     * 但各自的会话独立，不会互相清理）。
     */
    private String buildUploadId(String fileMd5, Long userId) {
        return fileMd5 + "-" + userId;
    }

    /**
     * 清洗文件名。
     *
     * 只保留最后一段，把路径部分全部去掉：
     *   ../../etc/passwd  →  passwd
     *   C:\Users\x\a.txt  →  a.txt
     *
     * 磁盘上的存储路径本来就用 MD5 生成、和文件名无关，
     * 这里是第二道保险 —— 万一以后有人把文件名拼进路径，也不会出路径穿越。
     */
    private String sanitizeFileName(String original) {
        if (original == null || original.isBlank()) {
            return "未命名文件";
        }
        String name = original.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            return "未命名文件";
        }
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }

    private String md5Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            // MD5 是 JDK 必须支持的算法，走不到这里
            throw new IllegalStateException("当前 JDK 不支持 MD5", e);
        }
    }

    private FileUploadVO toVO(FileRecord record, FileBlob blob, boolean instant) {
        FileUploadVO vo = new FileUploadVO();
        vo.setFileId(record.getId());
        vo.setFileName(record.getFileName());
        vo.setFileSize(blob.getFileSize());
        vo.setFileMd5(blob.getFileMd5());
        vo.setInstant(instant);
        return vo;
    }
}

package com.qqlin.oa.service;

import com.qqlin.oa.exception.FileStorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 文件在磁盘上的读写。
 *
 * 把「怎么存」和「存什么」分开：这个类只管磁盘操作，
 * 业务规则（谁能传、能不能秒传、挂在哪张单子上）在 FileService 里。
 * 以后要换成 OSS / MinIO，只要换掉这个类的实现，业务代码不用动。
 *
 * 【存储路径怎么定】
 * 用文件内容的 MD5 生成，形如 ab/abcdef123456...（前两位做目录）。
 * 绝对不用用户传上来的文件名做路径 —— 那是路径穿越漏洞的经典入口：
 * 用户传一个名叫 ../../etc/passwd 的文件，就能把文件写到任意位置。
 * 用户看到的原始文件名只存在数据库里，和磁盘路径完全无关。
 *
 * 【为什么按前两位分目录】
 * 如果所有文件都堆在一个目录下，几十万个文件时 ls 一个目录都要好几秒，
 * 某些文件系统还会因为单目录 inode 过多而变慢。分两层是最简单的打散方式。
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    /** 分片临时目录名，放在上传根目录下 */
    private static final String CHUNK_DIR = ".chunks";

    private final Path rootDir;

    public FileStorageService(@Value("${file.upload-dir:./uploads}") String uploadDir) {
        this.rootDir = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(rootDir);
        } catch (IOException e) {
            throw new FileStorageException("创建上传目录失败：" + rootDir, e);
        }
        log.info("文件上传根目录：{}", rootDir);
    }

    /** 由 MD5 生成存储路径：前两位做目录名。 */
    public String buildStoragePath(String md5) {
        return md5.substring(0, 2) + "/" + md5;
    }

    /** 保存整个文件（小文件直传用）。 */
    public void save(String relativePath, byte[] content) {
        Path target = resolve(relativePath);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new FileStorageException("保存文件失败：" + relativePath, e);
        }
    }

    /** 保存一个分片，落在 .chunks/{uploadId}/{index}.part。 */
    public void saveChunk(String uploadId, int chunkIndex, byte[] content) {
        Path target = chunkPath(uploadId, chunkIndex);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new FileStorageException("保存分片失败：" + uploadId + " #" + chunkIndex, e);
        }
    }

    /**
     * 把所有分片按顺序合并成完整文件。
     *
     * 【关键：先写临时文件，成功了再原子改名】
     * 如果直接往目标文件里一段一段追加，那么合并到一半失败（磁盘满、进程被杀）时，
     * 目标路径上会留下一个「半截文件」。更糟的是它和完整文件同名，
     * 下次秒传命中这个 MD5 时，拿到的就是一个损坏的文件。
     *
     * 先写 .tmp，全部写完并校验大小之后再 move 到最终位置。
     * 同一文件系统内的 move 是原子操作，要么看到完整文件，要么什么都看不到。
     */
    public void mergeChunks(String uploadId, int totalChunks, String targetRelativePath, long expectedSize) {
        Path target = resolve(targetRelativePath);
        Path tempFile = resolve(targetRelativePath + ".tmp");

        try {
            Files.createDirectories(target.getParent());

            try (OutputStream out = Files.newOutputStream(tempFile)) {
                for (int i = 0; i < totalChunks; i++) {
                    Path part = chunkPath(uploadId, i);
                    if (!Files.exists(part)) {
                        throw new FileStorageException("分片缺失，无法合并：" + uploadId + " #" + i);
                    }
                    try (InputStream in = Files.newInputStream(part)) {
                        in.transferTo(out);
                    }
                }
            }

            // 合并完先对一下大小，不对说明分片缺了或者坏了，不要留下错误文件
            long actualSize = Files.size(tempFile);
            if (expectedSize > 0 && actualSize != expectedSize) {
                Files.deleteIfExists(tempFile);
                throw new FileStorageException(
                        "合并后大小不符，期望 " + expectedSize + " 实际 " + actualSize);
            }

            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("分片合并完成。uploadId={}, 共 {} 片, 目标={}", uploadId, totalChunks, targetRelativePath);

        } catch (IOException e) {
            deleteQuietly(tempFile);
            throw new FileStorageException("合并分片失败：" + uploadId, e);
        }
    }

    /** 读取整个文件。 */
    public byte[] read(String relativePath) {
        Path target = resolve(relativePath);
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new FileStorageException("读取文件失败：" + relativePath, e);
        }
    }

    public boolean exists(String relativePath) {
        return Files.exists(resolve(relativePath));
    }

    /** 删除物理文件。调用方要确保没有逻辑记录再引用它。 */
    public void delete(String relativePath) {
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (IOException e) {
            throw new FileStorageException("删除文件失败：" + relativePath, e);
        }
    }

    /** 清理某个上传会话的所有分片。合并成功或上传放弃后调用。 */
    public void cleanChunks(String uploadId) {
        Path dir = rootDir.resolve(CHUNK_DIR).resolve(uploadId);
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(this::deleteQuietly);
        } catch (IOException e) {
            // 分片清理失败不影响主流程：文件已经合并好了，残留分片只是占点磁盘
            log.warn("清理分片失败。uploadId={}", uploadId, e);
        }
    }

    private Path chunkPath(String uploadId, int chunkIndex) {
        return rootDir.resolve(CHUNK_DIR).resolve(uploadId).resolve(chunkIndex + ".part");
    }

    /**
     * 把相对路径解析成绝对路径，并确保它没有跑到上传根目录之外。
     *
     * 这是防路径穿越的第二道保险：就算调用方不小心把用户输入当路径传进来了，
     * 这里也会把它挡住。安全相关的检查宁可多一道。
     */
    private Path resolve(String relativePath) {
        Path resolved = rootDir.resolve(relativePath).normalize();
        if (!resolved.startsWith(rootDir)) {
            throw new FileStorageException("非法的文件路径：" + relativePath);
        }
        return resolved;
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("删除失败：{}", path, e);
        }
    }
}

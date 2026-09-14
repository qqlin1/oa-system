package com.qqlin.oa.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qqlin.oa.entity.Department;
import com.qqlin.oa.entity.FileBlob;
import com.qqlin.oa.entity.FileChunk;
import com.qqlin.oa.entity.FileRecord;
import com.qqlin.oa.entity.User;
import com.qqlin.oa.entity.UserRole;
import com.qqlin.oa.exception.ForbiddenException;
import com.qqlin.oa.exception.InvalidFileException;
import com.qqlin.oa.mapper.DepartmentMapper;
import com.qqlin.oa.mapper.FileBlobMapper;
import com.qqlin.oa.mapper.FileChunkMapper;
import com.qqlin.oa.mapper.FileRecordMapper;
import com.qqlin.oa.mapper.UserMapper;
import com.qqlin.oa.mapper.UserRoleMapper;
import com.qqlin.oa.support.TestRoleAssigner;
import com.qqlin.oa.vo.FileDownloadVO;
import com.qqlin.oa.vo.FileUploadVO;
import com.qqlin.oa.vo.MultipartInitVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件上传：直传、秒传、分片、断点续传、删除。
 *
 * 用独立的测试上传目录（target/test-uploads），不污染项目根目录下的 uploads/。
 */
@SpringBootTest(properties = "file.upload-dir=./target/test-uploads")
class FileUploadTest {

    /** 测试用的上传根目录，和配置里保持一致 */
    private static final Path TEST_UPLOAD_DIR = Paths.get("./target/test-uploads");

    @Autowired private FileService fileService;
    @Autowired private UserMapper userMapper;
    @Autowired private UserRoleMapper userRoleMapper;
    @Autowired private DepartmentMapper departmentMapper;
    @Autowired private FileBlobMapper fileBlobMapper;
    @Autowired private FileRecordMapper fileRecordMapper;
    @Autowired private FileChunkMapper fileChunkMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TestRoleAssigner roleAssigner;

    private Long departmentId;
    private Long userId;
    private Long otherUserId;

    @BeforeEach
    void setUp() {
        String tag = "_" + System.nanoTime();
        departmentId = createDepartment("文件测试部门" + tag);
        userId = createUser("file_u1" + tag);
        otherUserId = createUser("file_u2" + tag);
    }

    @AfterEach
    void tearDown() {
        for (Long u : List.of(userId, otherUserId)) {
            List<FileRecord> records = fileRecordMapper.selectList(
                    new LambdaQueryWrapper<FileRecord>().eq(FileRecord::getUploaderId, u));
            for (FileRecord record : records) {
                fileRecordMapper.deleteById(record.getId());
                // 测试里直接删物理记录，不走引用计数 —— 保证数据干净
                fileBlobMapper.deleteById(record.getBlobId());
            }
            userRoleMapper.delete(new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, u));
            userMapper.deleteById(u);
        }
        fileChunkMapper.delete(new LambdaQueryWrapper<FileChunk>());
        deleteDirectoryQuietly(TEST_UPLOAD_DIR);
        departmentMapper.deleteById(departmentId);
    }

    // ---------------------------------------------------------------
    // 直传
    // ---------------------------------------------------------------

    @Test
    @DisplayName("小文件直传：内容能原样读回来")
    void uploadShouldStoreContent() {
        byte[] content = "这是一份请假证明".getBytes(StandardCharsets.UTF_8);
        MultipartFile file = mockFile("证明.txt", "text/plain", content);

        FileUploadVO vo = fileService.upload(userId, file, "LEAVE", 123L);

        assertNotNull(vo.getFileId());
        assertEquals("证明.txt", vo.getFileName());
        assertEquals(content.length, vo.getFileSize());
        assertFalse(vo.isInstant(), "第一次上传不该命中秒传");

        FileDownloadVO download = fileService.download(userId, vo.getFileId());
        assertArrayEquals(content, download.content(), "下载的内容必须和上传的一模一样");
    }

    @Test
    @DisplayName("空文件应当被拒绝")
    void emptyFileShouldBeRejected() {
        MultipartFile empty = mockFile("空.txt", "text/plain", new byte[0]);
        assertThrows(InvalidFileException.class,
                () -> fileService.upload(userId, empty, null, null));
    }

    @Test
    @DisplayName("文件名里的路径部分会被剥掉，防止路径穿越")
    void fileNameShouldBeSanitized() {
        byte[] content = "恶意内容".getBytes(StandardCharsets.UTF_8);

        // 模拟用户上传一个带路径的文件名
        MultipartFile windowsStyle = mockFile("..\\..\\windows\\system32\\evil.txt", "text/plain", content);
        assertEquals("evil.txt",
                fileService.upload(userId, windowsStyle, null, null).getFileName());

        MultipartFile unixStyle = mockFile("../../etc/passwd", "text/plain", content);
        assertEquals("passwd",
                fileService.upload(userId, unixStyle, null, null).getFileName());
    }

    // ---------------------------------------------------------------
    // 秒传
    // ---------------------------------------------------------------

    @Test
    @DisplayName("同一份内容传两次：第二次秒传，磁盘上只有一份物理文件")
    void secondUploadOfSameContentShouldBeInstant() {
        byte[] content = "公司规章制度 v1.0".getBytes(StandardCharsets.UTF_8);
        String md5 = md5Hex(content);

        FileUploadVO first = fileService.upload(userId, mockFile("制度A.pdf", "application/pdf", content), null, null);
        FileUploadVO second = fileService.upload(otherUserId, mockFile("制度B.pdf", "application/pdf", content), null, null);

        assertFalse(first.isInstant(), "第一次上传要真传");
        assertTrue(second.isInstant(), "第二个人传同样内容应当秒传");

        // 物理文件只有一份，但被两条逻辑记录引用
        List<FileBlob> blobs = fileBlobMapper.selectList(
                new LambdaQueryWrapper<FileBlob>().eq(FileBlob::getFileMd5, md5));
        assertEquals(1, blobs.size(), "同样的内容磁盘上只应该有一份");
        assertEquals(2, blobs.get(0).getRefCount(), "应当被两条逻辑记录引用");

        // 两条逻辑记录各自保留自己的文件名
        List<FileRecord> records = fileRecordMapper.selectList(
                new LambdaQueryWrapper<FileRecord>().eq(FileRecord::getBlobId, blobs.get(0).getId()));
        assertEquals(2, records.size(), "两个人各自有一条记录");
        assertTrue(records.stream().anyMatch(r -> "制度A.pdf".equals(r.getFileName())));
        assertTrue(records.stream().anyMatch(r -> "制度B.pdf".equals(r.getFileName())),
                "秒传不能把第二个人看到的文件名变成第一个人的");
    }

    // ---------------------------------------------------------------
    // 分片上传
    // ---------------------------------------------------------------

    @Test
    @DisplayName("分片上传：init → chunk → complete 走通，合并出来的内容正确")
    void multipartUploadShouldWork() {
        byte[] full = randomBytes(10 * 1024);
        int chunkSize = 4096;
        int totalChunks = (int) Math.ceil(full.length / (double) chunkSize);
        String md5 = md5Hex(full);

        MultipartInitVO init = fileService.initMultipart(
                userId, "大文件.bin", md5, full.length, totalChunks, null, null);

        assertFalse(init.isInstant());
        assertNotNull(init.getUploadId());
        assertTrue(init.getUploadedChunks().isEmpty(), "第一次初始化时不该有已传分片");

        for (int i = 0; i < totalChunks; i++) {
            fileService.uploadChunk(userId, init.getUploadId(), i, slice(full, i, chunkSize));
        }

        FileUploadVO vo = fileService.completeMultipart(
                userId, init.getUploadId(), "大文件.bin", md5, full.length, totalChunks, null, null);

        FileDownloadVO download = fileService.download(userId, vo.getFileId());
        assertArrayEquals(full, download.content(), "合并出来的内容必须和原文件完全一致");
        assertEquals("大文件.bin", download.fileName());
    }

    @Test
    @DisplayName("断点续传：再次 init 能拿到已上传的分片序号，uploadId 保持不变")
    void initShouldReportAlreadyUploadedChunks() {
        byte[] full = randomBytes(8 * 1024);
        int chunkSize = 2048;
        int totalChunks = 4;
        String md5 = md5Hex(full);

        MultipartInitVO first = fileService.initMultipart(
                userId, "续传.bin", md5, full.length, totalChunks, null, null);

        // 模拟：只传了前两片就断网了
        fileService.uploadChunk(userId, first.getUploadId(), 0, slice(full, 0, chunkSize));
        fileService.uploadChunk(userId, first.getUploadId(), 1, slice(full, 1, chunkSize));

        // 网络恢复，前端重新 init
        MultipartInitVO again = fileService.initMultipart(
                userId, "续传.bin", md5, full.length, totalChunks, null, null);

        assertEquals(first.getUploadId(), again.getUploadId(),
                "uploadId 必须是确定的，否则每次重连都是新会话，已传的分片就找不回来了");
        assertEquals(List.of(0, 1), again.getUploadedChunks(),
                "应当告诉前端已经传了第 0、1 片，它只需要补第 2、3 片");

        // 补完剩下的，能正常合并
        fileService.uploadChunk(userId, again.getUploadId(), 2, slice(full, 2, chunkSize));
        fileService.uploadChunk(userId, again.getUploadId(), 3, slice(full, 3, chunkSize));
        FileUploadVO vo = fileService.completeMultipart(
                userId, again.getUploadId(), "续传.bin", md5, full.length, totalChunks, null, null);

        assertArrayEquals(full, fileService.download(userId, vo.getFileId()).content());
    }

    @Test
    @DisplayName("重复上传同一个分片是幂等的，不会报错也不会产生重复记录")
    void uploadingSameChunkTwiceShouldBeIdempotent() {
        byte[] full = randomBytes(2048);
        String md5 = md5Hex(full);

        MultipartInitVO init = fileService.initMultipart(userId, "重复.bin", md5, full.length, 1, null, null);

        fileService.uploadChunk(userId, init.getUploadId(), 0, full);
        fileService.uploadChunk(userId, init.getUploadId(), 0, full);   // 重复传，不该抛异常

        Long count = fileChunkMapper.selectCount(
                new LambdaQueryWrapper<FileChunk>()
                        .eq(FileChunk::getUploadId, init.getUploadId())
                        .eq(FileChunk::getChunkIndex, 0));
        assertEquals(1L, count, "同一个分片只应该有一条记录");
    }

    @Test
    @DisplayName("分片不齐时合并应当报错，而不是合并出一个残缺文件")
    void completeShouldFailWhenChunksAreMissing() {
        byte[] full = randomBytes(4096);
        String md5 = md5Hex(full);

        MultipartInitVO init = fileService.initMultipart(userId, "缺片.bin", md5, full.length, 4, null, null);
        fileService.uploadChunk(userId, init.getUploadId(), 0, slice(full, 0, 1024));

        InvalidFileException ex = assertThrows(InvalidFileException.class,
                () -> fileService.completeMultipart(
                        userId, init.getUploadId(), "缺片.bin", md5, full.length, 4, null, null));
        assertTrue(ex.getMessage().contains("分片不完整"), "实际消息：" + ex.getMessage());
    }

    @Test
    @DisplayName("分片上传时如果发现内容已存在，也应当秒传")
    void multipartInitShouldHitInstantWhenContentAlreadyExists() {
        byte[] content = "早就传过的文件".getBytes(StandardCharsets.UTF_8);
        String md5 = md5Hex(content);

        // 先用直传把内容传上去
        fileService.upload(userId, mockFile("先传.txt", "text/plain", content), null, null);

        // 再走分片流程的 init —— 应当直接命中秒传
        MultipartInitVO init = fileService.initMultipart(
                userId, "后传.txt", md5, content.length, 1, null, null);

        assertTrue(init.isInstant(), "内容已经在库里了，不该再让用户传一遍");
        assertNotNull(init.getFileId());
    }

    // ---------------------------------------------------------------
    // 删除与引用计数
    // ---------------------------------------------------------------

    @Test
    @DisplayName("删除文件：还有别人引用时不动物理文件，最后一个删除时才清理")
    void deleteShouldRemovePhysicalFileOnlyWhenLastReferenceIsGone() {
        byte[] content = "共享的附件".getBytes(StandardCharsets.UTF_8);
        String md5 = md5Hex(content);

        FileUploadVO mine = fileService.upload(userId, mockFile("我的.txt", "text/plain", content), null, null);
        FileUploadVO theirs = fileService.upload(otherUserId, mockFile("他的.txt", "text/plain", content), null, null);
        assertTrue(theirs.isInstant());

        FileBlob blob = fileBlobMapper.selectOne(
                new LambdaQueryWrapper<FileBlob>().eq(FileBlob::getFileMd5, md5));

        // 删掉一条：物理文件必须还在，因为另一个人还在用
        fileService.deleteFile(userId, mine.getFileId());
        assertNotNull(fileBlobMapper.selectById(blob.getId()), "还有引用时不该删物理文件");
        assertArrayEquals(content, fileService.download(otherUserId, theirs.getFileId()).content(),
                "别人的文件必须还能正常下载");

        // 删掉最后一条：物理文件才清理
        fileService.deleteFile(otherUserId, theirs.getFileId());
        assertEquals(null, fileBlobMapper.selectById(blob.getId()), "引用归零后物理记录应当被清理");
    }

    @Test
    @DisplayName("普通员工能删自己传的，但删不了别人传的")
    void ownershipShouldDecideWhoCanDelete() {
        byte[] content = "别人的附件".getBytes(StandardCharsets.UTF_8);
        FileUploadVO theirs = fileService.upload(
                otherUserId, mockFile("他的.txt", "text/plain", content), null, null);

        // userId 是普通员工，没有 file:delete 权限，删别人的文件应当被拒
        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> fileService.deleteFile(userId, theirs.getFileId()));
        assertTrue(ex.getMessage().contains("只能删除自己上传的附件"),
                "实际消息：" + ex.getMessage());

        // 被拒之后文件必须原样还在
        assertNotNull(fileRecordMapper.selectById(theirs.getFileId()),
                "被拒绝的删除不应该动任何数据");

        // 但他删自己的文件是可以的 —— 不需要额外权限
        FileUploadVO mine = fileService.upload(
                userId, mockFile("我的.txt", "text/plain", content), null, null);
        fileService.deleteFile(userId, mine.getFileId());
        assertEquals(null, fileRecordMapper.selectById(mine.getFileId()));
    }

    // ---------------------------------------------------------------
    // 辅助方法
    // ---------------------------------------------------------------

    private MultipartFile mockFile(String name, String contentType, byte[] content) {
        return new MockMultipartFile("file", name, contentType, content);
    }

    /** 从完整文件里切出第 index 片 */
    private byte[] slice(byte[] full, int index, int chunkSize) {
        int from = index * chunkSize;
        int to = Math.min(from + chunkSize, full.length);
        return Arrays.copyOfRange(full, from, to);
    }

    private byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        new Random(42).nextBytes(bytes);
        return bytes;
    }

    private String md5Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Long createDepartment(String name) {
        Department d = new Department();
        d.setName(name);
        d.setParentId(0L);
        d.setStatus(1);
        d.setSort(0);
        departmentMapper.insert(d);
        return d.getId();
    }

    private Long createUser(String username) {
        User u = new User();
        u.setUsername(username);
        u.setName(username);
        u.setPassword(passwordEncoder.encode("test123456"));
        u.setDepartmentId(departmentId);
        u.setStatus(1);
        u.setRole("USER");
        u.setTokenVersion(0);
        userMapper.insert(u);
        roleAssigner.assign(u.getId(), "USER");
        return u.getId();
    }

    private void deleteDirectoryQuietly(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            List<Path> toDelete = new ArrayList<>();
            paths.sorted(Comparator.reverseOrder()).forEach(toDelete::add);
            for (Path p : toDelete) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 测试收尾，删不掉就算了
                }
            }
        } catch (IOException ignored) {
            // 同上
        }
    }
}

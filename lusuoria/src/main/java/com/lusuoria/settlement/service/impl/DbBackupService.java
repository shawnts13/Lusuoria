package com.lusuoria.settlement.service.impl;

import com.google.api.client.http.FileContent;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.lusuoria.settlement.entity.DbBackupAlert;
import com.lusuoria.settlement.repository.DbBackupAlertRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 数据库每日全量备份（2026-07-29 新增）：每天北京时间凌晨3点半跑一次 pg_dump 全量备份，压缩成
 * zip，上传到 Google Drive 指定文件夹，只保留最近5份（超出的旧文件删掉），失败了在"待处理"
 * 模块生成一条提醒（见 {@link DbBackupAlert}），提供重试；一旦有一次备份成功（不管是定时任务
 * 自己第二天又成功了，还是有人手动点了重试成功），提醒就会消失。
 *
 * pg_dump 走的是跟应用本身一样的 Supabase 连接串（见 application.yml 的
 * spring.datasource.url/username/password），单独开一个进程/连接，不占用 HikariCP 连接池
 * （Render 免费版整个连接池只给3个连接，这个备份是独立的操作系统进程，用自己的一条连接，
 * 用完就断，不会挤占应用本身的连接）。
 */
@Service
public class DbBackupService {

    private static final Logger log = LoggerFactory.getLogger(DbBackupService.class);
    private static final int KEEP_COUNT = 5;
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    // jdbc:postgresql://host:port/dbname?params...
    private static final Pattern JDBC_URL_PATTERN =
            Pattern.compile("jdbc:postgresql://([^:/]+):(\\d+)/([^?]+)");

    @Value("${spring.datasource.url}") private String jdbcUrl;
    @Value("${spring.datasource.username}") private String dbUsername;
    @Value("${spring.datasource.password}") private String dbPassword;
    @Value("${backup.google-drive.folder-id}") private String driveFolderId;

    @Autowired private GoogleDriveAuthService googleDriveAuthService;
    @Autowired private DbBackupAlertRepository alertRepo;

    @Scheduled(cron = "0 30 3 * * *")
    public void scheduledBackup() {
        runBackup();
    }

    /** 核心备份流程；定时任务和"待处理"里的手动重试按钮共用这一个方法。加 synchronized
     * 防止定时任务和手动重试恰好同时触发（可能性很低，但重复跑一遍没有意义还浪费资源） */
    public synchronized BackupResult runBackup() {
        String timestamp = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(TS_FORMAT);
        String zipName = "lusuoria-db-backup-" + timestamp + ".zip";
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("db-backup-");
            Path dumpFile = tempDir.resolve("backup-" + timestamp + ".sql");
            runPgDump(dumpFile);
            Path zipFile = tempDir.resolve(zipName);
            zipFile(dumpFile, zipFile);

            Drive drive = googleDriveAuthService.buildDriveClient();
            uploadToDrive(drive, zipFile, zipName);
            enforceRetention(drive);

            clearAlert();
            log.info("数据库备份成功：{}", zipName);
            return BackupResult.success(zipName);
        } catch (GoogleDriveAuthService.GoogleDriveNotConnectedException e) {
            log.error("数据库备份失败（Google Drive 未连接/授权失效）：{}", e.getMessage());
            recordFailure(e.getMessage(), true);
            return BackupResult.failure(e.getMessage(), true);
        } catch (Exception e) {
            log.error("数据库备份失败：{}", e.toString(), e);
            recordFailure(e.getMessage() != null ? e.getMessage() : e.toString(), false);
            return BackupResult.failure(e.getMessage(), false);
        } finally {
            if (tempDir != null) deleteRecursively(tempDir);
        }
    }

    private void runPgDump(Path outputFile) throws IOException, InterruptedException {
        Matcher m = JDBC_URL_PATTERN.matcher(jdbcUrl);
        if (!m.find()) throw new RuntimeException("无法解析数据库连接串，跳过备份：" + jdbcUrl);
        String host = m.group(1);
        String port = m.group(2);
        String dbName = m.group(3);

        ProcessBuilder pb = new ProcessBuilder(
                "pg_dump",
                "-h", host, "-p", port, "-U", dbUsername, "-d", dbName,
                "--no-owner", "--no-privileges",
                "-f", outputFile.toAbsolutePath().toString());
        pb.environment().put("PGPASSWORD", dbPassword);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = readAll(process.getInputStream());
        boolean finished = process.waitFor(10, java.util.concurrent.TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("pg_dump 执行超时（超过10分钟）");
        }
        if (process.exitValue() != 0) {
            throw new RuntimeException("pg_dump 执行失败（退出码 " + process.exitValue() + "）：" + output);
        }
        if (!Files.exists(outputFile) || Files.size(outputFile) == 0) {
            throw new RuntimeException("pg_dump 执行完成但没有生成有效的备份文件");
        }
    }

    private String readAll(java.io.InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
                if (sb.length() > 20000) break; // 出错信息够用了，避免异常巨长把内存/数据库字段撑爆
            }
        }
        return sb.toString();
    }

    private void zipFile(Path source, Path zipTarget) throws IOException {
        try (OutputStream fos = Files.newOutputStream(zipTarget);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            zos.putNextEntry(new ZipEntry(source.getFileName().toString()));
            Files.copy(source, zos);
            zos.closeEntry();
        }
    }

    private void uploadToDrive(Drive drive, Path zipFile, String fileName) throws IOException {
        File fileMetadata = new File();
        fileMetadata.setName(fileName);
        fileMetadata.setParents(java.util.Collections.singletonList(driveFolderId));
        FileContent mediaContent = new FileContent("application/zip", zipFile.toFile());
        drive.files().create(fileMetadata, mediaContent).setFields("id").execute();
    }

    /** 只保留这个文件夹里最近创建的5份备份（按文件名倒序——文件名带时间戳，字符串倒序等价于
     * 时间倒序，比再解析 createdTime 简单可靠），多出来的直接删除（进回收站，Drive 默认30天后
     * 自动清空，不需要额外清理逻辑） */
    private void enforceRetention(Drive drive) throws IOException {
        FileList result = drive.files().list()
                .setQ("'" + driveFolderId + "' in parents and trashed = false and name contains 'lusuoria-db-backup-'")
                .setOrderBy("name desc")
                .setFields("files(id, name)")
                .setPageSize(100)
                .execute();
        List<File> files = result.getFiles();
        if (files == null || files.size() <= KEEP_COUNT) return;
        for (File f : files.subList(KEEP_COUNT, files.size())) {
            try {
                drive.files().delete(f.getId()).execute();
            } catch (Exception e) {
                // 删旧备份失败不该影响这次备份本身"成功"的判定——记个日志就够了，
                // 下次跑批还会再尝试清理一遍
                log.warn("清理旧备份文件失败：{}（{}），{}", f.getName(), f.getId(), e.toString());
            }
        }
    }

    private void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        } catch (IOException e) {
            log.warn("清理备份临时目录失败：{}，{}", dir, e.toString());
        }
    }

    @Transactional
    public void recordFailure(String message, boolean authExpired) {
        Date now = new Date();
        DbBackupAlert alert = alertRepo.findFirstByIsDeletedFalseOrderByIdDesc().orElse(null);
        if (alert == null) {
            alert = new DbBackupAlert();
            alert.setIsDeleted(false);
            alert.setFirstFailedAt(now);
            alert.setFailureCount(0);
        }
        alert.setErrorMessage(message);
        alert.setAuthExpired(authExpired);
        alert.setLastFailedAt(now);
        alert.setFailureCount(alert.getFailureCount() + 1);
        alertRepo.save(alert);
    }

    @Transactional
    public void clearAlert() {
        alertRepo.findFirstByIsDeletedFalseOrderByIdDesc().ifPresent(alert -> {
            alert.setIsDeleted(true);
            alertRepo.save(alert);
        });
    }

    public static class BackupResult {
        private final boolean success;
        private final String message;
        private final boolean authExpired;

        private BackupResult(boolean success, String message, boolean authExpired) {
            this.success = success;
            this.message = message;
            this.authExpired = authExpired;
        }

        public static BackupResult success(String fileName) {
            return new BackupResult(true, "备份成功：" + fileName, false);
        }

        public static BackupResult failure(String message, boolean authExpired) {
            return new BackupResult(false, message, authExpired);
        }

        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public boolean isAuthExpired() { return authExpired; }
    }
}

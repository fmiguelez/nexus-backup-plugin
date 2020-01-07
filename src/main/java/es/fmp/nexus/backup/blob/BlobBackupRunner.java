package es.fmp.nexus.backup.blob;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.BufferedOutputStream;

/*
 * Inspired by https://github.com/sonatype/nexus-public/blob/master/components/nexus-core/src/main/java/org/sonatype/nexus/internal/backup/DatabaseBackupRunner.java
 */

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Background thread that creates the blob backup
 *
 */
public class BlobBackupRunner implements Callable<Void> {

    private final Logger log = LoggerFactory.getLogger(this.getClass());

    private final Path blobStorePath;

    private final File backupFile;

    /**
     * Constructor to instantiate thread for executing database backup
     *
     * @param blobStorePath
     *            the path of the file blob store
     * @param backupFile
     *            the backup data will be written onto this file
     */
    public BlobBackupRunner(final Path blobStorePath, final File backupFile) {
        this.blobStorePath = checkNotNull(blobStorePath);
        this.backupFile = checkNotNull(backupFile);
    }

    private class ZipStats {
        private static final long SIZE_PROGRESS_STEP = 1000 * 1024 * 1024;
        private static final long COUNT_PROGRESS_STEP = 10000;

        ZipStats(String name) {
            this.name = name;
        }

        long reportedSize = 0;
        int reportedCount = 0;
        long startTs = System.currentTimeMillis();

        String name;
        long totalSize = 0;
        long totalCompressedSize = 0;
        int count = 0;

        long partialSize;
        int partialCount;
        long elapsedTime;

        void add(ZipEntry entry) {
            totalSize += entry.getSize();
            totalCompressedSize += entry.getCompressedSize();
            count++;

            if ((totalSize - reportedSize) >= SIZE_PROGRESS_STEP || (count - reportedCount) >= COUNT_PROGRESS_STEP) {
                partialSize = totalSize - reportedSize;
                partialCount = count - reportedCount;
                elapsedTime = System.currentTimeMillis() - startTs;

                reportedSize = totalSize;
                reportedCount = count;
                startTs = System.currentTimeMillis();

                dump();
            }
        }

        void dump() {
            long dataThroughput = partialSize * 1000 / elapsedTime;
            long msgThroughtput = partialCount * 1000 / elapsedTime;

            log.info("blob store backup {} stats: {{}, {}, entries: {}, size: {}, compressedSize: {}}", name, humanReadableByteCountBin(dataThroughput) + "/s",
                    msgThroughtput + " files/s", count, humanReadableByteCountBin(totalSize), humanReadableByteCountBin(totalCompressedSize));
        }
    }

    /*
     * From https://stackoverflow.com/a/3758880/34880
     */
    private static String humanReadableByteCountBin(long bytes) {
        long b = bytes == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(bytes);
        return b < 1024L ? bytes + " B"
                : b <= 0xfffccccccccccccL >> 40 ? String.format("%.1f KiB", bytes / 0x1p10)
                        : b <= 0xfffccccccccccccL >> 30 ? String.format("%.1f MiB", bytes / 0x1p20)
                                : b <= 0xfffccccccccccccL >> 20 ? String.format("%.1f GiB", bytes / 0x1p30)
                                        : b <= 0xfffccccccccccccL >> 10 ? String.format("%.1f TiB", bytes / 0x1p40)
                                                : b <= 0xfffccccccccccccL ? String.format("%.1f PiB", (bytes >> 10) / 0x1p40)
                                                        : String.format("%.1f EiB", (bytes >> 20) / 0x1p40);
    }

    private void backup(Path blobStorePath, File zipFile) throws IOException {
        Path p = zipFile.toPath();
        long start = System.currentTimeMillis();

        final ZipStats stats = new ZipStats(zipFile.getName());
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(p)))) {
            Files.walk(blobStorePath).forEach(path -> {
                String relativizedPath = blobStorePath.relativize(path).toString();
                if (!StringUtils.isEmpty(relativizedPath)) {
                    Boolean isDirectory = Files.isDirectory(path);
                    ZipEntry ze = new ZipEntry(blobStorePath.relativize(path).toString() + (isDirectory ? "/" : ""));
                    try {
                        zos.putNextEntry(ze);
                        if (!isDirectory) {
                            Files.copy(path, zos);
                        }
                        zos.closeEntry();
                        stats.add(ze);
                        if (log.isDebugEnabled()) {
                            log.info("added entry to {} (size: {}, compressed size: {}); {}", zipFile.getName(), ze.getSize(), ze.getCompressedSize(),
                                    ze.getName());
                        }
                    } catch (IOException e) {
                        log.error("error backing up path: " + path, e);
                    }
                }
            });
        }
        long elapsedTime = System.currentTimeMillis() - start;
        log.info("backup of blob store {} finished successfully in {}.", zipFile.getName(), DurationFormatUtils.formatDuration(elapsedTime, "HH:mm:ss.S"));
        stats.dump();
    }

    @Override
    public Void call() throws Exception {
        try {
            backup(blobStorePath, backupFile);
        } catch (Throwable e) {
            throw new RuntimeException(String.format("backup of blob store %s to file %s failed", blobStorePath.getFileName(), backupFile.getName()), e);
        }

        return null;
    }
}

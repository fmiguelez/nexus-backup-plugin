package es.fmp.nexus.backup.blob;

import static com.google.common.base.Preconditions.checkNotNull;

/*
 * Inspired by https://github.com/sonatype/nexus-public/blob/master/components/nexus-core/src/main/java/org/sonatype/nexus/internal/backup/DatabaseBackupImpl.java
 */
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.blobstore.file.FileBlobStore;
import org.sonatype.nexus.common.app.ApplicationDirectories;
import org.sonatype.nexus.common.app.ApplicationVersion;
import org.sonatype.nexus.orient.restore.RestoreFile;

import com.google.common.annotations.VisibleForTesting;

/**
 * basic implementation of {@link BlobBackup}
 *
 * @since 3.2
 */
@Named
@Singleton
public class BlobBackupImpl extends ComponentSupport implements BlobBackup {

    private final BlobStoreManager blobStoreManager;

    private final ApplicationDirectories applicationDirectories;

    private final ApplicationVersion applicationVersion;

    @Inject
    public BlobBackupImpl(final BlobStoreManager blobStoreManager, final ApplicationDirectories applicationDirectories,
            final ApplicationVersion applicationVersion) {
        this.blobStoreManager = checkNotNull(blobStoreManager);
        this.applicationDirectories = checkNotNull(applicationDirectories);
        this.applicationVersion = checkNotNull(applicationVersion);
    }

    @Override
    public Callable<Void> internalBackup(final String backupFolder, final String blobStoreName, final LocalDateTime timestamp) throws IOException {
        File backupFile = checkTarget(backupFolder, blobStoreName, timestamp);
        return new BlobBackupRunner(getBlobStorePath(blobStoreName), backupFile);
    }

    @Override
    public Callable<Void> externalBackup(final String cmd) throws IOException {
        return new BlobBackupRunner(cmd);
    }

    @VisibleForTesting
    File checkTarget(final String backupFolder, final String blobName, final LocalDateTime timestamp) throws IOException {
        String filename = RestoreFile.formatFilename(blobName, timestamp, applicationVersion.getVersion());
        File parentDir = applicationDirectories.getWorkDirectory(backupFolder);
        File output = new File(parentDir, filename);
        if (output.createNewFile()) {
            return output;
        } else {
            throw new IOException("file creation failed for file: " + output.getAbsolutePath());
        }
    }

    private Path getBlobStorePath(String name) {
        BlobStore bs = blobStoreManager.get(name);
        checkNotNull(bs);
        Path configurationPath = Paths.get(bs.getBlobStoreConfiguration().attributes(FileBlobStore.CONFIG_KEY).require(FileBlobStore.PATH_KEY).toString());

        if (configurationPath.isAbsolute()) {
            return configurationPath;
        }
        try {
            Path basedir = applicationDirectories.getWorkDirectory(FileBlobStore.BASEDIR).toPath();
            Path normalizedBase = basedir.toRealPath().normalize();
            Path normalizedPath = configurationPath.normalize();
            return normalizedBase.resolve(normalizedPath);
        } catch (Exception e) {
            throw new RuntimeException(String.format("unable to determine real path for blob store %s", name), e);
        }
    }

    @Override
    public List<String> blobNames() {
        return StreamSupport.stream(blobStoreManager.browse().spliterator(), true)
                .filter(b -> b.getBlobStoreConfiguration().getType().equals(FileBlobStore.TYPE)).map(b -> b.getBlobStoreConfiguration().getName())
                .collect(Collectors.toList());
    }
}

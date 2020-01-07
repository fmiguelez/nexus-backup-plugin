package es.fmp.nexus.backup;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.File;

/*
 * Original version Sonatype DatabaseBackupTask: https://github.com/sonatype/nexus-public/blob/master/components/nexus-core/src/main/java/org/sonatype/nexus/internal/backup/DatabaseBackupTask.java
 * This is an improved version including Blob backup
 */

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.goodies.common.MultipleFailures;
import org.sonatype.goodies.i18n.I18N;
import org.sonatype.goodies.i18n.MessageBundle;
import org.sonatype.nexus.common.app.ApplicationDirectories;
import org.sonatype.nexus.orient.freeze.DatabaseFreezeService;
import org.sonatype.nexus.orient.freeze.FreezeRequest;
import org.sonatype.nexus.orient.freeze.FreezeRequest.InitiatorType;
import org.sonatype.nexus.scheduling.Cancelable;
import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskSupport;
import org.sonatype.nexus.security.subject.FakeAlmightySubject;
import org.sonatype.nexus.thread.NexusExecutorService;
import org.sonatype.nexus.thread.NexusThreadFactory;

import com.google.common.collect.Lists;

import es.fmp.nexus.backup.blob.BlobBackup;
import es.fmp.nexus.backup.db.DatabaseBackup;

/**
 * Task to backup both DBs and blobs.
 *
 * @since 3.2
 */
@Named
public class BackupTask extends TaskSupport implements Cancelable {

    private static final int MAX_CONCURRENT_BACKUPS = 32;

    private static final int MAX_QUEUED_BACKUPS = 2;

    private String location;

    private final DatabaseBackup databaseBackup;

    private final BlobBackup blobBackup;

    private final DatabaseFreezeService freezeService;

    @Inject
    public BackupTask(final ApplicationDirectories applicationDirectories, final DatabaseBackup databaseBackup, final BlobBackup blobBackup,
            final DatabaseFreezeService freezeService) {
        this.databaseBackup = checkNotNull(databaseBackup);
        this.blobBackup = checkNotNull(blobBackup);
        this.freezeService = checkNotNull(freezeService);
    }

    private interface Messages extends MessageBundle {
        @DefaultMessage(BackupTaskDescriptor.MSG)
        String message();
    }

    private static final Messages messages = I18N.create(Messages.class);

    @Override
    public String getMessage() {
        return messages.message();
    }

    @Override
    public void configure(final TaskConfiguration configuration) {
        super.configure(configuration);
        this.location = configuration.getString(BackupTaskDescriptor.BACKUP_LOCATION);
    }

    private File createBackupDirIfMissing(String basePath, String name) {
        File path = new File(basePath, name);
        if (!path.exists()) {
            if (!path.mkdir()) {
                throw new RuntimeException(
                        String.format("unable to perform backup task, attempt to create %s backup dir '%s' failed. Check parent path and permissions", name,
                                path.getAbsoluteFile()));
            }
        } else if (!path.isDirectory()) {
            throw new RuntimeException(
                    String.format("unable to perform backup task, %s backup location '%s' is not a directory", name, path.getAbsoluteFile()));
        }

        return path;
    }

    @Override
    protected Object execute() throws Exception {
        List<Callable<Void>> jobs = Lists.newArrayList();
        final LocalDateTime timestamp = LocalDateTime.now();
        log.info("task named '{}' database and blob backup to location {}", getName(), location);

        File blobBackupPath = createBackupDirIfMissing(location, "blob");
        File dbBackupPath = createBackupDirIfMissing(location, "db");
        
        MultipleFailures failures = new MultipleFailures();

        final FreezeRequest request = freezeService.requestFreeze(InitiatorType.SYSTEM, getConfiguration().getName());
        if (request == null) {
            throw new RuntimeException("unable to perform backup task, as attempt to freeze databases failed");
        }

        for (String dbName : databaseBackup.dbNames()) {
            try {
                log.info("database backup of {} starting", dbName);
                Callable<Void> job = databaseBackup.fullBackup(dbBackupPath.getAbsolutePath(), dbName, timestamp);
                jobs.add(job);
            } catch (Exception e) {
                failures.add(new RuntimeException(String.format(
                        "database backup of %s to location: %s please check filesystem permissions and that the location exists", dbName, location), e));
            }
        }

        for (String blobName : blobBackup.blobNames()) {
            try {
                log.info("blob backup of {} starting", blobName);
                Callable<Void> job = blobBackup.fullBackup(blobBackupPath.getAbsolutePath(), blobName, timestamp);
                jobs.add(job);
            } catch (Exception e) {
                failures.add(new RuntimeException(
                        String.format("blob backup of %s to location: %s please check filesystem permissions and that the location exists", blobName, location),
                        e));
            }
        }

        monitorBackupResults(jobs, failures);
        if (!freezeService.releaseRequest(request)) {
            failures.add(new RuntimeException("failed to automatically release read-only state; view the nodes screen to disable read-only mode."));
        }

        failures.maybePropagate();
        return null;

    }

    private void monitorBackupResults(final List<Callable<Void>> jobs, final MultipleFailures failures) throws InterruptedException {
        ExecutorService executorService = makeExecutorService();
        List<Future<Void>> futures = executorService.invokeAll(jobs);
        executorService.shutdown();
        for (Future<Void> future : futures) {
            try {
                future.get();
            } catch (ExecutionException e) {
                if (e.getCause() != null) {
                    failures.add(e.getCause()); // when cause is present, unwrapping to reduce log noise
                } else {
                    failures.add(e);
                }
            }
        }
    }

    private ExecutorService makeExecutorService() {
        BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(MAX_QUEUED_BACKUPS);
        ThreadFactory factory = new NexusThreadFactory("dbbackup", "dbbackup");
        ThreadPoolExecutor backing = new ThreadPoolExecutor(MAX_CONCURRENT_BACKUPS, MAX_CONCURRENT_BACKUPS, 1, TimeUnit.NANOSECONDS, queue, factory);
        backing.allowCoreThreadTimeOut(true);
        return NexusExecutorService.forFixedSubject(backing, FakeAlmightySubject.TASK_SUBJECT);
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(final String location) {
        this.location = location;
    }
}

package es.fmp.nexus.backup.blob;

/*
 * Inspired by https://github.com/sonatype/nexus-public/blob/master/components/nexus-core/src/main/java/org/sonatype/nexus/internal/backup/DatabaseBackup.java
 */
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Utilities for doing backups of databases
 *
 * @since 3.2
 */
public interface BlobBackup
{

  /**
   * @return java.util.List&lt;String&gt; Names of blobs
   */
  List<String> blobNames();

  /**
   * Creates a backup job
   *
   * @param backupFolder Name of folder where backup file will be created
   * @param blobName The name of the blob being backed up
   * @param timestamp a timestamp indicating when the backup was taken
   * @return java.util.concurrent.Callable For storing backup data
   * @throws IOException
   */
  Callable<Void> internalBackup(String backupFolder, String blobName, LocalDateTime timestamp) throws IOException;


  /**
   * Creates a backup job
   *
   * @param cmd External command to use alternatively to perform backup (all blob stores at once)
   * @return java.util.concurrent.Callable For storing backup data
   * @throws IOException
   */
  Callable<Void> externalBackup(String cmd) throws IOException;

}
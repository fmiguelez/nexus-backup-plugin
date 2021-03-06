# Nexus Backup Plugin

This plugin includes a custom backup task based on core Nexus OSS _"Backup databases"_ task ([DatabaseBackupTask](https://github.com/sonatype/nexus-public/blob/master/components/nexus-core/src/main/java/org/sonatype/nexus/internal/backup/DatabaseBackupTask.java)) that **also backs up blob stores** using same pattern (zip files with ".bak" extension) or alternatively executes an external script.

## Motivation

After a disk-full situation we faced a DB corruption issue (right in the middle of a release procedure of over 200 Maven artifacts). Luckily we had database backups (using Nexus _"Backup databases"_ task). Once we had databases restored to previous day version we faced another issue: blob and db insconsistencies that prevented artifacts from being even downloaded at all (mainly due to _maven-metadata.xml_ files no longer found in the "old" blobs pointed by metadata db). 

It is really funny that Nexus provides a way to back up/restore metadata databases but none to back up blob stores while still stating that "must be backed up together". The official documentation just says that blobs must be backed up externally but what it does not say is how to do this while still backing up databases and more importantly being both databases and blob stores perfectly consistent. When database backup task is launched Nexus is set in read-only mode so no more changes are allowed to any database or blob store. 

In read-only mode no other Nexus task can be run (how are we supposed to run the external blob store backup then?). We could stop Nexus to perform the copy but yet we would not have a clean way of knowing from the outside when database backup task has finished and also to prevent any more changes before Nexus is actually stopped.

We consider that the best moment to back up blob stores is **exactly at the same time** as the databases, thus inside the same backup task.

## Requirements

This plugin was developed for Sonatype Nexus OSS 3.19.1-01. 

Compilation requires Maven 3 and JDK 8 installed on local computer.

## Build 

Build plugin using KAR format:

```
mvn -PbuildKar clean install
```

## Plugin Installation

There are [several methods](https://sonatype-nexus-community.github.io/nexus-development-guides/plugin-install.html) to install the plugin. The easiest one is to copy the generated `kar` file to `<nexus_dir>/deploy`. Nexus will automatically install the plugin after a while. 

## Task Configuration

First of all you must create a new task ("Tasks" -> "Create task") from administration options page. You must now select _"Admin - Export databases and blobs for backup"_ (new option available after plugin has been installed). 

Configuration options are similar to the standard _"Admin - Export databases for backup"_ [backup task](https://help.sonatype.com/repomanager3/backup-and-restore/configure-and-run-the-backup-task). The _"Backup location"_ directory, instead of directly storing backup files (`.bak`) in the specified directory, indicates the base directory containing `db` subdirectory for database backups and `blob` subdirectory for blob store backups.

Blob store backup takes all files in every blob store subfolder and stores them in a compression-less zip file (with `.bak` extension) following same name pattern as the one used by db backups. This operation can be very slow (many hours) for blob stores with large number of small files that tend to also require many small blob files (although not that many). If this is the case _"Blob store backup command"_ option can be specified to use a more efficient backup method (e.g. using [rsync](https://rsync.samba.org/)). In this case instead of producing `.bak` files in `blob` subdirectory the given system command will be executed.       

## Backup Restore

Database backup restoration follows the same standard procedure as specified by [Sonatype documentation](https://help.sonatype.com/repomanager3/backup-and-restore/restore-exported-databases). However there is no automatic method to restore blob files. They must be restored manually. 

If an external tool (e.g. [rsnapshot](https://rsnapshot.org/)) was used to back up blob stores then you should follow that tool's indications to restore them. Below we describe a sample procedure to restore blobs from `.bak` files.   

Let us assume we have defined following environment variables `NEXUS_DATA_DIR` (Nexus data location), `NEXUS_BACKUP_DIR` (configured  _"Backup location"_), `NEXUS_BACKUP_TS` (the desired backup timestamp to restore, e.g. _"2019-12-14-00-00-00"_) and `NEXUS_VER` (e.g. _"3.19.1-01"_)  in a standard Linux installation.

With Nexus stopped we would back up current blob files (just in case):
```
$ mv $NEXUS_DATA_DIR/blobs $NEXUS_DATA_DIR/blobs.bak
$ mkdir $NEXUS_DATA_DIR/blobs 
```

Following bash script will extract backup files to correct location (we assume `unzip` in installed):

```
$ for file in $NEXUS_BACKUP_DIR/blob/*$NEXUS_BACKUP_TS*.bak; do dest="$NEXUS_DATA_DIR/blobs/$(echo $file | sed "s/.*\\/\([^\/]\+\)-$NEXUS_BACKUP_TS-$NEXUS_VER.bak/\1/g")"; echo "Restoring $file to $dest ..."; unzip "$file" -d "$dest"; echo "Done."; done
```

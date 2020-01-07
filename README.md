#Nexus Backup Plugin

This plugin includes a custom backup task based on core Nexus OSS "Backup databases" task ([DatabaseBackupTask](https://github.com/sonatype/nexus-public/blob/master/components/nexus-core/src/main/java/org/sonatype/nexus/internal/backup/DatabaseBackupTask.java)) that also backs up blobs files using same patterns (zip files with ".bak" extension).

#Requirements

This plugin was developed for Sonatype Nexus OSS 3.19.1-01. 

Compilation requires Maven 3 and JDK 8 installed on local computer.

##Build 

Build plugin using KAR format:

```
mvn -PbuildKar clean install
```

##Plugin Installation

There are [several methods](https://sonatype-nexus-community.github.io/nexus-development-guides/plugin-install.html) to install the plugin. The easiest one is to copy the generated `kar` file to `<nexus_dir>/deploy`. Nexus will automatically install the plugin after a while. 

##Task Configuration

First of all you must create a new task ("Tasks" -> "Create task") on administration options. You must now select _"Admin - Export databases and blobs for backup"_ (new option available after plugin has been installed). 

Configuration options are the same as the standard _"Admin - Export databases for backup"_ [backup task](https://help.sonatype.com/repomanager3/backup-and-restore/configure-and-run-the-backup-task). The only difference applies to _"Backup location"_ directory. Instead of directly storing backup files (`.bak`) in the specified directory, this backup task will use `db` subdirectory for database backups and `blob` subdirectory for blob backups.      

##Backup restoration

Database backup restoration follows the same standard procedure as specified by [Sonatype documentation](https://help.sonatype.com/repomanager3/backup-and-restore/restore-exported-databases). However there is no automatic method to restory blobs. Blob files must be restored manually. 

Let us assume we have defined following environment variables `NEXUS_DATA_DIR` (Nexus data location), `NEXUS_BACKUP_DIR` (configured  _"Backup location"_), `NEXUS_BACKUP_TS` (the desired backup timestamp to restore, e.g. _"2019-12-14-00-00-00"_) and `NEXUS_VER` (e.g. _"3.19.1-01"_)  in a standard Linux installation.

With Nexus OSS stopped we would backup current blob files (just in case):
```
$ mv $NEXUS_DATA_DIR/blobs $NEXUS_DATA_DIR/blobs.bak
$ mkdir $NEXUS_DATA_DIR/blobs 
```

Following bash script will extract backup files to correct location (we assume `unzip` in installed):

```
$ for file in $NEXUS_BACKUP_DIR/blob/*$NEXUS_BACKUP_TS*.bak; do dest="$NEXUS_DATA_DIR/blobs/$(echo $file | sed "s/.*\\/\([^\/]\+\)-$NEXUS_BACKUP_TS-$NEXUS_VER.bak/\1/g")"; echo "Restoring $file to $dest ..."; unzip "$file" -d "$dest"; echo "Done."; done
```
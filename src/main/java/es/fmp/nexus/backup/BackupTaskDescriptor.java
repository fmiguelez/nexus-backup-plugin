package es.fmp.nexus.backup;

import static org.sonatype.nexus.formfields.FormField.MANDATORY;
import static org.sonatype.nexus.formfields.FormField.OPTIONAL;

/*
 * Adapted from https://github.com/sonatype/nexus-public/blob/master/components/nexus-core/src/main/java/org/sonatype/nexus/internal/backup/BackupTaskDescriptor.java
 */

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.i18n.I18N;
import org.sonatype.goodies.i18n.MessageBundle;
import org.sonatype.nexus.common.node.NodeAccess;
import org.sonatype.nexus.formfields.StringTextFormField;
import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskDescriptorSupport;

/**
 * {@link DatabaseBackupTask} descriptor.
 *
 * @since 3.2
 */
@Named
@Singleton
public class BackupTaskDescriptor
    extends TaskDescriptorSupport
{
  public static final String MSG = "Admin - Export databases and blobs for backup";

  public static final String TYPE_ID = "backup";

  public static final String BACKUP_LOCATION = "location";

  public static final String BLOB_BACKUP_CMD = "cmd";

  private interface Messages
      extends MessageBundle
  {
    @DefaultMessage(MSG)
    String name();

    @DefaultMessage("Backup location")
    String locationLabel();

    @DefaultMessage("Filesystem location for backup data")
    String locationHelpText();

    @DefaultMessage("Blob store backup command")
    String cmdLabel();

    @DefaultMessage("If specified, provided system command is used to back up blobs instead of generating bak files")
    String cmdText();
  }

  private static final Messages messages = I18N.create(Messages.class);

  @Inject
  public BackupTaskDescriptor(final NodeAccess nodeAccess)
  {
    super(TYPE_ID, BackupTask.class, messages.name(), VISIBLE, EXPOSED,
        new StringTextFormField(
            BACKUP_LOCATION,
            messages.locationLabel(),
            messages.locationHelpText(),
            MANDATORY
        ),
        new StringTextFormField(
             BLOB_BACKUP_CMD,
             messages.cmdLabel(),
             messages.cmdText(),
             OPTIONAL
        ),
        nodeAccess.isClustered() ? newLimitNodeFormField() : null
    );
  }

  @Override
  public void initializeConfiguration(final TaskConfiguration configuration) {
    // cover upgrade from non-HA to HA: task will warn until a node is chosen
    configuration.setString(LIMIT_NODE_KEY, "");
  }
}
package com.claudecode.runtime.sessionlink;

import com.claudecode.core.annotation.Explanation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Cross-platform owner-only permission helper for local IPC sockets, worker discovery files,
 * and authentication records.
 *
 * <ul>
 *   <li>On POSIX filesystems (macOS / Linux): applies {@code 0600} on files/sockets and
 *       {@code 0700} on directories via {@link PosixFileAttributeView}.</li>
 *   <li>On Windows NTFS: applies an explicit single-ACE DACL granting access exclusively to
 *       {@link AclFileAttributeView#getOwner()}, stripping inherited {@code Users} /
 *       {@code Everyone} / {@code Authenticated Users} entries even under shared temp roots.</li>
 * </ul>
 */
@Explanation("Cross-platform POSIX 0600/0700 and Windows NTFS owner-only ACL enforcement")
public final class OwnerOnlyPermissions {

    private static final Set<PosixFilePermission> POSIX_FILE_0600 = Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE);

    private static final Set<PosixFilePermission> POSIX_DIR_0700 = Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE);

    private static final Set<PosixFilePermission> POSIX_EXEC_0700 = POSIX_DIR_0700;

    private OwnerOnlyPermissions() {}

    /** Restricts a regular file or Unix domain socket to the current OS user ({@code 0600} / NTFS owner DACL). */
    public static void restrictFile(Path path) {
        apply(path, POSIX_FILE_0600, false);
    }

    /** Restricts a directory to the current OS user ({@code 0700} / inheritable NTFS owner DACL). */
    public static void restrictDirectory(Path path) {
        apply(path, POSIX_DIR_0700, true);
    }

    /** Restricts a helper executable to the current OS user ({@code 0700} / NTFS owner DACL). */
    public static void restrictExecutable(Path path) {
        apply(path, POSIX_EXEC_0700, false);
    }

    private static void apply(Path path, Set<PosixFilePermission> posixPerms, boolean directory) {
        if (path == null) return;
        try {
            PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
            if (posix != null) {
                posix.setPermissions(posixPerms);
                return;
            }
        } catch (UnsupportedOperationException | IOException _) {
            // Fall through to Windows NTFS ACL view if available.
        }
        try {
            AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (acl != null) {
                applyOwnerOnlyAcl(acl, directory);
            }
        } catch (UnsupportedOperationException | IOException _) {
            // Exotic filesystem supporting neither POSIX modes nor NFSv4/NTFS ACLs (e.g. FAT32).
        }
    }

    static void applyOwnerOnlyAcl(AclFileAttributeView aclView, boolean directory) throws IOException {
        UserPrincipal owner = aclView.getOwner();
        if (owner == null) return;
        AclEntry.Builder builder = AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(owner)
            .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) {
            builder.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        }
        aclView.setAcl(List.of(builder.build()));
    }
}

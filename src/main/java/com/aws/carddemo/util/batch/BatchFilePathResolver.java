package com.aws.carddemo.util.batch;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Centralized, hardened resolver for every batch file path derived from a job parameter
 * (review finding&nbsp;#18).
 *
 * <p>Before this component, batch readers and writers built a
 * {@link org.springframework.core.io.FileSystemResource} directly from a raw job-parameter string,
 * with no containment, symlink, permission, or atomic-publication controls. A caller could therefore
 * traverse out of the intended working area ({@code ../../etc/passwd}), follow a planted symlink, or
 * leave a partially written output file behind on failure. This resolver closes those gaps for all
 * batch file I/O in one place:</p>
 *
 * <ul>
 *   <li><b>Safe-root containment.</b> Every resolved path must lie within one of a fixed set of
 *       allowed root directories (configured via {@code carddemo.batch.allowed-paths}, defaulting to
 *       the JVM temp directory and the module working directory). Paths are canonicalized
 *       ({@code ..} segments and symlinks resolved) before the containment check, so traversal
 *       attempts are rejected regardless of how they are spelled.</li>
 *   <li><b>Symlink rejection.</b> An input file, and the parent directory of an output target, are
 *       resolved to their real paths; a symlink whose real path escapes every allowed root is
 *       rejected. An output target that already exists as a symlink is refused outright.</li>
 *   <li><b>Restrictive permissions + atomic publication.</b> {@link #publish(Path, ContentWriter)}
 *       writes to a sibling temporary file created with owner-only {@code 0600} permissions, forces
 *       the bytes to durable storage ({@code fsync}), and atomically renames the temp file onto the
 *       final target. If the write fails the temp file is removed, so an incomplete or corrupt file
 *       is never published (rollback cleanup).</li>
 * </ul>
 *
 * <p>The class holds no per-invocation mutable state and is safe to share as a singleton bean.</p>
 */
public final class BatchFilePathResolver {

    private static final Set<PosixFilePermission> OWNER_ONLY =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    /**
     * Owner-only ({@code 0700}) directory permissions used for the private per-job-instance
     * <em>staging directory</em> that hosts a step's in-progress temp file(s) (QA finding
     * <strong>P4-SEC-01</strong>). The {@code OWNER_EXECUTE} (traverse) bit is required so the owner
     * process can enter the directory to create and read the temp; the absence of any group/other bit
     * is what makes the enclosed temp unreachable by other principals regardless of the temp file's
     * own umask-derived mode.
     */
    private static final Set<PosixFilePermission> OWNER_ONLY_DIR =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);

    /**
     * Fixed name prefix of the private per-job-instance staging directory created by
     * {@link #createSecureStagingDirectory(Path, long)}. The job-instance id is appended to make the
     * directory deterministic (a restart of the same instance reuses it) and unique per instance.
     */
    private static final String STAGING_DIR_PREFIX = ".carddemo-inprogress-";

    private final List<Path> allowedRoots;
    private final boolean posixSupported;

    /**
     * Creates a resolver with an explicit set of allowed root directories.
     *
     * @param allowedRootPaths the directories under which all resolved paths must lie; each is
     *                         canonicalized to its real path. Must not be {@code null} or empty.
     * @throws IllegalArgumentException if the list is {@code null}/empty or a root cannot be
     *                                  canonicalized
     */
    public BatchFilePathResolver(List<String> allowedRootPaths) {
        if (allowedRootPaths == null || allowedRootPaths.isEmpty()) {
            throw new IllegalArgumentException("At least one allowed root path is required");
        }
        List<Path> roots = new ArrayList<>(allowedRootPaths.size());
        for (String raw : allowedRootPaths) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            roots.add(canonicalizeExisting(Paths.get(raw).toAbsolutePath()));
        }
        if (roots.isEmpty()) {
            throw new IllegalArgumentException("At least one non-blank allowed root path is required");
        }
        this.allowedRoots = List.copyOf(roots);
        this.posixSupported = roots.get(0).getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    /**
     * Resolves and validates a job-parameter path that must refer to an existing, readable, regular
     * input file contained within an allowed root.
     *
     * @param rawPath the raw job-parameter path; must not be {@code null}/blank
     * @return the canonical (real) path of the input file
     * @throws IllegalArgumentException if the path is blank, escapes every allowed root, is a symlink
     *                                  escaping the allowed roots, or is not an existing regular file
     */
    public Path resolveInputFile(String rawPath) {
        Path requested = requireNonBlank(rawPath);
        Path real = canonicalizeExisting(requested);
        requireContained(real, requested);
        if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(real)) {
            throw new IllegalArgumentException(
                    "Batch input path is not an existing regular file: " + describe(requested));
        }
        return real;
    }

    /**
     * Resolves and validates a job-parameter path used as an output target. The target itself need
     * not exist, but its parent directory must resolve within an allowed root (it is created if
     * absent), and the target must not already exist as a symlink.
     *
     * @param rawPath the raw job-parameter output path; must not be {@code null}/blank
     * @return the resolved target path (its real parent joined with the target file name)
     * @throws IllegalArgumentException if the path is blank, its parent escapes every allowed root,
     *                                  or the target already exists as a symbolic link
     * @throws UncheckedIOException     if the parent directory cannot be created
     */
    public Path resolveOutputTarget(String rawPath) {
        Path requested = requireNonBlank(rawPath).toAbsolutePath().normalize();
        Path parent = requested.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Batch output path has no parent directory: "
                    + describe(requested));
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException ex) {
            throw new UncheckedIOException(
                    "Unable to create batch output directory: " + describe(parent), ex);
        }
        Path realParent = canonicalizeExisting(parent);
        requireContained(realParent, parent);
        Path target = realParent.resolve(requested.getFileName());
        if (Files.isSymbolicLink(target)) {
            throw new IllegalArgumentException(
                    "Batch output target must not be a symbolic link: " + describe(target));
        }
        return target;
    }

    /**
     * Creates (or, on a restart, re-asserts) a <em>private owner-only ({@code 0700}) staging
     * directory</em> alongside {@code target}, into which a step's in-progress temp file(s) are
     * written before atomic publication (QA finding <strong>P4-SEC-01</strong>).
     *
     * <p><strong>Why a staging directory.</strong> Spring Batch's
     * {@link org.springframework.batch.item.file.FlatFileItemWriter} creates its output file itself
     * (via {@code File.createNewFile()}), so the file's mode is dictated by the process umask
     * ({@code 0644} under the common {@code 0022}) for the entire duration of the step &mdash; a
     * world-readable window on data such as the {@code DALYREJS} reject feed or a customer statement.
     * The temp file's own mode cannot be pinned before the writer creates it without breaking the
     * writer's restart/append contract; instead the temp is placed inside a directory whose mode
     * <em>is</em> pinned to {@code 0700} at creation. Because a POSIX {@code mkdir} applies
     * {@code mode & ~umask} and {@code 0700} carries no group/other bits, the requested mode survives
     * any umask, so the directory is owner-only from the instant it exists (race-free). With the
     * enclosing directory non-traversable by group/other, the enclosed temp is unreachable by any
     * other principal regardless of its own {@code 0644} mode.</p>
     *
     * <p>The directory is a sibling-level child of the target's real parent
     * ({@code <parent>/.carddemo-inprogress-<instanceId>}), guaranteeing it shares a filesystem with
     * the final target so the subsequent {@link #atomicPublish(Path, Path)} rename stays atomic. The
     * name is deterministic per job instance so a restart reuses the same directory and resumes the
     * same in-progress temp; a step that publishes several outputs sharing one parent shares one
     * staging directory. The directory is created if absent, and if it already exists (restart, or a
     * sibling output of the same step) its owner-only mode is re-asserted and a planted symlink or
     * non-directory in its place is rejected.</p>
     *
     * @param target     the resolved final target (from {@link #resolveOutputTarget(String)}) whose
     *                   parent hosts the staging directory; must not be {@code null}
     * @param instanceId the job-instance id, making the staging directory deterministic and unique
     * @return the private staging directory path (created with, or re-asserted to, {@code 0700})
     * @throws IllegalArgumentException if {@code target} has no parent, or the staging path already
     *                                  exists as a symbolic link or non-directory
     * @throws UncheckedIOException     if the staging directory cannot be created
     */
    public Path createSecureStagingDirectory(Path target, long instanceId) {
        Objects.requireNonNull(target, "target must not be null");
        Path parent = target.getParent();
        if (parent == null) {
            throw new IllegalArgumentException(
                    "Batch output target has no parent directory: " + describe(target));
        }
        Path stagingDir = parent.resolve(STAGING_DIR_PREFIX + instanceId);
        try {
            if (posixSupported) {
                FileAttribute<Set<PosixFilePermission>> attr =
                        PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIR);
                try {
                    Files.createDirectory(stagingDir, attr);
                } catch (FileAlreadyExistsException alreadyExists) {
                    // Restart of the same instance, or a second output of the same step reusing the
                    // directory: it must be a real directory (never a planted symlink), and its
                    // owner-only mode is re-asserted defensively.
                    if (Files.isSymbolicLink(stagingDir)
                            || !Files.isDirectory(stagingDir, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IllegalArgumentException(
                                "Batch staging path must be a real directory: " + describe(stagingDir));
                    }
                    Files.setPosixFilePermissions(stagingDir, OWNER_ONLY_DIR);
                }
            } else {
                // Non-POSIX filesystem: create the directory and best-effort restrict it to the owner.
                Files.createDirectories(stagingDir);
                stagingDir.toFile().setReadable(false, false);
                stagingDir.toFile().setReadable(true, true);
                stagingDir.toFile().setWritable(false, false);
                stagingDir.toFile().setWritable(true, true);
                stagingDir.toFile().setExecutable(false, false);
                stagingDir.toFile().setExecutable(true, true);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(
                    "Unable to create batch staging directory: " + describe(stagingDir), ex);
        }
        return stagingDir;
    }

    /**
     * Atomically publishes content to {@code target}: content is written to a sibling temporary file
     * created with owner-only ({@code 0600}) permissions, flushed to durable storage, and then
     * atomically renamed onto {@code target}. On any failure the temporary file is deleted so a
     * partially written file is never left in place.
     *
     * @param target the final output path (typically produced by {@link #resolveOutputTarget(String)})
     * @param writer the callback that writes the complete content to the supplied stream
     * @throws UncheckedIOException if the content cannot be written or published
     */
    public void publish(Path target, ContentWriter writer) {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(writer, "writer must not be null");
        Path dir = target.getParent();
        Path temp = null;
        try {
            temp = createSecureTemp(dir, target.getFileName().toString());
            try (OutputStream out = Files.newOutputStream(temp, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                writer.writeTo(out);
                out.flush();
            }
            fsync(temp);
            atomicMove(temp, target);
            temp = null; // published; nothing to clean up
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to publish batch output: " + describe(target), ex);
        } finally {
            if (temp != null) {
                deleteQuietly(temp);
            }
        }
    }

    /**
     * Creates a sibling temporary file for {@code target} with owner-only ({@code 0600}) permissions.
     * The temporary lives in the same directory as the final target so that the subsequent rename is
     * atomic (same filesystem).
     *
     * @param target the final target path whose directory hosts the temporary file
     * @return the created temporary file path
     * @throws UncheckedIOException if the temporary file cannot be created
     */
    public Path createSecureTemp(Path target) {
        Objects.requireNonNull(target, "target must not be null");
        return createSecureTemp(target.getParent(), target.getFileName().toString());
    }

    /**
     * Atomically renames {@code temp} onto {@code target}, forcing the bytes to durable storage
     * first. Falls back to a non-atomic replacing move only if the filesystem does not support
     * atomic moves.
     *
     * @param temp   the fully written temporary file
     * @param target the final target path
     * @throws UncheckedIOException if the move fails
     */
    public void atomicPublish(Path temp, Path target) {
        try {
            fsync(temp);
            atomicMove(temp, target);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to publish batch output: " + describe(target), ex);
        }
    }

    /**
     * Deletes {@code path} if it exists, swallowing any error (best-effort rollback cleanup).
     *
     * @param path the path to delete; may be {@code null}
     */
    public void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort cleanup; nothing actionable if it fails
        }
    }

    private Path createSecureTemp(Path dir, String baseName) {
        try {
            if (posixSupported) {
                FileAttribute<Set<PosixFilePermission>> attr =
                        PosixFilePermissions.asFileAttribute(OWNER_ONLY);
                return Files.createTempFile(dir, baseName + ".", ".tmp", attr);
            }
            Path temp = Files.createTempFile(dir, baseName + ".", ".tmp");
            temp.toFile().setReadable(false, false);
            temp.toFile().setReadable(true, true);
            temp.toFile().setWritable(false, false);
            temp.toFile().setWritable(true, true);
            return temp;
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to create secure temp file in " + describe(dir), ex);
        }
    }

    private static void fsync(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void requireContained(Path realPath, Path requestedForMessage) {
        for (Path root : allowedRoots) {
            if (realPath.startsWith(root)) {
                return;
            }
        }
        throw new IllegalArgumentException(
                "Batch path is outside every allowed root directory: " + describe(requestedForMessage));
    }

    private static Path requireNonBlank(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("Batch file path must not be null or blank");
        }
        return Paths.get(rawPath);
    }

    private static Path canonicalizeExisting(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path existing = absolute;
        // Walk up to the nearest existing ancestor so a not-yet-created leaf can still be canonicalized.
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return absolute;
        }
        try {
            Path realExisting = existing.toRealPath();
            Path remainder = existing.relativize(absolute);
            return remainder.toString().isEmpty() ? realExisting : realExisting.resolve(remainder);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to resolve batch path: " + describe(path), ex);
        }
    }

    /**
     * Renders a path for diagnostics using only its file name, never the full absolute path, to avoid
     * leaking directory layout into logs or exception messages (CWE-532-aware).
     *
     * @param path the path to describe
     * @return a non-sensitive description (the file name, or {@code "<root>"})
     */
    private static String describe(Path path) {
        Path name = path.getFileName();
        return (name == null) ? "<root>" : name.toString();
    }

    /**
     * Callback that writes the complete content of a batch output file to the supplied stream. The
     * stream is closed by the resolver.
     */
    @FunctionalInterface
    public interface ContentWriter {
        /**
         * Writes the entire file content to {@code out}.
         *
         * @param out the destination stream (already opened; closed by the caller)
         * @throws IOException if writing fails
         */
        void writeTo(OutputStream out) throws IOException;
    }
}

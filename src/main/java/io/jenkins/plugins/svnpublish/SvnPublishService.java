package io.jenkins.plugins.svnpublish;

import org.tmatesoft.svn.core.SVNCommitInfo;
import org.tmatesoft.svn.core.SVNDirEntry;
import org.tmatesoft.svn.core.SVNErrorCode;
import org.tmatesoft.svn.core.SVNErrorMessage;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNNodeKind;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.auth.ISVNAuthenticationManager;
import org.tmatesoft.svn.core.io.ISVNEditor;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.io.SVNRepositoryFactory;
import org.tmatesoft.svn.core.io.diff.SVNDeltaGenerator;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Performs the actual SVN publish: adds new files, overwrites existing files and (optionally)
 * deletes files that vanished from the publish set — all in a single atomic SVN commit.
 *
 * <p>Uses only SVNKit's low-level commit-editor API so the whole publish is one transaction
 * with one revision.</p>
 */
public final class SvnPublishService {

    private static final int MAX_LIST_DEPTH = 10;

    private SvnPublishService() {
    }

    /** Outcome of a publish: new revision plus per-file actions. */
    public static final class Result implements java.io.Serializable {
        private static final long serialVersionUID = 1L;

        private long newRevision = -1;
        private final List<String> added = new ArrayList<>();
        private final List<String> updated = new ArrayList<>();
        private final List<String> deleted = new ArrayList<>();

        void setNewRevision(long revision) {
            this.newRevision = revision;
        }

        public long getNewRevision() {
            return newRevision;
        }

        public List<String> getAdded() {
            return added;
        }

        public List<String> getUpdated() {
            return updated;
        }

        public List<String> getDeleted() {
            return deleted;
        }

        @Override
        public String toString() {
            return "r" + newRevision + " +" + added.size() + " ~" + updated.size() + " -" + deleted.size();
        }
    }

    /**
     * Publishes the mapped files to {@code url} in one commit.
     *
     * @param url           target SVN directory (must already exist in the repository)
     * @param authManager   SVNKit auth manager built from Jenkins credentials, null for anonymous
     * @param mapped        files to publish (sorted or not; will be processed sorted)
     * @param commitMessage commit message
     * @param cleanup       when true, delete versioned target files absent from {@code mapped}
     * @param logger        optional sink for per-file progress lines ("A path", "U path", "D path")
     * @param targetExcludes Ant-style patterns; during cleanup, remote paths matching any pattern
     *                       are never deleted
     * @return publish outcome with the committed revision
     */
    public static Result publish(@NonNull SVNURL url,
                                 @Nullable ISVNAuthenticationManager authManager,
                                 @NonNull List<SvnPublishMapping.MappedFile> mapped,
                                 @NonNull String commitMessage,
                                 boolean cleanup,
                                 @Nullable Consumer<String> logger,
                                 @Nullable List<String> targetExcludes) throws IOException, InterruptedException {
        SVNRepository repo;
        try {
            repo = SVNRepositoryFactory.create(url);
        } catch (SVNException e) {
            throw new IOException("Invalid SVN URL " + url + ": " + e.getMessage(), e);
        }
        if (authManager != null) {
            repo.setAuthenticationManager(authManager);
        }

        Map<String, SVNDirEntry> remoteFiles = new TreeMap<>();
        Set<String> remoteDirs = new TreeSet<>();
        try {
            SVNNodeKind kind = repo.checkPath("", -1);
            if (kind != SVNNodeKind.DIR) {
                throw new IOException("SVN path is not a directory (or does not exist): " + url
                        + " — create it first; this plugin does not create the target directory.");
            }
            collectExisting(repo, "", remoteFiles, remoteDirs, 0);
        } catch (SVNException e) {
            throw new IOException("Failed to inspect SVN target " + url + ": " + e.getMessage(), e);
        }

        // Sort by target path so that directory state changes are grouped.
        List<SvnPublishMapping.MappedFile> sorted = new ArrayList<>(mapped);
        sorted.sort((a, b) -> a.getSvnRelativePath().compareTo(b.getSvnRelativePath()));

        Result result = new Result();
        Set<String> keptDirs = new HashSet<>(remoteDirs);
        Deque<String> openDirs = new ArrayDeque<>();
        ISVNEditor editor;
        try {
            editor = repo.getCommitEditor(commitMessage, null);
        } catch (SVNException e) {
            throw new IOException("Failed to start SVN commit: " + e.getMessage(), e);
        }

        boolean finished = false;
        try {
            editor.openRoot(-1);

            for (SvnPublishMapping.MappedFile mf : sorted) {
                if (Thread.interrupted()) {
                    throw new InterruptedException("SVN publish interrupted");
                }
                String rel = mf.getSvnRelativePath();
                openTo(editor, parentOf(rel), openDirs, keptDirs);
                boolean exists = remoteFiles.containsKey(rel);
                if (exists) {
                    editor.openFile(rel, -1);
                    result.getUpdated().add(rel);
                } else {
                    editor.addFile(rel, null, -1);
                    result.getAdded().add(rel);
                }
                editor.applyTextDelta(rel, null);
                String checksum;
                try (InputStream in = mf.getWorkspaceFile().read()) {
                    checksum = new SVNDeltaGenerator().sendDelta(rel, in, editor, true);
                }
                editor.closeFile(rel, checksum);
                if (logger != null) {
                    logger.accept((exists ? "U " : "A ") + rel);
                }
            }

            if (cleanup) {
                openTo(editor, "", openDirs, keptDirs);
                Set<String> mappedPaths = new HashSet<>();
                for (SvnPublishMapping.MappedFile mf : sorted) {
                    mappedPaths.add(mf.getSvnRelativePath());
                }
                List<String> excludes = targetExcludes == null ? Collections.emptyList() : targetExcludes;
                // Delete files not in the mapped set and not target-excluded, deepest first.
                List<String> filesToDelete = new ArrayList<>();
                for (String rel : remoteFiles.keySet()) {
                    if (mappedPaths.contains(rel)) {
                        continue;
                    }
                    if (matchesAny(excludes, rel)) {
                        if (logger != null) {
                            logger.accept("? " + rel + " (kept by target exclude)");
                        }
                        continue;
                    }
                    filesToDelete.add(rel);
                }
                filesToDelete.sort(DESCENDING_DEPTH);
                for (String rel : filesToDelete) {
                    editor.deleteEntry(rel, -1);
                    result.getDeleted().add(rel);
                    if (logger != null) {
                        logger.accept("D " + rel);
                    }
                }
                // Delete directories that no longer contain any mapped, kept or target-excluded file.
                List<String> dirsByDepth = new ArrayList<>(remoteDirs);
                dirsByDepth.sort(DESCENDING_DEPTH);
                for (String dir : dirsByDepth) {
                    boolean hasMappedChild = false;
                    for (String p : mappedPaths) {
                        if (p.startsWith(dir + "/")) {
                            hasMappedChild = true;
                            break;
                        }
                    }
                    if (hasMappedChild) {
                        continue;
                    }
                    boolean hadRemoteContent = false;
                    for (String f : remoteFiles.keySet()) {
                        if (f.startsWith(dir + "/")) {
                            hadRemoteContent = true;
                            break;
                        }
                    }
                    if (!hadRemoteContent) {
                        continue; // leave pristine subtrees untouched
                    }
                    if (matchesAny(excludes, dir) || matchesAny(excludes, dir + "/**")) {
                        if (logger != null) {
                            logger.accept("? " + dir + " (kept by target exclude)");
                        }
                        continue;
                    }
                    editor.deleteEntry(dir, -1);
                    if (logger != null) {
                        logger.accept("D " + dir);
                    }
                }
            }

            openTo(editor, "", openDirs, keptDirs);
            editor.closeDir(); // root
            SVNCommitInfo info = editor.closeEdit();
            finished = true;
            if (info.getErrorMessage() != null) {
                throw new IOException("SVN commit rejected: " + info.getErrorMessage().getMessage());
            }
            result.setNewRevision(info.getNewRevision());
            return result;
        } catch (SVNException e) {
            throw new IOException("SVN publish failed: " + e.getMessage(), e);
        } finally {
            if (!finished) {
                try {
                    editor.abortEdit();
                } catch (SVNException ignore) {
                    // secondary failure during abort is not actionable
                }
            }
        }
    }

    private static final java.util.Comparator<String> DESCENDING_DEPTH =
            (a, b) -> Integer.compare(depth(b), depth(a));

    private static boolean matchesAny(List<String> patterns, String path) {
        for (String p : patterns) {
            if (AntGlobMatcher.match(p, path)) {
                return true;
            }
        }
        return false;
    }

    private static int depth(String path) {
        int d = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') {
                d++;
            }
        }
        return d;
    }

    private static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    /**
     * Moves the editor's open-directory stack so that {@code target} is the innermost open dir.
     * {@code ""} means the edit root. Missing intermediate dirs are created with addDir.
     */
    private static void openTo(ISVNEditor editor, String target, Deque<String> openDirs, Set<String> knownDirs) throws SVNException {
        String current = openDirs.isEmpty() ? "" : openDirs.peek();
        String common = commonDirPrefix(current, target);
        // Close dirs deeper than the common prefix.
        while (!openDirs.isEmpty()) {
            String top = openDirs.peek();
            if (top.equals(common) || common.startsWith(top + "/") || top.isEmpty()) {
                break;
            }
            openDirs.pop();
            editor.closeDir();
        }
        // Open/add remaining components of target beyond common.
        if (target.equals(common) || target.isEmpty()) {
            return;
        }
        String[] segments = target.split("/");
        StringBuilder pos = new StringBuilder();
        int startFrom = countSegments(common);
        for (int i = 0; i < segments.length; i++) {
            if (segments[i].isEmpty()) {
                continue;
            }
            if (pos.length() > 0) {
                pos.append('/');
            }
            pos.append(segments[i]);
            if (i < startFrom) {
                continue; // already open
            }
            String dir = pos.toString();
            if (knownDirs.contains(dir)) {
                editor.openDir(dir, -1);
            } else {
                editor.addDir(dir, null, -1);
                knownDirs.add(dir);
            }
            openDirs.push(dir);
        }
    }

    /** Longest directory-prefix of both strings measured in whole segments. */
    private static String commonDirPrefix(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return "";
        }
        String[] sa = a.split("/");
        String[] sb = b.split("/");
        int n = Math.min(sa.length, sb.length);
        StringBuilder common = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (!sa[i].equals(sb[i])) {
                break;
            }
            if (common.length() > 0) {
                common.append('/');
            }
            common.append(sa[i]);
        }
        return common.toString();
    }

    private static int countSegments(String path) {
        if (path == null || path.isEmpty()) {
            return 0;
        }
        return path.split("/").length;
    }

    private static void collectExisting(SVNRepository repo, String path,
                                        Map<String, SVNDirEntry> files,
                                        Set<String> dirs,
                                        int depth) throws SVNException {
        Collection entries = repo.getDir(path, -1, null, (Collection) null);
        if (entries == null) {
            return;
        }
        for (Object o : entries) {
            SVNDirEntry e = (SVNDirEntry) o;
            String child = path.isEmpty() ? e.getName() : path + "/" + e.getName();
            if (e.getKind() == SVNNodeKind.DIR) {
                dirs.add(child);
                if (depth < MAX_LIST_DEPTH) {
                    collectExisting(repo, child, files, dirs, depth + 1);
                }
            } else if (e.getKind() == SVNNodeKind.FILE) {
                files.put(child, e);
            }
        }
    }
}

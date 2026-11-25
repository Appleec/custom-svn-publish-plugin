package io.jenkins.plugins.svnpublish;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.FilePath;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Maps a set of workspace files (Ant include/exclude patterns) to relative paths
 * inside the target SVN directory. Pure logic, no SVN access — easy to unit test.
 */
public final class SvnPublishMapping {

    /** One workspace file and the SVN-relative path (forward slashes) it will be committed to. */
    public static final class MappedFile implements Serializable {
        private static final long serialVersionUID = 1L;

        private final @NonNull FilePath workspaceFile;
        private final @NonNull String svnRelativePath;

        MappedFile(@NonNull FilePath workspaceFile, @NonNull String svnRelativePath) {
            this.workspaceFile = workspaceFile;
            this.svnRelativePath = svnRelativePath;
        }

        public @NonNull FilePath getWorkspaceFile() {
            return workspaceFile;
        }

        /** Valid only when the workspace is on the controller node. */
        public @NonNull File getLocalFile() {
            return new File(workspaceFile.getRemote());
        }

        /** Path relative to the target SVN directory, forward slashes, no leading slash. */
        public @NonNull String getSvnRelativePath() {
            return svnRelativePath;
        }

        @Override
        public String toString() {
            return svnRelativePath + " <- " + workspaceFile.getRemote();
        }
    }

    private SvnPublishMapping() {
    }

    /**
     * Scans the workspace and maps every file matching {@code include} (and not matching
     * {@code exclude}) to a path under the (already normalized) {@code target} sub-path.
     * An include pattern that names an existing workspace directory (no wildcards) publishes
     * that directory's whole subtree.
     *
     * @param workspace   build workspace
     * @param include     Ant-style include pattern (comma-separated patterns allowed)
     * @param target optional sub-path inside the SVN repository, may be null/blank
     * @param exclude     optional Ant-style exclude pattern, may be null/blank
     * @return mapped files sorted (and de-duplicated) by SVN path
     */
    public static List<MappedFile> scan(@NonNull FilePath workspace,
                                        @NonNull String include,
                                        String target,
                                        String exclude) throws IOException, InterruptedException {
        if (include == null || include.trim().isEmpty()) {
            throw new IllegalArgumentException("The include pattern must not be empty.");
        }

        String sub = normalize(target);
        if (sub.contains("..")) {
            throw new IllegalArgumentException(
                    "The target sub-path must not contain '..': " + target);
        }

        String effectiveInclude = expandDirectorySources(workspace, include);
        FilePath[] files = workspace.list(effectiveInclude, blankToNull(exclude));
        Map<String, MappedFile> byPath = new TreeMap<>();
        String root = toForwardSlash(workspace.getRemote());
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        for (FilePath f : files) {
            if (f.isDirectory()) {
                continue;
            }
            String rel = relativize(root, f.getRemote());
            String svnPath = sub.isEmpty() ? rel : sub + "/" + rel;
            byPath.putIfAbsent(svnPath, new MappedFile(f, svnPath));
        }
        List<MappedFile> mapped = new ArrayList<>(byPath.values());
        mapped.sort(Comparator.comparing(MappedFile::getSvnRelativePath));
        return mapped;
    }

    /**
     * Rewrites wildcard-free include tokens that name an existing workspace directory into
     * {@code dir/**} so the whole subtree is published (Ant DirectoryScanner would otherwise
     * match the directory itself and return no files). Other tokens pass through unchanged.
     */
    private static String expandDirectorySources(@NonNull FilePath workspace, @NonNull String include)
            throws IOException, InterruptedException {
        StringBuilder out = new StringBuilder();
        for (String token : include.split(",")) {
            String t = token.trim().replace('\\', '/');
            if (t.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(',');
            }
            if (t.indexOf('*') < 0 && t.indexOf('?') < 0 && workspace.child(t).isDirectory()) {
                out.append(t).append("/**");
            } else {
                out.append(t);
            }
        }
        return out.length() == 0 ? include : out.toString();
    }

    /** Normalizes a user-entered sub-path: trims, converts separators, strips boundary slashes. */
    static String normalize(String target) {
        if (target == null) {
            return "";
        }
        String s = target.trim().replace('\\', '/');
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String relativize(String root, String file) {
        String full = toForwardSlash(file);
        if (full.startsWith(root + "/")) {
            return full.substring(root.length() + 1);
        }
        if (full.startsWith(root)) {
            return full.substring(root.length());
        }
        // Fallback: file name only (defensive, should not happen with FilePath.list)
        int slash = full.lastIndexOf('/');
        return slash >= 0 ? full.substring(slash + 1) : full;
    }

    private static String toForwardSlash(String p) {
        return p.replace('\\', '/');
    }

    private static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s;
    }
}

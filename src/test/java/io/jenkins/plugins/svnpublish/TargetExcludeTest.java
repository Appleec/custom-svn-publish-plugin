package io.jenkins.plugins.svnpublish;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Target-exclude keeps matching remote paths alive when cleanup deletes the rest. */
class TargetExcludeTest {

    @TempDir
    File reposRoot;

    @TempDir
    File workspaceDir;

    private static final String KEEP_DIR = "keep-dir";
    private static final String KEEP_TXT = "keep.txt";
    private static final String DROP_TXT = "drop.txt";

    @Test
    void cleanupRespectsTargetExclude() throws Exception {
        org.tmatesoft.svn.core.io.SVNRepository repo = createRepoWith("keep-dir/inner.txt", "keep.txt", "drop.txt");

        // Workspace publishes nothing new; cleanup with target-exclude must preserve the
        // keep-* paths while deleting drop.txt.
        SvnPublishService.Result r = SvnPublishService.publish(
                repo.getLocation(),
                null,
                Collections.emptyList(),
                "cleanup",
                true,
                null,
                Arrays.asList("keep-dir/**", "keep.txt"));

        assertTrue(r.getDeleted().contains(DROP_TXT), r.toString());
        assertFalse(r.getDeleted().contains(KEEP_TXT), r.toString());
        assertFalse(r.getDeleted().stream().anyMatch(p -> p.startsWith(KEEP_DIR + "/")), r.toString());
    }

    @Test
    void targetExcludeWithoutCleanupDeletesNothing() throws Exception {
        org.tmatesoft.svn.core.io.SVNRepository repo = createRepoWith("keep.txt", "drop.txt");

        SvnPublishService.Result r = SvnPublishService.publish(
                repo.getLocation(), null, Collections.emptyList(), "no-cleanup", false, null, null);

        assertTrue(r.getDeleted().isEmpty(), r.toString());
        assertTrue(r.getAdded().isEmpty() && r.getUpdated().isEmpty(), r.toString());
    }

    private org.tmatesoft.svn.core.io.SVNRepository createRepoWith(String... files) throws Exception {
        Path repoPath = reposRoot.toPath().resolve("repo");
        org.tmatesoft.svn.core.wc.admin.SVNAdminClient admin =
                new org.tmatesoft.svn.core.wc.admin.SVNAdminClient(
                        (org.tmatesoft.svn.core.auth.ISVNAuthenticationManager) null,
                        org.tmatesoft.svn.core.wc.SVNWCUtil.createDefaultOptions(true));
        admin.doCreateRepository(repoPath.toFile(), null, true, true);
        org.tmatesoft.svn.core.io.SVNRepository repo =
                org.tmatesoft.svn.core.io.SVNRepositoryFactory.create(
                        org.tmatesoft.svn.core.SVNURL.fromFile(repoPath.toFile()));
        org.tmatesoft.svn.core.io.ISVNEditor editor = repo.getCommitEditor("seed", null);
        editor.openRoot(-1);
        String openDir = null;
        for (String f : files) {
            if (f.contains("/")) {
                String dir = f.substring(0, f.lastIndexOf('/'));
                if (!dir.equals(openDir)) {
                    if (openDir != null) {
                        editor.closeDir();
                    }
                    editor.addDir(dir, null, -1);
                    openDir = dir;
                }
            }
            editor.addFile(f, null, -1);
            editor.applyTextDelta(f, null);
            java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream("seed".getBytes("UTF-8"));
            String checksum = new org.tmatesoft.svn.core.io.diff.SVNDeltaGenerator().sendDelta(f, in, editor, true);
            editor.closeFile(f, checksum);
        }
        if (openDir != null) {
            editor.closeDir();
        }
        editor.closeDir();
        editor.closeEdit();
        return repo;
    }
}

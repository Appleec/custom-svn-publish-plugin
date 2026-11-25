package io.jenkins.plugins.svnpublish;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lists sub-directories of a live file:// repository for form auto-completion. */
class SvnDirectoryBrowserTest {

    @TempDir
    File reposRoot;

    private String repoUrl;

    @BeforeEach
    void setUp() throws Exception {
        Path repoPath = reposRoot.toPath().resolve("repo");
        org.tmatesoft.svn.core.wc.admin.SVNAdminClient admin =
                new org.tmatesoft.svn.core.wc.admin.SVNAdminClient(
                        (org.tmatesoft.svn.core.auth.ISVNAuthenticationManager) null,
                        org.tmatesoft.svn.core.wc.SVNWCUtil.createDefaultOptions(true));
        repoUrl = admin.doCreateRepository(repoPath.toFile(), null, true, true).toString();

        // trunk, trunk/releases, tags
        org.tmatesoft.svn.core.io.SVNRepository repo =
                org.tmatesoft.svn.core.io.SVNRepositoryFactory.create(
                        org.tmatesoft.svn.core.SVNURL.parseURIEncoded(repoUrl));
        mkdir(repo, "trunk");
        mkdir(repo, "trunk/releases");
        mkdir(repo, "tags");
    }

    private static void mkdir(org.tmatesoft.svn.core.io.SVNRepository repo, String path) throws Exception {
        org.tmatesoft.svn.core.io.ISVNEditor editor = repo.getCommitEditor("mkdir " + path, null);
        editor.openRoot(-1);
        String parent = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "";
        if (!parent.isEmpty()) {
            editor.openDir(parent, -1);
        }
        editor.addDir(path, null, -1);
        editor.closeDir();
        if (!parent.isEmpty()) {
            editor.closeDir();
        }
        editor.closeDir();
        editor.closeEdit();
    }

    @Test
    void listsTopLevelDirectories() {
        List<String> dirs = SvnDirectoryBrowser.listChildDirs(repoUrl, null, null, null, null);
        assertTrue(dirs.contains("trunk"), dirs.toString());
        assertTrue(dirs.contains("tags"), dirs.toString());
        assertFalse(dirs.contains("releases"), "nested dirs must not appear at top level");
    }

    @Test
    void listsNestedDirectoriesWhenPathGiven() {
        List<String> dirs = SvnDirectoryBrowser.listChildDirs(
                repoUrl + "/trunk", null, null, null, null);
        assertTrue(dirs.contains("releases"), dirs.toString());
    }

    @Test
    void invalidUrlYieldsEmptyList() {
        List<String> dirs = SvnDirectoryBrowser.listChildDirs("not-a-url", null, null, null, null);
        assertTrue(dirs.isEmpty());
    }
}

package io.jenkins.plugins.svnpublish;

import hudson.FilePath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tmatesoft.svn.core.SVNDepth;
import org.tmatesoft.svn.core.SVNDirEntry;
import org.tmatesoft.svn.core.SVNNodeKind;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.io.SVNRepositoryFactory;
import org.tmatesoft.svn.core.wc.SVNRevision;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test against a real (file://) SVN repository created with SVNKit's own
 * repository creation API — no external SVN server needed.
 */
class SvnPublishServiceTest {

    @TempDir
    File reposRoot;

    @TempDir
    File workspaceDir;

    private SVNRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        Path repoPath = reposRoot.toPath().resolve("repo");
        org.tmatesoft.svn.core.wc.admin.SVNAdminClient admin =
                new org.tmatesoft.svn.core.wc.admin.SVNAdminClient(
                        (org.tmatesoft.svn.core.auth.ISVNAuthenticationManager) null,
                        org.tmatesoft.svn.core.wc.SVNWCUtil.createDefaultOptions(true));
        admin.doCreateRepository(repoPath.toFile(), null, true, true);
        repo = SVNRepositoryFactory.create(
                org.tmatesoft.svn.core.SVNURL.fromFile(repoPath.toFile()));

        // Create the destination directory trunk/releases in r1.
        createDir(repo, "trunk");
        createDir(repo, "trunk/releases");
    }

    private static void createDir(SVNRepository repo, String path) throws Exception {
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

    private void writeWorkspace(String rel, String content) throws IOException {
        Path p = workspaceDir.toPath().resolve(rel.replace('/', File.separatorChar));
        Files.createDirectories(p.getParent());
        Files.write(p, content.getBytes("UTF-8"));
    }

    private List<String> listFiles(String path) throws Exception {
        List<String> out = new ArrayList<>();
        for (Object o : repo.getDir(path, -1, null, (java.util.Collection) null)) {
            SVNDirEntry e = (SVNDirEntry) o;
            String child = path.isEmpty() ? e.getName() : path + "/" + e.getName();
            if (e.getKind() == SVNNodeKind.DIR) {
                out.addAll(listFiles(child));
            } else {
                out.add(child);
            }
        }
        return out;
    }

    private List<SvnPublishMapping.MappedFile> map(String include, String dest, String exclude) throws Exception {
        return SvnPublishMapping.scan(new FilePath(workspaceDir), include, dest, exclude);
    }

    @Test
    void addsNewFilesInSingleCommit() throws Exception {
        writeWorkspace("app.jar", "jar-bytes");
        writeWorkspace("dist/style.css", "css");

        SvnPublishService.Result r = SvnPublishService.publish(
                repo.getLocation(), null, map("**/*", "", null), "first publish", false, null, null);

        assertTrue(r.getNewRevision() >= 2, "committed revision should advance, got " + r.getNewRevision());
        assertEquals(2, r.getAdded().size());
        assertEquals(0, r.getUpdated().size());
        List<String> files = listFiles("");
        assertTrue(files.contains("app.jar"), files.toString());
        assertTrue(files.contains("dist/style.css"), files.toString());
    }

    @Test
    void updatesExistingFileContent() throws Exception {
        writeWorkspace("app.jar", "v1");
        SvnPublishService.publish(repo.getLocation(), null, map("**/*", "", null), "add", false, null, null);

        writeWorkspace("app.jar", "v2-longer-content");
        SvnPublishService.Result r = SvnPublishService.publish(
                repo.getLocation(), null, map("**/*", "", null), "update", false, null, null);

        assertEquals(0, r.getAdded().size());
        assertEquals(1, r.getUpdated().size());
        assertEquals("app.jar", r.getUpdated().get(0));
    }

    @Test
    void publishesIntoDestinationSubPath() throws Exception {
        writeWorkspace("app.jar", "x");
        SvnPublishService.Result r = SvnPublishService.publish(
                repo.getLocation(), null, map("**/*", "trunk/releases", null), "sub", false, null, null);
        assertEquals(1, r.getAdded().size());
        assertTrue(listFiles("trunk/releases").contains("trunk/releases/app.jar"));
    }

    @Test
    void cleanupDeletesVanishedFiles() throws Exception {
        writeWorkspace("a.txt", "a");
        writeWorkspace("b.txt", "b");
        SvnPublishService.publish(repo.getLocation(), null, map("**/*", "", null), "initial", false, null, null);

        new File(workspaceDir, "b.txt").delete();
        SvnPublishService.Result r = SvnPublishService.publish(
                repo.getLocation(), null, map("**/*", "", null), "cleanup", true, null, null);

        assertEquals(1, r.getDeleted().size());
        assertEquals("b.txt", r.getDeleted().get(0));
        List<String> files = listFiles("");
        assertTrue(files.contains("a.txt"));
        assertTrue(!files.contains("b.txt"));
    }

    @Test
    void missingDestinationDirectoryFails() throws Exception {
        writeWorkspace("a.txt", "a");
        org.tmatesoft.svn.core.SVNURL missing = repo.getLocation().appendPath("no/such/dir", true);
        IOException ex = assertThrows(IOException.class, () -> SvnPublishService.publish(
                missing, null, map("**/*", "", null), "msg", false, null, null));
        assertTrue(ex.getMessage().contains("not a directory") || ex.getMessage().contains("does not exist"),
                ex.getMessage());
    }
}

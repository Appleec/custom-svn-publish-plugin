package io.jenkins.plugins.svnpublish;

import hudson.FilePath;
import hudson.util.Secret;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tmatesoft.svn.core.auth.ISVNAuthenticationManager;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SvnPublishTaskTest {

    @TempDir
    File workspaceDir;

    private void write(String rel, String content) throws Exception {
        Path p = workspaceDir.toPath().resolve(rel.replace('/', File.separatorChar));
        Files.createDirectories(p.getParent());
        Files.write(p, content.getBytes("UTF-8"));
    }

    @Test
    void excludeFiltersResolvedTargets() throws Exception {
        write("keep.txt", "k");
        write("drop.log", "d");
        List<SvnPublishMapping.MappedFile> targets = SvnPublishTask.resolveTargets(
                new FilePath(workspaceDir), "**/*", "**/*.log", null);
        assertEquals(1, targets.size());
        assertEquals("keep.txt", targets.get(0).getSvnRelativePath());
    }

    @Test
    void targetSubPathIsPrepended() throws Exception {
        write("dist/app.jar", "jar");
        List<SvnPublishMapping.MappedFile> targets = SvnPublishTask.resolveTargets(
                new FilePath(workspaceDir), "dist", null, "releases/v1");
        assertEquals(1, targets.size());
        // A directory source keeps its own name as the first path segment.
        assertEquals("releases/v1/dist/app.jar", targets.get(0).getSvnRelativePath());
    }

    @Test
    void inlineAuthProducesManager() {
        InlineAuth auth = new InlineAuth("alice", Secret.fromString("s3cret"));
        assertEquals("alice", auth.getUsername());
        ISVNAuthenticationManager m = auth.toManager(null);
        assertNotNull(m, "inline username/password must yield an SVNKit auth manager");
    }

    @Test
    void anonymousAuthYieldsNullManager() {
        assertEquals(null, new AnonymousAuth().toManager(null));
    }
}
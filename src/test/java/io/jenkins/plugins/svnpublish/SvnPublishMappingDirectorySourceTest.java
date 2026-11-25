package io.jenkins.plugins.svnpublish;

import hudson.FilePath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SvnPublishMappingDirectorySourceTest {

    @TempDir
    File workspaceDir;

    private void write(String rel, String content) throws Exception {
        Path p = workspaceDir.toPath().resolve(rel.replace('/', File.separatorChar));
        Files.createDirectories(p.getParent());
        Files.write(p, content.getBytes("UTF-8"));
    }

    @Test
    void directorySourcePublishesWholeSubtree() throws Exception {
        write("dist/index.html", "html");
        write("dist/assets/app.js", "js");
        write("dist/assets/style.css", "css");
        write("outside.txt", "x");

        // Pattern names the directory itself (no /**) — subtree must still be published.
        List<SvnPublishMapping.MappedFile> mapped = SvnPublishMapping.scan(
                new FilePath(workspaceDir), "dist", "", null);

        assertEquals(3, mapped.size());
        assertTrue(mapped.stream().anyMatch(m -> m.getSvnRelativePath().equals("dist/index.html")));
        assertTrue(mapped.stream().anyMatch(m -> m.getSvnRelativePath().equals("dist/assets/app.js")));
    }

    @Test
    void directorySourceHonorsDestinationAndExclude() throws Exception {
        write("dist/a.txt", "a");
        write("dist/b.tmp", "b");

        List<SvnPublishMapping.MappedFile> mapped = SvnPublishMapping.scan(
                new FilePath(workspaceDir), "dist", "releases", "**/*.tmp");

        assertEquals(1, mapped.size());
        assertEquals("releases/dist/a.txt", mapped.get(0).getSvnRelativePath());
    }

    @Test
    void doubleStarDoesNotDuplicateDirectoryEntries() throws Exception {
        write("dist/app.jar", "jar");
        write("top.txt", "t");

        // "**/*" matches both the dir and its files — each target path must appear once.
        List<SvnPublishMapping.MappedFile> mapped = SvnPublishMapping.scan(
                new FilePath(workspaceDir), "**/*", "", null);

        assertEquals(2, mapped.size());
        assertEquals(1, mapped.stream()
                .filter(m -> m.getSvnRelativePath().equals("dist/app.jar"))
                .count());
    }

    @Test
    void plainFileSourceStillWorks() throws Exception {
        write("target/app-1.0.jar", "jar");

        List<SvnPublishMapping.MappedFile> mapped = SvnPublishMapping.scan(
                new FilePath(workspaceDir), "target/app-1.0.jar", "", null);

        assertEquals(1, mapped.size());
        assertEquals("target/app-1.0.jar", mapped.get(0).getSvnRelativePath());
    }
}

package io.jenkins.plugins.svnpublish;

import hudson.FilePath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SvnPublishMappingTest {

    @TempDir
    File workspaceDir;

    @Test
    void mapsFilesUnderIncludesToSvnTargetPaths() throws Exception {
        Path root = workspaceDir.toPath();
        Files.write(root.resolve("a.txt"), "hello".getBytes("UTF-8"));
        Files.createDirectories(root.resolve("dist"));
        Files.write(root.resolve("dist/app.jar"), "jar".getBytes("UTF-8"));
        Files.write(root.resolve("notes.md"), "notes".getBytes("UTF-8"));

        List<SvnPublishMapping.MappedFile> mapped = SvnPublishMapping.scan(
                new FilePath(workspaceDir), "**/*", "", null);

        assertEquals(3, mapped.size());
        assertEquals("a.txt", pathOf(mapped, root.resolve("a.txt")));
        assertEquals("dist/app.jar", pathOf(mapped, root.resolve("dist/app.jar")));
        assertEquals("notes.md", pathOf(mapped, root.resolve("notes.md")));
    }

    @Test
    void prependsTargetSubPath() throws Exception {
        Path root = workspaceDir.toPath();
        Files.write(root.resolve("a.txt"), "hello".getBytes("UTF-8"));

        List<SvnPublishMapping.MappedFile> mapped = SvnPublishMapping.scan(
                new FilePath(workspaceDir), "**/*", "releases/v1", null);

        assertEquals(1, mapped.size());
        assertEquals("releases/v1/a.txt", mapped.get(0).getSvnRelativePath());
    }

    @Test
    void appliesExcludesAfterIncludes() throws Exception {
        Path root = workspaceDir.toPath();
        Files.write(root.resolve("keep.txt"), "k".getBytes("UTF-8"));
        Files.write(root.resolve("skip.log"), "s".getBytes("UTF-8"));

        List<SvnPublishMapping.MappedFile> mapped = SvnPublishMapping.scan(
                new FilePath(workspaceDir), "**/*", "", "**/*.log");

        assertEquals(1, mapped.size());
        assertEquals("keep.txt", mapped.get(0).getSvnRelativePath());
    }

    @Test
    void normalizesRelativePaths() throws Exception {
        Path root = workspaceDir.toPath();
        Files.write(root.resolve("a.txt"), "hello".getBytes("UTF-8"));

        // Backslashes and boundary slashes in destination must normalize to "dist".
        List<SvnPublishMapping.MappedFile> mapped = SvnPublishMapping.scan(
                new FilePath(workspaceDir), "*.txt", "\\dist\\", null);

        assertEquals(1, mapped.size());
        assertEquals("dist/a.txt", mapped.get(0).getSvnRelativePath());
    }

    @Test
    void rejectsBlankIncludePattern() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SvnPublishMapping.scan(new FilePath(workspaceDir), "  ", "", null));
        assertThat(ex.getMessage(), containsString("include"));
    }

    @Test
    void rejectsEscapingRelativePaths() throws Exception {
        Path root = workspaceDir.toPath();
        // The ".." in the svn sub-path must be rejected before any SVN operation happens.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SvnPublishMapping.scan(new FilePath(workspaceDir), "**/*", "../evil", null));
        assertThat(ex.getMessage(), containsString(".."));
    }

    private String pathOf(List<SvnPublishMapping.MappedFile> mapped, Path workspaceFile) {
        String remote = workspaceFile.getFileName().toString();
        for (SvnPublishMapping.MappedFile m : mapped) {
            if (m.getLocalFile().getName().equals(remote)) {
                return m.getSvnRelativePath();
            }
        }
        throw new AssertionError("not found: " + remote);
    }
}

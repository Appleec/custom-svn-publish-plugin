package io.jenkins.plugins.svnpublish;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AntGlobMatcherTest {

    @Test
    void matchesDoubleStarAcrossDirectories() {
        assertTrue(AntGlobMatcher.match("**/*.tmp", "a.tmp"));
        assertTrue(AntGlobMatcher.match("**/*.tmp", "x/y/z.tmp"));
        assertFalse(AntGlobMatcher.match("**/*.tmp", "x/y/z.jar"));
    }

    @Test
    void matchesSingleStarWithinSegment() {
        assertTrue(AntGlobMatcher.match("*.jar", "app.jar"));
        assertFalse(AntGlobMatcher.match("*.jar", "dist/app.jar"));
        assertTrue(AntGlobMatcher.match("dist/*.jar", "dist/app.jar"));
    }

    @Test
    void matchesQuestionMarkSingleChar() {
        assertTrue(AntGlobMatcher.match("?.txt", "a.txt"));
        assertFalse(AntGlobMatcher.match("?.txt", "ab.txt"));
        assertFalse(AntGlobMatcher.match("?.txt", "d/a.txt"));
    }

    @Test
    void matchesDirectoryPrefixPattern() {
        assertTrue(AntGlobMatcher.match("keep/**", "keep/a.txt"));
        assertTrue(AntGlobMatcher.match("keep/**", "keep/sub/b.txt"));
        assertFalse(AntGlobMatcher.match("keep/**", "other/a.txt"));
    }

    @Test
    void dotIsLiteralNotRegex() {
        assertTrue(AntGlobMatcher.match("a.tmp", "a.tmp"));
        assertFalse(AntGlobMatcher.match("a.tmp", "aXtmp"));
        assertFalse(AntGlobMatcher.match("*.tmp", "aXtmp"));
    }

    @Test
    void exactPathMatches() {
        assertTrue(AntGlobMatcher.match("config.xml", "config.xml"));
        assertFalse(AntGlobMatcher.match("config.xml", "sub/config.xml"));
    }
}

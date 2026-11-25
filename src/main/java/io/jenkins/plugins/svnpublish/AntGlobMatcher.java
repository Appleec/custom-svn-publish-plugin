package io.jenkins.plugins.svnpublish;

/**
 * Minimal Ant-style glob matcher for target-exclude patterns evaluated against
 * repository-relative paths. Supports {@code *}, {@code **} and {@code ?}.
 */
public final class AntGlobMatcher {

    private AntGlobMatcher() {
    }

    /**
     * @param pattern Ant-style pattern, forward slashes
     * @param path    repository-relative path, forward slashes
     * @return true when the path matches the pattern
     */
    public static boolean match(String pattern, String path) {
        if (pattern == null || path == null) {
            return false;
        }
        String p = pattern.replace('\\', '/');
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        String t = path.startsWith("/") ? path.substring(1) : path;
        return matchSegments(p.split("/"), 0, t.split("/"), 0);
    }

    private static boolean matchSegments(String[] pat, int pi, String[] str, int si) {
        while (pi < pat.length) {
            String seg = pat[pi];
            if ("**".equals(seg)) {
                // '**' consumes zero or more path segments; try longest match first.
                if (pi == pat.length - 1) {
                    return true;
                }
                for (int k = si; k <= str.length; k++) {
                    if (matchSegments(pat, pi + 1, str, k)) {
                        return true;
                    }
                }
                return false;
            }
            if (si >= str.length) {
                return false;
            }
            if (!matchSegment(seg, str[si])) {
                return false;
            }
            pi++;
            si++;
        }
        return si == str.length;
    }

    private static boolean matchSegment(String pattern, String text) {
        // Classic wildcard match within one segment; '.' is literal.
        int p = 0, t = 0, star = -1, mark = -1;
        while (t < text.length()) {
            if (p < pattern.length() && (pattern.charAt(p) == '?' || pattern.charAt(p) == text.charAt(t))) {
                p++;
                t++;
            } else if (p < pattern.length() && pattern.charAt(p) == '*') {
                star = p++;
                mark = t;
            } else if (star >= 0) {
                p = star + 1;
                t = ++mark;
            } else {
                return false;
            }
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') {
            p++;
        }
        return p == pattern.length();
    }
}

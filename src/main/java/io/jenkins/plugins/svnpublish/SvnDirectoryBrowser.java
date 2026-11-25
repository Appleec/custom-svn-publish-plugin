package io.jenkins.plugins.svnpublish;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import org.tmatesoft.svn.core.SVNDirEntry;
import org.tmatesoft.svn.core.SVNNodeKind;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.auth.ISVNAuthenticationManager;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.io.SVNRepositoryFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Lists child directories of an SVN URL, used by the form to offer dynamic
 * target-directory selection (tags).
 */
public final class SvnDirectoryBrowser {

    private static final Logger LOGGER = Logger.getLogger(SvnDirectoryBrowser.class.getName());

    private SvnDirectoryBrowser() {
    }

    /** Outcome of a listing attempt: either the child directories or the reason it failed. */
    public static final class Listing {
        private final List<String> dirs;
        private final String error;

        private Listing(List<String> dirs, String error) {
            this.dirs = Collections.unmodifiableList(dirs);
            this.error = error;
        }

        public List<String> getDirs() {
            return dirs;
        }

        /** Human readable failure reason, or null when the listing succeeded. */
        public @CheckForNull String getError() {
            return error;
        }
    }

    /**
     * Lists immediate child directory names of {@code rawUrl} using an explicit
     * authentication manager (null = anonymous), reporting the failure reason.
     *
     * @param rawUrl repository URL to browse (already env-expanded by caller or literal)
     * @param path   optional sub-path appended to the URL before listing
     * @param auth   authentication manager, or null for anonymous
     */
    public static Listing list(@CheckForNull String rawUrl,
                               @CheckForNull String path,
                               @CheckForNull ISVNAuthenticationManager auth) {
        List<String> out = new ArrayList<>();
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            return new Listing(out, "SVN repository URL is empty");
        }
        try {
            String full = rawUrl.trim();
            if (path != null && !path.trim().isEmpty()) {
                String p = path.trim().replace('\\', '/');
                while (p.startsWith("/")) {
                    p = p.substring(1);
                }
                while (p.endsWith("/")) {
                    p = p.substring(0, p.length() - 1);
                }
                if (!p.isEmpty()) {
                    full = full + "/" + p;
                }
            }
            SVNURL url = SVNURL.parseURIEncoded(full);
            SVNRepository repo = SVNRepositoryFactory.create(url);
            if (auth != null) {
                repo.setAuthenticationManager(auth);
            }
            if (repo.checkPath("", -1) != SVNNodeKind.DIR) {
                return new Listing(out, "Not a directory: " + full);
            }
            for (Object o : repo.getDir("", -1, null, (Collection) null)) {
                SVNDirEntry e = (SVNDirEntry) o;
                if (e.getKind() == SVNNodeKind.DIR) {
                    out.add(e.getName());
                }
            }
            return new Listing(out, null);
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "SVN directory listing failed for " + rawUrl, e);
            return new Listing(out, describe(e));
        }
    }

    /** Turns a failure into a short, actionable message for the form. */
    private static String describe(Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String lower = msg.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("certificate") || lower.contains("ssl") || lower.contains("handshake")) {
            return msg + " — enable \"Trust SSL certificate\" if the server uses a self-signed certificate";
        }
        if (lower.contains("authorization") || lower.contains("401") || lower.contains("authentication")) {
            return msg + " — check the credentials (or pick a different authentication mode)";
        }
        if (lower.contains("unknown host") || lower.contains("connection") || lower.contains("refused")) {
            return msg + " — check the URL and that the server is reachable from the Jenkins controller";
        }
        return msg;
    }

    /**
     * Lists immediate child directory names of {@code rawUrl} using an explicit
     * authentication manager (null = anonymous).
     */
    public static List<String> listChildDirs(@CheckForNull String rawUrl,
                                             @CheckForNull String path,
                                             @CheckForNull ISVNAuthenticationManager auth) {
        return list(rawUrl, path, auth).getDirs();
    }

    /**
     * Lists immediate child directory names from raw form values. The credential
     * id cannot be resolved without an item context, so callers holding one
     * should resolve the manager themselves.
     */
    public static List<String> listChildDirs(@CheckForNull String rawUrl,
                                             @CheckForNull String path,
                                             @CheckForNull String credentialsId,
                                             @CheckForNull String username,
                                             @CheckForNull String password) {
        return listChildDirs(rawUrl, path, SvnCredentials.inline(username, password, false));
    }
}
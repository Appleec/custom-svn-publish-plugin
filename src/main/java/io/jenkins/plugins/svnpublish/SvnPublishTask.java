package io.jenkins.plugins.svnpublish;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Util;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.Secret;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.auth.ISVNAuthenticationManager;

import java.io.IOException;
import java.io.PrintStream;
import java.util.List;

/**
 * Shared execution logic used by both the Pipeline step and the post-build publisher,
 * so the two entry points cannot drift apart.
 */
final class SvnPublishTask {

    private SvnPublishTask() {
    }

    /**
     * Resolves the effective publish targets: expands env vars, applies include/exclude
     * and the optional target sub-path.
     */
    static List<SvnPublishMapping.MappedFile> resolveTargets(@NonNull FilePath workspace,
                                                             @NonNull String source,
                                                             @CheckForNull String exclude,
                                                             @CheckForNull String target)
            throws IOException, InterruptedException {
        String dest = target == null ? "" : SvnPublishMapping.normalize(target);
        return SvnPublishMapping.scan(workspace, source, dest, exclude);
    }

    /**
     * Resolves the effective auth manager: Jenkins credential id first, then inline
     * username/password, null for anonymous.
     */
    static ISVNAuthenticationManager resolveAuth(@CheckForNull Run<?, ?> run,
                                                 @CheckForNull String credentialsId,
                                                 @CheckForNull String username,
                                                 @CheckForNull Secret password,
                                                 boolean trustSsl) {
        if (credentialsId != null && !credentialsId.trim().isEmpty()) {
            if (run == null) {
                return null;
            }
            return SvnCredentials.managerFor(run.getParent(), credentialsId, trustSsl);
        }
        return SvnCredentials.inline(username,
                password == null ? null : Secret.toString(password), trustSsl);
    }

    /**
     * Expands environment variables, scans the workspace, resolves authentication and
     * performs the SVN commit.
     *
     * @return the publish result (revision, per-file actions)
     */
    static SvnPublishService.Result publish(@NonNull TaskListener listener,
                                            @NonNull FilePath workspace,
                                            @CheckForNull Run<?, ?> run,
                                            @CheckForNull EnvVars env,
                                            @NonNull String rawUrl,
                                            @CheckForNull String credentialsId,
                                            @CheckForNull String username,
                                            @CheckForNull Secret password,
                                            @NonNull String source,
                                            @CheckForNull String exclude,
                                            @CheckForNull String target,
                                            @CheckForNull String commitMessage,
                                            boolean cleanup,
                                            @CheckForNull String targetExclude,
                                            boolean trustSsl) throws IOException, InterruptedException {
        PrintStream log = listener.getLogger();
        EnvVars vars = env != null ? env : new EnvVars();

        String url = Util.fixEmptyAndTrim(vars.expand(rawUrl));
        if (url == null) {
            throw new IOException("SVN repository URL is empty after environment expansion");
        }
        String src = Util.fixEmptyAndTrim(vars.expand(source));
        if (src == null) {
            throw new IOException("Source pattern is empty after environment expansion");
        }
        String dest = target == null ? "" : SvnPublishMapping.normalize(vars.expand(target));
        String message = vars.expand(commitMessage == null || commitMessage.trim().isEmpty()
                ? "Published from Jenkins"
                : commitMessage);
        List<String> targetExcludes = parsePatterns(Util.fixEmptyAndTrim(vars.expand(targetExclude)));

        log.println("Publishing to SVN: " + url);
        if (!dest.isEmpty()) {
            log.println("Target sub-path: " + dest);
        }

        List<SvnPublishMapping.MappedFile> mapped =
                resolveTargets(workspace, src, exclude, dest);
        if (mapped.isEmpty()) {
            log.println("No files matched '" + src + "' — nothing to publish.");
            return new SvnPublishService.Result();
        }
        log.println("Publishing " + mapped.size() + " file(s):");
        for (SvnPublishMapping.MappedFile mf : mapped) {
            log.println("  " + mf.getSvnRelativePath());
        }

        SVNURL svnUrl;
        try {
            svnUrl = SVNURL.parseURIEncoded(url);
        } catch (org.tmatesoft.svn.core.SVNException e) {
            throw new IOException("Cannot parse SVN URL '" + url + "': " + e.getMessage(), e);
        }

        ISVNAuthenticationManager auth = resolveAuth(run, credentialsId, username, password, trustSsl);

        try {
            return SvnPublishService.publish(
                    svnUrl, auth, mapped, message, cleanup, log::println,
                    targetExcludes.isEmpty() ? null : targetExcludes);
        } catch (IOException e) {
            throw new IOException("SVN publish to " + url + " failed: " + e.getMessage(), e);
        }
    }

    /** Splits a comma-separated pattern list into trimmed non-empty tokens. */
    private static List<String> parsePatterns(String raw) {
        if (raw == null) {
            return java.util.Collections.emptyList();
        }
        List<String> out = new java.util.ArrayList<>();
        for (String token : raw.split(",")) {
            String t = token.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }
}

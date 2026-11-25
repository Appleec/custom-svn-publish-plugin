package io.jenkins.plugins.svnpublish;

import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import jenkins.tasks.SimpleBuildStep;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.tmatesoft.svn.core.auth.ISVNAuthenticationManager;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;

/**
 * Post-build step "Custom SVN Publish" for freestyle projects (also usable from Pipeline
 * via {@code step([$class: 'SvnPublisher', ...])} through SimpleBuildStep).
 */
public class SvnPublisher extends Notifier implements SimpleBuildStep, Serializable {

    private static final long serialVersionUID = 1L;

    /** Authentication mode constants. */
    public static final String AUTH_CREDENTIAL = "credential";
    public static final String AUTH_INLINE = "inline";
    public static final String AUTH_ANONYMOUS = "anonymous";

    private final @NonNull String url;

    private String authMode = AUTH_ANONYMOUS;
    private String credentialsId;
    private String username;
    private Secret password;
    private String source;
    private String exclude;
    private String target;
    private String commitMessage;
    private String targetExclude;
    private boolean cleanup;
    private boolean trustSsl;

    @DataBoundConstructor
    public SvnPublisher(@NonNull String url) {
        this.url = url;
    }

    public @NonNull String getUrl() {
        return url;
    }

    public @NonNull String getAuthMode() {
        return authMode == null ? AUTH_ANONYMOUS : authMode;
    }

    @DataBoundSetter
    public void setAuthMode(String authMode) {
        this.authMode = authMode == null ? AUTH_ANONYMOUS : authMode;
    }

    public @CheckForNull String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = credentialsId;
    }

    public @CheckForNull String getUsername() {
        return username;
    }

    @DataBoundSetter
    public void setUsername(String username) {
        this.username = username;
    }

    public @CheckForNull Secret getPassword() {
        return password;
    }

    @DataBoundSetter
    public void setPassword(Secret password) {
        this.password = password;
    }

    public @CheckForNull String getSource() {
        return source;
    }

    @DataBoundSetter
    public void setSource(String source) {
        this.source = source;
    }

    public @CheckForNull String getExclude() {
        return exclude;
    }

    @DataBoundSetter
    public void setExclude(String exclude) {
        this.exclude = exclude;
    }

    public @CheckForNull String getTarget() {
        return target;
    }

    @DataBoundSetter
    public void setTarget(String target) {
        this.target = target;
    }

    public @CheckForNull String getCommitMessage() {
        return commitMessage;
    }

    @DataBoundSetter
    public void setCommitMessage(String commitMessage) {
        this.commitMessage = commitMessage;
    }

    public @CheckForNull String getTargetExclude() {
        return targetExclude;
    }

    @DataBoundSetter
    public void setTargetExclude(String targetExclude) {
        this.targetExclude = targetExclude;
    }

    public boolean isCleanup() {
        return cleanup;
    }

    @DataBoundSetter
    public void setCleanup(boolean cleanup) {
        this.cleanup = cleanup;
    }

    /** Accept any TLS certificate (self-signed SVN servers). */
    public boolean isTrustSsl() {
        return trustSsl;
    }

    @DataBoundSetter
    public void setTrustSsl(boolean trustSsl) {
        this.trustSsl = trustSsl;
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public void perform(@NonNull Run<?, ?> run, @NonNull FilePath workspace, @NonNull EnvVars env,
                        @NonNull Launcher launcher, @NonNull TaskListener listener)
            throws InterruptedException, IOException {
        SvnPublishService.Result result = SvnPublishTask.publish(
                listener, workspace, run, env,
                url,
                AUTH_CREDENTIAL.equals(getAuthMode()) ? credentialsId : null,
                AUTH_INLINE.equals(getAuthMode()) ? username : null,
                AUTH_INLINE.equals(getAuthMode()) ? password : null,
                source == null ? "**/*" : source,
                exclude,
                target,
                commitMessage,
                cleanup,
                targetExclude,
                trustSsl);
        listener.getLogger().println("SVN publish completed: " + result);
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return Messages.SvnPublisher_DisplayName();
        }

        /** Credential dropdown for CREDENTIAL mode: everything the job can read. */
        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item item,
                                                     @QueryParameter String credentialsId) {
            if (item == null || !item.hasPermission(Item.CONFIGURE)) {
                return new ListBoxModel();
            }
            return new StandardListBoxModel()
                    .includeEmptyValue()
                    .includeAs(ACL.SYSTEM, item, StandardUsernameCredentials.class)
                    .includeCurrentValue(credentialsId);
        }

        /**
         * Target combobox suggestions: child directories of url (or of the
         * parent of the currently typed path, so nested browsing works).
         * The form passes the authentication fields along via fillDependsOn.
         */
        public ListBoxModel doFillTargetItems(@AncestorInPath Item item,
                                                   @QueryParameter String url,
                                                   @QueryParameter String authMode,
                                                   @QueryParameter String credentialsId,
                                                   @QueryParameter String username,
                                                   @QueryParameter String password,
                                                   @QueryParameter String trustSsl,
                                                   @QueryParameter String target) {
            if (item == null || !item.hasPermission(Item.CONFIGURE)) {
                return new ListBoxModel();
            }
            ListBoxModel m = new ListBoxModel();
            String effectiveUrl = hudson.Util.fixEmptyAndTrim(url);
            if (effectiveUrl == null) {
                return m;
            }
            // Browse the parent of what is typed, so "trunk/" suggests its children.
            String typed = target == null ? "" : target.trim().replace('\\', '/');
            while (typed.endsWith("/")) {
                typed = typed.substring(0, typed.length() - 1);
            }
            String parent = typed.contains("/") ? typed.substring(0, typed.lastIndexOf('/')) : "";
            String typedLeaf = typed.contains("/") ? typed.substring(typed.lastIndexOf('/') + 1) : typed;

            boolean trust = isChecked(trustSsl);
            // The password arrives encrypted (Secret form value); decode it for SVNKit.
            String plainPassword = password == null ? null : Secret.fromString(password).getPlainText();

            ISVNAuthenticationManager auth;
            if (AUTH_CREDENTIAL.equals(authMode)) {
                try {
                    auth = SvnCredentials.managerFor(item, credentialsId, trust);
                } catch (IllegalArgumentException e) {
                    auth = null; // unknown or unreadable credential: browse anonymously
                }
            } else if (AUTH_INLINE.equals(authMode)) {
                auth = SvnCredentials.inline(username, plainPassword, trust);
            } else {
                auth = SvnCredentials.managerFor(item, null, trust);
            }

            SvnDirectoryBrowser.Listing listing =
                    SvnDirectoryBrowser.list(effectiveUrl, parent, auth);
            String error = listing.getError();
            if (error != null) {
                // Surface the reason instead of an empty dropdown.
                m.add("⚠ " + error, "");
                return m;
            }
            for (String dir : listing.getDirs()) {
                String value = parent.isEmpty() ? dir : parent + "/" + dir;
                if (typedLeaf.isEmpty() || dir.startsWith(typedLeaf)) {
                    m.add(value);
                }
            }
            return m;
        }

        private static boolean isChecked(String value) {
            return value != null
                    && ("true".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value)
                    || "yes".equalsIgnoreCase(value));
        }
    }
}

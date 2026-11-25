package io.jenkins.plugins.svnpublish;

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
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import java.io.IOException;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * Build step flavour of the SVN publisher, so the same configuration can run
 * mid-build instead of only in the post-build phase.
 *
 * <p>All configuration state lives in a {@link SvnPublisher} instance, which is
 * also what performs the commit — the two entry points cannot drift apart.</p>
 */
public class SvnPublishBuilder extends Builder implements SimpleBuildStep {

    private final SvnPublisher delegate;

    @DataBoundConstructor
    public SvnPublishBuilder(@NonNull String url) {
        this.delegate = new SvnPublisher(url);
    }

    /** The shared configuration/execution object. */
    public SvnPublisher getDelegate() {
        return delegate;
    }

    public @NonNull String getUrl() {
        return delegate.getUrl();
    }

    public @NonNull String getAuthMode() {
        return delegate.getAuthMode();
    }

    @DataBoundSetter
    public void setAuthMode(String authMode) {
        delegate.setAuthMode(authMode);
    }

    public @CheckForNull String getCredentialsId() {
        return delegate.getCredentialsId();
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        delegate.setCredentialsId(credentialsId);
    }

    public @CheckForNull String getUsername() {
        return delegate.getUsername();
    }

    @DataBoundSetter
    public void setUsername(String username) {
        delegate.setUsername(username);
    }

    public @CheckForNull Secret getPassword() {
        return delegate.getPassword();
    }

    @DataBoundSetter
    public void setPassword(Secret password) {
        delegate.setPassword(password);
    }

    public @CheckForNull String getSource() {
        return delegate.getSource();
    }

    @DataBoundSetter
    public void setSource(String source) {
        delegate.setSource(source);
    }

    public @CheckForNull String getExclude() {
        return delegate.getExclude();
    }

    @DataBoundSetter
    public void setExclude(String exclude) {
        delegate.setExclude(exclude);
    }

    public @CheckForNull String getTarget() {
        return delegate.getTarget();
    }

    @DataBoundSetter
    public void setTarget(String target) {
        delegate.setTarget(target);
    }

    public @CheckForNull String getCommitMessage() {
        return delegate.getCommitMessage();
    }

    @DataBoundSetter
    public void setCommitMessage(String commitMessage) {
        delegate.setCommitMessage(commitMessage);
    }

    public @CheckForNull String getTargetExclude() {
        return delegate.getTargetExclude();
    }

    @DataBoundSetter
    public void setTargetExclude(String targetExclude) {
        delegate.setTargetExclude(targetExclude);
    }

    public boolean isCleanup() {
        return delegate.isCleanup();
    }

    @DataBoundSetter
    public void setCleanup(boolean cleanup) {
        delegate.setCleanup(cleanup);
    }

    public boolean isTrustSsl() {
        return delegate.isTrustSsl();
    }

    @DataBoundSetter
    public void setTrustSsl(boolean trustSsl) {
        delegate.setTrustSsl(trustSsl);
    }

    @Override
    public void perform(@NonNull Run<?, ?> run, @NonNull FilePath workspace, @NonNull EnvVars env,
                        @NonNull Launcher launcher, @NonNull TaskListener listener)
            throws InterruptedException, IOException {
        delegate.perform(run, workspace, env, launcher, listener);
    }

    @Extension
    @Symbol("customSvnPublishBuilder")
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return Messages.SvnPublishBuilder_DisplayName();
        }

        /** Credential dropdown — same data as the post-build step. */
        @POST
        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item item,
                                                     @QueryParameter String credentialsId) {
            return svn().doFillCredentialsIdItems(item, credentialsId);
        }

        /** Target combobox — same browsing logic as the post-build step. */
        @POST
        public ListBoxModel doFillTargetItems(@AncestorInPath Item item,
                                              @QueryParameter String url,
                                              @QueryParameter String authMode,
                                              @QueryParameter String credentialsId,
                                              @QueryParameter String username,
                                              @QueryParameter String password,
                                              @QueryParameter String trustSsl,
                                              @QueryParameter String target) {
            return svn().doFillTargetItems(
                    item, url, authMode, credentialsId, username, password, trustSsl, target);
        }

        private static SvnPublisher.DescriptorImpl svn() {
            return Jenkins.get().getDescriptorByType(SvnPublisher.DescriptorImpl.class);
        }
    }
}
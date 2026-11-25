package io.jenkins.plugins.svnpublish;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.Secret;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Pipeline step: {@code customSvnPublish ...} — publishes workspace files to an SVN repository.
 *
 * <pre>{@code
 * customSvnPublish(
 *   url: 'https://svn.example.com/repos/releases',
 *   credentialsId: 'svn-creds',          // or username/password, or anonymous
 *   source: 'target/*.jar',
 *   target: 'v1.0',
 *   commitMessage: 'Publish from build #${BUILD_NUMBER}'
 * )
 * }</pre>
 */
public class SvnPublishStep extends Step implements Serializable {

    private static final long serialVersionUID = 1L;

    private final @NonNull String url;
    private final @NonNull String source;

    private String credentialsId;
    private String username;
    private Secret password;
    private String exclude;
    private String target;
    private String commitMessage;
    private String targetExclude;
    private boolean cleanup;

    @DataBoundConstructor
    public SvnPublishStep(@NonNull String url, @NonNull String source) {
        this.url = url;
        this.source = source;
    }

    public @NonNull String getUrl() {
        return url;
    }

    public @NonNull String getSource() {
        return source;
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

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new ExecutionImpl(context, this);
    }

    private static final class ExecutionImpl extends SynchronousNonBlockingStepExecution<Void> {

        private static final long serialVersionUID = 1L;

        private final String url;
        private final String source;
        private final String credentialsId;
        private final String username;
        private final Secret password;
        private final String exclude;
        private final String target;
        private final String commitMessage;
        private final String targetExclude;
        private final boolean cleanup;

        ExecutionImpl(StepContext context, SvnPublishStep step) {
            super(context);
            this.url = step.url;
            this.source = step.source;
            this.credentialsId = step.credentialsId;
            this.username = step.username;
            this.password = step.password;
            this.exclude = step.exclude;
            this.target = step.target;
            this.commitMessage = step.commitMessage;
            this.targetExclude = step.targetExclude;
            this.cleanup = step.cleanup;
        }

        @Override
        protected Void run() throws Exception {
            TaskListener listener = getContext().get(TaskListener.class);
            FilePath ws = getContext().get(FilePath.class);
            Run<?, ?> run = getContext().get(Run.class);
            EnvVars env = getContext().get(EnvVars.class);

            if (listener == null || ws == null) {
                throw new IOException("Missing TaskListener or FilePath in step context");
            }

            SvnPublishService.Result result = SvnPublishTask.publish(
                    listener, ws, run, env,
                    url, credentialsId, username, password,
                    source, exclude, target, commitMessage, cleanup, targetExclude);
            listener.getLogger().println("SVN publish completed: " + result);
            return null;
        }
    }

    @Extension
    public static final class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "customSvnPublish";
        }

        @Override
        public String getDisplayName() {
            return "Publish artifacts to SVN";
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
                    FilePath.class, TaskListener.class, Run.class, EnvVars.class)));
        }
    }
}

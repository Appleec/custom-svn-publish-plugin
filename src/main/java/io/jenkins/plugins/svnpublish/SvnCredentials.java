package io.jenkins.plugins.svnpublish;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Item;
import hudson.model.Run;
import hudson.security.ACL;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.auth.BasicAuthenticationManager;
import org.tmatesoft.svn.core.auth.ISVNAuthenticationManager;

import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.logging.Logger;

/**
 * Bridges Jenkins credentials to SVNKit authentication.
 */
public final class SvnCredentials {

    private static final Logger LOGGER = Logger.getLogger(SvnCredentials.class.getName());

    /**
     * Trust manager that accepts any server certificate. Only used when the job
     * explicitly opts in, which is the common case for internal SVN servers
     * fronted by a self-signed certificate.
     */
    private static final X509TrustManager ACCEPT_ALL = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };

    private SvnCredentials() {
    }

    /**
     * Resolves the SVNKit authentication manager for the given credential id,
     * scoped to the job that owns the run.
     */
    public static @CheckForNull ISVNAuthenticationManager managerFor(@NonNull Run<?, ?> run,
                                                                     @CheckForNull String credentialsId) {
        return managerFor(run.getParent(), credentialsId, false);
    }

    /**
     * Resolves the SVNKit authentication manager for the given credential id,
     * scoped to an item. Used by the configuration form so the target-directory
     * browser can list directories with the selected credential.
     *
     * @return the manager, or null for anonymous access when the id is blank
     * @throws IllegalArgumentException when the id is set but no matching credential exists
     */
    public static @CheckForNull ISVNAuthenticationManager managerFor(@CheckForNull Item item,
                                                                     @CheckForNull String credentialsId) {
        return managerFor(item, credentialsId, false);
    }

    /**
     * @param trustSsl accept any server certificate (self-signed SVN servers)
     */
    public static @CheckForNull ISVNAuthenticationManager managerFor(@CheckForNull Item item,
                                                                     @CheckForNull String credentialsId,
                                                                     boolean trustSsl) {
        if (credentialsId == null || credentialsId.trim().isEmpty()) {
            return trustSsl ? trusting(null, null) : null;
        }
        if (item == null) {
            throw new IllegalArgumentException(
                    "No item context to resolve credential '" + credentialsId + "'.");
        }
        StandardUsernameCredentials c = CredentialsProvider.lookupCredentials(
                        StandardUsernameCredentials.class,
                        item,
                        ACL.SYSTEM,
                        Collections.<DomainRequirement>emptyList())
                .stream()
                .filter(cred -> CredentialsMatchers.withId(credentialsId).matches(cred))
                .findFirst()
                .orElse(null);
        if (c == null) {
            throw new IllegalArgumentException(
                    "Cannot find credential with id '" + credentialsId + "'. Configure it under "
                            + "Manage Jenkins > Credentials and make sure the job can read it.");
        }
        if (c instanceof StandardUsernamePasswordCredentials) {
            StandardUsernamePasswordCredentials up = (StandardUsernamePasswordCredentials) c;
            return manager(up.getUsername(), up.getPassword().getPlainText().toCharArray(), trustSsl);
        }
        // Other credential kinds (e.g. SSH private keys): fall back to username-only.
        LOGGER.warning(() -> "Credential " + credentialsId + " of type "
                + c.getClass().getSimpleName() + " is not username/password; using username only.");
        return manager(c.getUsername(), new char[0], trustSsl);
    }

    /**
     * Builds an authentication manager for inline credentials (or anonymous
     * when the username is blank).
     */
    public static @CheckForNull ISVNAuthenticationManager inline(@CheckForNull String username,
                                                                 @CheckForNull String password,
                                                                 boolean trustSsl) {
        String user = username == null ? null : username.trim();
        if (user == null || user.isEmpty()) {
            return trustSsl ? trusting(null, null) : null;
        }
        return manager(user, password == null ? new char[0] : password.toCharArray(), trustSsl);
    }

    private static ISVNAuthenticationManager manager(String username, char[] password, boolean trustSsl) {
        return trustSsl
                ? trusting(username, password)
                : BasicAuthenticationManager.newInstance(username, password);
    }

    /**
     * A manager that keeps normal username/password authentication but accepts
     * any TLS certificate, for servers behind a self-signed certificate.
     */
    private static BasicAuthenticationManager trusting(String username, char[] password) {
        String user = username == null ? "" : username;
        String pass = password == null ? "" : new String(password);
        return new BasicAuthenticationManager(user, pass) {
            @Override
            public TrustManager getTrustManager(SVNURL url) {
                return ACCEPT_ALL;
            }
        };
    }
}
package io.jenkins.plugins.svnpublish;

/**
 * Anonymous access — no authentication.
 */
public class AnonymousAuth {

    public org.tmatesoft.svn.core.auth.ISVNAuthenticationManager toManager(Void unused) {
        return null;
    }
}

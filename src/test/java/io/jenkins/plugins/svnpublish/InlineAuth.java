package io.jenkins.plugins.svnpublish;

import hudson.util.Secret;

/**
 * Inline (direct-entry) username/password authentication.
 */
public class InlineAuth {

    private final String username;
    private final Secret password;

    public InlineAuth(String username, Secret password) {
        this.username = username;
        this.password = password;
    }

    public String getUsername() {
        return username;
    }

    public Secret getPassword() {
        return password;
    }

    public org.tmatesoft.svn.core.auth.ISVNAuthenticationManager toManager(Void unused) {
        return org.tmatesoft.svn.core.auth.BasicAuthenticationManager.newInstance(
                username, password == null ? new char[0] : Secret.toString(password).toCharArray());
    }
}

package me.qoomon.maven.gitversioning;

import me.qoomon.gitversioning.commons.GitRefType;
import me.qoomon.maven.gitversioning.Configuration.RefPatchDescription;

import static java.util.Objects.requireNonNull;

public class RefPatchMatch {
    private final String commit;
    private final String refName;
    private final RefPatchDescription patchDescription;

    public RefPatchMatch(String commit, String refName, RefPatchDescription patchDescription) {

        this.commit = requireNonNull(commit);
        this.refName = requireNonNull(refName);
        this.patchDescription = requireNonNull(patchDescription);
    }

    public String getCommit() {
        return commit;
    }

    public GitRefType getRefType() {
        return patchDescription.type;
    }

    public String getRefName() {
        return refName;
    }

    public RefPatchDescription getPatchDescription() {
        return patchDescription;
    }
}

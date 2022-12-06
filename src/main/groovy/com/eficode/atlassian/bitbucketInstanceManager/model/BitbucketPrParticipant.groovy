package com.eficode.atlassian.bitbucketInstanceManager.model

import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest
import com.eficode.atlassian.bitbucketInstanceManager.impl.BitbucketPullRequest
import com.fasterxml.jackson.annotation.JsonProperty


class BitbucketPrParticipant implements BitbucketEntity{

    BitbucketUser user




    @JsonProperty("lastReviewedCommit")
    String lastReviewedCommitId

    String role //AUTHOR, REVIEWER, PARTICIPANT
    boolean approved
    String status //UNAPPROVED, NEEDS_WORK, APPROVED

    @Override
    BitbucketEntity getParent() {
        return this.instance
    }

    String toAtlassianWikiMarkup() {
        return user.toAtlassianWikiMarkup() + " " + role.capitalize() + " " + status.capitalize()
    }
    @Override
    void setParent(BitbucketEntity parent) {
        assert parent instanceof BitbucketInstanceManagerRest
        this.setInstance(parent as BitbucketInstanceManagerRest)
        assert this.instance instanceof BitbucketInstanceManagerRest
    }

    @Override
    boolean isValid() {
        return validJsonEntity && role in ["AUTHOR", "REVIEWER", "PARTICIPANT"] && status in ["UNAPPROVED", "NEEDS_WORK", "APPROVED"] && user != null
    }
}

package com.eficode.atlassian.bitbucketInstanceManager.model

import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest
import com.eficode.atlassian.bitbucketInstanceManager.impl.BitbucketPullRequest
import com.eficode.atlassian.bitbucketInstanceManager.impl.BitbucketRepo
import com.fasterxml.jackson.annotation.JsonProperty


class BitbucketPrParticipant implements BitbucketEntity{

    BitbucketUser user
    BitbucketPullRequest pullRequest

    @JsonProperty("lastReviewedCommit")
    String lastReviewedCommitId

    String role //AUTHOR, REVIEWER, PARTICIPANT
    boolean approved
    String status //UNAPPROVED, NEEDS_WORK, APPROVED

    @Override
    void setParent(BitbucketEntity pr) {
        this.pullRequest = pr as BitbucketPullRequest
    }

    String toString() {
        return user.toString() + " (Role: ${role.toLowerCase().capitalize()}, Status: ${status.toLowerCase().capitalize()})"
    }

    String toAtlassianWikiMarkup() {
        return user.toAtlassianWikiMarkup() + ", *Role:* " + role.toLowerCase().capitalize() + ", *Status:* " + status.toLowerCase().capitalize()
    }

    String toMarkdown() {
        return user.toMarkdown() + ", **Role:** " + role.toLowerCase().capitalize() + ", **Status:** " + status.toLowerCase().capitalize()
    }

    @Override
    boolean isValid() {
        return validJsonEntity && role in ["AUTHOR", "REVIEWER", "PARTICIPANT"] && status in ["UNAPPROVED", "NEEDS_WORK", "APPROVED"] && user != null
    }
}

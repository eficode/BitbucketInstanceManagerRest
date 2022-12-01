package com.eficode.atlassian.bitbucketInstanceManager.model

import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest
import com.google.gson.annotations.SerializedName


class BitbucketPrParticipant implements BitbucketJsonEntity{

    BitbucketUser user


    @SerializedName("lastReviewedCommit")
    String lastReviewedCommitId

    String role //AUTHOR, REVIEWER, PARTICIPANT
    boolean approved
    String status //UNAPPROVED, NEEDS_WORK, APPROVED

    @Override
    Object getParent() {
        return validJsonEntity && role in ["AUTHOR", "REVIEWER", "PARTICIPANT"] && status in ["UNAPPROVED", "NEEDS_WORK", "APPROVED"] && user != null
    }

    @Override
    void setParent(Object parent) {
        assert parent instanceof BitbucketInstanceManagerRest
        this.setInstance(parent as BitbucketInstanceManagerRest)
        assert this.instance instanceof BitbucketInstanceManagerRest
    }

    @Override
    boolean isValid() {
        return this.instance
    }
}

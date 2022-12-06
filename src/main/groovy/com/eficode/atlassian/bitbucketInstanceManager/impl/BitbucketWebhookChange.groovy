package com.eficode.atlassian.bitbucketInstanceManager.impl

class BitbucketWebhookChange {

    BitbucketBranch ref
    String refId
    String fromHash
    String toHash
    String type

    BitbucketBranch getBranch() {
        return ref
    }
}

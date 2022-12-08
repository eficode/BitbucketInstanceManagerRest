package com.eficode.atlassian.bitbucketInstanceManager.impl

import com.eficode.atlassian.bitbucketInstanceManager.model.BitbucketEntity

class BitbucketWebhookChange implements BitbucketEntity{

    BitbucketBranch ref
    String refId
    String fromHash
    String toHash
    String type
    BitbucketRepo repository

    boolean isValid() {
        return isValidJsonEntity() && toHash && ref
    }

    @Override
    void setParent(BitbucketEntity repo) {

        this.repository = repo as BitbucketRepo


    }

    BitbucketBranch getBranch() {
        return ref
    }

    BitbucketCommit getToCommit() {
        if (toHash) {
            return repository.getCommit(toHash)
        }else {
            return null
        }
    }

    BitbucketCommit getFromCommit() {
        if (fromHash) {
            return repository.getCommit(fromHash)
        }else {
            return null
        }
    }


}

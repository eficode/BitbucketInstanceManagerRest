package com.eficode.atlassian.bitbucketInstanceManager.model

import com.google.gson.annotations.SerializedName

public enum WebhookEventType {


    @SerializedName("mirror:repo_synchronized")
    MIRROR_REPO_SYNCHRONIZED,
    @SerializedName("pr:comment:added")
    PR_COMMENT_ADDED,
    @SerializedName("pr:comment:deleted")
    PR_COMMENT_DELETED,
    @SerializedName("pr:comment:edited")
    PR_COMMENT_EDITED,
    @SerializedName("pr:declined")
    PR_DECLINED,
    @SerializedName("pr:deleted")
    PR_DELETED,
    @SerializedName("pr:from_ref_updated")
    PR_FROM_REF_UPDATED,
    @SerializedName("pr:merged")
    PR_MERGED,
    @SerializedName("pr:modified")
    PR_MODIFIED,
    @SerializedName("pr:opened")
    PR_OPENED,
    @SerializedName("pr:reviewer:approved")
    PR_REVIEWER_APPROVED,
    @SerializedName("pr:reviewer:needs_work")
    PR_REVIEWER_NEEDS_WORK,
    @SerializedName("pr:reviewer:unapproved")
    PR_REVIEWER_UNAPPROVED,
    @SerializedName("pr:reviewer:updated")
    PR_REVIEWER_UPDATED,
    @SerializedName("repo:comment:added")
    REPO_COMMENT_ADDED,
    @SerializedName("repo:comment:deleted")
    REPO_COMMENT_DELETED,
    @SerializedName("repo:comment:edited")
    REPO_COMMENT_EDITED,
    @SerializedName("repo:forked")
    REPO_FORKED,
    @SerializedName("repo:modified")
    REPO_MODIFIED,
    @SerializedName("repo:refs_changed")
    REPO_REFS_CHANGED


}

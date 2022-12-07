package com.eficode.atlassian.bitbucketInstanceManager.model

import com.fasterxml.jackson.annotation.JsonProperty

public enum WebhookEventType {


    @JsonProperty("mirror:repo_synchronized")
    MIRROR_REPO_SYNCHRONIZED,
    @JsonProperty("pr:comment:added")
    PR_COMMENT_ADDED,
    @JsonProperty("pr:comment:deleted")
    PR_COMMENT_DELETED,
    @JsonProperty("pr:comment:edited")
    PR_COMMENT_EDITED,
    @JsonProperty("pr:declined")
    PR_DECLINED,
    @JsonProperty("pr:deleted")
    PR_DELETED,
    @JsonProperty("pr:from_ref_updated")
    PR_FROM_REF_UPDATED,
    @JsonProperty("pr:merged")
    PR_MERGED,
    @JsonProperty("pr:modified")
    PR_MODIFIED,
    @JsonProperty("pr:opened")
    PR_OPENED,
    @JsonProperty("pr:reviewer:approved")
    PR_REVIEWER_APPROVED,
    @JsonProperty("pr:reviewer:needs_work")
    PR_REVIEWER_NEEDS_WORK,
    @JsonProperty("pr:reviewer:unapproved")
    PR_REVIEWER_UNAPPROVED,
    @JsonProperty("pr:reviewer:updated")
    PR_REVIEWER_UPDATED,
    @JsonProperty("repo:comment:added")
    REPO_COMMENT_ADDED,
    @JsonProperty("repo:comment:deleted")
    REPO_COMMENT_DELETED,
    @JsonProperty("repo:comment:edited")
    REPO_COMMENT_EDITED,
    @JsonProperty("repo:forked")
    REPO_FORKED,
    @JsonProperty("repo:modified")
    REPO_MODIFIED,
    @JsonProperty("repo:refs_changed")
    REPO_REFS_CHANGED


}

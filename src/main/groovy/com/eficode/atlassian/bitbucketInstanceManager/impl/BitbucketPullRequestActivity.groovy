package com.eficode.atlassian.bitbucketInstanceManager.impl

import com.eficode.atlassian.bitbucketInstanceManager.model.BitbucketJsonEntity
import com.eficode.atlassian.bitbucketInstanceManager.model.BitbucketUser
import kong.unirest.JsonNode
import kong.unirest.UnirestInstance

class BitbucketPullRequestActivity implements BitbucketJsonEntity{

    Integer id
    Long createdDate
    BitbucketUser user
    String action
    BitbucketCommit commit
    BitbucketPullRequest pullRequest


    @Override
    BitbucketPullRequest getParent() {

        return this.pullRequest
    }

    @Override
    void setParent(Object pr) {

        assert pr instanceof BitbucketPullRequest
        this.pullRequest = pr as BitbucketPullRequest

        assert this.pullRequest instanceof BitbucketPullRequest
    }

    @Override
    boolean isValid() {
        return validJsonEntity && id && pullRequest && user && (commit == null || commit.isValid())
    }


    static ArrayList<BitbucketPullRequestActivity> getPrActivities(BitbucketPullRequest pr, long maxActivities = 25) {

        ArrayList<JsonNode> rawActs = getPrActivities(pr.newUnirest, pr.repo.projectKey, pr.repo.slug, pr.id, maxActivities)

        ArrayList<BitbucketPullRequestActivity> activities = fromJson(rawActs.toString(),BitbucketPullRequestActivity,pr.instance, pr)

        //Re-fetch the commits to get rich data
        activities.findAll {it.commit != null}.each { it.commit = pr.repo.getCommit(it.commit.id)}

        activities.every{it.isValid()}
        return activities

    }



    static ArrayList<JsonNode> getPrActivities(UnirestInstance unirest, String projectKey, String repositorySlug, int prId, long maxActivities) {

        String url = "/rest/api/latest/projects/${projectKey}/repos/${repositorySlug}/pull-requests/$prId/activities"

        ArrayList<JsonNode> rawActs = getJsonPages(unirest, url, maxActivities)

        return rawActs

    }
}

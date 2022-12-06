package com.eficode.atlassian.bitbucketInstanceManager.impl

import com.eficode.atlassian.bitbucketInstanceManager.model.BitbucketEntity
import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class BitbucketBranch implements BitbucketEntity{

    static Logger log = LoggerFactory.getLogger(BitbucketRepo)

    String id
    String displayId
    String type
    String latestCommit
    String latestChangeset
    boolean isDefault

    @JsonProperty("repo")
    @JsonAlias("repository")
    BitbucketRepo repo

    boolean isValid() {

        return isValidJsonEntity() && id && latestCommit && latestChangeset && repo && (type == "BRANCH")
    }

    @Override
    BitbucketRepo getParent() {

        return this.repo
    }

    @Override
    void setParent(BitbucketEntity repo) {

        assert repo instanceof BitbucketRepo
        this.repo = repo as BitbucketRepo

        assert this.repo instanceof BitbucketRepo
    }

    /**
     * Returns the branch type
     * @return ex: hotfix, feature, etc
     */
    String getBranchType() {

        if (displayId.contains("/")) {
            return displayId.replaceFirst(/\/.*$/, "")
        } else {
            return ""
        }

    }


    /**
     * Quries the API for new data and returnes a new object with that data.
     * @return
     */
    BitbucketBranch refreshInfo() {
        return getBranch(displayId, repo)
    }

    static BitbucketBranch getBranch(String branchName, BitbucketRepo repo) {

        ArrayList<BitbucketBranch> matches = findBranches(repo, branchName, 25)

        assert matches.size() == 1: "Error getting branch, ID matches ${matches.size()} branches. Name: $branchName, Repo: ${repo.name}"


        return matches.first()
    }

    static ArrayList<BitbucketBranch> getAllBranches(BitbucketRepo repo, long maxBranches = 25) {

        return findBranches(repo, "", maxBranches )
    }

    static ArrayList<BitbucketBranch> findBranches(BitbucketRepo repo, String filter = "", long maxMatches = 25) {

        String url = "/rest/api/latest/projects/${repo.projectKey}/repos/${repo.repositorySlug}/branches"


        ArrayList<String> rawResponse = getJsonPages(repo.newUnirest,url,maxMatches,[filterText: filter])

        ArrayList<BitbucketBranch> branches = fromJson(rawResponse.toString(),BitbucketBranch,repo.instance,repo)
        return branches


    }

}

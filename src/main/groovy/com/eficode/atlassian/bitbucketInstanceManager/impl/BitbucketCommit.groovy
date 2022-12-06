package com.eficode.atlassian.bitbucketInstanceManager.impl


import com.eficode.atlassian.bitbucketInstanceManager.model.BitbucketEntity
import com.eficode.atlassian.bitbucketInstanceManager.model.BitbucketUser
import kong.unirest.JsonNode
import kong.unirest.UnirestInstance
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.text.DateFormat
import java.text.SimpleDateFormat

class BitbucketCommit implements BitbucketEntity {

    String id
    String displayId
    BitbucketUser author
    long authorTimestamp
    BitbucketUser committer
    long committerTimestamp
    String message
    public BitbucketRepo repository
    Logger log = LoggerFactory.getLogger(this.class)

    ArrayList<Map> parents = [["id": "", "displayId": ""]]
    //ArrayList<Map<String,String>> parents = [["id": "", "displayId": ""]]


    static final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")


    String getLink() {
        return baseUrl + "/projects/${repository.projectKey}/repos/${repository.slug}/commits/$id"
    }


    /**
     * Gets the first timestamp between committerTimestamp and authorTimeStamp
     * @return A Epoch MS long
     */
    long getTimeStamp() {

        return [committerTimestamp, authorTimestamp].findAll { it != 0 }.sort().first()

    }


    String getProjectKey() {
        return repository.projectKey
    }

    String getRepositorySlug() {
        return repository.slug
    }

    @Override
    boolean isValid() {

        return isValidJsonEntity() && id && displayId && message && repository.isValid() && author instanceof BitbucketUser

    }

    boolean isAMerge() {
        return parents.size() > 1
    }

    static String getMergerSymbol() {
        return "🔗"
    }


    String toString() {

        return displayId + " - " + message
    }


    String getMessageTruncated(int maxLen) {

        if (message.length() <= maxLen) {
            return message
        }

        return "..." + message.takeRight(maxLen - 3)

    }


    String toAtlassianWikiMarkup() {

        String parentsWithLinks = parents.collect { "[$displayId|${baseUrl + "/projects/${repository.projectKey}/repos/${repository.slug}/commits/$id"}]" }.join(", ")

        BitbucketPullRequest pr = getPullRequest()

        String mainOut = "h2. Commit ID: [$displayId|${link}]" + (isAMerge() ? " " + mergerSymbol : "") + "\n" +
                "*Author:* ${author.toAtlassianWikiMarkup()}\n\n" +
                "*Timestamp:* " + dateFormat.format(new Date(timeStamp as long)) + "\n\n" +
                "*Repository:* " + repository.toAtlassianWikiMarkupUrl() + "\n\n" +
                "*Branch:* " + branch.displayId + "\n\n" +
                "*Parents:* " + parentsWithLinks + "\n\n" +
                "*Message:*\n\n"


        message.eachLine { mainOut += " * " + it + "\n" }

        mainOut += "\n\\\\\n ---- \n\\\\\n"


        String changesOut = BitbucketChange.atlassianWikiHeader
        changes.each { changesOut += it.toAtlassianWikiMarkup() }
        changesOut += BitbucketChange.atlassianWikiFooter


        return mainOut + changesOut

    }

    /**
     * Return a MarkDown representation (optimized for bitbucket) of the commit including:
     * Commit it, Author, Timestamp, Parents (commits ids) and commit message
     * @return
     */
    String toMarkdown() {


        String mainOut = "## Commit ID: " + displayId + (isAMerge() ? " " + mergerSymbol : "") + "\n" +
                "**Author:** [${author.name}](${author.getProfileUrl(baseUrl)}) \n\n" +
                "**Timestamp:** " + dateFormat.format(new Date(timeStamp as long)) + "\n\n" +
                "**Repository:** " + repository.toMarkdownUrl() + "\n\n" +
                "**Branch:** " + branch.displayId + "\n\n" +
                "**Parents:** " + parents.displayId.join(", ") + "\n\n" +
                "**Message:**\n\n"


        message.eachLine { mainOut += "\t" + it + "\n" }

        mainOut += "\n\n"

        String changesOut = BitbucketChange.markdownHeader
        changes.each { changesOut += it.toMarkdown() }
        changesOut += BitbucketChange.markdownFooter


        return mainOut + changesOut

    }


    /**
     * Turn several commits in to Markdown (optimized for bitbucket)
     * @param commits
     * @return A String representation
     */
    static String toMarkdown(ArrayList<BitbucketCommit> commits) {

        ArrayList<String> changesMd = commits.collect { it.toMarkdown() }

        return changesMd.join("\n\n---\n\n\n")


    }

    ArrayList<BitbucketChange> getChanges(long maxChanges = 150) {
        return getChanges(repository.project.key, repository.slug, id, maxChanges)
    }


    ArrayList<BitbucketChange> getChanges(String projectKey, String repoSlug, String commitId, long maxChanges) {


        log.info("Getting changes for commit:")
        log.info("\tProject:" + projectKey)
        log.info("\tRepo:" + repoSlug)
        log.info("\tCommit:" + commitId)


        ArrayList<JsonNode> rawChangesResp = getRawChanges(newUnirest, projectKey, repoSlug, commitId, maxChanges)
        ArrayList<BitbucketChange> result = BitbucketChange.fromJson(rawChangesResp.toString(), BitbucketChange, instance) as ArrayList<BitbucketChange>


        if (result.isEmpty() && this.isAMerge()) {
            log.info("\tCommit has no changes but have been confirmed to be a Merge-commit, fetching changes performed by merge")
            ArrayList<JsonNode> rawChangesParent = getRawChanges(newUnirest, projectKey, repoSlug, commitId, maxChanges, parents.first().id as String)
            result = BitbucketChange.fromJson(rawChangesParent.toString(), BitbucketChange, instance) as ArrayList<BitbucketChange>
        }

        result.each { it.commit = this }

        return result

    }


    static ArrayList getRawChanges(UnirestInstance instance, String projectKey, String repoSlug, String commitId, long maxChanges, String since = null) {

        String url = "/rest/api/1.0/projects/$projectKey/repos/$repoSlug/commits/$commitId/changes"

        since ? url += "?since=$since" : ""

        return getJsonPages(instance, url, maxChanges)

    }


    /**
     * Get PRs that involve the commit
     * IE, commits that are part of the merge suggested by the PR
     * @param maxPRs Max nr of PRs to return
     * @return
     */
    ArrayList<BitbucketPullRequest> getPullRequestsInvolvingCommit(long maxPRs = 25) {

        return BitbucketPullRequest.getPullRequestsInvolvingCommit(repository, id, maxPRs)

    }


    /**
     * Returns the PR that created this Commit if any
     * @return
     */
    BitbucketPullRequest getPullRequest() {

        ArrayList<BitbucketPullRequest> relatedPrs = getPullRequestsInvolvingCommit(50)
        relatedPrs = relatedPrs.findAll {
            it.toRef.id == this.branch.id &&
                    it.toRef.repo.slug && this.branch.repo.slug
        }
        relatedPrs = relatedPrs.findAll {
            BitbucketCommit prCommit = it.getMergeCommit()
            prCommit.id == this.id
        }

        if (relatedPrs.size() > 1) {
            throw new InputMismatchException("Got several PRs matching commit:" + this.toString())
        } else if (relatedPrs.size() == 1) {
            return relatedPrs.first()
        } else {
            return null
        }

    }

    /**
     * Returns true if this is a commit due to a PR merge/rebase/squash
     * @return
     */
    boolean isAPrMerge() {

        return getPullRequest() != null


    }


    /**
     * Get the branch that the commit was made in
     * @return
     */
    BitbucketBranch getBranch() {


        String url = "/rest/branch-utils/latest/projects/${repository.project.key}/repos/${repository.slug}/branches/info/${displayId}"

        ArrayList branchesRaw = getJsonPages(newUnirest, url, 1)
        assert branchesRaw.size() == 1: "Error finding branch for Commit ${displayId}, API returned ${branchesRaw.size()} branches"

        //The raw output is not complete, thus the branch has to get fetched again from another Endpoint
        return BitbucketBranch.getBranch(branchesRaw.first().displayId, repository)


        //BitbucketBranch.fromRaw(branchesRaw, repository).first()
    }

    BitbucketCommit refreshInfo() {
        return getCommit(this.repository, this.id)
    }

    static BitbucketCommit getCommit(BitbucketRepo repo, String commitId) {

        String url = "/rest/api/latest/projects/${repo.projectKey}/repos/${repo.slug}/commits/" + commitId

        ArrayList rawCommits = getJsonPages(repo.newUnirest, url, 1, [:], false)

        assert rawCommits.size() == 1: "Error getting commit $commitId, API returned ${rawCommits.size()} matches"


        BitbucketCommit commit = fromJson(rawCommits.toString(), BitbucketCommit, repo.instance).first() as BitbucketCommit
        return commit

    }

}

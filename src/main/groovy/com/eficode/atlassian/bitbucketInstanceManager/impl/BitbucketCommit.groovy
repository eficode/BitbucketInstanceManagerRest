package com.eficode.atlassian.bitbucketInstanceManager.impl


import com.eficode.atlassian.bitbucketInstanceManager.model.BitbucketJsonEntity
import com.eficode.atlassian.bitbucketInstanceManager.model.BitbucketUser
import kong.unirest.JsonNode
import kong.unirest.UnirestInstance
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.text.DateFormat
import java.text.SimpleDateFormat

class BitbucketCommit implements BitbucketJsonEntity{

    String id
    String displayId
    BitbucketUser author
    long authorTimeStamp
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

    @Override
    BitbucketRepo getParent() {
        return this.repository
    }

    @Override
    void setParent(Object repo) {

        assert repo instanceof BitbucketRepo
        this.repository = repo as BitbucketRepo

        assert this.repository instanceof BitbucketRepo
    }

    String getProjectKey() {
        return repository.projectKey
    }

    String getRepositorySlug() {
        return repository.slug
    }

    @Override
    boolean isValid() {

        return isValidJsonEntity() && id && displayId && message && parent instanceof BitbucketRepo && repository.isValid() && author instanceof BitbucketUser

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

        String parentsWithLinks = parents.collect {"[$displayId|${baseUrl + "/projects/${repository.projectKey}/repos/${repository.slug}/commits/$id"}]"}.join(", ")

        String mainOut = "h2. Commit ID: [$displayId|${link}]"  + (isAMerge() ? " " + mergerSymbol : "") + "\n" +
                "*Author:* [~${author.name}] (Remote user: [${author.name}|${author.getProfileUrl(baseUrl)}])\n\n" +
                "*Timestamp:* " + (committerTimestamp != 0 ? dateFormat.format(new Date(committerTimestamp as long)) : dateFormat.format(new Date(authorTimeStamp as long))) + "\n\n" +
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
                "**Timestamp:** " + (committerTimestamp != 0 ? dateFormat.format(new Date(committerTimestamp as long)) : dateFormat.format(new Date(authorTimeStamp as long))) + "\n\n" +
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
        ArrayList<BitbucketChange> result = BitbucketChange.fromJson(rawChangesResp.toString(), BitbucketChange, instance, this) as ArrayList<BitbucketChange>



        if (result.isEmpty() && this.isAMerge()) {
            log.info("\tCommit has no changes but have been confirmed to be a Merge-commit, fetching changes performed by merge")
            ArrayList<JsonNode> rawChangesParent = getRawChanges(newUnirest, projectKey, repoSlug, commitId, maxChanges, parents.first().id as String)
            result = BitbucketChange.fromJson(rawChangesParent.toString(), BitbucketChange, instance, this) as ArrayList<BitbucketChange>
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


    /**

     static ArrayList<BitbucketCommit> fromRaw(ArrayList rawCommits, BitbucketRepo repo) {
     ArrayList<BitbucketCommit> bitbucketCommits = fromJson(rawCommits.toString(), BitbucketCommit, repo.parentObject)
     bitbucketCommits.each { commit ->
     //Set repo
     commit.repository = repo
     //Remove all but id and displayId, as getCommit and getCommits return different amount of info
     commit.parents.each { parentMap ->
     parentMap.removeAll { key, value ->
     !((key as String) in ["id", "displayId"])
     }
     }

     }

     return bitbucketCommits

     }
     */
}

package com.eficode.atlassian.bitbucketInstanceManager.impl

import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest
import com.eficode.atlassian.bitbucketInstanceManager.model.BitbucketEntity
import com.eficode.atlassian.bitbucketInstanceManager.model.MergeStrategy
import com.eficode.atlassian.bitbucketInstanceManager.model.WebhookEventType
import kong.unirest.HttpResponse
import kong.unirest.JsonNode
import kong.unirest.MultipartBody
import kong.unirest.UnirestInstance
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import unirest.shaded.com.google.gson.annotations.SerializedName
import com.fasterxml.jackson.annotation.JsonProperty
import java.nio.charset.StandardCharsets

class BitbucketRepo implements BitbucketEntity {


    public String slug
    public String id
    public String name
    public String hierarchyId
    public String scmId
    public String state
    public String statusMessage
    public boolean forkable
    public BitbucketProject project
    static Logger log = LoggerFactory.getLogger(BitbucketRepo)

    @JsonProperty("public")
    public boolean isPublic
    public boolean archived
    public Map<String, ArrayList> links = ["clone": [[:]], "self": [[:]]]

    BitbucketInstanceManagerRest instanceLocalCache

    @Override
    boolean isValid() {

        return isValidJsonEntity() && slug && id && name && hierarchyId && project?.isValid() && instance instanceof BitbucketInstanceManagerRest


    }



    @Override
    BitbucketProject getParent() {

        return this.project
    }

    @Override
    void setParent(BitbucketEntity proj) {

        assert proj instanceof BitbucketProject
        this.project = proj as BitbucketProject

        assert this.project instanceof BitbucketProject
    }


    boolean equals(Object object) {

        return object instanceof BitbucketRepo && this.name == object.name && this.id == object.id
    }

    /** --- GET Meta Data --- **/
    String getProjectKey() {
        return project.key
    }

    String getRepositorySlug() {
        return slug
    }

    String toString() {
        return project?.name + "/" + name
    }

    String getRepoBrowseUrl() {
        try {
            return links?.self?.first()?.href
        } catch (Exception ignore) {
            return null
        }
    }

    String toAtlassianWikiMarkupUrl() {

        return "[$name|$repoBrowseUrl]"
    }

    String toMarkdownUrl() {
        return "[$name]($repoBrowseUrl)"
    }


    /** --- GET --- **/


    static ArrayList<BitbucketRepo> getRepos(BitbucketProject project, long maxRepos = 50) {
        ArrayList<String> rawRepos = getReposRaw(project.newUnirest, project.key, maxRepos)

        ArrayList<BitbucketRepo> repos = fromJson(rawRepos.toString(), BitbucketRepo, project.instance, project)

        return repos
    }


    static ArrayList<String> getReposRaw(UnirestInstance unirest, String projectKey, long maxRepos = 100) {

        String url = "/rest/api/1.0/projects/${projectKey}/repos"

        ArrayList<JsonNode> responseRaw = getJsonPages(unirest, url, maxRepos, [:], true)

        return responseRaw.collect { it.toString() }

    }


    /** --- CREATE --- **/


    static BitbucketRepo createRepo(BitbucketProject project, String repoName) {


        String repoJson = createRepo(project.key, repoName, project.newUnirest)

        //Perhaps have to fetch a new representation of repo from API
        return fromJson(repoJson, BitbucketRepo, project.instance, project).first() as BitbucketRepo


    }

    static String createRepo(String projectKey, String repoName, UnirestInstance unirest) {

        log.info("Creating repo $repoName in project: " + projectKey)


        HttpResponse<JsonNode> response = unirest.post("/rest/api/latest/projects/${projectKey}/repos").contentType("application/json").body(name: repoName).asJson()
        unirest.shutDown()

        assert response.status == 201: "Got unexpected response when creating repo $repoName in project: $projectKey"

        String slug = response.body.object.slug
        log.info("\tRepo created with slug:" + slug)

        return response.body.toString()
    }


    /** --- DELETE --- **/


    boolean delete() {
        return deleteRepo(this)
    }

    static boolean deleteRepos(ArrayList<BitbucketRepo> repos) {

        UnirestInstance unirest = repos.first().newUnirest
        repos.each { bitbucketRepo ->
            assert bitbucketRepo instanceof BitbucketRepo && bitbucketRepo.isValid(): "Error deleting bitbucket repo, was supplied an invalid repo object:" + bitbucketRepo.toString()

            HttpResponse response = unirest.delete("/rest/api/latest/projects/${bitbucketRepo.project.key}/repos/${bitbucketRepo.slug}").asEmpty()

            assert response.status == 202: "Error deleting repo ($bitbucketRepo), request returned unexpected HTTP status"
        }
        unirest.shutDown()
        return true
    }

    static boolean deleteRepo(BitbucketRepo bitbucketRepo) {

        return deleteRepos([bitbucketRepo])
    }


    /** --- Branch CRUD --- **/

    /**
     * Set the default branch of repo
     * @param branchId ex: refs/heads/master or just master
     * @return
     */
    boolean setDefaultBranch(String branch) {

        UnirestInstance unirest = newUnirest

        String branchId

        if (branch.startsWith("refs/heads/")) {
            branchId = branch
        } else {
            branchId = "refs/heads/" + branch
        }

        HttpResponse response = unirest.put("/rest/api/latest/projects/${project.key}/repos/${slug}/default-branch")
                .contentType("application/json")
                .body([id: branchId])
                .asEmpty()

        return response.status == 204
    }


    BitbucketBranch getDefaultBranch() {


        ArrayList rawOut = getJsonPages(newUnirest, "/rest/api/latest/projects/${project.key}/repos/${slug}/default-branch", 1, [:], false)
        assert rawOut.size() == 1: "Error getting default branch for repo $name, API returned:" + rawOut


        return BitbucketBranch.fromJson(rawOut.toString(), BitbucketBranch, this.instance, this).first() as BitbucketBranch

    }


    /**
     * Create a new branch
     * @param branchName Name of new branch
     * @param branchFrom ex: refs/heads/master, master, commit id
     * @param branchType (Optional) ex: bugfix, hotfix, feature
     *              If branchType doesnt already exist in repo, it will be created
     * @return Full branch name: ex:  refs/heads/new-branch, refs/heads/hotfix/new-hotfix
     */
    BitbucketBranch createBranch(String branchName, String branchFrom, String branchType = null) {

        UnirestInstance unirest = newUnirest

        Map body = [

                name      : (branchType ? branchType + "/" : "") + branchName,
                startPoint: branchFrom
        ]

        HttpResponse<JsonNode> rawResponse = unirest.post("/rest/branch-utils/latest/projects/${projectKey}/repos/${repositorySlug}/branches").body(body).contentType("application/json").asJson()
        Map rawOut = jsonPagesToGenerics(rawResponse.body)

        if (rawOut.containsKey("errors")) {
            throw new Exception(rawOut.errors.collect { it?.message }?.join(", "))
        }

        assert rawResponse.status == 201: "API returned unexpected output when creating new branch:" + rawResponse?.body?.toString()

        unirest.shutDown()

        return BitbucketBranch.fromJson(rawResponse.body.toString(), BitbucketBranch, this.instance, this).first() as BitbucketBranch

    }


    ArrayList<BitbucketBranch> getAllBranches(long maxBranches = 25) {

        return BitbucketBranch.getAllBranches(this, maxBranches)
    }

    ArrayList<BitbucketBranch> findBranches(String filter = "", long maxMatches = 25) {

        return BitbucketBranch.findBranches(this, filter, maxMatches)
        /*
        String url = "/rest/api/latest/projects/${projectKey}/repos/${repositorySlug}/branches"

        if (filter) {
            url += "?filterText=" + filter
        }

        ArrayList rawResponse = getJsonPages(url,maxMatches)


        ArrayList<BitbucketBranch> branches = BitbucketBranch.fromRaw(rawResponse, this)
        return branches

         */


    }

    /** --- Get commits --- **/
    /**
     * Get Commits from repo
     * @param fromId Get all commits starting from this commit (not inclusive)
     * @param toId Get all commits until this commit
     * @return An array of commit objects
     */
    ArrayList<BitbucketCommit> getCommits(String fromId = "", String toId = "", long maxCommits) {


        //ArrayList<String> urlParameters = []

        //fromId ? urlParameters.add("since=$fromId") : null
        //toId ? urlParameters.add("until=$toId") : null

        String url = "/rest/api/latest/projects/${project.key}/repos/${slug}/commits"

        // urlParameters ? (url += "?" + urlParameters.join("&")) : null

        ArrayList<JsonNode> rawCommits = getJsonPages(newUnirest, url, maxCommits, [since: fromId, until: toId], true)

        return BitbucketCommit.fromJson(rawCommits.toString(), BitbucketCommit, instance, this)


    }

    BitbucketCommit getLastCommitInBranch(String branchName) {

        String parameter = "?limit=1&until=" + URLEncoder.encode("refs/heads/$branchName", StandardCharsets.UTF_8)
        String url = "/rest/api/latest/projects/${project.key}/repos/${slug}/commits" + parameter

        ArrayList<JsonNode> rawCommits = getJsonPages(newUnirest, url, 1)


        return BitbucketCommit.fromJson(rawCommits.toString(), BitbucketCommit, instance, this).first() as BitbucketCommit


    }

    BitbucketCommit getCommit(String commitId) {

       return BitbucketCommit.getCommit(this, commitId)

    }


    /** --- File CRUD --- **/

    /**
     *
     * @param repoFilePath Path to file in repo (no starting "/")
     * @param branchName Name of branch to update in
     * @param fileContent The content that the file should have
     * @param commitMessage Message for the commit/change
     * @return the new BitbucketCommit
     */
    BitbucketCommit createFile(String repoFilePath, String branchName, String fileContent, String commitMessage) {


        File tempFile = File.createTempFile("bitbucketUpdateFile", "tmp")
        tempFile.text = fileContent

        BitbucketCommit newCommit = editFileRaw(repoFilePath, branchName, tempFile, commitMessage)

        tempFile.delete()

        return newCommit
    }



    String getFileContent(String repoFilePath, String branchName = "", String commitId = "") {


        assert ((!branchName && commitId) || (branchName && !commitId)) || !(branchName && commitId): "Error you must supply either branchName or commitId, or neither, got branchName:\"$branchName\", commitId:\"$commitId\""

        String url = "/rest/api/latest/projects/${project.key}/repos/${slug}/browse/$repoFilePath"

        if (branchName) {

            url += "?at=" + URLEncoder.encode("refs/heads/$branchName", StandardCharsets.UTF_8)
        } else if (commitId) {
            url += "?at=" + commitId
        }


        Map rawOut = jsonPagesToGenerics(getJsonPages(newUnirest, url, 1, [:], false).first())

        if (rawOut.containsKey("errors")) {
            throw new Exception(rawOut.errors.collect { it?.message }?.join(", "))
        }

        String out = rawOut.lines.collect { it?.text }.join("\n")

        return out

    }

    /**
     *
     * @param repoFilePath Path to file in repo (no starting "/")
     * @param branchName Name of branch to update in
     * @param fileContent The content that the file should have
     * @param commitMessage Message for the commit/change
     * @return the new BitbucketCommit
     */
    BitbucketCommit updateFile(String repoFilePath, String branchName, String fileContent, String commitMessage) {

        BitbucketCommit lastCommit = getLastCommitInBranch(branchName)
        File tempFile = File.createTempFile("bitbucketUpdateFile", "tmp")
        tempFile.text = fileContent

        BitbucketCommit newCommit = editFileRaw(repoFilePath, branchName, tempFile, commitMessage, lastCommit.id)


        tempFile.delete()

        return newCommit
    }


    /**
     * Add content to start of file
     * @param repoFilePath Path to file in repo (no starting "/")
     * @param branchName Name of branch to update in
     * @param head content to add to the head/start of existing file
     * @param commitMessage Message for the commit/change
     * @return the new BitbucketCommit
     */
    BitbucketCommit prependFile(String repoFilePath, String branchName, String head, String commitMessage) {

        String previousContent = getFileContent(repoFilePath, branchName)

        return updateFile(repoFilePath, branchName, head + previousContent, commitMessage)

    }

    /**
     * Add content to end of file
     * @param repoFilePath Path to file in repo (no starting "/")
     * @param branchName Name of branch to update in
     * @param tail content to add to the tail end of existing file
     * @param commitMessage Message for the commit/change
     * @return the new BitbucketCommit
     */
    BitbucketCommit appendFile(String repoFilePath, String branchName, String tail, String commitMessage) {

        String previousContent = getFileContent(repoFilePath, branchName)

        return updateFile(repoFilePath, branchName, previousContent + tail, commitMessage)

    }


    /**
     *
     * Intended for private use, use updateFile or createFile
     *
     * @param repoFilePath Path to file in repo (no starting "/")
     * @param branchName Name of branch to update in
     * @param fileContent The content that the file should have
     * @param commitMessage Message for the commit/change
     * @param sourceCommit the commit ID of the file before it was edited, used to identify if content has changed. Or null if this is a new file
     * @return the new BitbucketCommit
     */
    BitbucketCommit editFileRaw(String repoFilePath, String branchName, File sourceFile, String commitMessage, String sourceCommit = null) {

        UnirestInstance unirest = newUnirest


        MultipartBody body = unirest.put("/rest/api/latest/projects/${project.key}/repos/${slug}/browse/${repoFilePath}")
                .field("branch", branchName)
                .field("content", sourceFile)
                .field("message", commitMessage)

        if (sourceCommit) {
            body.field("sourceCommitId", sourceCommit)
        }

        HttpResponse<JsonNode> response = body.asJson()


        unirest.shutDown()


        if (response.body.toString().contains("error")) {

            throw new Exception("Error updating Bitbucket file, API responded:" + response.body.toPrettyString())
        }

        BitbucketCommit newCommit = BitbucketCommit.fromJson(response.body.toString(), BitbucketCommit, instance, this as BitbucketRepo).first() as BitbucketCommit


        return newCommit
    }

    /** --- Pull Request Config CRUD --- **/

    boolean enableAllMergeStrategies(MergeStrategy defaultStrategy = MergeStrategy.NO_FF) {
        return setEnabledMergeStrategies(defaultStrategy, MergeStrategy.values() as ArrayList<MergeStrategy>)
    }

    boolean setEnabledMergeStrategies(MergeStrategy defaultStrategy, ArrayList<MergeStrategy> enabledStrategies) {

        log.info("Setting enabled Merge strategies for repo " + name)
        ArrayList<MergeStrategy> possibleStrategies = MergeStrategy.values()
        assert enabledStrategies.every { it in possibleStrategies }: "Got unsupported merge strategy"
        assert defaultStrategy in enabledStrategies: "The default strategy must be part of the enabledStrategies"

        log.info("\tDefault strategy:" + defaultStrategy)
        log.info("\tEnabled strategies:" + enabledStrategies.join(","))

        Map restBody = [
                "mergeConfig": [
                        strategies     : enabledStrategies.collect { [id: it.serializedName] },
                        defaultStrategy: [id: defaultStrategy.serializedName]

                ]
        ]

        UnirestInstance unirest = newUnirest

        HttpResponse<JsonNode> responseRaw = unirest.post("/rest/api/latest/projects/${projectKey}/repos/${repositorySlug}/settings/pull-requests")
                .contentType("application/json")
                .body(restBody)
                .asJson()
        unirest.shutDown()

        assert responseRaw.status == 200: "Error updating merge strategies:" + response?.body?.toString()

        Map response = jsonPagesToGenerics(responseRaw.body)

        assert response.mergeConfig.type == "REPOSITORY": "Error setting REPO-level merge strategies"
        assert response.mergeConfig.defaultStrategy.id == defaultStrategy.serializedName: "Error setting default merge strategy"
        assert response.mergeConfig.strategies.findAll { it.enabled }.id == enabledStrategies.serializedName: "Error setting enabled strategies, API returned enabled strategies:" + response?.mergeConfig?.strategies?.findAll { it.enabled }?.id

        log.info("\tMerge strategies successfully set")
        return true

    }


    /**
     * True if the merge settings are inherited from project
     */
    boolean mergeStrategyIsInherited() {

        return prRawConfig.mergeConfig.type != "REPOSITORY"
    }

    /**
     * Get the id of all the enabled merge strategies
     * Possible IDs: no-ff, ff, ff-only, rebase-no-ff, rebase-ff-only, squash, squash-ff-only
     * @return Array of IDs
     */
    ArrayList<MergeStrategy> getEnabledMergeStrategies() {

        Map mergeConfig = prRawConfig.mergeConfig as Map
        ArrayList<Map> strategies = mergeConfig.strategies as ArrayList<Map>

        strategies.removeAll { !it.enabled }

        ArrayList<MergeStrategy> strategiesEnum = []

        strategies.each {
            MergeStrategy enumStrat = MergeStrategy.getFromSerializedName(it.id as String)
            assert enumStrat: "Error finding Merge strategy for:" + it?.id
            strategiesEnum.add(enumStrat)
        }

        return strategiesEnum

    }

    /**
     * Get the default merge strategy for the repo
     * @return one of: no-ff, ff, ff-only, rebase-no-ff, rebase-ff-only, squash, squash-ff-only
     */
    MergeStrategy getDefaultMergeStrategy() {

        Map mergeConfig = prRawConfig.mergeConfig as Map
        Map defaultStrategy = mergeConfig.defaultStrategy as Map

        return MergeStrategy.getFromSerializedName(defaultStrategy.id as String)
    }


    /**
     * Get the raw API output for PR config
     * @return
     */
    Map getPrRawConfig() {

        String url = "/rest/api/latest/projects/${projectKey}/repos/${repositorySlug}/settings/pull-requests"

        Map rawStrategies = jsonPagesToGenerics(getJsonPages(newUnirest, url, 1, [:], false).first())


        return rawStrategies
    }


    /** -- Pull Request CRUD -- **/


    /**
     * Get all open pull requests in repo
     * @param targetBranch (Optional), filter on PRs to branch
     * @return
     */
    ArrayList<BitbucketPullRequest> getOpenPullRequests(BitbucketBranch targetBranch = null) {

        return getPullRequests("OPEN", targetBranch)

    }


    /**
     * Get All pull requests in repo
     * @param targetBranch (Optional), filter on PRs to branch
     * @return
     */
    ArrayList<BitbucketPullRequest> getAllPullRequests(BitbucketBranch targetBranch = null) {

        return getPullRequests("ALL", targetBranch)

    }

    /**
     * Get pull requests in repo
     * @param state What state to filter PRs by, available options: ALL, OPEN, DECLINED or MERGED
     * @param targetBranch (Optional), filter on PRs to branch
     * @return
     */
    ArrayList<BitbucketPullRequest> getPullRequests(String state, BitbucketBranch targetBranch = null) {

        return BitbucketPullRequest.getPullRequests(this, state, targetBranch)

    }


    /**
     * Creates a new PR, autogenerate title and description based on branches provided.
     * @param fromRef Source branch, ie feature branch, bug branch etc
     * @param toBranch Destination branch, ie master
     * @return The new PR object
     */
    static BitbucketPullRequest createPullRequest(BitbucketBranch fromRef, BitbucketBranch toBranch) {
        return BitbucketPullRequest.createPullRequest(fromRef, toBranch)
    }

    static BitbucketPullRequest createPullRequest(String title, String description, BitbucketBranch fromRef, BitbucketBranch toBranch) {

        return BitbucketPullRequest.createPullRequest(title, description, fromRef.id, toBranch.id, toBranch.repo)
    }

    /** --- Webhook Config CRUD --- */

    /**
     * Get all webhooks in repo which has one of the $events enabled.
     * If events=[] all webhooks will be returned
     * @param events
     * @param maxReturns
     * @return
     */
    ArrayList<BitbucketWebhook> getWebhooks(ArrayList events = [], long maxReturns = 25) {
        return BitbucketWebhook.getWebhooks(this, events, maxReturns, newUnirest)
    }

    /**
     * Create a new webhook
     * @param name name of new webhook
     * @param remoteUrl The url that the webhook should communicate with
     * @param events The events that the webhook should fire on (see BitbucketWebhook.Event), if emtpy all
     * @param secret See https://docs.atlassian.com/bitbucketserver/docs-085/Manage+webhooks?utm_campaign=in-app-help&utm_medium=in-app-help&utm_source=stash#Managewebhooks-webhooksecrets
     * @return The new WebHook
     */
    BitbucketWebhook createWebhook(String name, String remoteUrl, ArrayList<WebhookEventType> events, String secret = "") {
        return BitbucketWebhook.createWebhook(name, remoteUrl, this, events, secret, newUnirest)
    }

    static boolean deleteWebhook(BitbucketWebhook webhook) {
        return BitbucketWebhook.deleteWebhook(webhook)
    }


}

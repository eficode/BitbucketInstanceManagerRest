package com.eficode.atlassian.bitbucketInstanceManager.impl

import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest
import com.eficode.atlassian.bitbucketInstanceManager.model.BitbucketJsonEntity
import com.eficode.atlassian.bitbucketInstanceManager.model.MergeStrategy
import kong.unirest.HttpResponse
import kong.unirest.JsonNode
import kong.unirest.UnirestInstance
import kong.unirest.json.JSONObject
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class BitbucketPullRequest implements BitbucketJsonEntity {

    BitbucketRepo repo
    Logger log = LoggerFactory.getLogger(this.class)
    Integer id
    Integer version
    String title
    String description
    String state
    boolean open
    boolean closed
    boolean locked
    Long createdDate
    Long updatedDate
    BitbucketBranch fromRef
    BitbucketBranch toRef
    Map author
    ArrayList reviewers
    ArrayList participants
    Map links


    @Override
    BitbucketRepo getParent() {

        return this.repo
    }

    @Override
    void setParent(Object repo) {

        assert repo instanceof BitbucketRepo
        this.repo = repo as BitbucketRepo

        assert this.repo instanceof BitbucketRepo
    }

    @Override
    boolean isValid() {


        return isValidJsonEntity() && repo instanceof BitbucketRepo && id && title && fromRef instanceof BitbucketBranch && toRef instanceof BitbucketBranch && author.size() >= 4 && instance instanceof BitbucketInstanceManagerRest

    }

    /** --- CREATE --- **/

    /**
     * Creates a new PR, autogenerate title and description based on branches provided.
     * @param fromRef Source branch, ie feature branch, bug branch etc
     * @param toBranch Destination branch, ie master
     * @return The new PR object
     */
    static BitbucketPullRequest createPullRequest(BitbucketBranch fromRef, BitbucketBranch toBranch) {
        assert fromRef.displayId: "Could not determine name of branch to pull from"
        assert toBranch.displayId: "Could not determine name of branche to merge to"

        String title = "Merge ${fromRef.displayId} in to ${toBranch.displayId}"

        String description = fromRef.repo.getCommits(toBranch.latestCommit, fromRef.latestCommit, 100).reverse().collect { "* " + it.message + " (${it.displayId})" }.join("\n")

        assert description: "Could not determine description for PR, tried getting commit messaged between: ${fromRef.latestCommit} and ${toBranch.latestCommit}"

        return createPullRequest(title, description, fromRef, toBranch)

    }

    static BitbucketPullRequest createPullRequest(String title, String description, BitbucketBranch fromRef, BitbucketBranch toBranch) {

        return createPullRequest(title, description, fromRef.id, toBranch.id, toBranch.repo)
    }

    static BitbucketPullRequest createPullRequest(String title, String description, String fromRef, String toBranch, BitbucketRepo repo) {


        String prJson = createPullRequest(title, description, fromRef, toBranch, repo.projectKey, repo.repositorySlug, repo.newUnirest)

        ArrayList<BitbucketPullRequest> prs = fromJson(prJson, BitbucketPullRequest, repo.instance, repo)

        assert prs.size() == 1: "Library failed to parse response from API:" + prJson
        assert prs.first().isValid(): " Library returned invalid object"
        return prs.first()
    }

    static String createPullRequest(String title, String description, String fromRef, String toBranch, String projectKey, String repositorySlug, UnirestInstance unirest) {

        String url = "/rest/api/latest/projects/${projectKey}/repos/${repositorySlug}/pull-requests"


        Map body = [
                description: description,
                title      : title,
                fromRef    : [
                        id: fromRef
                ],
                toRef      : [
                        id: toBranch
                ]
        ]

        HttpResponse responseRaw = unirest.post(url).body(body).contentType("application/json").asJson()
        unirest.shutDown()

        assert responseRaw.status == 201: "Error creating Pull Request, API returned stauts ${responseRaw.status}, and body:" + responseRaw?.body

        return responseRaw.body

    }

    /** --- GET --- **/

    /**
     * Get Pull requests
     * @param repo the repo to fetch from
     * @param state (Optional)  What state to filter PRs by, available options: ALL, OPEN, DECLINED or MERGED. Default: OPEN
     * @param targetBranch (Optional)  Get PRs to a  branch
     * @return An array of BitbucketPullRequest, or an empty array if no matches
     *
     */

    static ArrayList<BitbucketPullRequest> getPullRequests(BitbucketRepo repo, String state = "OPEN", BitbucketBranch targetBranch = null) {

        String prJson = getPullRequests(repo.projectKey, repo.repositorySlug, repo.newUnirest, state, targetBranch?.id)

        ArrayList<BitbucketPullRequest> prs = fromJson(prJson, BitbucketPullRequest, repo.instance, repo)

        if (prs.empty) {
            return []
        }

        assert prs.every { it.isValid() }: " Library returned invalid object"
        return prs


    }

    /**
     * Get Pull requests
     * @param projectKey Key of project to fetch from
     * @param repositorySlug Slug of repo to fetch from
     * @param unirest a Unirest instance to use to make the call
     * @param state (Optional) What state to filter PRs by, available options: ALL, OPEN, DECLINED or MERGED. Default: OPEN
     * @param branchId (Optional) Get PRs to a full branch id ( ex refs/heads/master)
     * @param maxPrs (Optional) Max PRs to return (will get the latest)
     * @return An array of  String JSON representations of the PR
     *
     */
    static ArrayList<String> getPullRequests(String projectKey, String repositorySlug, UnirestInstance unirest, String state = "OPEN", String branchId = "", int maxPrs = 100) {

        String url = "/rest/api/latest/projects/${projectKey}/repos/${repositorySlug}/pull-requests"

        Map<String, String> parameters = [state: state, at: branchId]
        //state ? parameters.put("state", state) : ""
        //branchId ? parameters.put("at", branchId) : ""


        ArrayList<JsonNode> responseRaw = getJsonPages(unirest, url, maxPrs, ["state": state, at: branchId], true)


        return responseRaw.collect {it.toString()}

    }



    //TODO continue
    /*
    static ArrayList<BitbucketPullRequest> getPullRequestsWithCommit(BitbucketCommit commit, int maxPrs = 100 ) {


        UnirestInstance unirest = commit.parentObject.newUnirest
        String prJson = getPullRequestsWithCommit(commit.projectKey, commit.repositorySlug, commit.id,  unirest, maxPrs)

        ArrayList<BitbucketPullRequest> prs = fromJson(prJson, BitbucketPullRequest, commit.repository.parentObject as BitbucketInstanceManagerRest, repo)

        if (prs.empty) {
            return []
        }

        assert prs.every { it.isValid() }: " Library returned invalid object"
        return prs

    }

     */

    static ArrayList<BitbucketPullRequest> getPullRequestsWithCommit(BitbucketRepo repo, String commitId, int maxPrs = 100 ) {


        String prJson = getPullRequestsWithCommit(repo.projectKey, repo.repositorySlug,commitId,  repo.newUnirest, maxPrs)

        ArrayList<BitbucketPullRequest> prs = fromJson(prJson, BitbucketPullRequest, repo.instance, repo)

        if (prs.empty) {
            return []
        }

        assert prs.every { it.isValid() }: " Library returned invalid object"
        return prs

    }




    static ArrayList<String>getPullRequestsWithCommit(String projectKey, String repositorySlug, String commitId, UnirestInstance unirest, int maxPrs = 100) {

        String url = "/rest/api/latest/projects/${projectKey}/repos/${repositorySlug}/commits/$commitId/pull-requests"

        ArrayList<JsonNode> responseRaw = getJsonPages(unirest, url, maxPrs, [:], true)


        return responseRaw.collect {it.toString()}

    }


    /** --- MERGE --- **/

    BitbucketPullRequest mergePullRequest(MergeStrategy mergeStrategy = null) {
        return mergePullRequest(this, mergeStrategy)
    }

    static BitbucketPullRequest mergePullRequest(BitbucketPullRequest pullRequest, MergeStrategy mergeStrategy = null) {

        return mergePullRequest(pullRequest.id.toString(), pullRequest.version, mergeStrategy, pullRequest.repo)

    }

    static BitbucketPullRequest mergePullRequest(String prId, Integer prVersion, MergeStrategy mergeStrategy = null, BitbucketRepo repo) {

        String prJson = mergePullRequest(repo.projectKey, repo.slug, prId, prVersion, repo.newUnirest, mergeStrategy ? mergeStrategy.getSerializedName() : "")

        ArrayList<BitbucketPullRequest> prs = fromJson(prJson, BitbucketPullRequest, repo.instance, repo)

        assert prs.size() == 1: "Library failed to parse response from API:" + prJson
        assert prs.first().isValid(): " Library returned invalid object"
        return prs.first()

    }

    /**
     * Merge en exsiting PR
     * @param projectKey Name of project where the PR is
     * @param repositorySlug Slug of Repo where PR is
     * @param prId Id of PR
     * @param prVersion Version of PR, this makes sure that the PR hasn't been updated since fetched by the library. Will error if out of date
     * @param unirest Unirest instance to use while making the request
     * @param strategyId ID of merge strategy to use ex no-ff, ff etc. Check MergeStrategy
     * @return A JSON string representation of the PR after merge.
     */
    static String mergePullRequest(String projectKey, String repositorySlug, String prId, Integer prVersion, UnirestInstance unirest, String strategyId = "") {

        String url = "/rest/api/latest/projects/${projectKey}/repos/${repositorySlug}/pull-requests/$prId/merge"

        Map<String, Object> body = ["version": prVersion]
        strategyId != "" ? body.put("strategyId", strategyId) : null

        HttpResponse<JsonNode> responseRaw = unirest.post(url).body(body).contentType("application/json").asJson()
        unirest.shutDown()

        if (responseRaw.status == 409) {
            String errorMsg
            try {
                JSONObject error = responseRaw.body.getObject().getJSONArray("errors").getJSONObject(0)
                errorMsg = error.get("message") + " Latest version of PR is:" + error.get("currentVersion") + ", " + error.get("expectedVersion") + " was supplied"

            } catch (Exception ignore) {
                throw new InputMismatchException("Error merging Pull Request, API returned stauts ${responseRaw.status}, and body:" + responseRaw?.body)
            }

            throw new InputMismatchException(errorMsg)
        }

        assert responseRaw.status == 200: "Error merging Pull Request, API returned stauts ${responseRaw.status}, and body:" + responseRaw?.body


        return responseRaw.body


    }


}

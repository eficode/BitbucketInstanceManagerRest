package com.eficode.atlassian.bitbucketInstanceManager.impl

import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest
import com.eficode.atlassian.bitbucketInstanceManager.model.BitbucketEntity
import com.eficode.atlassian.bitbucketInstanceManager.model.BitbucketPrParticipant
import com.eficode.atlassian.bitbucketInstanceManager.model.BitbucketUser
import com.eficode.atlassian.bitbucketInstanceManager.model.MergeStrategy
import kong.unirest.HttpResponse
import kong.unirest.JsonNode
import kong.unirest.UnirestInstance
import kong.unirest.json.JSONObject
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.text.DateFormat
import java.text.SimpleDateFormat

class BitbucketPullRequest implements BitbucketEntity {

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
    BitbucketPrParticipant author
    ArrayList<BitbucketPrParticipant> reviewers
    ArrayList<BitbucketPrParticipant> participants
    Map links
    Map properties


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

    @Override
    boolean isValid() {


        return isValidJsonEntity() && repo instanceof BitbucketRepo && id && title && fromRef instanceof BitbucketBranch && toRef instanceof BitbucketBranch && author && instance instanceof BitbucketInstanceManagerRest

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


    /** --- GET PRs--- **/

    BitbucketPullRequest refreshInfo() {
        return getPullRequest(repo, id)
    }

    static BitbucketPullRequest getPullRequest(BitbucketRepo repo, long id) {

        ArrayList<String> rawPr = getJsonPages(repo.newUnirest, "/rest/api/latest/projects/${repo.projectKey}/repos/${repo.repositorySlug}/pull-requests/$id", 1, [:], false)

        ArrayList<BitbucketPullRequest> prs = fromJson(rawPr.first().toString(), BitbucketPullRequest, repo.instance, repo)

        if (prs.size() == 1) {
            return prs.first()
        }
        return null

    }

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


        return responseRaw.collect { it.toString() }

    }


    /**
     * Get the Pull Requests that involve a commit
     * @param repo The repo where the commit is
     * @param commitId The full commit id
     * @param maxPrs Max number of PRs to return
     * @return An array of PR objects
     */
    static ArrayList<BitbucketPullRequest> getPullRequestsInvolvingCommit(BitbucketRepo repo, String commitId, long maxPrs = 100) {


        String prJson = getPullRequestsInvolvingCommit(repo.projectKey, repo.repositorySlug, commitId, repo.newUnirest, maxPrs)

        ArrayList<BitbucketPullRequest> prs = fromJson(prJson, BitbucketPullRequest, repo.instance, repo)

        if (prs.empty) {
            return []
        }

        assert prs.every { it.isValid() }: " Library returned invalid object"
        return prs

    }


    /**
     * Get the Pull Requests that involve a commit
     * @param projectKey The project where the commit is
     * @param repositorySlug The repo where the commit is
     * @param commitId The full commit id
     * @param unirest A unirest instance to use
     * @param maxPrs Max number of PRs to return
     * @return An array of PR json strings
     */
    static ArrayList<String> getPullRequestsInvolvingCommit(String projectKey, String repositorySlug, String commitId, UnirestInstance unirest, long maxPrs = 100) {

        String url = "/rest/api/latest/projects/${projectKey}/repos/${repositorySlug}/commits/$commitId/pull-requests"

        ArrayList<JsonNode> responseRaw = getJsonPages(unirest, url, maxPrs, [:], true)


        return responseRaw.collect { it.toString() }

    }


    /** --- GET METADATA--- **/

    static final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

    String toString() {
        return repo.toString() + ": " + title + " (ID: ${this.id})"
    }

    String toAtlassianWikiMarkup() {

        String mainOut = "h2. Pull Request: $title (ID: $id)\n\n" +
                "*Description:* \n\n${description.lines().collect {"\t" + it}.join("\n")}\n\n" +
                "*State:* $state\n" +
                "*Created*:" + dateFormat.format(new Date(createdDate as long))  + "\n\n" +
                "*Updated*:" + dateFormat.format(new Date(updatedDate as long)) + "\n\n" +
                "*From Branch*:" + fromRef.displayId + "\t*To Branch:* " + toRef.displayId + "\n\n" +
                "*Author:* " + author.toAtlassianWikiMarkup() + "\n\n" +
                "*Reviewers:*\n" + reviewers.collect {"\t" + it.toAtlassianWikiMarkup()}.join("\n") + "\n\n" +
                "*Participants:*\n" + participants.collect {"\t" + it.toAtlassianWikiMarkup()}.join("\n")



        return mainOut

    }


    ArrayList<BitbucketPullRequestActivity> getPrActivities(long maxActivities = 25) {
        return BitbucketPullRequestActivity.getPrActivities(this, maxActivities)
    }

    /**
     * Get the resulting commit after a PR has been merged
     * @return a BitbucketCommit object
     */
    BitbucketCommit getMergeCommit() {

        ArrayList<BitbucketPullRequestActivity> activities = getPrActivities(50).findAll { it.commit != null && it.action == "MERGED" }


        if (activities.size() > 1) {
            throw new InputMismatchException("Unexpected data from Bitbucket, PR has more than 1 merging commit. PR: ${this.toString()}, Commits: ${activities.commit.id.join(",")}")
        } else if (activities.size() == 1) {
            return activities.first().commit
        } else {
            return null
        }


    }

    /**
     * Get all Reviewers who have approved this PR up until the latest commit
     * @return
     */
    ArrayList<BitbucketPrParticipant> getApprovers() {

        return reviewers.findAll {
            it.status == "APPROVED" &&
                    it.approved &&
                    it.role == "REVIEWER" &&
                    it.lastReviewedCommitId == this.fromRef.latestCommit
        }


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
     * Merge en existing PR
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


    /** --- CRUD Approvals --- **/


    /**
     * Set approval status of this PR for a different user
     * @param userName User name of the other user
     * @param password Password of the other user
     * @param status "UNAPPROVED", "NEEDS_WORK", "APPROVED"
     * @return a BitbucketPrParticipant-object
     */
    BitbucketPrParticipant setApprovalStatus(String userName, String password, String status) {

        BitbucketInstanceManagerRest bb = new BitbucketInstanceManagerRest(userName, password, this.instance.baseUrl)

        BitbucketUser currentUser = bb.getCurrentUser()

        String rawResponse = setApprovalStatus(bb.newUnirest, repo.projectKey, repo.slug, id, currentUser.slug,status)

        BitbucketPrParticipant participant = BitbucketPrParticipant.fromJson(rawResponse,BitbucketPrParticipant, instance, instance).first() as BitbucketPrParticipant

        //Re-fetching so that the correct user is logged-in in the new object
        ArrayList<BitbucketPrParticipant> reviewers = this.refreshInfo().getReviewers()
        BitbucketPrParticipant reFetched = reviewers.find {
            it.user.slug.equalsIgnoreCase(currentUser.slug)
        }
        return reFetched



    }


    /**
     * Set the approval status of this PR as the currently logged in user
     * @param userSlug The currently logged in user
     * @param status "UNAPPROVED", "NEEDS_WORK", "APPROVED"
     * @return a BitbucketPrParticipant-object
     */
    BitbucketPrParticipant setApprovalStatus(String userSlug, String status) {

        String rawResponse = setApprovalStatus(newUnirest, repo.projectKey, repo.slug, id, userSlug,status)

        BitbucketPrParticipant participant = BitbucketPrParticipant.fromJson(rawResponse,BitbucketPrParticipant, instance, instance).first() as BitbucketPrParticipant

        return participant

    }

    /**
     * Set approval status on a PR for the current user
     * @param unirest The unirest instanstce to use, NOTE this instance must be logged in as the userSlug-user
     * @param projectKey The project where the PR is
     * @param repositorySlug The repo where the PR is
     * @param prId The ID of the pr
     * @param userSlug Slug of the user
     * @param status "UNAPPROVED", "NEEDS_WORK", "APPROVED"
     * @return A string representation of BitbucketPrParticipant
     */
    static String setApprovalStatus(UnirestInstance unirest, String projectKey, String repositorySlug, long prId,String userSlug, String status) {

        assert status in ["UNAPPROVED", "NEEDS_WORK", "APPROVED"] : "Unknown Approval status submitted"

        String url = "/rest/api/latest/projects/${projectKey}/repos/${repositorySlug}/pull-requests/$prId/participants/$userSlug"

        HttpResponse<JsonNode> rawResponse = unirest.put(url).body(["status" : status]).contentType("application/json").asJson()
        unirest.shutDown()


        Map rawOut = jsonPagesToGenerics(rawResponse.body)
        if (rawOut.containsKey("errors")) {
            throw new Exception(rawOut.errors.collect { it?.message }?.join(", "))
        }

        assert rawResponse.status == 200: "API returned unexpected output when setting approval status $status on PR $prId for user $userSlug:" + rawResponse?.body?.toString()

        return rawResponse.body

    }


    /** --- CRUD Reviewers --- **/


    /**
     * Remove a reviewer from this PR
     * @param participant a BitbucketPrParticipant-object
     * @return true on success
     */
    boolean removeReviewer(BitbucketPrParticipant participant) {
        return removeReviewer(newUnirest, repo.projectKey, repo.slug, id, participant.user.slug)
    }

    /**
     * Remove a reviewer from this PR
     * @param user a BitbucketUser-object
     * @return true on success
     */
    boolean removeReviewer(BitbucketUser user) {
        return removeReviewer(newUnirest, repo.projectKey, repo.slug, id, user.slug)
    }


    /**
     * Remove a reviewer from a pull request
     * @param unirest a Unirest instance to use to make the call
     * @param projectKey The project where the pr is
     * @param repositorySlug The repo where the pr is
     * @param prId The id of the PR
     * @param userName The username of the user to add
     * @return true on success
     */
    static boolean removeReviewer(UnirestInstance unirest, String projectKey, String repositorySlug, long prId,String userSlug) {

        String url = "/rest/api/latest/projects/${projectKey}/repos/${repositorySlug}/pull-requests/$prId/participants/$userSlug"

        HttpResponse<JsonNode> rawResponse = unirest.delete(url).asJson()
        unirest.shutDown()


        Map rawOut = jsonPagesToGenerics(rawResponse.body)
        if (rawOut.containsKey("errors")) {
            throw new Exception(rawOut.errors.collect { it?.message }?.join(", "))
        }

        assert rawResponse.status == 204: "API returned unexpected output when removing Reviewer $userSlug from PR: $prId:" + rawResponse?.body?.toString()

        return true

    }

    /**
     * Add a reviewer to this pull request
     * @param user The user to add
     * @return A BitbucketPrParticipant representing the participant
     */
    BitbucketPrParticipant addReviewer(BitbucketUser user){

        String rawJsonResponse = addReviewer(instance.newUnirest, repo.projectKey, repo.slug, id, user.name)
        BitbucketPrParticipant participant = BitbucketPrParticipant.fromJson(rawJsonResponse,BitbucketPrParticipant, user.instance, user.instance).first() as BitbucketPrParticipant
        return  participant

    }


    /**
     * Add a reviewer to a pull request
     * @param unirest a Unirest instance to use to make the call
     * @param projectKey The project where the pr is
     * @param repositorySlug The repo where the pr is
     * @param prId The id of the PR
     * @param userName The userName of the user to add
     * @return A JSON string representing BitbucketPrParticipant
     */
    static String addReviewer(UnirestInstance unirest, String projectKey, String repositorySlug, long prId,String userName) {



        String url = "/rest/api/latest/projects/${projectKey}/repos/${repositorySlug}/pull-requests/$prId/participants"

        Map<String, Object> body = [
                user : [name: userName],
                role :"REVIEWER",
        ]


        HttpResponse<JsonNode> rawResponse = unirest.post(url).body(body).contentType("application/json").asJson()
        unirest.shutDown()


        Map rawOut = jsonPagesToGenerics(rawResponse.body)

        if (rawOut.containsKey("errors")) {
            throw new Exception(rawOut.errors.collect { it?.message }?.join(", "))
        }

        assert rawResponse.status == 200: "API returned unexpected output when adding Reviewer $userName to PR: $prId:" + rawResponse?.body?.toString()

        return rawResponse.body

    }


}

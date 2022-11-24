package com.eficode.atlassian.bitbucketInstanceManager.impl

import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest
import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest.BitbucketBranch
import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest.BitbucketRepo
import com.eficode.atlassian.bitbucketInstanceManager.model.BitbucketJsonEntity2
import kong.unirest.HttpResponse
import kong.unirest.UnirestInstance

class BitbucketPullRequest implements BitbucketJsonEntity2 {

    BitbucketRepo repo

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

        return repo instanceof BitbucketRepo && id && title && fromRef instanceof BitbucketBranch && toRef instanceof BitbucketBranch && author.size() >= 4

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

        UnirestInstance unirest = repo.parentObject.newUnirest
        String prJson = createPullRequest(title, description, fromRef, toBranch, repo.projectKey, repo.repositorySlug, unirest)

        ArrayList<BitbucketPullRequest> prs = fromJson(prJson, BitbucketPullRequest, repo.parentObject as BitbucketInstanceManagerRest, repo)

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

        assert responseRaw.status == 201: "Error creating Pull Request, API returned stauts ${responseRaw.status}, and body:" + responseRaw?.body

        return responseRaw.body

    }

    /** --- GET --- **/


    static ArrayList<BitbucketPullRequest> getPullRequests(BitbucketRepo repo, String state = "OPEN", String branchId = "") {

        UnirestInstance unirest = repo.parentObject.newUnirest
        String prJson = getPullRequests(repo.projectKey, repo.repositorySlug, unirest, state, branchId)

        ArrayList<BitbucketPullRequest> prs = fromJson(prJson, BitbucketPullRequest, repo.parentObject as BitbucketInstanceManagerRest, repo)

        if (prs.empty) {
            return prs
        }

        assert prs.every { it.isValid() }: " Library returned invalid object"
        return prs


    }

    static String getPullRequests(String projectKey, String repositorySlug, UnirestInstance unirest, String state = "OPEN", String branchId = "") {

        String url = "/rest/api/latest/projects/${projectKey}/repos/${repositorySlug}/pull-requests"

        Map<String, String> parameters = [:]
        state ? parameters.put("state", state) : ""
        branchId ? parameters.put("at", branchId) : ""

        HttpResponse responseRaw = unirest.get(url).queryString(parameters).asJson()

        assert responseRaw.status == 201: "Error getting Pull Requests from $projectKey/$repositorySlug, API returned stauts ${responseRaw.status}, and body:" + responseRaw?.body

        return responseRaw.body

    }


}

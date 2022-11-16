package com.eficode.atlassian.bitbucketInstanceManager


import kong.unirest.Cookie
import kong.unirest.Cookies
import kong.unirest.HttpResponse
import kong.unirest.JsonNode
import kong.unirest.Unirest
import kong.unirest.UnirestException
import kong.unirest.UnirestInstance
import kong.unirest.json.JSONArray
import org.eclipse.jgit.api.CloneCommand
import org.eclipse.jgit.api.CommitCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.TransportCommand
import org.eclipse.jgit.lib.StoredConfig
import org.eclipse.jgit.lib.TextProgressMonitor
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.transport.PushResult
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteConfig
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import unirest.shaded.com.google.gson.JsonObject
import unirest.shaded.com.google.gson.annotations.SerializedName

import java.nio.charset.StandardCharsets
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.regex.Matcher
import java.util.regex.Pattern


class BitbucketInstanceManagerRest {

    static Logger log = LoggerFactory.getLogger(BitbucketInstanceManagerRest.class)
    String adminUsername
    String adminPassword
    //JsonObjectMapper objectMapper
    String baseUrl

    BitbucketInstanceManagerRest(String username, String password, String baseUrl) {
        this.baseUrl = baseUrl
        this.adminUsername = username
        this.adminPassword = password


    }


    /** --- Generic Helper Methods --- **/
    UnirestInstance getNewUnirest() {
        return getNewUnirest(this.baseUrl, this.adminUsername, this.adminPassword)
    }

    static UnirestInstance getNewUnirest(String url, String user, String password) {

        UnirestInstance unirest = Unirest.spawnInstance()
        unirest.config().defaultBaseUrl(url)

        if (user && password) {
            unirest.config().setDefaultBasicAuth(user, password)
        }

        return unirest
    }

    static Cookies extractCookiesFromResponse(HttpResponse response, Cookies existingCookies = null) {

        if (existingCookies == null) {
            existingCookies = new Cookies()
        }

        response.headers.all().findAll { it.name == "Set-Cookie" }.each {

            String name = it.value.split(";")[0].split("=")[0]
            String value = it.value.split(";")[0].split("=")[1]

            existingCookies.removeAll { it.name == name }
            existingCookies.add(new Cookie(name, value))

        }


        return existingCookies

    }

    static String resolveRedirectPath(HttpResponse response, String previousPath = null) {

        String newLocation = response.headers.getFirst("Location")
        if (!newLocation) {
            return null
        } else if (!newLocation.startsWith("/")) {
            newLocation = previousPath.substring(0, previousPath.lastIndexOf("/") + 1) + newLocation
        }

        return newLocation

    }

    String searchBodyForAtlToken(String body) {

        Pattern pattern = Pattern.compile(/<input type="hidden" name="atl_token" value="(.*?)"/)
        Matcher matcher = pattern.matcher(body)

        String token = matcher.size() ? matcher[0][1] : ""

        return token

    }


    ArrayList getJsonPages(String subPath, long maxPages, boolean returnValueOnly = true) {

        return getJsonPages(newUnirest, subPath, maxPages, returnValueOnly)
    }


    static ArrayList getJsonPages(UnirestInstance unirest, String subPath, long maxResponses, boolean returnValueOnly = true) {


        int start = 0
        boolean isLastPage = false

        ArrayList responses = []

        while (!isLastPage && start >= 0) {


            //Extra loop protection


            if (maxResponses != 0 && responses.size() > maxResponses) {
                log.warn("Returned more than expected responses (${responses.size()}) when querying:" + subPath)
                responses = responses[0..maxResponses-1]
                break
            }



            HttpResponse<JsonNode> response = unirest.get(subPath).accept("application/json").queryString("start", start).asJson()


            isLastPage = response?.body?.object?.has("isLastPage") ? response?.body?.object?.get("isLastPage") as boolean : true
            //start = response?.body?.object?.has("nextPageStart") ? response?.body?.object?.get("nextPageStart") as int : -1
            start = response?.body?.object?.has("nextPageStart") && response.body.object["nextPageStart"] != null ? response.body.object["nextPageStart"] as int : -1

            if (returnValueOnly) {
                if (response.body.object.has("values")) {
                    responses += response.body.object.get("values") as ArrayList<Map>
                } else {
                    throw new InputMismatchException("Unexpected body returned from $subPath, expected JSON with \"values\"-node but got: " + response.body.toString())
                }

            } else {
                responses += response.body
            }


        }

        unirest.shutDown()
        return responses


    }


    /** --- Instance Methods --- **/

    boolean setApplicationProperties(String bbLicense, String appTitle = "Bitbucket", String baseUrl = this.baseUrl) {
        log.info("Setting up initial application properties")

        UnirestInstance unirestInstance = getNewUnirest(this.baseUrl, null, null)

        unirestInstance.config().socketTimeout(1000)

        long startTime = System.currentTimeMillis()

        while (startTime + (5 * 60000) > System.currentTimeMillis()) {
            try {
                HttpResponse<String> response = unirestInstance.get("/setup").accept("text/html").asString()


                if (response?.body?.contains("<span>Database</span>")) {
                    log.info("\tThe Setup dialog has appeared")
                    break
                } else {
                    log.info("\tBitbucket has started but the Setup dialog has not appeared yet, waited ${((System.currentTimeMillis() - startTime) / 1000).round(0)}s")
                    sleep(5000)
                }

            } catch (UnirestException ex) {

                log.info("---- Bitbucket not available yet ----")
                log.debug("\tGot error when trying to access bitbucket:" + ex.message)
                sleep(1000)
            }
        }


        unirestInstance.shutDown()
        unirestInstance = getNewUnirest(this.baseUrl, null, null)
        Cookies cookies = unirestInstance.get("/setup").asString().cookies
        assert cookies.find { it.name == "BITBUCKETSESSIONID" }
        HttpResponse bodyString = unirestInstance.get("/setup").cookie(cookies).asString()

        String atlToken = searchBodyForAtlToken(bodyString.body)
        HttpResponse setDBResponse = unirestInstance.post("/setup")
                .cookie(cookies)
                .field("atl_token", atlToken)
                .field("locale", "en_US")
                .field("step", "database")
                .field("internal", "true")
                .field("type", "postgres")
                .field("hostname", "")
                .field("port", "5432")
                .field("database", "")
                .field("username", "")
                .field("password", "")
                .field("submit", "next")
                .asString()

        assert setDBResponse.status == 302: "Error setting local database"
        log.info("\tFinished setting up local database")

        HttpResponse setPropResponse = unirestInstance.post("/setup")
                .cookie(cookies)
                .field("step", "settings")
                .field("applicationTitle", appTitle)
                .field("baseUrl", baseUrl)
                .field("license", bbLicense)
                .field("licenseDisplay", bbLicense)
                .field("submit", "next")
                .field("atl_token", atlToken)
                .asString()
        assert setPropResponse.status == 302: "Error setting application properties or license"
        log.info("\tFinished setting up licence")

        HttpResponse setAdminResponse = unirestInstance.post("/setup")
                .cookie(cookies)
                .field("step", "user")
                .field("atl_token", atlToken)
                .field("username", adminUsername)
                .field("fullname", adminUsername)
                .field("email", adminUsername + "@" + adminUsername + ".com")
                .field("password", adminPassword)
                .field("confirmPassword", adminPassword)
                .field("skipJira", "Go to Bitbucket")
                .asString()

        assert setAdminResponse.status == 302: "Error setting admin account"
        log.info("\tFinished setting up admin account, you should be able to reach Bitbucket on:" + baseUrl)

        unirestInstance.shutDown()
        return true
    }

    String getStatus() {

        Map statusMap = newUnirest.get("/status").asJson()?.body?.object?.toMap()

        return statusMap?.state
    }


    /** --- Project CRUD --- **/

    ArrayList<BitbucketProject> getProjects(long maxProjects = 25) {


        ArrayList rawProjects = getJsonPages("/rest/api/latest/projects", maxProjects)
        ArrayList<BitbucketProject> projects = BitbucketProject.fromJson(rawProjects.toString(), BitbucketProject, this)
        return projects

    }


    BitbucketProject getProject(String projectKey) {
        ArrayList<JsonNode> rawProject = getJsonPages("/rest/api/1.0/projects/$projectKey" as String, 1, false) as ArrayList<JsonNode>


        if (rawProject.toString().contains("Project $projectKey does not exist")) {
            return null
        }

        assert rawProject.size() == 1: "Error getting project with key:" + projectKey

        return BitbucketProject.fromJson(rawProject.first().toString(), BitbucketProject, this).find { it.valid } as BitbucketProject
    }

    boolean deleteProject(BitbucketProject project, boolean deleteRepos = false) {

        return deleteProject(project.key, deleteRepos)
    }

    /**
     * Deletes project, will fail if the project has repos and deleteRepos is false
     * @param projectKey
     * @param deleteProjectRepos if true, will delete repos if present in project
     * @return
     */
    boolean deleteProject(String projectKey, boolean deleteProjectRepos = false) {

        log.info("Deleting project:" + projectKey)
        UnirestInstance unirest = newUnirest

        HttpResponse<JsonNode> response = unirest.delete("/rest/api/latest/projects/$projectKey").asJson()


        if (response.body.object.has("errors")) {
            JSONArray errors = response.body.object.errors
            ArrayList<String> messages = errors.collect { it.message }

            if (deleteProjectRepos && messages.size() == 1 && messages.first().contains("The project \"$projectKey\" cannot be deleted because it has repositories")) {
                log.info("\tProject has repositories, deleting them now")

                ArrayList<BitbucketRepo> projectRepos = getRepos(projectKey)
                log.info("\t\tRepos:" + projectRepos.name.join(", "))

                assert deleteRepos(projectRepos): "Error deleting project repos"

                log.info("\tFinished deleting project repositories, deleting project")
                response = unirest.delete("/rest/api/latest/projects/$projectKey").asJson()
            } else {
                throw new Exception("Error deleting project $projectKey:" + messages.join(","))
            }


        }
        assert response.status == 204: "Deletion of project returned unexpected HTTP status: " + response.status
        unirest.shutDown()

        log.info("\tFinished deleting project:" + projectKey)
        return true

    }

    /**
     * Uses the public REST API available for creating a project, this only allows for setting project key
     * @param key Key of the new project
     * @return A BitbucketProject representation of the new project
     */
    BitbucketProject createProject(String key) {

        UnirestInstance unirest = newUnirest

        HttpResponse<JsonNode> response = unirest.post("/rest/api/latest/projects").contentType("application/json").body([key: key]).asJson()

        assert response.status == 201: "Creation of project returned unexpected HTTP status: " + response.status
        assert response.body.object.get("key") == key: "Creation of project returned unexpected JSON: " + response.body

        unirest.shutDown()


        return getProject(key)


    }

    /**
     * This creates a project using private APIs as the native APIs doesn't support setting name or description
     * This should still be safe, but might break after upgrades, for longevity use createProject(String key)
     * @param name Project name
     * @param key Project key
     * @param description An optional description
     * @return A BitbucketProject representation of the new project
     */
    BitbucketProject createProject(String name, String key, String description = "") {

        UnirestInstance unirest = newUnirest
        unirest.config().followRedirects(false)
        String createProjectBody = unirest.get("/projects?create").asString().body


        String atlToken = searchBodyForAtlToken(createProjectBody)
        assert atlToken: "Could not find token for form submition"

        HttpResponse response = unirest.post("/projects?create")
                .field("name", name)
                .field("key", key)
                .field("avatar", "")
                .field("description", description)
                .field("submit", "Create project")
                .field("atl_token", atlToken).asEmpty()

        assert response.status == 302: "Creation of project returned unexpected HTTP status: " + response.status
        assert response.headers.get("Location").first().endsWith("/$key"): "Creation of project returned unexpected redirect:" + response?.headers?.get("Location")

        unirest.shutDown()
        return getProject(key)


    }


    /** --- Repo CRUD --- **/

    BitbucketRepo getRepo(String projectKey, String repoNameOrSlug) {
        ArrayList<BitbucketRepo> projectRepos = getRepos(projectKey)

        BitbucketRepo repo = projectRepos.find { it.name == repoNameOrSlug || it.slug == repoNameOrSlug }

        assert repo: "Error, could not find repo \"$repoNameOrSlug\" in project \"$projectKey\" "
        return projectRepos.find { it.name == repoNameOrSlug || it.slug == repoNameOrSlug }

    }

    ArrayList<BitbucketRepo> getRepos(String projectKey, long maxRepos = 50) {
        ArrayList<JsonObject> rawRepos = getJsonPages("/rest/api/1.0/projects/${projectKey}/repos", maxRepos) as ArrayList<JsonNode>


        ArrayList<BitbucketRepo> repos = BitbucketRepo.fromJson(rawRepos.toString(), BitbucketRepo, this)

        return repos
    }

    ArrayList<BitbucketRepo> getRepos(BitbucketProject project) {

        return getRepos(project.key)
    }


    BitbucketRepo createRepo(BitbucketProject project, String repoName) {

        return createRepo(project.key, repoName)
    }

    BitbucketRepo createRepo(String projectKey, String repoName) {

        log.info("Creating repo $repoName in project: " + projectKey)
        UnirestInstance unirest = newUnirest

        HttpResponse<JsonNode> response = unirest.post("/rest/api/latest/projects/${projectKey}/repos").contentType("application/json").body(name: repoName).asJson()


        assert response.status == 201: "Got unexpected response when creating repo $repoName in project: $projectKey"

        String slug = response.body.object.slug
        log.info("\tRepo created with slug:" + slug)

        return getRepo(projectKey, response.body.object.slug)
    }

    boolean deleteRepos(ArrayList<BitbucketRepo> repos) {

        UnirestInstance unirest = newUnirest
        repos.each { bitbucketRepo ->
            assert bitbucketRepo instanceof BitbucketRepo && bitbucketRepo.isValid(): "Error deleting bitbucket repo, was supplied an invalid repo object:" + bitbucketRepo.toString()

            HttpResponse response = unirest.delete("/rest/api/latest/projects/${bitbucketRepo.project.key}/repos/${bitbucketRepo.slug}").asEmpty()

            assert response.status == 202: "Error deleting repo ($bitbucketRepo), request returned unexpected HTTP status"
        }
        unirest.shutDown()
        return true
    }

    boolean deleteRepo(BitbucketRepo bitbucketRepo) {

        return deleteRepos([bitbucketRepo])
    }


    /** --- GIT CRUD --- **/


    /**
     * Pushes the current check out branch in $localRepoDir to remoteRepo
     * If the repo in $localRepoDir doesnt have a remote setup for $remoteRepo, one will be setup
     * Push will be performed using $adminUsername
     * @param localRepoDir A directory with a git repo
     * @param remoteRepo The remote Bitbucket repo to push to.
     * @return true on success
     */
    boolean pushToRepo(File localRepoDir, BitbucketRepo remoteRepo, boolean pushAllBranches = false) {

        log.info("Pushing local repo to remote repo")
        log.info("\tLocal repo:..." + localRepoDir.absolutePath.takeRight(50))

        Git localRepo = Git.open(localRepoDir)
        URIish suppliedRepoUrl = new URIish(remoteRepo.links.get("clone").find { it.name.contains("http") }.href as String)

        log.info("\tRemote repo:" + suppliedRepoUrl.toString())


        List<RemoteConfig> remoteList = localRepo.remoteList().call()

        RemoteConfig existingRemote = remoteList.find { remote ->
            remote.getURIs().any { it.path == suppliedRepoUrl.path && it.host == suppliedRepoUrl.host }
        }

        if (existingRemote) {
            log.info("\tLocal repo already has the needed git remote:" + existingRemote.toString())
        } else {
            log.info("\tLocal repo is missing git remote, adding it now")

            assert localRepo.remoteAdd().setUri(suppliedRepoUrl).setName(remoteRepo.name).call(): "Error adding new remote:" + suppliedRepoUrl.toString()
            existingRemote = localRepo.remoteList().call().find { it.name == remoteRepo.name }

            assert existingRemote: "Error finding Git Remote after adding it"

            log.info("\t\tFinished adding new git remote")
        }

        TransportCommand pushCommand = localRepo.push().setRemote(existingRemote.name).setCredentialsProvider(new UsernamePasswordCredentialsProvider(adminUsername, adminPassword))


        if (pushAllBranches) {
            ArrayList<RefSpec> branches = localRepo.branchList().call().collect { new RefSpec(it.getName()) }
            pushCommand.setRefSpecs(branches)
            log.debug("\tPushing branches: " + branches.collect { it.toString() }.join(","))
        }

        if (log.isEnabledForLevel(Level.INFO)) {
            pushCommand.setProgressMonitor(new TextProgressMonitor(new PrintWriter(System.out)))
        }

        log.info("\tStarting push")
        ArrayList<PushResult> results = pushCommand.call() as ArrayList<PushResult>
        log.info("\tFinished push")
        log.debug("\t\tOutput:" + results.messages.join(","))

        return !results.empty


    }


    /**
     * Mirrors a repo (downloads all branches and their data) and checks out the apparent main branch
     * @param outputDir an empty dir to checkout/mirror to
     * @param srcUrl Url to mirror from
     * @return true on success
     */
    static boolean mirrorRepo(File outputDir, String srcUrl) {

        log.info("Performing Git mirror clone")
        log.info("\tSource url:" + srcUrl)
        log.info("\tLocal destination folder:" + outputDir.absoluteFile.absolutePath)

        log.debug("\tStarting clone")
        long cloneStarted = System.currentTimeSeconds()
        Git git
        CloneCommand cloneCommand = Git.cloneRepository().setURI(srcUrl).setDirectory(new File(outputDir.canonicalPath + "/.git")).setMirror(true)

        if (log.isEnabledForLevel(Level.INFO)) {
            cloneCommand.setProgressMonitor(new TextProgressMonitor(new PrintWriter(System.out)))
        }

        Thread cloneThread = Thread.start { git = cloneCommand.call() }


        while (cloneThread.isAlive()) {
            sleep(2000)

            log.debug("\t\tClone in progress, so far cloned: " + (outputDir.directorySize() / (1024 * 1024)).round() + "MBytes")

        }

        log.debug("\tFinished clone after ${System.currentTimeSeconds() - cloneStarted}s")
        log.debug("\tGot " + (outputDir.directorySize() / (1024 * 1024)).round() + "MB")


        //Set bare to false
        log.debug("\tSetting directory to bare=false")
        StoredConfig config = git.repository.getConfig()
        config.setBoolean("core", null, "bare", false)
        config.save()

        //Try and determine remotes main branch, if it fails default to master
        log.debug("\tDetermining main branch")
        String mainBranch = git.lsRemote().call().find { it.name == "HEAD" }?.target?.name ?: "master"
        mainBranch = mainBranch.takeRight(mainBranch.length() - mainBranch.lastIndexOf("/") - 1)
        log.debug("\t\tMain Branch:" + mainBranch)

        log.info("\tChecking out main branch" + mainBranch)
        //Reopen repo
        git = Git.open(outputDir)
        //Checkout main branch
        git.checkout().setName(mainBranch).call()
        log.info("\tFinished mirroring Git repo.")

        return true

    }


    static RevCommit addAndCommit(File localRepoDir, String message, String filePattern = ".", String authorName = "", String authorMail = "") {

        Git localRepo = Git.open(localRepoDir)
        localRepo.add().addFilepattern(filePattern).call()
        CommitCommand commitCommand = localRepo.commit().setMessage(message)
        if (authorName && authorMail) {
            commitCommand.setAuthor(authorName, authorMail)
        }

        return commitCommand.call()


    }


    class BitbucketChange implements BitbucketJsonEntity {


        String contentId
        String fromContentId
        Map path = [components: [], parent: "", name: "", extension: "", toString: ""]
        boolean executable
        long percentUnchanged
        String type
        String nodeType
        boolean srcExecutable
        Map<String, ArrayList> links = ["self": [[href: ""]]]
        Map properties = [gitChangeType: ""]
        BitbucketCommit commit


        boolean isValid() {

            return contentId && parentObject instanceof BitbucketInstanceManagerRest && commit.isValid()

        }

        String getActionSymbol() {

            switch (type) {
                case "MODIFY":
                    return "📝"
                case "ADD":
                    return "➕"
                case "DELETE":
                    return "🗑️"
                case "COPY":
                    return "📋"
                case "MOVE":
                    return "🗂️"
                default:
                    return "❓ - " + type
            }


        }


        String getFileNameTruncated(int maxLen) {

            assert maxLen > 4
            String completeName = path.toString

            if (completeName.length() <= maxLen) {
                return completeName
            }

            return "..." + completeName.takeRight(maxLen - 3)

        }


        static getMarkdownHeader() {

            return "| Action | File |\n|--|--|\n"

        }

        static getMarkdownFooter() {

            return "\n\n➕ - Added\t📝 - Modified\t📋 - Copied\t🗂️ - Moved\t🗑 - Deleted\t"

        }

        String toMarkdown() {

            String out = "|  " + actionSymbol + "  | [${getFileNameTruncated(60)}](${links.self.href.first()}) |" + "\n"


            return out

        }


    }


    public class BitbucketCommit implements BitbucketJsonEntity {

        String id
        String displayId
        CommitUser author
        long authorTimeStamp
        CommitUser committer
        long committerTimestamp
        String message


        ArrayList<Map> parents = [["id": "", "displayId": ""]]
        //ArrayList<Map<String,String>> parents = [["id": "", "displayId": ""]]
        public BitbucketRepo repository

        static final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")


        class CommitUser {
            String name
            String emailAddress


            String getProfileUrl(String baseUrl) {

                return baseUrl + "/users/" + URLEncoder.encode(name, StandardCharsets.UTF_8)
            }
        }


        boolean isValid() {

            return id && displayId && message && parentObject instanceof BitbucketInstanceManagerRest && repository.isValid()

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

        String toMarkdown() {


            String mainOut = "## Commit ID: " + displayId + (isAMerge() ? " " + mergerSymbol : "") + "\n" +
                    "**Author:** [${author.name}](${author.getProfileUrl(baseUrl)}) \n\n" +
                    "**Timestamp:** " + (committerTimestamp != 0 ? dateFormat.format(new Date(committerTimestamp as long)) : dateFormat.format(new Date(authorTimeStamp as long))) + "\n\n" +
                    "**Parents:** " + parents.displayId.join(", ") + "\n\n"
                    "**Message:**\n\n"


            message.eachLine {mainOut += "\t" +it  + "\n"}

            mainOut += "\n\n"

            String changesOut = BitbucketChange.markdownHeader
            changes.each { changesOut += it.toMarkdown() }
            changesOut += BitbucketChange.markdownFooter


            return mainOut + changesOut

        }


        ArrayList<BitbucketChange> getChanges(long maxChanges = 150) {
            return getChanges(repository.project.key, repository.slug, id, maxChanges)
        }


        ArrayList<BitbucketChange> getChanges(String projectKey, String repoSlug, String commitId, long maxChanges) {


            log.info("Getting changes for commit:")
            log.info("\tProject:" + projectKey)
            log.info("\tRepo:" + repoSlug)
            log.info("\tCommit:" + commitId)


            ArrayList<BitbucketChange> result = fromJson(getRawChanges(newUnirest, projectKey, repoSlug, commitId, maxChanges).toString(), BitbucketChange, parentObject) as ArrayList<BitbucketChange>

            if (result.isEmpty() && this.isAMerge()) {
                log.info("\tCommit has no changes but have been confirmed to be a Merge-commit, fetching changes performed by merge")
                result = fromJson(getRawChanges(newUnirest, projectKey, repoSlug, commitId, maxChanges, parents.first().id as String).toString(), BitbucketChange, parentObject) as ArrayList<BitbucketChange>
            }

            result.each { it.commit = this }

            return result

        }


        static ArrayList getRawChanges(UnirestInstance instance, String projectKey, String repoSlug, String commitId, long maxChanges, String since = null ) {

            String url = "/rest/api/1.0/projects/$projectKey/repos/$repoSlug/commits/$commitId/changes"

            since ? url += "?since=$since"  : ""

            return getJsonPages(instance, url, maxChanges)

        }


        String getBranchId() {
            return branchRaw.id
        }

        String getBranchName() {
            return branchRaw.name
        }

        Map getBranchRaw() {

            String url = "/rest/branch-utils/latest/projects/${repository.project.key}/repos/${repository.slug}/branches/info/${displayId}"

            ArrayList<Map> branches = getJsonPages(newUnirest, url, 1)
            assert branches.size() == 1: "Error finding branch for Commit ${displayId}, API returned ${branches.size()} branches"

            return branches.first() as Map
        }


    }


    class BitbucketRepo implements BitbucketJsonEntity {


        public String slug
        public String id
        public String name
        public String hierarchyId
        public String scmId
        public String state
        public String statusMessage
        public boolean forkable
        public BitbucketProject project

        @SerializedName("public")
        public boolean isPublic
        public boolean archived
        public Map<String, ArrayList> links = ["clone": [[:]], "self": [[:]]]


        @Override
        boolean isValid() {

            return slug && id && name && hierarchyId && project?.isValid() && parentObject instanceof BitbucketInstanceManagerRest


        }

        String toString() {
            return project?.name + "/" + name
        }

        boolean equals(Object object) {

            return object instanceof BitbucketRepo && this.name == object.name && this.id == object.id
        }


        /**
         * Get Commits from repo
         * @param fromId Get all commits starting from this commit (not inclusive)
         * @param toId Get all commits until this commit
         * @return An array of commit objects
         */
        ArrayList<BitbucketCommit> getCommits(String fromId = "", String toId = "", long maxCommits) {


            UnirestInstance instance = getNewUnirest()

            ArrayList<String> urlParameters = []

            fromId ? urlParameters.add("since=$fromId") : null
            toId ? urlParameters.add("until=$toId") : null

            String url = "/rest/api/latest/projects/${project.key}/repos/${slug}/commits"

            urlParameters ? (url += "?" + urlParameters.join("&")) : null

            ArrayList rawCommits = getJsonPages(instance, url, maxCommits, true)


            ArrayList<BitbucketCommit> bitbucketCommits = fromJson(rawCommits.toString(), BitbucketCommit, parentObject)
            bitbucketCommits.each { it.repository = this }
            return bitbucketCommits.sort { it.authorTimeStamp }


        }

        BitbucketCommit getCommit(String commitId) {

            String url = "/rest/api/latest/projects/${project.key}/repos/${slug}/commits/" + commitId

            UnirestInstance instance = getNewUnirest()
            ArrayList rawCommits = getJsonPages(instance, url, 1, false )

            assert rawCommits.size() == 1 : "Error getting commit $commitId, API returned ${rawCommits.size()} matches"


            ArrayList<BitbucketCommit> bitbucketCommits = fromJson(rawCommits.toString(), BitbucketCommit, parentObject)
            bitbucketCommits.each { it.repository = this }

            BitbucketCommit commit = bitbucketCommits.first()

            //Remove all but id and displayId, to return same value has getCommits()
            commit.parents.each {parentMap ->
                parentMap.removeAll {key, value ->
                   ! (key as String in ["id", "displayId"])
                }
            }



            return commit

        }


    }


    class BitbucketProject implements BitbucketJsonEntity {

        String key
        String id
        String name
        String type
        Map<String, ArrayList> links
        //static JsonObjectMapper objectMapper = Unirest.config().getObjectMapper() as JsonObjectMapper

        @SerializedName("public")
        boolean isPublic


        boolean equals(Object object) {

            return object instanceof BitbucketProject && this.key == object.key && this.id == object.id
        }

        boolean isValid() {

            return key && id && name && type && parentObject instanceof BitbucketInstanceManagerRest

        }


    }


}
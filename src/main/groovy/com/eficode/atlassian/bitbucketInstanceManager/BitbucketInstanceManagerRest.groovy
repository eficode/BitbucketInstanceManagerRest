package com.eficode.atlassian.bitbucketInstanceManager

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kong.unirest.Cookie
import kong.unirest.Cookies
import kong.unirest.HttpResponse
import kong.unirest.JsonNode
import kong.unirest.MultipartBody
import kong.unirest.Unirest
import kong.unirest.UnirestException
import kong.unirest.UnirestInstance
import kong.unirest.json.JSONArray
import org.eclipse.jgit.api.CloneCommand
import org.eclipse.jgit.api.CommitCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.TransportCommand
import org.eclipse.jgit.api.errors.TransportException
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
    static Gson gson = new Gson()

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


    ArrayList<JsonNode> getJsonPages(String subPath, long maxPages, boolean returnValueOnly = true) {

        return getJsonPages(newUnirest, subPath, maxPages, returnValueOnly)
    }

    static ArrayList<Map> jsonPagesToGenerics(ArrayList jsonPages) {

        return gson.fromJson(jsonPages.toString(), TypeToken.getParameterized(ArrayList.class, Map).getType())
    }

    static Map jsonPagesToGenerics(JsonNode jsonNode) {

        return gson.fromJson(jsonNode.toString(), TypeToken.get(Map).getType())
    }


    static ArrayList<JsonNode> getJsonPages(UnirestInstance unirest, String subPath, long maxResponses, boolean returnValueOnly = true) {


        int start = 0
        boolean isLastPage = false

        ArrayList responses = []

        while (!isLastPage && start >= 0) {


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

            if (maxResponses != 0) {
                if (responses.size() > maxResponses) {
                    log.warn("Returned more than expected responses (${responses.size()}) when querying:" + subPath)
                    responses = responses[0..maxResponses - 1]
                    break
                } else if (responses.size() == maxResponses) {
                    break
                }
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

    Map getApplicationProperties() {

        ArrayList<JsonNode> rawOut = getJsonPages("/rest/api/latest/application-properties", 1, false)

        return jsonPagesToGenerics(rawOut.first())

    }

    String getStatus() {

        Map statusMap = newUnirest.get("/status").asJson()?.body?.object?.toMap()

        return statusMap?.state
    }

    String getLicense() {

        ArrayList rawOut = getJsonPages("/rest/api/latest/admin/license", 1, false)

        return jsonPagesToGenerics(rawOut)?.first()?.license

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
            log.info("\tLocal repo already has the needed git remote:" + existingRemote.name)
        } else {
            log.info("\tLocal repo is missing git remote, adding it now")

            assert localRepo.remoteAdd().setUri(suppliedRepoUrl).setName(remoteRepo.name.replace(" ", "_")).call(): "Error adding new remote:" + suppliedRepoUrl.toString()
            existingRemote = localRepo.remoteList().call().find { it.name == remoteRepo.name.replace(" ", "_") }

            assert existingRemote: "Error finding Git Remote after adding it"

            log.info("\t\tFinished adding new git remote")
        }

        TransportCommand pushCommand = localRepo.push().setRemote(existingRemote.name).setCredentialsProvider(new UsernamePasswordCredentialsProvider(adminUsername, adminPassword))


        ArrayList<RefSpec> branches = []
        if (pushAllBranches) {
            branches = localRepo.branchList().call().collect { new RefSpec(it.getName()) }
            pushCommand.setRefSpecs(branches)
            log.debug("\tPushing branches: " + branches.collect { it.toString() }.join(","))
        }

        if (log.isEnabledForLevel(Level.INFO)) {
            pushCommand.setProgressMonitor(new TextProgressMonitor(new PrintWriter(System.out)))
        }

        log.info("\tStarting push")
        ArrayList<PushResult> results
        try {
            results = pushCommand.call() as ArrayList<PushResult>
        } catch (TransportException ex) {
            if (ex.message.contains("Short read of block")) {
                log.info("\t\tPush error due to a bug, triggering it again.")
                //Appears to be a bug in Jgit, running it once more solves the problem
                pushCommand = localRepo.push().setRemote(existingRemote.name).setCredentialsProvider(new UsernamePasswordCredentialsProvider(adminUsername, adminPassword))
                if (pushAllBranches) {
                    pushCommand.setRefSpecs(branches)
                }
                results = pushCommand.call() as ArrayList<PushResult>
            } else {
                throw ex
            }
        }

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

        log.info("\tChecking out main branch: " + mainBranch)
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


    class BitbucketBranch implements BitbucketJsonEntity {


        String id
        String displayId
        String type
        String latestCommit
        String latestChangeset
        boolean isDefault
        BitbucketRepo repo

        boolean isValid() {

            return id && latestCommit && latestChangeset && repo && (type == "BRANCH")
        }

        /**
         * Returns the branch type
         * @return ex: hotfix, feature, etc
         */
        String getBranchType() {

            if (displayId.contains("/")) {
                return displayId.replaceFirst(/\/.*$/, "")
            }
            else {
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

            ArrayList<BitbucketBranch> matches = findBranches(branchName, 25, repo)

            assert matches.size() == 1 : "Error getting branch, ID matches ${matches.size()} branches. Name: $branchName, Repo: ${repo.name}"


            return matches.first()
        }

        static ArrayList<BitbucketBranch> getAllBranches(BitbucketRepo repo, long maxBranches = 25) {

            return findBranches("", maxBranches, repo)
        }

        static ArrayList<BitbucketBranch> findBranches(String filter = "", long maxMatches = 25, BitbucketRepo repo) {

            String url = "/rest/api/latest/projects/${repo.projectKey}/repos/${repo.repositorySlug}/branches"

            if (filter) {
                url += "?filterText=" + filter
            }

            BitbucketInstanceManagerRest instance = repo.parentObject as BitbucketInstanceManagerRest
            ArrayList rawResponse = instance.getJsonPages(url,maxMatches)


            ArrayList<BitbucketBranch> branches = fromRaw(rawResponse, repo)
            return branches


        }

        static ArrayList<BitbucketBranch> fromRaw(ArrayList rawBranches, BitbucketRepo repo) {

            ArrayList<BitbucketBranch> branches = fromJson(rawBranches.toString(),BitbucketBranch, repo.parentObject)

            branches.each {branch ->
                branch.repo = repo
            }

            return branches


        }


    }


    class BitbucketCommit implements BitbucketJsonEntity {

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

        static ArrayList<BitbucketCommit> fromRaw(ArrayList rawCommits, BitbucketRepo repo) {
            ArrayList<BitbucketCommit> bitbucketCommits = fromJson(rawCommits.toString(), BitbucketCommit, repo.parentObject)
            bitbucketCommits.each { commit ->
                //Set repo
                commit.repository = repo
                //Remove all but id and displayId, as getCommit and getCommits return different amount of info
                commit.parents.each { parentMap ->
                    parentMap.removeAll { key, value ->
                        !(key as String in ["id", "displayId"])
                    }
                }

            }

            return bitbucketCommits

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

        /**
         * Return a MarkDown representation (optimized for bitbucket) of the commit including:
         * Commit it, Author, Timestamp, Parents (commits ids) and commit message
         * @return
         */
        String toMarkdown() {


            String mainOut = "## Commit ID: " + displayId + (isAMerge() ? " " + mergerSymbol : "") + "\n" +
                    "**Author:** [${author.name}](${author.getProfileUrl(baseUrl)}) \n\n" +
                    "**Timestamp:** " + (committerTimestamp != 0 ? dateFormat.format(new Date(committerTimestamp as long)) : dateFormat.format(new Date(authorTimeStamp as long))) + "\n\n" +
                    "**Parents:** " + parents.displayId.join(", ") + "\n\n"
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


            ArrayList<BitbucketChange> result = fromJson(getRawChanges(newUnirest, projectKey, repoSlug, commitId, maxChanges).toString(), BitbucketChange, parentObject) as ArrayList<BitbucketChange>

            if (result.isEmpty() && this.isAMerge()) {
                log.info("\tCommit has no changes but have been confirmed to be a Merge-commit, fetching changes performed by merge")
                result = fromJson(getRawChanges(newUnirest, projectKey, repoSlug, commitId, maxChanges, parents.first().id as String).toString(), BitbucketChange, parentObject) as ArrayList<BitbucketChange>
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

        String getProjectKey() {
            return project.key
        }

        String getRepositorySlug() {
            return slug
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

            ArrayList rawOut = getJsonPages("/rest/api/latest/projects/${project.key}/repos/${slug}/default-branch", 1, false)
            assert rawOut.size() == 1: "Error getting default branch for repo $name, API returned:" + rawOut


            return BitbucketBranch.fromRaw(rawOut, this).first()

        }


        /**
         * Create a new branch
         * @param branchName Name of new branch
         * @param branchFrom ex: refs/heads/master, master,
         * @param branchType (Optional) ex: bugfix, hotfix, feature
         *              If branchType doesnt already exist in repo, it will be created
         * @return Full branch name: ex:  refs/heads/new-branch, refs/heads/hotfix/new-hotfix
         */
        BitbucketBranch createBranch(String branchName,String branchFrom, String branchType = null) {

            UnirestInstance unirest = newUnirest

            Map body = [

                    name: (branchType ? branchType +"/" : "") + branchName,
                    startPoint : branchFrom
            ]

            HttpResponse<JsonNode> rawResponse = unirest.post("/rest/branch-utils/latest/projects/${projectKey}/repos/${repositorySlug}/branches").body(body).contentType("application/json").asJson()
            Map rawOut = jsonPagesToGenerics(rawResponse.body)

            if (rawOut.containsKey("errors")) {
                throw new Exception(rawOut.errors.collect { it?.message }?.join(", "))
            }

            assert rawResponse.status == 201 : "API returned unexpected output when creating new branch:" + rawResponse?.body?.toString()

            unirest.shutDown()

            return BitbucketBranch.fromRaw([rawResponse.body.toString()], this).first()

        }



        ArrayList<BitbucketBranch> getAllBranches(long maxBranches = 25) {

            return BitbucketBranch.getAllBranches(this, maxBranches)
        }

        ArrayList<BitbucketBranch> findBranches(String filter = "", long maxMatches = 25) {

            return BitbucketBranch.findBranches(filter, maxMatches, this)
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


            UnirestInstance instance = getNewUnirest()

            ArrayList<String> urlParameters = []

            fromId ? urlParameters.add("since=$fromId") : null
            toId ? urlParameters.add("until=$toId") : null

            String url = "/rest/api/latest/projects/${project.key}/repos/${slug}/commits"

            urlParameters ? (url += "?" + urlParameters.join("&")) : null

            ArrayList rawCommits = getJsonPages(instance, url, maxCommits, true)

            return BitbucketCommit.fromRaw(rawCommits, this)


        }

        BitbucketCommit getLastCommitInBranch(String branchName) {

            String parameter = "?limit=1&until=" + URLEncoder.encode("refs/heads/$branchName", StandardCharsets.UTF_8)
            String url = "/rest/api/latest/projects/${project.key}/repos/${slug}/commits" + parameter

            ArrayList rawCommits = getJsonPages(newUnirest, url, 1)


            return BitbucketCommit.fromRaw(rawCommits, this).first()


        }

        BitbucketCommit getCommit(String commitId) {

            String url = "/rest/api/latest/projects/${project.key}/repos/${slug}/commits/" + commitId

            UnirestInstance instance = getNewUnirest()
            ArrayList rawCommits = getJsonPages(instance, url, 1, false)

            assert rawCommits.size() == 1: "Error getting commit $commitId, API returned ${rawCommits.size()} matches"


            BitbucketCommit commit = BitbucketCommit.fromRaw(rawCommits, this).first()
            return commit

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


            Map rawOut = jsonPagesToGenerics(getJsonPages(url, 1, false).first())

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

            BitbucketCommit newCommit = BitbucketCommit.fromRaw([response.body], this).first()


            return newCommit
        }

        /** --- Pull Request Config CRUD --- **/

        boolean enableAllMergeStrategies(String defaultStrategy = "no-ff") {
            return setEnabledMergeStrategies(defaultStrategy, ["no-ff", "ff", "ff-only", "rebase-no-ff", "rebase-ff-only", "squash", "squash-ff-only"])
        }

        boolean setEnabledMergeStrategies(String defaultStrategy, ArrayList<String> enabledStrategies) {

            log.info("Setting enabled Merge strategies for repo " + name)
            ArrayList<String> possibleStrategies = ["no-ff", "ff", "ff-only", "rebase-no-ff", "rebase-ff-only", "squash", "squash-ff-only"]
            assert enabledStrategies.every { it in possibleStrategies }: "Got unsupported merge strategy"
            assert defaultStrategy in enabledStrategies: "The default strategy must be part of the enabledStrategies"

            log.info("\tDefault strategy:" + defaultStrategy)
            log.info("\tEnabled strategies:" + enabledStrategies.join(","))

            Map restBody = [
                    "mergeConfig": [
                            strategies     : enabledStrategies.collect { [id: it] },
                            defaultStrategy: [id: defaultStrategy]
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
            assert response.mergeConfig.defaultStrategy.id == defaultStrategy: "Error setting default merge strategy"
            assert response.mergeConfig.strategies.findAll { it.enabled }.id == enabledStrategies: "Error setting enabled strategies, API returned enabled strategies:" + response?.mergeConfig?.strategies?.findAll { it.enabled }?.id

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
        ArrayList<String> getEnabledMergeStrategies() {

            Map mergeConfig = prRawConfig.mergeConfig as Map
            ArrayList<Map> strategies = mergeConfig.strategies as ArrayList<Map>

            strategies.removeAll { !it.enabled }

            return strategies.id

        }

        /**
         * Get the default merge strategy for the repo
         * @return one of: no-ff, ff, ff-only, rebase-no-ff, rebase-ff-only, squash, squash-ff-only
         */
        String getDefaultMergeStrategy() {

            Map mergeConfig = prRawConfig.mergeConfig as Map
            Map defaultStrategy = mergeConfig.defaultStrategy as Map

            return defaultStrategy.id
        }


        /**
         * Get the raw API output for PR config
         * @return
         */
        Map getPrRawConfig() {

            String url = "/rest/api/latest/projects/${projectKey}/repos/${repositorySlug}/settings/pull-requests"

            Map rawStrategies = jsonPagesToGenerics(getJsonPages(url, 1, false).first())


            return rawStrategies
        }


        /** -- Pull Request CRUD -- **/


        void createPullRequestRaw(String title, String description, String fromRef, String toBranch) {

            //Source branch 11aaa
            //Destination branch: 6c8

            //GET /projects/SMP/repos/vscode/commits?until=11add3cf2ad5bcaead733aa4c8c9d2a017b4b7fc&since=6c85bb68aae4fdbcecea4797407259ca2cabec60&secondaryRepositoryId=153

            String url = "/rest/api/latest/projects/${projectKey}/repos/${repositorySlug}/pull-requests"

            Map body = [
                    description: description,
                    title : title,
                    fromRef    : [
                            id: fromRef
                    ],
                    toRef      : [
                            id: toBranch
                    ]
            ]

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
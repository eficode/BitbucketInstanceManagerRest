package com.eficode.atlassian.bitbucketInstanceManagerOld

import com.eficode.atlassian.bitbucketInstanceManagerOld.entities.BitbucketProject
import com.eficode.atlassian.bitbucketInstanceManagerOld.entities.BitbucketRepo
import kong.unirest.Cookie
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.PushResult
import org.eclipse.jgit.transport.RemoteConfig
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import unirest.shaded.com.google.gson.JsonObject
import kong.unirest.Cookies
import kong.unirest.GetRequest
import kong.unirest.HttpResponse
import kong.unirest.JsonNode
import kong.unirest.JsonObjectMapper
import kong.unirest.Unirest
import kong.unirest.UnirestException
import kong.unirest.UnirestInstance
import kong.unirest.json.JSONArray
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.regex.Matcher
import java.util.regex.Pattern

class BitbucketInstanceManagerRest {

    static Logger log = LoggerFactory.getLogger(BitbucketInstanceManagerRest.class)
    public static String baseUrl = "http://localhost:7990"
    static Cookies cookies
    public String adminUsername = "admin"
    public String adminPassword = "admin"
    JsonObjectMapper objectMapper = Unirest.config().getObjectMapper() as JsonObjectMapper


    /**
     * Setup BitbucketInstanceManagerRest with admin/admin as credentials.
     * @param BaseUrl Defaults to http://localhost:8080
     */
    BitbucketInstanceManagerRest(String BaseUrl = baseUrl) {
        baseUrl = BaseUrl
        Unirest.config().defaultBaseUrl(BaseUrl)

    }

    /**
     * Setup BitbucketInstanceManagerRest with custom credentials
     * @param BaseUrl Defaults to http://localhost:7990
     * @param username
     * @param password
     */
    BitbucketInstanceManagerRest(String username, String password, String BaseUrl = baseUrl) {
        baseUrl = BaseUrl
        Unirest.config().defaultBaseUrl(baseUrl)
        adminUsername = username
        adminPassword = password

    }

    UnirestInstance getNewUnirest() {

        UnirestInstance unirest = Unirest.spawnInstance()
        unirest.config().defaultBaseUrl(baseUrl).setDefaultBasicAuth(adminPassword, adminPassword)

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

    /**
     * Unirest by default gets lost when several redirects return cookies, this method will retain them
     * @param path
     * @return
     */
    Cookies getCookiesFromRedirect(String path, String username = adminUsername, String password = adminPassword, Map headers = [:]) {

        UnirestInstance unirestInstance = newUnirest
        unirestInstance.config().followRedirects(false)

        Cookies cookies = new Cookies()
        GetRequest getRequest = unirestInstance.get(path).headers(headers)
        if (username && password) {
            getRequest.basicAuth(username, password)
        }
        HttpResponse getResponse = getRequest.asString()
        cookies = extractCookiesFromResponse(getResponse, cookies)

        String newLocation = getResponse.headers.getFirst("Location")

        while (getResponse.status == 302) {


            newLocation = resolveRedirectPath(getResponse, newLocation)
            getResponse = unirestInstance.get(newLocation).asString()
            cookies = extractCookiesFromResponse(getResponse, cookies)

        }


        unirestInstance.shutDown()
        return cookies
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

    boolean setApplicationProperties(String bbLicense, String appTitle = "Bitbucket", String baseUrl = this.baseUrl) {
        log.info("Setting up initial application properties")

        UnirestInstance unirestInstance = newUnirest
        unirestInstance.config().socketTimeout(1000)

        long startTime = System.currentTimeMillis()

        while (startTime + (5 * 60000) > System.currentTimeMillis()) {
            try {
                HttpResponse<String> response = unirestInstance.get("/setup").asString()


                if (response?.body?.contains("<span>Database</span>")) {
                    log.info("\tBitbucket has started and the Setup dialog has appeared")
                    unirestInstance.shutDown()
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


        cookies = Unirest.get("/setup").asString().cookies
        assert cookies.find { it.name == "BITBUCKETSESSIONID" }
        HttpResponse bodyString = Unirest.get("/setup").cookie(cookies).asString()

        String atlToken = searchBodyForAtlToken(bodyString.body)
        HttpResponse setDBResponse = Unirest.post("/setup")
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

        HttpResponse setPropResponse = Unirest.post("/setup")
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

        HttpResponse setAdminResponse = Unirest.post("/setup")
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

        return true
    }

    String getStatus() {

        Map statusMap = Unirest.get("/status").asJson()?.body?.object?.toMap()

        return statusMap?.state
    }


    /*
    def getJsonObjects(String subPath, GenericType type) {


        ArrayList rawObjects = getJsonPages(subPath, true)

        return objectMapper.readValue(rawObjects.toString(), type)


    }

     */

    ArrayList getJsonPages(String subPath, boolean returnValueOnly = true, int maxPages = 50) {

        UnirestInstance unirest = newUnirest

        int start = 0
        boolean isLastPage = false
        int page = 1

        ArrayList responses = []

        while (!isLastPage && start >= 0) {


            //Extra loop protection
            if (page > maxPages) {
                throw new Exception("Returned more than expected pages (${page}) when querying:" + subPath)
            }
            page++

            HttpResponse<JsonNode> response = unirest.get(subPath).accept("application/json").queryString("start", start).asJson()


            isLastPage = response?.body?.object?.has("isLastPage") ? response?.body?.object?.get("isLastPage") as boolean : true
            start = response?.body?.object?.has("nextPageStart") ? response?.body?.object?.get("nextPageStart") as int : -1

            if (returnValueOnly) {
                if (response.body.object.has("values")) {
                    responses += response.body.object.get("values") as ArrayList<Map>
                }else {
                    throw new InputMismatchException("Unexpected body returned from $subPath, expected JSON with \"values\"-node but got: " + response.body.toString())
                }

            } else {
                responses += response.body
            }


        }

        unirest.shutDown()
        return responses


    }

    /** --- Project CRUD --- **/

    ArrayList<BitbucketProject> getProjects() {


        ArrayList rawProjects = getJsonPages("/rest/api/latest/projects", true)
        return BitbucketProject.fromJson(rawProjects.toString())

    }


    BitbucketProject getProject(String projectKey) {
        ArrayList<JsonNode> rawProject = getJsonPages("/rest/api/1.0/projects/" + projectKey, false) as ArrayList<JsonNode>


        if (rawProject.toString().contains("Project $projectKey does not exist")) {
            return null
        }

        assert rawProject.size() == 1: "Error getting project with key:" + projectKey

        return BitbucketProject.fromJson(rawProject.first().toString()).find { it.valid }
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

        return projectRepos.find {it.name == repoNameOrSlug || it.slug == repoNameOrSlug}

    }

    ArrayList<BitbucketRepo> getRepos(String projectKey) {
        ArrayList<JsonObject> rawRepos = getJsonPages("/rest/api/1.0/projects/${projectKey}/repos") as ArrayList<JsonNode>


        ArrayList<BitbucketRepo> repos = BitbucketRepo.fromJson(rawRepos.toString())

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
            assert bitbucketRepo.valid: "Error deleting bitbucket repo, was supplied an invalid repo object:" + bitbucketRepo.toString()

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
    boolean pushToRepo(File localRepoDir, BitbucketRepo remoteRepo) {

        log.info("Pushing local repo to remote repo")
        log.info("\tLocal repo:..." + localRepoDir.absolutePath.takeRight(50))

        Git localRepo = Git.open(localRepoDir)
        URIish suppliedRepoUrl = new URIish( remoteRepo.links.get("clone").find {it.name.contains("http")}.href as String)

        log.info("\tRemote repo:" + suppliedRepoUrl.toString())



        List<RemoteConfig> remoteList = localRepo.remoteList().call()

        RemoteConfig existingRemote = remoteList.find {remote ->
            remote.getURIs().any {it.path == suppliedRepoUrl.path && it.host == suppliedRepoUrl.host}
        }

        if (existingRemote) {
            log.info("\tLocal repo already has the needed git remote:" + existingRemote.toString())
        }else {
            log.info("\tLocal repo is missing git remote, adding it now")

            assert localRepo.remoteAdd().setUri(suppliedRepoUrl).setName(remoteRepo.name).call() : "Error adding new remote:" + suppliedRepoUrl.toString()
            existingRemote = localRepo.remoteList().call().find {it.name == remoteRepo.name}

            assert existingRemote : "Error finding Git Remote after adding it"

            log.info("\t\tFinished adding new git remote")
        }


        ArrayList<PushResult> results = localRepo.push().setRemote(existingRemote.name).setCredentialsProvider(new UsernamePasswordCredentialsProvider(adminUsername, adminPassword)).call() as  ArrayList<PushResult>


        return  !results.empty


    }


    def getGitLogString(String startCommit, String endCommit) {




    }

    /**
     * Not finished

    void gitCommit(File localRepoDir, String filePattern = "*") {
        Git localRepo = Git.open(localRepoDir)

        Status test = localRepo.status().call()


        true
    }

     */


}

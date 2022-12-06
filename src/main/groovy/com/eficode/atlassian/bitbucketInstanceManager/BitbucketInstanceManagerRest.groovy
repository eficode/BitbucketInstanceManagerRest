package com.eficode.atlassian.bitbucketInstanceManager


import com.eficode.atlassian.bitbucketInstanceManager.impl.BitbucketRepo
import com.eficode.atlassian.bitbucketInstanceManager.impl.BitbucketProject
import com.eficode.atlassian.bitbucketInstanceManager.model.BitbucketEntity
import com.eficode.atlassian.bitbucketInstanceManager.model.BitbucketUser
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.gson.reflect.TypeToken
import kong.unirest.Cookie
import kong.unirest.Cookies
import kong.unirest.HttpResponse
import kong.unirest.JsonNode
import kong.unirest.Unirest
import kong.unirest.UnirestException
import kong.unirest.UnirestInstance
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

import java.util.regex.Matcher
import java.util.regex.Pattern


class BitbucketInstanceManagerRest implements BitbucketEntity{

    static Logger log = LoggerFactory.getLogger(BitbucketInstanceManagerRest.class)
    String adminUsername
    String adminPassword
    String baseUrl
    static ObjectMapper objectMapper = new ObjectMapper()

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

    static String searchBodyForAtlToken(String body) {

        Pattern pattern = Pattern.compile(/<input type="hidden" name="atl_token" value="(.*?)"/)
        Matcher matcher = pattern.matcher(body)

        String token = matcher.size() ? matcher[0][1] : ""

        return token

    }


    ArrayList<JsonNode> getJsonPages(String subPath, long maxPages, boolean returnValueOnly = true) {

        return getJsonPages(newUnirest, subPath, maxPages, [:],returnValueOnly)
    }



    String toString() {
        return adminUsername + "@" + baseUrl
    }



    @Override
    boolean isValid(){
        return baseUrl && adminPassword && adminUsername
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
        return BitbucketProject.getProjects(this, 25)
    }

    BitbucketProject getProject(String key) {
        return BitbucketProject.getProject(this, key)
    }

    /**
     * Uses the public REST API available for creating a project, this only allows for setting project key
     * @param key Key of the new project
     * @return A BitbucketProject representation of the new project
     */
    BitbucketProject createProject(String projectKey) {
        return BitbucketProject.createProject(this, projectKey)
    }

    BitbucketProject createProject(String name, String key, String description = "") {
        return BitbucketProject.createProject(this, name, key, description)
    }

    /**
     * Deletes project, will fail if the project has repos and deleteRepos is false
     * @param projectKey
     * @param deleteProjectRepos if true, will delete repos if present in project
     * @return true on success
     */
    boolean deleteProject(String projectKey, boolean deleteRepos = false) {

        return BitbucketProject.deleteProject(this, projectKey, deleteRepos)
    }


    /** --- Repo Getters --- **/



    BitbucketRepo getRepo(String projectKey, String repoNameOrSlug) {

        BitbucketProject project = BitbucketProject.getProject(this, projectKey)

        if (!project) {
            log.warn("Could not find Project with key:" + projectKey)
            return null
        }

        return getRepo(project, repoNameOrSlug)

    }

    static BitbucketRepo getRepo(BitbucketProject project, String repoNameOrSlug) {

        return project.getRepo(repoNameOrSlug)
    }


    /** --- User Crud --- **/


    /**
     * Creates a new user
     * @param email
     * @param password required if notify == false
     * @param displayName
     * @param userName
     * @param notify If true instead of requiring a password,<br>
     *      the created user will be notified via email their account has been created and requires a password to be reset.<br>
     *      This option can only be used if a mail server has been configured.
     * @param addToDefaultGroup
     * @return
     */
    BitbucketUser createUser(String email, String password, String displayName, String userName, boolean notify = false,  boolean addToDefaultGroup = true) {

        return BitbucketUser.createUser(this, email, password,displayName, userName, notify, addToDefaultGroup)
    }

    /**
     * Get users matching the filter

     * @param filter only users with usernames, display name or email addresses containing the supplied string will be returned.
     * @param maxUsers Max number of users to return
     * @return An array of BitbucketUser objects
     */
    ArrayList<BitbucketUser>getUsers(String filter, long maxUsers) {

        return BitbucketUser.getUsers(this, filter, maxUsers)
    }

    /**
     * Set the global permissions of several users
     * @param users userNames of the users
     * @param permission The permission that the users should have: LICENSED_USER, PROJECT_CREATE, ADMIN, SYS_ADMIN
     * @return true on success
     */
    boolean setUsersGlobalPermission( ArrayList<BitbucketUser> users, String permission) {

        return BitbucketUser.setUsersGlobalPermission(this, users, permission)
    }


    BitbucketUser getCurrentUser() {

        UnirestInstance unirest = newUnirest
        String userSlug = unirest.get("/plugins/servlet/applinks/whoami").asString().body

        unirest.shutDown()



        BitbucketUser user =  getUsers(userSlug, 25).find {it.slug.equalsIgnoreCase(userSlug)}

        return user

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


}
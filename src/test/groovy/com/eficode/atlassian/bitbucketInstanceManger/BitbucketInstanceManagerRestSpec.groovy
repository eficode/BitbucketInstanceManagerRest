package com.eficode.atlassian.bitbucketInstanceManger

import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest
import com.eficode.atlassian.bitbucketInstanceManager.impl.BitbucketRepo
import com.eficode.atlassian.bitbucketInstanceManager.impl.BitbucketCommit
import com.eficode.atlassian.bitbucketInstanceManager.impl.BitbucketProject
import com.eficode.atlassian.bitbucketInstanceManager.impl.BitbucketChange
import com.eficode.atlassian.bitbucketInstanceManager.impl.BitbucketBranch
import com.eficode.atlassian.bitbucketInstanceManager.impl.BitbucketCommit
import com.eficode.atlassian.bitbucketInstanceManager.impl.BitbucketPullRequest
import com.eficode.atlassian.bitbucketInstanceManager.impl.BitbucketWebhook
import com.eficode.atlassian.bitbucketInstanceManager.impl.BitbucketWebhookBody
import com.eficode.atlassian.bitbucketInstanceManager.impl.BitbucketWebhookInvocation
import com.eficode.atlassian.bitbucketInstanceManager.model.BitbucketPrParticipant
import com.eficode.atlassian.bitbucketInstanceManager.model.BitbucketUser
import com.eficode.atlassian.bitbucketInstanceManager.model.MergeStrategy
import com.eficode.atlassian.bitbucketInstanceManager.model.WebhookEventType
import com.eficode.devstack.container.impl.BitbucketContainer
import groovy.test.GroovyAssert
import kong.unirest.JsonNode
import kong.unirest.Unirest
import kong.unirest.UnirestInstance
import kong.unirest.json.JSONArray
import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.TrueFileFilter
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Ignore
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path


/**
 * Presumes that there is a Docker engine listening on the default socket
 *      bitbucketLicensePath points to a file containing a Bitbucket license
 *      baseUrl points to the url (and port) of bitbucket
 **/

class BitbucketInstanceManagerRestSpec extends Specification {


    @Shared
    Logger log = LoggerFactory.getLogger(BitbucketInstanceManagerRestSpec.class)

    @Shared
    static String baseUrl = "http://bitbucket.domain.se:7990"

    @Shared
    static String dockerRemoteHost = "https://docker.domain.se:2376"

    @Shared
    static String bitbucketLicensePath = "licenses/bitbucketLicense"

    @Shared
    static String restAdmin = "admin"

    @Shared
    static String restPw = "admin"

    @Shared
    UnirestInstance unirestInstance = Unirest.spawnInstance()

    @Shared
    Path projectRoot = new File("").absoluteFile.toPath()

    @Shared
    BitbucketContainer bitbucketContainer

    @Shared
    File mainTempDir = Files.createTempDirectory(BitbucketInstanceManagerRestSpec.simpleName).toFile().absoluteFile

    @Shared
    File repoCacheDir = new File(mainTempDir, "main-repo-cache/").absoluteFile


    // run once before the first feature method
    def setupSpec() {


        if (dockerRemoteHost) {
            bitbucketContainer = new BitbucketContainer(baseUrl, dockerRemoteHost, "Environments/docker/certs/")
            bitbucketContainer.jvmMaxRam = 12000
        } else {
            bitbucketContainer = new BitbucketContainer(baseUrl)
        }

        unirestInstance.config().defaultBaseUrl(baseUrl).setDefaultBasicAuth(restAdmin, restPw).enableCookieManagement(false)
        bitbucketContainer.containerName = bitbucketContainer.extractDomainFromUrl(baseUrl)


        repoCacheDir.mkdirs()
    }

    def cleanupSpec() {

        log.info("Deleting directory:" + mainTempDir.path)
        mainTempDir.deleteDir()

    }


    /**
     * Get a new BitbucketInstanceManagerRest instance
     * @return
     */
    BitbucketInstanceManagerRest setupBb() {

        return new BitbucketInstanceManagerRest(restAdmin, restPw, baseUrl)
    }

    String getBitbucketLicense() {
        return new File(bitbucketLicensePath).text
    }


    /**
     *
     * Mirror-clones the VSCODE repo from Github
     * ~600MB, 700B ranches
     *
     * @return File object of the new temp folder
     */
    File setupLocalGitRepo(String repoUrl = "https://github.com/microsoft/vscode.git") {

        repoUrl = "https://github.com/eficode/BitbucketInstanceManagerRest.git"

        log.info("Setting up local git repo")
        if (repoCacheDir.listFiles().size() == 0) {
            log.info("\tCloning remote repo $repoUrl to " + repoCacheDir.absolutePath)
            BitbucketInstanceManagerRest.mirrorRepo(repoCacheDir, repoUrl)
            assert FileUtils.listFilesAndDirs(repoCacheDir, TrueFileFilter.INSTANCE, null).any { it.name == ".git" }

        } else {
            log.debug("\tAlready have a cached clone of the remote repo")
        }

        File newDir = new File(mainTempDir, "repo-clone-${System.currentTimeSeconds().toString()[-4..-1]}")
        log.info("\tCreating new directory:" + newDir.absolutePath)
        log.info("\tCopying cached repo to the new dir")

        FileUtils.copyDirectory(repoCacheDir, newDir)

        log.info("\t\tFinished")
        return newDir
    }

    /**
     * Sets up a new project, repo and pushes output of setupLocalGitRepo to it
     * @param projectName
     * @param projectKey
     * @param repoName
     * @return
     */
    BitbucketRepo setupRepo(String projectName, String projectKey, String repoName) {

        log.info("Setting of project and repo")
        log.info("\tProject:" + projectName)
        log.info("\tProject key:" + projectKey)
        log.info("\tRepo:" + repoName)


        BitbucketInstanceManagerRest bb = setupBb()


        //Cleanup conflicting projects
        log.info("\tCleaning up conflicting projects")
        ArrayList<BitbucketProject> allProjects = bb.getProjects()
        log.info("\tInstance curretnly has ${allProjects.size()} projects (${allProjects.key.join(", ")})")
        ArrayList<BitbucketProject> matching = allProjects.findAll { it.name == projectName || it.key == projectKey }

        log.info("\tFound ${matching.size()} conflicitng projects")

        matching.each { project ->
            log.info("\t\tDeleting project:" + project.toString())
            assert project.delete( true)
            log.info("\t"*3 + "Deleted")
        }

        //Setup new project and repo
        BitbucketProject sampleProject = bb.createProject(projectName, projectKey)
        log.info("\tCreated new project:" + sampleProject.toString())
        BitbucketRepo sampleRepo = sampleProject.createRepo(repoName)
        log.info("\tCreated new repo:" + sampleRepo.toString())

        //Mirror-clone remote repo locally, push all of its branches to the new repo
        File localGitRepoDir = setupLocalGitRepo()
        assert bb.pushToRepo(localGitRepoDir, sampleRepo, true)

        //If master branch not found, presume main is the default branch
        if (!sampleRepo.findBranches("master").findAll { it.displayId == "master" }) {
            assert sampleRepo.setDefaultBranch("main"): "Error setting default branch to main"
        }

        return sampleRepo
    }

    @Ignore
    def "Test setup of basic application config"() {

        setup: "Remove container if it exists, create a new one"

        bitbucketContainer.containerImageTag = "8.5.0"
        bitbucketContainer.stopAndRemoveContainer()
        bitbucketContainer.createContainer()
        bitbucketContainer.startContainer()

        String instanceDisplayName = "SPOCK Bitbucket"

        when: "Setting application properties"


        BitbucketInstanceManagerRest bb = setupBb()


        bb.setApplicationProperties(bitbucketLicense, instanceDisplayName, baseUrl)

        then:
        bb.status == "RUNNING"
        bb.license.strip() == bitbucketLicense.strip()


    }


    def "Test update of file via API"() {

        setup:
        String projectName = "File update tests"
        String projectKey = "FU"
        String repoName = "Updating files"
        String branchName = "master"
        String fileName = "NewFile${System.currentTimeMillis().toString()[-3..-1]}.md"

        BitbucketRepo sampleRepo = setupRepo(projectName, projectKey, repoName)


        when:
        BitbucketCommit createFileCommit = sampleRepo.createFile(fileName, branchName, "Initical commit of file in branch: $branchName, on ${new Date()}", "Initial commit of $fileName")

        then:
        createFileCommit != null
        createFileCommit.message.startsWith("Initial commit")
        createFileCommit.branch.displayId == branchName

        when:
        BitbucketCommit updateFileCommit = sampleRepo.updateFile(fileName, branchName, "An updated to the file $fileName on ${new Date()}", "A Commit MSG" + new Date().toString())


        then:
        updateFileCommit != null
        updateFileCommit.message.startsWith("A Commit MSG")
        updateFileCommit.branch.displayId == branchName
        updateFileCommit.parents.first().id == createFileCommit.id
        sampleRepo.getFileContent(fileName, branchName).contains("An updated to the file")


    }


    def "Test PR config crud"() {


        setup:
        log.info("Testing Pull Request Config CRUD actions using Bitbucket API")
        String projectName = "Pull Request Actions"
        String projectKey = "PRA"
        String repoName = "CRUDing PR Config"

        BitbucketInstanceManagerRest bb = new BitbucketInstanceManagerRest(restAdmin, restPw, baseUrl)
        BitbucketProject bbProject = bb.getProject(projectKey)

        if (bbProject) {
            bbProject.delete(true)
        }

        bbProject = bb.createProject(projectName, projectKey)
        BitbucketRepo bbRepo = bbProject.createRepo(repoName)

        expect:
        //Reading the default settings of a newly created repo

        assert bbRepo.defaultMergeStrategy == MergeStrategy.NO_FF: "The new repo got an unexpected default merge strategy"
        assert bbRepo.enabledMergeStrategies == [MergeStrategy.NO_FF]: "The new repo got an unexpected enabled merge strategies"
        assert bbRepo.mergeStrategyIsInherited(): "The new repo started with repo-local strategies"


        //Updating repo with new settings, and reading them back
        assert bbRepo.setEnabledMergeStrategies(MergeStrategy.REBASE_NO_FF, [MergeStrategy.FF_ONLY, MergeStrategy.REBASE_NO_FF]): "Error setting Merge Strategies "

        assert bbRepo.defaultMergeStrategy == MergeStrategy.REBASE_NO_FF: "API does not report the expected default merge strategy after setting it"
        assert bbRepo.enabledMergeStrategies == [MergeStrategy.FF_ONLY, MergeStrategy.REBASE_NO_FF]: "API does not report the expected  merge strategies after setting them"
        assert !bbRepo.mergeStrategyIsInherited(): "After setting repo-local merge strategies, they are still inherited from project"


        assert bbRepo.enableAllMergeStrategies(): "Error enabling all Merge Strategies"
        assert bbRepo.defaultMergeStrategy == MergeStrategy.NO_FF: "Enabling all merge strategies, ended up setting an unexpected default strategy"

        assert bbRepo.enableAllMergeStrategies(MergeStrategy.FF_ONLY): "Error enabling all Merge Strategies"
        assert bbRepo.defaultMergeStrategy == MergeStrategy.FF_ONLY


        //Giving the wrong input
        GroovyAssert.shouldFail { bbRepo.setEnabledMergeStrategies(MergeStrategy.FF_ONLY, [MergeStrategy.FF]) }


    }


    def "Test PR CRUD"(MergeStrategy mergeStrategy) {


        setup:
        log.info("Testing Pull Request CRUD actions using Bitbucket API")
        String projectName = "Pull Request Actions"
        String projectKey = "PRA"
        String repoName = "CRUDing PRs"

        BitbucketInstanceManagerRest bb = new BitbucketInstanceManagerRest(restAdmin, restPw, baseUrl)
        BitbucketProject bbProject = bb.getProject(projectKey)

        if (bbProject) {
            bbProject.delete(true)
        }

        bbProject = bb.createProject(projectName, projectKey)
        BitbucketRepo bbRepo = bbProject.createRepo(repoName)

        bbRepo.setEnabledMergeStrategies(mergeStrategy, [mergeStrategy])

        log.info("\tUsing Project:" + bbProject.key)
        log.info("\tUsing Repo:" + bbRepo.slug)
        log.info("\tUsing merge strategy:" + mergeStrategy)

        String expectedFileContent = "Initial file content"
        BitbucketCommit creatingFileCommit = bbRepo.createFile("README.md", "master", expectedFileContent, "Creating the file in the master branch")


        BitbucketBranch masterBranch = bbRepo.getAllBranches().find { it.displayId == "master" }
        log.info("\tCreated file ${creatingFileCommit.getChanges().first().getFileNameTruncated(60)} in " + masterBranch.id)
        BitbucketBranch secondBranch = bbRepo.createBranch("a-second-branch", "master")
        log.info("\tCreated branch:" + secondBranch.id)

        expectedFileContent += "\n\nFirst update from second branch"
        BitbucketCommit firstUpdateCommit = bbRepo.appendFile("README.md", "a-second-branch", expectedFileContent, "A commit in separate branch")
        log.info("\tUpdated file ${firstUpdateCommit.getChanges().first().getFileNameTruncated(60)} in " + secondBranch.id)

        expectedFileContent += "\n\nSecond update from second branch"
        BitbucketCommit secondUpdateCommit = bbRepo.appendFile("README.md", secondBranch.displayId, expectedFileContent, "A second commit in the separate branch")
        log.info("\tUpdated file ${firstUpdateCommit.getChanges().first().getFileNameTruncated(60)} again in " + secondBranch.id)
        secondBranch = secondBranch.refreshInfo() //Needed to update latestCommit in the branch

        when: "When creating a pull request from a branch with two new commits"
        BitbucketPullRequest pr = bbRepo.createPullRequest(secondBranch, masterBranch)
        log.info("\tCreated PR ${pr.title} from ${secondBranch.id} to ${masterBranch.id}")

        then: "The PR should have an autogenerated description, and title"
        log.info("\tChecking returned PR object")
        pr.description.readLines().first().contains(firstUpdateCommit.message)
        pr.description.readLines().last().contains(secondUpdateCommit.message)
        log.info("\t\tDescription is correct")
        pr.title.contains(secondBranch.displayId)
        pr.title.contains(masterBranch.displayId)
        log.info("\t\tTitle is correct")
        pr.isValid()
        log.info("\t\tObject is valid")

        log.info("\tChecking that various GET requests can find the new PR")
        assert [
                bbRepo.getOpenPullRequests().first().id,
                bbRepo.getAllPullRequests().first().id,
                bbRepo.getOpenPullRequests(masterBranch).first().id,
                bbRepo.getAllPullRequests(masterBranch).first().id,
                firstUpdateCommit.getPullRequestsInvolvingCommit().first().id,
                secondUpdateCommit.getPullRequestsInvolvingCommit().first().id,

        ].every { it == pr.id }: "Error getting the newly created PR"

        assert bbRepo.getPullRequests("DECLINED").empty: "Library found Declined PRs in the repo which shouldn't be there"
        assert bbRepo.getPullRequests("MERGED").empty: "Library found MERGED PRs in the repo which shouldn't be there"

        log.info("\t\tGet requests succeeded in finding the PR")

        when:

        BitbucketPullRequest prAfterMerge = pr.mergePullRequest()


        then:
        prAfterMerge.isValid()
        assert prAfterMerge.state == "MERGED"
        assert bbRepo.getPullRequests("MERGED").id == [pr.id]
        assert prAfterMerge.getMergeCommit() != null
        assert prAfterMerge.getMergeCommit().id == masterBranch.refreshInfo().getLatestCommit()
        assert prAfterMerge.state == pr.refreshInfo().state
        assert prAfterMerge.getMergeCommit().id == pr.refreshInfo().getMergeCommit().id
        assert bbRepo.getAllPullRequests().find {it.id == pr.id}.mergeCommit.id == prAfterMerge.getMergeCommit().id
        assert prAfterMerge.getMergeCommit().isAPrMerge()
        assert bbRepo.getCommits(10).findAll {it.isAPrMerge()}.size() == 1

        where:
        mergeStrategy << MergeStrategy.values()

    }

    def "Test PR Approval"() {

        setup:
        log.info("Testing Pull Request approval actions using Bitbucket API")
        String projectName = "Pull Request Actions"
        String projectKey = "PRA"
        String repoName = "CRUDing PR Approval"
        String otherUserName = "otherUser"

        BitbucketInstanceManagerRest bb = new BitbucketInstanceManagerRest(restAdmin, restPw, baseUrl)
        BitbucketProject bbProject = bb.getProject(projectKey)

        if (bbProject) {
            bbProject.delete(true)
        }

        bbProject = bb.createProject(projectName, projectKey)
        BitbucketRepo bbRepo = bbProject.createRepo(repoName)

        BitbucketUser otherUser = bb.getUsers(otherUserName,1).find ()
        if (!otherUser) {
            otherUser = bb.createUser(otherUserName+"@email.com",otherUserName.reverse(), otherUserName, otherUserName )
        }
        assert otherUser.setUserGlobalPermission("SYS_ADMIN") : "Error setting $otherUser to Sys Admin"


        log.info("\tUsing Project:" + bbProject.key)
        log.info("\tUsing Repo:" + bbRepo.slug)


        String expectedFileContent = "Initial file content"
        BitbucketCommit creatingFileCommit = bbRepo.createFile("README.md", "master", expectedFileContent, "Creating the file in the master branch")


        BitbucketBranch masterBranch = bbRepo.getAllBranches().find { it.displayId == "master" }
        log.info("\tCreated file ${creatingFileCommit.getChanges().first().getFileNameTruncated(60)} in " + masterBranch.id)
        BitbucketBranch secondBranch = bbRepo.createBranch("a-second-branch", "master")
        log.info("\tCreated branch:" + secondBranch.id)

        expectedFileContent += "\n\nFirst update from second branch"
        BitbucketCommit firstUpdateCommit = bbRepo.appendFile("README.md", "a-second-branch", expectedFileContent, "A commit in separate branch")
        log.info("\tUpdated file ${firstUpdateCommit.getChanges().first().getFileNameTruncated(60)} in " + secondBranch.id)

        expectedFileContent += "\n\nSecond update from second branch"
        BitbucketCommit secondUpdateCommit = bbRepo.appendFile("README.md", secondBranch.displayId, expectedFileContent, "A second commit in the separate branch")
        log.info("\tUpdated file ${firstUpdateCommit.getChanges().first().getFileNameTruncated(60)} again in " + secondBranch.id)
        secondBranch = secondBranch.refreshInfo() //Needed to update latestCommit in the branch

        when: "When creating a pull request from a branch with two new commits"
        BitbucketPullRequest pr = bbRepo.createPullRequest(secondBranch, masterBranch)
        log.info("\tCreated PR ${pr.title} from ${secondBranch.id} to ${masterBranch.id}")

        then:
        assert pr.author.user.name == "admin" : "Unexpected author of PR"
        assert pr.approvers.size() == 0 : "A new PR should have no Approvers"
        assert pr.reviewers.size() == 0 : "A new PR should have no Reviewers"

        when: "Adding a user as a Reviewer"
        BitbucketPrParticipant participant = pr.addReviewer(otherUser)
        pr = pr.refreshInfo()

        then: "A refreshed version of the PR should have the user as a reviewer"
        pr.reviewers.first().user.id == otherUser.id
        pr.reviewers.first().role == "REVIEWER"
        pr.reviewers.first().status == "UNAPPROVED"
        participant.isValid()

        when: "Removing the reviewer"
        pr.removeReviewer(participant)
        pr = pr.refreshInfo()

        then: "The PR should have no more Reviewers"
        pr.reviewers.isEmpty()

        when:
        participant = pr.setApprovalStatus(otherUserName, otherUserName.reverse(), "APPROVED")
        pr = pr.refreshInfo()

        then:
        participant.user.name == otherUserName
        pr.getApprovers().user.id == [otherUser.id]
        pr.getApprovers().status == ["APPROVED"]


    }



    def "Test branch CRUD"() {

        log.info("Testing Branch CRUD actions")
        String projectName = "Branch Actions"
        String projectKey = "BRA"
        String repoName = "CRUDing Branches"

        BitbucketInstanceManagerRest bb = new BitbucketInstanceManagerRest(restAdmin, restPw, baseUrl)
        BitbucketProject bbProject = bb.getProject(projectKey)

        if (bbProject) {
            bbProject.delete(true)
        }

        bbProject = bb.createProject(projectName, projectKey)
        BitbucketRepo bbRepo = bbProject.createRepo( repoName)

        //Repo must contain something before branches can be created
        bbRepo.createFile("README.md", "master", "Initial content ${new Date()}", "Initial commit")
        String originalDefaultBranch = bbRepo.defaultBranch.displayId


        when: "Creating new branches"


        assert bbRepo.createBranch("a-branch-from-master", "master").id == "refs/heads/a-branch-from-master": "Error creating a branch from master"
        assert bbRepo.createBranch("a-feature-branch", "master", "feature").id == "refs/heads/feature/a-feature-branch": "Error creating a feature branch"
        BitbucketBranch newBranchWithNewType = bbRepo.createBranch("a-new-branch-type", "master", "new-type")


        then: "They should all be valid, get the correct branch types and only one should be default"
        with(newBranchWithNewType) {
            assert id == "refs/heads/new-type/a-new-branch-type": "Error creating a branch with a new type"
            assert branchType == "new-type"
            assert it.isValid()
        }


        with(bbRepo.findBranches("a-branch-from-master").first()) {
            branchType == ""
        }

        with(bbRepo.getAllBranches()) { branches ->
            branches.every {
                it.isValid()
            } && branches.latestCommit.unique().size() == 1 && branches.latestChangeset.unique().size() == 1

        }

        assert bbRepo.findBranches("a-branch-from-master").size() == 1: "Error finding a newly created branch"
        assert bbRepo.getAllBranches().size() == 4: "Error finding all the expected branched"
        assert bbRepo.getAllBranches().findAll { it.isDefault }.size() == 1: "Library returned unexpected amount of default branches."


        when: "Committing a file update in a branch, and refreshing the branch"
        BitbucketCommit featureCommit = bbRepo.appendFile("README.md", newBranchWithNewType.displayId, "\n\nA commit made in the \"${newBranchWithNewType.id}.\" branch", "Committing to \"${newBranchWithNewType.id}\" branch")
        newBranchWithNewType = newBranchWithNewType.refreshInfo()

        then: "The branch should have the new latestCommit"
        assert featureCommit.branch.id == newBranchWithNewType.id: "The API says the commit belongs to the incorrect branch"
        assert featureCommit.branch.latestCommit == featureCommit.id: "The API says the last commit in the branch is the wrong one "
        assert newBranchWithNewType.latestCommit == featureCommit.id: "The library didn't return the correct latestCommit after refreshing the branch object"


    }


    def "Test webhook Config CRUD"(String hookName, ArrayList<WebhookEventType> events, String secret, boolean cleanProject) {

        setup:
        log.info("Testing webhook config CRUD actions")
        log.info("\tHook Name:" + hookName)
        String projectName = "Webhook config"
        String projectKey = "hookconf"
        String repoName = "CRUDing Webhook config"
        String remoteUrl = "https://postman-echo.com/post"

        BitbucketInstanceManagerRest bb = new BitbucketInstanceManagerRest(restAdmin, restPw, baseUrl)
        BitbucketProject bbProject = bb.getProject(projectKey)


        ArrayList<WebhookEventType> expectedEvents = events.isEmpty() ? WebhookEventType.values().toList() : events
        ArrayList<WebhookEventType> unExpectedEvents = WebhookEventType.values().toList().findAll { event -> !(event in expectedEvents) }

        log.info("\tWith events:" + expectedEvents.join(","))

        if (bbProject && cleanProject) {
            bbProject.delete(true)
            bbProject = bb.createProject(projectName, projectKey)
        } else if (!bbProject) {
            bbProject = bb.createProject(projectName, projectKey)
        }



        BitbucketRepo bbRepo = bbProject.getRepos().find { it.name == repoName }

        if (!bbRepo) {
            bbRepo = bbProject.createRepo(repoName)
        }


        when: "When creating the webhook"
        BitbucketWebhook newHook = bbRepo.createWebhook(hookName, remoteUrl, events, secret)

        then: "The returned object should be valid and have the correct settings"
        newHook.isValid()
        newHook.events.sort() == expectedEvents.sort()
        newHook.name == hookName
        newHook.configuration.secret == secret
        newHook.secret == secret
        newHook.active

        when: "When querying library for all webhooks"

        BitbucketWebhook foundHook = bbRepo.getWebhooks([], 100).find { it.id == newHook.id }

        then: "The same webhook should be returned with expected values"
        foundHook.isValid()
        foundHook.events.sort() == expectedEvents.sort()
        foundHook.name == hookName
        foundHook.configuration.secret == secret
        foundHook.secret == secret
        foundHook.active

        expect:

        assert (unExpectedEvents ? bbRepo.getWebhooks(unExpectedEvents, 100).find { it.id == newHook.id } == null : true): "getWebhooks() returned webhook even though it should have been filtered out"
        assert bbRepo.getWebhooks([expectedEvents.shuffled().first()], 100).find { it.id == newHook.id }: "getWebhooks() did not return webhook when filtering on events"
        assert bbRepo.deleteWebhook(foundHook)
        GroovyAssert.assertThrows("status 404", AssertionError, { bbRepo.deleteWebhook(newHook) })

        where:
        hookName        | events                                       | secret         | cleanProject
        "All events"    | WebhookEventType.values()                    | null           | true
        "Random events" | WebhookEventType.values().shuffled().take(5) | null           | false
        "With a secret" | WebhookEventType.values().shuffled().take(7) | "super secret" | false


    }


    def "Test webhook Body" () {

        setup: "Create a repo with a Webhook that listens for changes"

        log.info("Testing webhook actions")

        String projectName = "Webhooks"
        String projectKey = "hook"
        String repoName = "CRUDing Webhooks"
        String remoteUrl = "https://postman-echo.com/post"

        BitbucketInstanceManagerRest bb = new BitbucketInstanceManagerRest(restAdmin, restPw, baseUrl)
        BitbucketProject project = bb.getProject(projectKey) ?: bb.createProject(projectName, projectKey)
        BitbucketRepo repo = project.getRepo(repoName)

        if (repo) {
            repo.delete()
        }
        repo = project.createRepo(repoName)

        BitbucketWebhook webhook = repo.createWebhook("Postman Echo", remoteUrl,[])


        when: "When performing a change in repo"
        log.info("Triggering Webhook")
        BitbucketCommit initialCommit = repo.createFile("README.md", "master", "\nInitial SPOC Commit", "Initial SPOC Commit")
        log.info("\tWebhook triggered at:" +  initialCommit.committerTimestamp)

        BitbucketWebhookInvocation invocation = null
        while (System.currentTimeMillis()  < initialCommit.committerTimestamp + 60000) {
            log.info("\tWaiting for API to return invocation, waited: " + (System.currentTimeMillis() - initialCommit.committerTimestamp) + "ms")
            sleep(1000)

            invocation = webhook.getLastInvocation()
            if (invocation != null) {
                log.info("\tWebhook invocation finished after:" + (System.currentTimeMillis() - initialCommit.committerTimestamp) + "ms")
                break
            }
        }
        assert invocation != null : "Time out waiting for Webhook invocation after: " + (System.currentTimeMillis() - initialCommit.committerTimestamp) + "ms"


        then: "A successful webhook invocation should have been performed"
        invocation.result.description == "200"
        invocation.event == WebhookEventType.REPO_REFS_CHANGED



        when:"Transforming the JSON body of the webhook into a BitbucketWebhookBody object"
        BitbucketWebhookBody webhookBody = BitbucketWebhookBody.fromJson(invocation.request.body, setupBb())



        then: "It should be valid and have rich child object"
        BitbucketWebhookBody.getInstanceUrl(invocation.request.body) == baseUrl
        webhookBody.repository.isValid()
        webhookBody.repository.project.isValid()
        webhookBody.repository.project.getRepo(repoName).getWebhooks().id.contains(webhook.id)
        webhookBody.changes.size() == 1
        webhookBody.repository.slug == repo.slug


    }

    def "Test file crud using Bitbucket API"() {

        setup:
        log.info("Testing File CRUD actions using Bitbucket API")
        String projectName = "File Actions"
        String projectKey = "FA"
        String repoName = "CRUDing files"
        String branchName = "main"
        String expectedFileText = "Created ${new Date()}\n\nIn Branch: $branchName \n\n ---- \n"


        BitbucketInstanceManagerRest bb = new BitbucketInstanceManagerRest(restAdmin, restPw, baseUrl)
        BitbucketProject bbProject = bb.getProject(projectKey)

        if (bbProject) {
            bbProject.delete(true)
        }

        bbProject = bb.createProject(projectName, projectKey)
        BitbucketRepo bbRepo = bbProject.createRepo(repoName)


        when: "Setting default branch to non standard value"
        bbRepo.defaultBranch = branchName

        then: "Expected default branch should be returned by API"
        assert bbRepo.defaultBranch.displayId == branchName

        when: "Creating a new file"
        BitbucketCommit createFileCommit = bbRepo.createFile("README.md", branchName, expectedFileText, "Initial Commit" + new Date().toString())

        then: "The new commit should be created in the right branch, with the right commit msg and the file should retrievable with the correct content"
        assert createFileCommit.branch.displayId == branchName: "New commit not performed in the right branch"
        assert createFileCommit.message.contains("Initial Commit"): "New commit got the wrong msg"
        assert bbRepo.getFileContent("README.md").strip() == expectedFileText.strip(): "Retrieving the file with just a name failed"
        assert bbRepo.getFileContent("README.md", branchName).strip() == expectedFileText.strip(): "Retrieving the file with name and branch name failed"
        assert bbRepo.getFileContent("README.md", null, createFileCommit.id).strip() == expectedFileText.strip(): "Retrieving the file with name and commit id failed"


        when: "Creating a new file with an existing file name"
        bbRepo.createFile("README.md", branchName, expectedFileText + "123", "Initial Commit" + new Date().toString())

        then: "An exception should be thrown"
        Exception e = thrown(Exception)
        e.message.contains("could not be created because it already exists")
        log.info("\tCreation of file tested successfully")


        when: "Updating an existing file"

        expectedFileText = expectedFileText + "\n\nUpdated ${new Date()} \n\n ---- \n"
        BitbucketCommit updateFileCommit = bbRepo.updateFile("README.md", branchName, expectedFileText, "Updating existing file")

        then: "The new commit should be created in the right branch, with the right commit msg and the file should retrievable with the updated content"
        assert updateFileCommit.branch.displayId == branchName: "New commit not performed in the right branch"
        assert updateFileCommit.message.contains("Updating existing file"): "New commit got the wrong msg"
        assert bbRepo.getFileContent("README.md").strip() == expectedFileText.strip(): "Retrieving the file with just a name failed"
        assert bbRepo.getFileContent("README.md", branchName).strip() == expectedFileText.strip(): "Retrieving the file with name and branch name failed"
        assert bbRepo.getFileContent("README.md", null, updateFileCommit.id).strip() == expectedFileText.strip(): "Retrieving the file with name and commit id failed"


        when: "Updating a non existent file"
        bbRepo.updateFile("NonExistentFile.md", branchName, "Some content", "A commit msg")

        then: "An exception should be thrown"
        e = thrown(Exception)
        e.message.contains("could not be edited")
        log.info("\tUpdate of file tested successfully")


        when: "Prepending a file"
        expectedFileText = "## A new heading\n\n" + expectedFileText

        assert bbRepo.prependFile("README.md", branchName, "## A new heading\n\n", "Added a heading")

        then: "The start of the file should be changed"
        assert bbRepo.getFileContent("README.md").strip() == expectedFileText.strip()
        log.info("\tPrepend of file tested successfully")

        when: "Appending a file"
        expectedFileText = expectedFileText + "\n\n\t A new tail"
        assert bbRepo.appendFile("README.md", branchName, "\n\n\t A new tail", "Added a tail")

        then: "The end of the file should be changed, and the REPO should have two new commits"
        assert bbRepo.getFileContent("README.md").endsWith("A new tail")
        assert bbRepo.getCommits(100).message.containsAll(["Added a tail", "Added a heading"])
        log.info("\tAppend of file tested successfully")
    }


    def "Test getFileNameTruncated"(String full, String parent, String name) {

        setup:
        BitbucketInstanceManagerRest bb = setupBb()

        ArrayList<Integer> lengths = [36, 5, 10, 32, 33, 34, 35, 36, 37, 38, 39, 40, 62, 98, 500]

        BitbucketChange change = new BitbucketChange()
        change.path = [toString: full,
                       parent  : parent,
                       name    : name]

        log.info("Testing truncation of file names")
        log.info("\tFileName:" + full)
        expect:

        assert lengths.every { expectedLen ->

            log.info("\tTo length:" + expectedLen)
            String truncatedOut = change.getFileNameTruncated(expectedLen)
            log.info("\t\tOutput was: " + truncatedOut)
            log.info("\t\tOutput length: " + truncatedOut.length())

            if (expectedLen > full.length()) {
                return truncatedOut.length() == full.length()
            }
            return truncatedOut.length() == expectedLen

        }


        where:
        full                                                                                                 | parent                                                           | name
        "src/main/groovy/com/eficode/atlassian/bitbucketInstanceManager/BitbucketInstanceManagerRest.groovy" | "src/main/groovy/com/eficode/atlassian/bitbucketInstanceManager" | "BitbucketInstanceManagerRest.groovy"


    }

    def "Test git actions"() {

        setup:

        BitbucketInstanceManagerRest bb = setupBb()


        bb.getProjects().each {
            it.delete(true)
        }


        BitbucketProject sampleProject = bb.createProject("Sample Project", "SMP")
        BitbucketRepo sampleRepo = sampleProject.createRepo(BitbucketInstanceManagerRestSpec.simpleName)

        when: "When pushing the project git repo, to the new bitbucket project/repo"
        File localGitRepoDir = setupLocalGitRepo()
        boolean pushSuccess = bb.pushToRepo(localGitRepoDir, sampleRepo, true)


        then: "Success should be returned"
        pushSuccess
        sampleProject instanceof BitbucketProject
        sampleRepo instanceof BitbucketRepo


        when: "Getting all repo commits, and a subset of the commits"
        ArrayList<BitbucketCommit> allCommits = sampleRepo.getCommits(100)

        ArrayList<BitbucketCommit> subsetCommits = sampleRepo.getCommits(allCommits[5].id, allCommits[0].id, 100)

        then: "All commits should be more than subset, they should all be BitbucketCommit, the subset should not contain"
        allCommits.size() >= subsetCommits.size()
        allCommits.every { it instanceof BitbucketCommit }
        !subsetCommits.any { it == allCommits[5] }
        allCommits.every { it.isValid() }
        sampleProject.isValid()
        sampleRepo.isValid()


        when: "Get changes from commit"
        ArrayList<BitbucketChange> changes = allCommits.last().getChanges()

        then:
        String changesMd = changes.collect { it.toMarkdown() }.join("\n")



        cleanup:
        localGitRepoDir.deleteDir()


    }


    def testRepoCrud() {

        setup:
        BitbucketInstanceManagerRest bb = setupBb()

        bb.getProjects().each {
            it.delete(true)
        }

        BitbucketProject sampleProject = bb.createProject("Sample Project", "SMP")

        when:
        BitbucketRepo firstRepo = sampleProject.createRepo("a repo with spaces")

        then:
        firstRepo != null
        firstRepo.isValid()

        firstRepo == sampleProject.getRepo("a repo with spaces")

        when:
        sampleProject.delete(false)

        then:
        Exception ex = thrown(Exception)
        ex.message.contains("cannot be deleted because it has repositories")


        expect:
        firstRepo.delete()
        sampleProject.delete(false)


        /*

        //Make Sure least one project has a repo
        projectsAtStart.any { project ->
            if (bb.getRepos(project) != []) {
                projectWithRepo = project
                return true
            }

        }
        //Make sure getting with project string key works
        bb.getRepos(projectWithRepo.key) != []
        //Make sure the repos project variable is correct
        bb.getRepos(projectWithRepo.key).first().project.collect {[it.key, it.id]} == projectWithRepo.collect {[it.key, it.id]}
        bb.getRepos(projectWithRepo.key).first().project == projectWithRepo

         */


    }

    def testProjectCrud() {

        setup:
        BitbucketInstanceManagerRest bb = setupBb()

        bb.getProjects().each {
            it.delete(true)
        }


        ArrayList<BitbucketProject> projectsAtStart = bb.getProjects()

        BitbucketProject projectWithRepo = null


        when: "Creating a project using private APIs"
        String rndNr = System.currentTimeMillis().toString()[-5..-1]
        BitbucketProject newPrivProject = bb.createProject("New Project $rndNr with priv API", "NEWPRIV$rndNr", "A spock project: Using private API")


        then: "Should return the new project, should be able to find it, and should have no repos"
        newPrivProject != null
        newPrivProject.key == "NEWPRIV$rndNr"
        newPrivProject.name == "New Project $rndNr with priv API"
        newPrivProject == bb.getProject("NEWPRIV$rndNr")
        newPrivProject.getRepos().isEmpty()

        when: "Creating project using public APIs"
        BitbucketProject newPubProject = bb.createProject("NEWPUB$rndNr")

        then:
        newPubProject != null
        newPubProject.key == "NEWPUB$rndNr"
        newPubProject.name.contains(rndNr)
        newPubProject == bb.getProject("NEWPUB$rndNr")
        newPubProject.getRepos().isEmpty()
        !(newPubProject == newPrivProject)

        then: "Getting the raw data, it should match well with what the library returns"
        JsonNode responseRaw = unirestInstance.get("/rest/api/latest/projects").asJson().body
        JSONArray projectsRaw = responseRaw.getObject().get("values") as JSONArray


        bb.getProject(projectsRaw.first().key as String).id.toLong() == projectsRaw.first().id
        bb.getProjects().key.containsAll(projectsRaw.collect { it.key })


        when: "Deleting projects"
        boolean pubDelete = bb.deleteProject(newPubProject.key)
        boolean privDelete = bb.deleteProject(newPrivProject.key)

        then: "Should return true, and fail to find it again"
        pubDelete
        privDelete
        bb.getProject(newPubProject.key) == null
        bb.getProject(newPrivProject.key) == null


        cleanup:
        bb.getProjects().each {it.delete(true)}


    }

}
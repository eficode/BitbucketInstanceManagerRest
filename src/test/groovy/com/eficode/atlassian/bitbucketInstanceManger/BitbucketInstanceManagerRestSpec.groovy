package com.eficode.atlassian.bitbucketInstanceManger

import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest
import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest.BitbucketRepo as BitbucketRepo
import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest.BitbucketCommit as BitbucketCommit
import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest.BitbucketProject as BitbucketProject
import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest.BitbucketChange as BitbucketChange
import com.eficode.devstack.container.impl.BitbucketContainer
import groovy.test.GroovyAssert
import kong.unirest.Unirest
import kong.unirest.UnirestInstance
import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.TrueFileFilter
import org.eclipse.jgit.api.Git
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
 *
 */

class BitbucketInstanceManagerRestSpec extends Specification {


    @Shared
    Logger log = LoggerFactory.getLogger(BitbucketInstanceManagerRestSpec.class)

    @Shared
    static String baseUrl = "http://bitbucket.domain.se:7990"

    @Shared
    static String bitbucketLicensePath = "src/test/testResources/bitbucket/licenses/bitbucketLicense"

    @Shared
    static String restAdmin = "admin"

    @Shared
    static String restPw = "admin"

    @Shared
    UnirestInstance unirestInstance = Unirest.spawnInstance()

    @Shared
    Path projectRoot = new File("").absoluteFile.toPath()

    @Shared
    BitbucketContainer bitbucketContainer = new BitbucketContainer(baseUrl)

    @Shared
    File mainTempDir = Files.createTempDirectory(BitbucketInstanceManagerRestSpec.simpleName).toFile().absoluteFile

    @Shared
    File repoCacheDir = new File(mainTempDir, "main-repo-cache/").absoluteFile


    // run once before the first feature method
    def setupSpec() {

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

        BitbucketInstanceManagerRest bb = setupBb()


        //Cleanup conflicting projects
        bb.getProjects().findAll { it.name == projectName || it.key == projectKey }.each { bb.deleteProject(it, true) }

        //Setup new project and repo
        BitbucketProject sampleProject = bb.createProject(projectName, projectKey)
        BitbucketRepo sampleRepo = bb.createRepo(sampleProject, repoName)

        //Mirror-clone remote repo locally, push all of its branches to the new repo
        File localGitRepoDir = setupLocalGitRepo()
        assert bb.pushToRepo(localGitRepoDir, sampleRepo, true)

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


        //bb.setApplicationProperties(bitbucketLicense, "Bitbucket", baseUrl)

        then:
        bb.status == "RUNNING"
        bb.license.strip() == bitbucketLicense.strip()
        bb.applicationProperties.displayName == instanceDisplayName


    }


    def "Test update of file via API"() {

        setup:
        String projectName = "File update tests"
        String projectKey = "FU"
        String repoName = "Updating files"

        BitbucketRepo sampleRepo = setupRepo(projectName, projectKey, repoName)
        BitbucketInstanceManagerRest bb = sampleRepo.parentObject as BitbucketInstanceManagerRest

        BitbucketCommit lastCommitAtSetup = sampleRepo.getLastCommitInBranch("main")


        when:
        BitbucketCommit updateFileCommit = sampleRepo.updateFile("README.md", "main", "An updated to the file ${new Date()}", "A Commit MSG" + new Date().toString())

        then:
        updateFileCommit != null
        updateFileCommit.message.startsWith("A Commit MSG")
        updateFileCommit.branchName == "main"
        updateFileCommit.parents.first().id == lastCommitAtSetup.id


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
            bb.deleteProject(bbProject, true)
        }

        bbProject = bb.createProject(projectName, projectKey)
        BitbucketRepo bbRepo = bb.createRepo(bbProject, repoName)

        expect:
        //Reading the default settings of a newly created repo
        assert bbRepo.defaultMergeStrategy == "no-ff" : "The new repo got an unexpected default merge strategy"
        assert bbRepo.enabledMergeStrategies == ["no-ff"] : "The new repo got an unexpected enabled merge strategies"
        assert bbRepo.mergeStrategyIsInherited() : "The new repo started with repo-local strategies"


        //Updating repo with new settings, and reading them back
        assert bbRepo.setEnabledMergeStrategies("rebase-no-ff", ["ff-only", "rebase-no-ff"]) : "Error setting Merge Strategies "

        assert bbRepo.defaultMergeStrategy == "rebase-no-ff" : "API does not report the expected default merge strategy after setting it"
        assert bbRepo.enabledMergeStrategies == ["ff-only", "rebase-no-ff"] : "API does not report the expected  merge strategies after setting them"
        assert ! bbRepo.mergeStrategyIsInherited() : "After setting repo-local merge strategies, they are still inherited from project"


        assert bbRepo.enableAllMergeStrategies() : "Error enabling all Merge Strategies"
        assert bbRepo.defaultMergeStrategy == "no-ff" : "Enabling all merge strategies, ended up setting an unexpected default strategy"

        assert bbRepo.enableAllMergeStrategies("ff-only") : "Error enabling all Merge Strategies"
        assert bbRepo.defaultMergeStrategy == "ff-only"


        //Giving the wrong input
        GroovyAssert.shouldFail {bbRepo.setEnabledMergeStrategies("wrong-default" , ["wrong-default", "ff-only"]) }
        GroovyAssert.shouldFail {bbRepo.setEnabledMergeStrategies("ff-only" , ["wrong-strategy", "ff-only"]) }

    }


    def "Test PR CRUD"() {

        Continue here


        setup:
        log.info("Testing Pull Request Config CRUD actions using Bitbucket API")
        String projectName = "Pull Request Actions"
        String projectKey = "PRA"
        String repoName = "CRUDing PRs"

        BitbucketInstanceManagerRest bb = new BitbucketInstanceManagerRest(restAdmin, restPw, baseUrl)
        BitbucketProject bbProject = bb.getProject(projectKey)

        if (bbProject) {
            bb.deleteProject(bbProject, true)
        }

        bbProject = bb.createProject(projectName, projectKey)
        BitbucketRepo bbRepo = bb.createRepo(bbProject, repoName)

        String expectedFileContent = "Initial file content"
        BitbucketCommit creatingFileCommit = bbRepo.createFile("README.md", "master", expectedFileContent, "Creating the file in the master branch")

        expectedFileContent += "\n\nUpdate from second branch"
        BitbucketCommit firstUpdateCommit = bbRepo.updateFile("README.md", "a-separate-branch", expectedFileContent, "A commit in separate branch")


        when:
        true
        //bbRepo.createPullRequestRaw("PR Title", "PR Description")

        then:
        true

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
            bb.deleteProject(bbProject, true)
        }

        bbProject = bb.createProject(projectName, projectKey)
        BitbucketRepo bbRepo = bb.createRepo(bbProject, repoName)


        when: "Setting default branch to non standard value"
        bbRepo.defaultBranch = branchName

        then: "Expected default branch should be returned by API"
        assert bbRepo.defaultBranchName == branchName

        when: "Creating a new file"
        BitbucketCommit createFileCommit = bbRepo.createFile("README.md", branchName, expectedFileText, "Initial Commit" + new Date().toString())

        then: "The new commit should be created in the right branch, with the right commit msg and the file should retrievable with the correct content"
        assert createFileCommit.branchName == branchName : "New commit not performed in the right branch"
        assert createFileCommit.message.contains("Initial Commit") : "New commit got the wrong msg"
        assert bbRepo.getFileContent("README.md").strip()  == expectedFileText.strip() : "Retrieving the file with just a name failed"
        assert bbRepo.getFileContent("README.md", branchName).strip()  == expectedFileText.strip() : "Retrieving the file with name and branch name failed"
        assert bbRepo.getFileContent("README.md", null, createFileCommit.id).strip()  == expectedFileText.strip() : "Retrieving the file with name and commit id failed"


        when:"Creating a new file with an existing file name"
        bbRepo.createFile("README.md", branchName, expectedFileText + "123", "Initial Commit" + new Date().toString())

        then: "An exception should be thrown"
        Exception e = thrown(Exception)
        e.message.contains( "could not be created because it already exists")
        log.info("\tCreation of file tested successfully")


        when: "Updating an existing file"

        expectedFileText = expectedFileText + "\n\nUpdated ${new Date()} \n\n ---- \n"
        BitbucketCommit updateFileCommit =  bbRepo.updateFile("README.md", branchName, expectedFileText, "Updating existing file")

        then: "The new commit should be created in the right branch, with the right commit msg and the file should retrievable with the updated content"
        assert updateFileCommit.branchName == branchName : "New commit not performed in the right branch"
        assert updateFileCommit.message.contains("Updating existing file") : "New commit got the wrong msg"
        assert bbRepo.getFileContent("README.md").strip()  == expectedFileText.strip() : "Retrieving the file with just a name failed"
        assert bbRepo.getFileContent("README.md", branchName).strip()  == expectedFileText.strip(): "Retrieving the file with name and branch name failed"
        assert bbRepo.getFileContent("README.md", null, updateFileCommit.id).strip() == expectedFileText.strip() : "Retrieving the file with name and commit id failed"


        when: "Updating a non existent file"
        bbRepo.updateFile("NonExistentFile.md", branchName, "Some content", "A commit msg")

        then: "An exception should be thrown"
        e = thrown(Exception)
        e.message.contains( "could not be edited")
        log.info("\tUpdate of file tested successfully")


        when:"Prepending a file"
        expectedFileText = "## A new heading\n\n" + expectedFileText

        assert bbRepo.prependFile("README.md", branchName, "## A new heading\n\n", "Added a heading")

        then: "The start of the file should be changed"
        assert bbRepo.getFileContent("README.md").strip()  == expectedFileText.strip()
        log.info("\tPrepend of file tested successfully")

        when: "Appending a file"
        expectedFileText = expectedFileText + "\n\n\t A new tail"
        assert bbRepo.appendFile("README.md", branchName,  "\n\n\t A new tail", "Added a tail")

        then:"The end of the file should be changed, and the REPO should have two new commits"
        assert bbRepo.getFileContent("README.md").endsWith("A new tail")
        assert bbRepo.getCommits(100).message.containsAll(["Added a tail", "Added a heading"])
        log.info("\tAppend of file tested successfully")
    }

    def "Test markdown generation"() {

        setup:
        String projectName = "Markdown Tests"
        String projectKey = "MD"
        String repoName = "MD Tests"

        BitbucketRepo sampleRepo = setupRepo(projectName, projectKey, repoName)
        BitbucketInstanceManagerRest bb = sampleRepo.parentObject as BitbucketInstanceManagerRest

        ArrayList<BitbucketCommit> commits = sampleRepo.getCommits(100)

        //ArrayList<Map> branches = commits.collect {[id: it.displayId, "branches" : it.branches]}
        ArrayList<String> changesMd = commits.collect { it.collect { it.toMarkdown() } }.flatten()

        //File targetDir = new File("target").getAbsoluteFile()
        //targetDir.mkdir()


        when:

        File mDFile = new File(localGitRepoDir, "changes.md")
        mDFile.createNewFile()
        mDFile.text = changesMd.join("\n\n---\n\n\n") //BitbucketChange.markdownHeader + changesMd.take(5).join("\n") + BitbucketChange.markdownFooter
        //bb.addAndCommit(localGitRepoDir, "Testing Markdown", mDFile.name, "Spock", "spock@starship.enterprise")
        //bb.pushToRepo(localGitRepoDir, sampleRepo)


        then:
        true


    }

    def "Test getFileNameTruncated"(String full, String parent, String name) {

        setup:
        BitbucketInstanceManagerRest bb = setupBb()

        ArrayList<Integer> lengths = [36, 5, 10, 32, 33, 34, 35, 36, 37, 38, 39, 40, 62, 98, 500]

        BitbucketChange change = new BitbucketChange(bb)
        change.path = [
                toString: full,
                parent  : parent,
                name    : name
        ]

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
            bb.deleteProject(it, true)
        }


        BitbucketProject sampleProject = bb.createProject("Sample Project", "SMP")
        BitbucketRepo sampleRepo = bb.createRepo(sampleProject, BitbucketInstanceManagerRestSpec.simpleName)

        when: "When pushing the project git repo, to the new bitbucket project/repo"
        File localGitRepoDir = setupLocalGitRepo()
        boolean pushSuccess = bb.pushToRepo(localGitRepoDir, sampleRepo, true)


        then: "Success should be returned"
        pushSuccess
        sampleProject instanceof BitbucketProject
        sampleRepo instanceof BitbucketRepo


        when: "Getting all repo commits, and a subset of the commits"
        ArrayList<BitbucketCommit> allCommits = sampleRepo.getCommits()

        ArrayList<BitbucketCommit> subsetCommits = sampleRepo.getCommits(allCommits[5].id, allCommits[0].id)

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
        true


        cleanup:
        localGitRepoDir.deleteDir()


    }


    def testRepoCrud() {

        setup:
        BitbucketInstanceManagerRest bb = setupBb()

        bb.getProjects().each {
            bb.deleteProject(it, true)
        }

        BitbucketProject sampleProject = bb.createProject("Sample Project", "SMP")

        when:
        BitbucketRepo firstRepo = bb.createRepo(sampleProject, "a repo with spaces")

        then:
        firstRepo != null
        firstRepo.isValid()
        firstRepo == bb.getRepo("SMP", "a repo with spaces")

        when:
        bb.deleteProject(sampleProject, false)

        then:
        Exception ex = thrown(Exception)
        ex.message.contains("cannot be deleted because it has repositories")


        expect:
        bb.deleteRepo(firstRepo)
        bb.deleteProject(sampleProject, false)


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
            bb.deleteProject(it, true)
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
        bb.getRepos(newPrivProject).isEmpty()

        when: "Creating project using public APIs"
        BitbucketProject newPubProject = bb.createProject("NEWPUB$rndNr")

        then:
        newPubProject != null
        newPubProject.key == "NEWPUB$rndNr"
        newPubProject.name.contains(rndNr)
        newPubProject == bb.getProject("NEWPUB$rndNr")
        bb.getRepos(newPubProject).isEmpty()
        !(newPubProject == newPrivProject)

        then: "Getting the raw data, it should match well with what the library returns"
        ArrayList<Map> projectsRaw = unirestInstance.get("/rest/api/latest/projects").asObject(Map).body.get("values") as ArrayList<Map>
        assert projectsRaw instanceof ArrayList
        assert projectsRaw.first() instanceof Map

        BitbucketProject.fromJson(projectsRaw, bb).id == projectsRaw.collect { BitbucketProject.fromJson(it, bb) }.id.flatten() //Verify Converting singel project and list of projects return the same value
        bb.getProjects().key.containsAll(BitbucketProject.fromJson(projectsRaw, bb).key)
        bb.getProject(projectsRaw.first().key as String).id.toLong() == projectsRaw.first().id
        bb.getProjects().key.containsAll(projectsRaw.key)


        when: "Deleting projects"
        boolean pubDelete = bb.deleteProject(newPubProject.key)
        boolean privDelete = bb.deleteProject(newPrivProject.key)

        then: "Should return true, and fail to find it again"
        pubDelete
        privDelete
        bb.getProject(newPubProject.key) == null
        bb.getProject(newPrivProject.key) == null


        cleanup:
        bb.getProjects().each { bb.deleteProject(it, true) }


    }

}
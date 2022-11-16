package com.eficode.atlassian.bitbucketInstanceManger

import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest
import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest.BitbucketRepo as BitbucketRepo
import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest.BitbucketCommit as BitbucketCommit
import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest.BitbucketProject as BitbucketProject
import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest.BitbucketChange as BitbucketChange
import com.eficode.devstack.container.impl.BitbucketContainer
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


    // run before the first feature method
    def setupSpec() {

        unirestInstance.config().defaultBaseUrl(baseUrl).setDefaultBasicAuth(restAdmin, restPw).enableCookieManagement(false)

        //bitbucketContainer.containerImageTag = "8.5.0"
        bitbucketContainer.containerName = bitbucketContainer.extractDomainFromUrl(baseUrl)
        //bitbucketContainer.stopAndRemoveContainer()
        //bitbucketContainer.createContainer()
        //bitbucketContainer.startContainer()
    }


    BitbucketInstanceManagerRest setupBb() {

        return new BitbucketInstanceManagerRest(restAdmin, restPw, baseUrl)
    }

    String getBitbucketLicense() {
        return new File(bitbucketLicensePath).text
    }


    /**
     *
     * Mirrors this project from github
     *
     * @return File object of the new temp folder
     */
    File setupLocalGitRepo() {

        File tempDir = Files.createTempDirectory(BitbucketInstanceManagerRestSpec.simpleName).toFile().absoluteFile

        BitbucketInstanceManagerRest.mirrorRepo(tempDir, "https://github.com/eficode/BitbucketInstanceManagerRest.git")

        //Delete the large packages branch from the local repo
        Git.open(tempDir).branchDelete().setBranchNames("packages").setForce(true).call()

        assert FileUtils.listFilesAndDirs(tempDir, TrueFileFilter.INSTANCE, null).any { it.name == ".git" }

        return tempDir
    }

    @Ignore
    def testSetupOfBase() {

        setup:

        BitbucketInstanceManagerRest bb = setupBb()
        bb.setApplicationProperties(bitbucketLicense, "Bitbucket", baseUrl)

        expect:
        bb.status == "RUNNING"


    }

    def "Test markdown generation"() {

        setup:
        BitbucketInstanceManagerRest bb = setupBb()


        /*
        bb.getProjects().each {
            bb.deleteProject(it, true)
        }
        BitbucketProject sampleProject = bb.createProject("Sample Project", "SMP")
        BitbucketRepo sampleRepo = bb.createRepo(sampleProject, BitbucketInstanceManagerRestSpec.simpleName)

        File localGitRepoDir = setupLocalGitRepo()
        assert bb.pushToRepo(localGitRepoDir, sampleRepo, true)


         */




        File localGitRepoDir = new File("/Users/anders/Downloads/RepoTemp2")
        BitbucketRepo sampleRepo = bb.getRepo("SMP", "VSCODE")



        ArrayList<BitbucketCommit> commits = sampleRepo.getCommits(100)

        //ArrayList<Map> branches = commits.collect {[id: it.displayId, "branches" : it.branches]}
        ArrayList<String> changesMd = commits.collect { it.collect {it.toMarkdown()} }.flatten()

        //File targetDir = new File("target").getAbsoluteFile()
        //targetDir.mkdir()


        when:

        File mDFile = new File(localGitRepoDir, "changes.md")
        mDFile.createNewFile()
        mDFile.text = changesMd.join("\n\n---\n\n\n") //BitbucketChange.markdownHeader + changesMd.take(5).join("\n") + BitbucketChange.markdownFooter
        bb.addAndCommit(localGitRepoDir, "Testing Markdown", mDFile.name, "Spock", "spock@starship.enterprise")
        bb.pushToRepo(localGitRepoDir, sampleRepo)




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
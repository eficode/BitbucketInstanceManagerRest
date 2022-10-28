package com.eficode.atlassian.bitbucketInstanceManger

import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest
import com.eficode.atlassian.bitbucketInstanceManager.entities.BitbucketProject
import com.eficode.atlassian.bitbucketInstanceManager.entities.BitbucketRepo
import kong.unirest.Unirest
import kong.unirest.UnirestInstance
import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.DirectoryFileFilter
import org.apache.commons.io.filefilter.FalseFileFilter
import org.apache.commons.io.filefilter.FileFilterUtils
import org.apache.commons.io.filefilter.IOFileFilter
import org.apache.commons.io.filefilter.NotFileFilter
import org.apache.commons.io.filefilter.TrueFileFilter
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification
import unirest.shaded.com.google.gson.JsonObject

import java.nio.file.Files
import java.nio.file.Path


/**
 * Presumes that the Bitbucket instance at baseUrl
 *      has been started for the first time ever
 *      bitbucketLicensePath points to a file containing a Bitbucket license
 *      baseUrl points to the url (and port) of bitbucket
 *
 *  Example script using devStack: https://github.com/eficode/devStack
 *
 *
 *
 import com.eficode.devstack.container.impl.BitbucketContainer
 BitbucketContainer bitbucketContainer = new BitbucketContainer(dockerHost, dockerCertPath)
 bitbucketContainer.stopAndRemoveContainer()
 bitbucketContainer.createContainer()
 bitbucketContainer.startContainer()
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





    def setupSpec() {

        unirestInstance.config().defaultBaseUrl(baseUrl).setDefaultBasicAuth(restAdmin, restPw).enableCookieManagement(false)
    }


    BitbucketInstanceManagerRest setupBb() {

        return new BitbucketInstanceManagerRest(restAdmin, restPw, baseUrl)
    }

    String getBitbucketLicense() {
        return new File(bitbucketLicensePath).text
    }


    /**
     * Makes sure $projectRoot contains .git
     * Copies the project dir to a new temporary directory
     *
     * @return File object of the new temp folder
     */
    File setupLocalGitRepo() {


        assert FileUtils.listFilesAndDirs(projectRoot.toFile(), TrueFileFilter.INSTANCE, null).any {it.name == ".git"}

        File tempDir = Files.createTempDirectory(BitbucketInstanceManagerRestSpec.simpleName).toFile().absoluteFile


        FileUtils.copyDirectory(projectRoot.toFile(), tempDir)


        assert FileUtils.listFilesAndDirs(tempDir, TrueFileFilter.INSTANCE, null).any {it.name == ".git"}

        return tempDir
    }

    def testSetupOfBase() {

        setup:

        BitbucketInstanceManagerRest bb = setupBb()
        bb.setApplicationProperties(bitbucketLicense, "Bitbucket", baseUrl)

        expect:
        //bb.status == "RUNNING"
        true



    }

    def "Test git actions"(){

        setup:
        BitbucketInstanceManagerRest bb = setupBb()
        bb.getProjects().each {
            bb.deleteProject(it, true)
        }

        BitbucketProject sampleProject =  bb.createProject("Sample Project", "SMP")
        BitbucketRepo sampleRepo = bb.createRepo(sampleProject, BitbucketInstanceManagerRestSpec.simpleName)
        File localGitRepoDir = setupLocalGitRepo()

        when:
        true
        bb.pushToRepo(localGitRepoDir, sampleRepo)

        then:
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

        BitbucketProject sampleProject =  bb.createProject("Sample Project", "SMP")

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


        ArrayList<BitbucketProject> projectsAtStart =  bb.getProjects()

        BitbucketProject projectWithRepo = null



        when:"Creating a project using private APIs"
        String rndNr = System.currentTimeMillis().toString()[-5..-1]
        BitbucketProject newPrivProject = bb.createProject("New Project $rndNr with priv API", "NEWPRIV$rndNr", "A spock project: Using private API")


        then:"Should return the new project, should be able to find it, and should have no repos"
        newPrivProject != null
        newPrivProject.key == "NEWPRIV$rndNr"
        newPrivProject.name == "New Project $rndNr with priv API"
        newPrivProject == bb.getProject("NEWPRIV$rndNr")
        bb.getRepos(newPrivProject).isEmpty()

        when: "Creating project using public APIs"
        BitbucketProject newPubProject = bb.createProject( "NEWPUB$rndNr")

        then:
        newPubProject != null
        newPubProject.key == "NEWPUB$rndNr"
        newPubProject.name.contains(rndNr)
        newPubProject == bb.getProject("NEWPUB$rndNr")
        bb.getRepos(newPubProject).isEmpty()
        !(newPubProject == newPrivProject)

        then: "Getting the raw data, it should match well with what the library returns"
        ArrayList<Map> projectsRaw = unirestInstance.get("/rest/api/latest/projects").asObject(Map).body.get("values") as ArrayList<Map>
        BitbucketProject.fromJson(projectsRaw).id == projectsRaw.collect {BitbucketProject.fromJson(it)}.id.flatten() //Verify Converting singel project and list of projects return the same value
        bb.getProjects().key.containsAll(BitbucketProject.fromJson(projectsRaw).key)
        bb.getProject(projectsRaw.first().key as String).id.toLong() == projectsRaw.first().id
        bb.getProjects().key.containsAll(projectsRaw.key)


        when:"Deleting projects"
        boolean pubDelete = bb.deleteProject(newPubProject.key)
        boolean privDelete = bb.deleteProject(newPrivProject.key)

        then: "Should return true, and fail to find it again"
        pubDelete
        privDelete
        bb.getProject(newPubProject.key) == null
        bb.getProject(newPrivProject.key) == null



        cleanup:
        bb.getProjects().each {bb.deleteProject(it, true)}



    }

}
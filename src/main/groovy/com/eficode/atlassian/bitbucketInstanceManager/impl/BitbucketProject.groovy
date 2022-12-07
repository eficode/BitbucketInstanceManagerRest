package com.eficode.atlassian.bitbucketInstanceManager.impl

import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest
import com.eficode.atlassian.bitbucketInstanceManager.model.BitbucketEntity
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import kong.unirest.HttpResponse
import kong.unirest.JsonNode
import kong.unirest.UnirestInstance
import kong.unirest.json.JSONArray
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import unirest.shaded.com.google.gson.annotations.SerializedName


public class BitbucketProject implements BitbucketEntity {


    static Logger log = LoggerFactory.getLogger(this.class)
    String key
    String id
    String name
    String type
    String description
    Map<String, ArrayList> links

    @JsonProperty("public")
    boolean isPublic


    @Override
    void setParent(BitbucketEntity instance) {
        this.instance = instance as BitbucketInstanceManagerRest
    }



    boolean equals(Object object) {

        return object instanceof BitbucketProject && this.key == object.key && this.id == object.id
    }

    boolean isValid() {

        return isValidJsonEntity() && key && id && name && type

    }


    String toString() {
        return name + "(Key: ${key}, ID:$id)"
    }


    BitbucketProject refreshInfo() {
        return getProject(getInstance(), key)
    }



    /** --- CREATE --- **/

    /**
     * Uses the public REST API available for creating a project, this only allows for setting project key
     * @param instance The bitbucket instance to create the project in
     * @param key Key of the new project
     * @return A BitbucketProject representation of the new project
     */
    static BitbucketProject createProject(BitbucketInstanceManagerRest instance, String key) {

        UnirestInstance unirest = instance.newUnirest

        HttpResponse<JsonNode> response = unirest.post("/rest/api/latest/projects").contentType("application/json").body([key: key]).asJson()

        assert response.status == 201: "Creation of project returned unexpected HTTP status: " + response.status
        assert response.body.object.get("key") == key: "Creation of project returned unexpected JSON: " + response.body

        unirest.shutDown()


        return getProject(instance, key)


    }

    /**
     * This creates a project using private APIs as the native APIs doesn't support setting name or description
     * This should still be safe, but might break after upgrades, for longevity use createProject(String key)
     * @param instance The bitbucket instance to create the project in
     * @param name Project name
     * @param key Project key
     * @param description An optional description
     * @return A BitbucketProject representation of the new project
     */
    static BitbucketProject createProject(BitbucketInstanceManagerRest instance, String name, String key, String description = "") {


        UnirestInstance unirest = instance.getNewUnirest()
        unirest.config().followRedirects(false)
        String createProjectBody = unirest.get("/projects?create").asString().body


        String atlToken = BitbucketInstanceManagerRest.searchBodyForAtlToken(createProjectBody)
        assert atlToken: "Could not find token for form submission"

        HttpResponse response = unirest.post("/projects?create")
                .field("name", name)
                .field("key", key)
                .field("avatar", "")
                .field("description", description)
                .field("submit", "Create project")
                .field("atl_token", atlToken).asEmpty()

        assert response.status == 302: "Creation of project returned unexpected HTTP status: " + response.status
        assert response.headers.get("Location").first().endsWith("/${key.toUpperCase()}"): "Creation of project returned unexpected redirect:" + response?.headers?.get("Location")

        unirest.shutDown()
        return getProject(instance, key)


    }

    /** --- GET --- **/


    static ArrayList<BitbucketProject> getProjects(BitbucketInstanceManagerRest bbInstance, long maxProjects = 25) {

        ArrayList<String> rawProjects = getProjects(bbInstance.newUnirest, maxProjects)
        ArrayList<BitbucketProject> projects = fromJson(rawProjects.toString(), BitbucketProject, bbInstance, bbInstance)
        assert projects.every { it.isValid() }: "Library returned invalid projects"
        return projects

    }

    static ArrayList<String> getProjects(UnirestInstance unirest, long maxProjects = 25) {

        ArrayList<String> rawProjects = getJsonPages(unirest, "/rest/api/latest/projects", maxProjects)
        return rawProjects
    }


    static BitbucketProject getProject(BitbucketInstanceManagerRest bbInstance, String projectKey) {

        ArrayList<JsonNode> rawProject
        try {
            rawProject = getJsonPages(bbInstance.newUnirest, "/rest/api/1.0/projects/$projectKey" as String, 1, [:], false) as ArrayList<JsonNode>
        } catch (AssertionError ex) {
            if (ex.message.containsIgnoreCase("Project $projectKey does not exist")) {
                return null
            } else {
                throw ex
            }
        }


        assert rawProject.size() == 1: "Error getting project with key:" + projectKey

        return fromJson(rawProject.first().toString(), BitbucketProject, bbInstance, bbInstance).find { it.valid } as BitbucketProject
    }


    /** --- DELETE --- **/

    boolean delete(boolean deleteRepos = false) {
        return deleteProject(instance, key, deleteRepos)
    }

    static boolean deleteProject(BitbucketProject project, boolean deleteRepos = false) {
        return deleteProject(project.instance, project.key, deleteRepos)
    }

    /**
     * Deletes project, will fail if the project has repos and deleteRepos is false
     * @param instance The bitbucket instance to delete the project in
     * @param projectKey
     * @param deleteProjectRepos if true, will delete repos if present in project
     * @return
     */
    static boolean deleteProject(BitbucketInstanceManagerRest bbInstance, String projectKey, boolean deleteProjectRepos = false) {

        log.info("Deleting project:" + projectKey)
        UnirestInstance unirest = bbInstance.newUnirest

        HttpResponse<JsonNode> response = unirest.delete("/rest/api/latest/projects/$projectKey").asJson()


        if (response.body.object.has("errors")) {
            JSONArray errors = response.body.object.errors
            ArrayList<String> messages = errors.collect { it.message }

            if (deleteProjectRepos && messages.size() == 1 && messages.first().contains("The project \"${projectKey.toUpperCase()}\" cannot be deleted because it has repositories")) {
                log.info("\tProject has repositories, deleting them now")


                ArrayList<BitbucketRepo> projectRepos = getProject(bbInstance, projectKey).getRepos(100)
                log.info("\t\tRepos:" + projectRepos.name.join(", "))

                assert BitbucketRepo.deleteRepos(projectRepos): "Error deleting project repos"

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


    /** --- REPO CR --- **/

    BitbucketRepo getRepo(String repoNameOrSlug) {
        ArrayList<BitbucketRepo> projectRepos = getRepos()

        BitbucketRepo repo = projectRepos.find {
            it.name == repoNameOrSlug ||
                    it.slug == repoNameOrSlug
        }

        if (!repo){
            log.warn("Could not find repo \"$repoNameOrSlug\" in project \"$this\" ")
        }

        return repo

    }

    ArrayList<BitbucketRepo> getRepos(long maxRepos = 100) {

        return BitbucketRepo.getRepos(this, maxRepos)
    }


    BitbucketRepo createRepo(String repoName) {
        return BitbucketRepo.createRepo(this, repoName)
    }


}

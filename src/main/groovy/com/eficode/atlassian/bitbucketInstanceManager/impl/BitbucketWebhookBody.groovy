package com.eficode.atlassian.bitbucketInstanceManager.impl

import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest
import com.eficode.atlassian.bitbucketInstanceManager.impl.BitbucketRepo
import com.eficode.atlassian.bitbucketInstanceManager.impl.BitbucketProject as BitbucketProject


import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import unirest.shaded.com.google.gson.annotations.SerializedName

import java.lang.reflect.Type

/**
 * Intended to represent the body of a webhook sent by Bitbucket.
 *
 * Information in fields of complex types such as BitbucketRepo, BitbucketBranch etc
 * will be lacking and only contain the data provided by the webhook body
 *
 */

class BitbucketWebhookBody {
    String eventKey
    String date
    Actor actor
    BitbucketRepo repository
    ArrayList<Changes> changes

    static Gson objectMapper = new Gson()


    boolean isValid() {

        return eventKey && repository.isValid() && !changes.empty && changes.branch.every { it instanceof BitbucketBranch }
    }

    String toString() {
        String out = "Event type:\t$eventKey\n" +
                "Time stamp:\t$date\n" +
                "Actor:\t${actor.displayName}\n" +
                "Project:\t${repository.project.name}\n" +
                "Repo:\t${repository.name}\n" +
                "Changes:\n"

        changes.each { change ->
            out += "\t\tCommit:\t" + change.toHash.take(11) + "\n"
            out += "\t\tBranch:\t" + change.branch.displayId + "\n"
            out += "\t\tType:\t" + change.type + "\n"
            out += "\t\tFrom commit:\t" + change.fromHash.take(11) + "\n"

        }

        return out
    }

    /**
     * Get the webhook source instance URL
     * @param jsonString The raw webhook body
     * @return ex: http://bitbucket.domain.se:7990
     */
    static String getInstanceUrl(String jsonString ) {

        Map bodyMap = getMapBody(jsonString)


        ArrayList<String> hrefs = bodyMap?.repository?.links?.self?.href
        String url = hrefs.size() ? hrefs.first() : null
        url = url.contains("/projects") ? url.takeBefore("/projects") : null
        return url
    }

    /**
     * Gets a raw map body representation of the webhook
     * @param json
     * @return A map representation of the webhook
     */
    static Map getMapBody(String jsonString) {
        return objectMapper.fromJson(jsonString, Map)
    }

    static BitbucketWebhookBody fromJson(String jsonString, BitbucketInstanceManagerRest instance) {

        assert jsonString[0] == "{": "Expected a Json object starting with \"{\""

        assert instance.baseUrl == getInstanceUrl(jsonString) : "The URL of the webhook and the BitbucketInstanceManagerRest provided does no match"

        Type webhookBodyType = TypeToken.get(BitbucketWebhookBody).getType()
        BitbucketWebhookBody body = objectMapper.fromJson(jsonString, webhookBodyType)

        //Re-fetch repository to get a proper object
        body.repository = instance.getProject(body.repository.projectKey).getRepo(body.repository.slug)


        assert body.isValid() : "Error creating WebhookBody from input:" + jsonString
        return body
    }

    class Actor {
        String name
        String emailAddress
        boolean active
        String displayName
        Integer id
        String slug
        String type
        LinkedHashMap links
    }

    class Repository {
        String slug
        Integer id
        String name
        String hierarchyId
        String scmId
        String state
        String statusMessage
        boolean forkable
        BitbucketProject project
        @SerializedName("public")
        boolean isPublic
        boolean archived
        Map links
    }

    class Changes {


        BitbucketBranch ref
        String refId
        String fromHash
        String toHash
        String type

        BitbucketBranch getBranch() {
            return ref
        }
    }

}

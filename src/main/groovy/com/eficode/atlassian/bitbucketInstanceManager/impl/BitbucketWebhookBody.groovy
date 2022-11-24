package com.eficode.atlassian.bitbucketInstanceManager.impl

import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest
import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest.BitbucketRepo as BitbucketRepo
import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest.BitbucketProject as BitbucketProject
import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest.BitbucketBranch as BitbucketBranch

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
        return eventKey && repository && !changes.empty && changes.branch.every { it instanceof BitbucketBranch }
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

    static BitbucketWebhookBody fromJson(String jsonString) {

        assert jsonString[0] == "{": "Expected a Json object starting with \"{\""


        Type webhookBodyType = TypeToken.get(BitbucketWebhookBody).getType()
        return objectMapper.fromJson(jsonString, webhookBodyType)
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

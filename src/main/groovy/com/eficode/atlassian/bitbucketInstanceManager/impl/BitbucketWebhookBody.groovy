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
        return eventKey && repository && !changes.empty && changes.branch.every {it instanceof BitbucketBranch}
    }

    static BitbucketWebhookBody fromJson(String jsonString) {

        assert jsonString[0] == "{" : "Expected a Json object starting with \"{\""



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

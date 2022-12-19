package com.eficode.atlassian.bitbucketInstanceManager.impl

import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest
import com.eficode.atlassian.bitbucketInstanceManager.impl.BitbucketRepo
import com.eficode.atlassian.bitbucketInstanceManager.impl.BitbucketProject as BitbucketProject
import com.eficode.atlassian.bitbucketInstanceManager.model.BitbucketEntity
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.gson.reflect.TypeToken
import unirest.shaded.com.google.gson.annotations.SerializedName

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.lang.reflect.Type

/**
 * Intended to represent the body of a webhook sent by Bitbucket.
 *
 * Information in some fields of complex types such as, BitbucketBranch etc
 * will be lacking and only contain the data provided by the webhook body
 *
 */

class BitbucketWebhookBody implements BitbucketEntity {
    String eventKey
    String date
    Actor actor
    BitbucketRepo repository
    ArrayList<BitbucketWebhookChange> changes
    String rawBody


    @Override
    void setParent(BitbucketEntity repo) {

        this.repository = repo as BitbucketRepo
        this.changes.each {
            it.setParent(repo)
        }

    }

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
            BitbucketPullRequest pr = change?.toCommit?.pullRequest

            out += "\t\tCommit:\t" + change.toHash.take(11) + "\n"
            out += "\t\tBranch:\t" + change.branch.displayId + "\n"
            out += "\t\tType:\t" + change.type + "\n"
            out += "\t\tFrom commit:\t" + change.fromHash.take(11) + "\n"

            if (pr) {
                out += "\t\tPull Request:\t" + pr.toString() + "\n"
                out += "\t\tPR Approved by:\t" + pr.approvers.collect {it.toString()}.join(",") + "\n"
            }

        }

        return out
    }

    /**
     * Get the webhook source instance URL from a webhook json body
     * @param jsonString The raw webhook body
     * @return ex: http://bitbucket.domain.se:7990
     */
    static String getInstanceUrl(String jsonString) {

        Map bodyMap = getMapBody(jsonString)


        ArrayList<String> hrefs = bodyMap?.repository?.links?.self?.href
        String url = hrefs.size() ? hrefs.first() : null


        url = url.contains("/projects") ? url.take(url.indexOf("/projects")) : null

        return url
    }

    BitbucketProject getProject() {
        return repository?.project
    }

    /**
     * Gets a raw map body representation of the webhook
     * @param json
     * @return A map representation of the webhook
     */
    static Map getMapBody(String jsonString) {
        return objectMapper.readValue(jsonString, Map)
    }

    /**
     * Checks if the body has a correct signature <br>
     * Requires that a "Secret" has been setup for the webhook in bitbucket
     * @param secret The secret that the webhook is configured to use
     * @param requestSignature The signature of the webhook requested, stored in the "X-Hub-Signature" header
     * @return true if a signature is present and valid
     */
    boolean hasValidSignature(String secret, String requestSignature) {


        Mac mac = Mac.getInstance("HmacSHA256")
        SecretKeySpec secretKeySpec = new SecretKeySpec(secret.getBytes(), "HmacSHA256")
        mac.init(secretKeySpec)
        byte[] digest = mac.doFinal(rawBody.getBytes("UTF-8"))

        String calculatedSignature = digest.encodeHex().toString()


        return requestSignature == "sha256=" + calculatedSignature
    }

    static BitbucketWebhookBody fromJson(String jsonString, BitbucketInstanceManagerRest instance) {

        assert jsonString[0] == "{": "Expected a Json object starting with \"{\""

        assert instance.baseUrl == getInstanceUrl(jsonString): "The URL of the webhook and the BitbucketInstanceManagerRest provided does no match"


        BitbucketWebhookBody body = objectMapper.readValue(jsonString, BitbucketWebhookBody.class)
        body.rawBody = jsonString

        //Re-fetch repository to get a proper object
        body.setParent(instance.getProject(body.repository.projectKey).getRepo(body.repository.slug))


        assert body.isValid(): "Error creating WebhookBody from input:" + jsonString
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


}

package com.eficode.atlassian.bitbucketInstanceManager.impl


import com.eficode.atlassian.bitbucketInstanceManager.model.BitbucketEntity
import com.eficode.atlassian.bitbucketInstanceManager.model.WebhookEventType
import kong.unirest.HttpResponse
import kong.unirest.JsonNode
import kong.unirest.UnirestInstance
import org.slf4j.LoggerFactory
import org.slf4j.Logger
import unirest.shaded.com.google.gson.JsonObject

class BitbucketWebhook implements BitbucketEntity {


    int id
    String name
    long createdDate
    long updatedDate
    Map configuration
    String url
    ArrayList<WebhookEventType> events
    boolean active
    BitbucketRepo repo
    Logger log = LoggerFactory.getLogger(this.class)


    /*
    BitbucketWebhook(){}

    BitbucketWebhook(BitbucketRepo repo) {

        this.repo = repo

    }

     */

    @Override
    BitbucketRepo getParent() {
        return this.repo
    }

    @Override
    void setParent(BitbucketEntity repo) {

        assert repo instanceof BitbucketRepo
        this.repo = repo as BitbucketRepo

        assert this.repo instanceof BitbucketRepo
    }

    @Override
    boolean isValid() {
        isValidJsonEntity() &&
                name &&
                id &&
                url &&
                repo &&
                parent &&
                instance &&
                events.every { it instanceof WebhookEventType }
    }

    String getRepositorySlug() {
        return repo?.slug
    }

    String getProjectKey() {
        return repo?.project?.key
    }

    String getSecret() {
        return configuration?.secret
    }


    /**
     * Get any webhook in the repo that has at least one of the supplied events enebled
     * @param repo
     * @param events Return only webhooks that has at least one of these events enabled, if [] all webhooks will be returned
     * @param maxReturns
     * @param unirest
     * @return
     */
    static ArrayList<BitbucketWebhook> getWebhooks(BitbucketRepo repo, ArrayList<WebhookEventType> events = [], long maxReturns, UnirestInstance unirest) {

        //TODO Should use unirest from BitbucketRepo
        String url = "/rest/api/latest/projects/${repo.projectKey}/repos/${repo.repositorySlug}/webhooks"


        ArrayList<JsonObject> rawResponse = getJsonPages(unirest, url, maxReturns, [event: getEventNames(events)], true)

        ArrayList<BitbucketWebhook> hooks = fromJson(rawResponse.toString(), BitbucketWebhook, repo.instance, repo)

        hooks.every { it.isValid() }

        return hooks
    }


    static BitbucketWebhook createWebhook(String name, String remoteUrl, BitbucketRepo repo, ArrayList<WebhookEventType> events, String secret = "", UnirestInstance unirest) {
        //TODO Should use unirest from BitbucketRepo
        String url = "/rest/api/latest/projects/${repo.projectKey}/repos/${repo.repositorySlug}/webhooks"

        if (events.isEmpty()) {
            events = WebhookEventType.values()
        }

        Map outBody = [
                active: true,
                events: getEventNames(events),
                name  : name,
                url   : remoteUrl
        ]

        if (secret) {
            outBody.put("configuration",
                    [
                            secret: secret
                    ]
            )
        }

        HttpResponse responseRaw = unirest.post(url).body(outBody).contentType("application/json").asJson()
        unirest.shutDown()

        assert responseRaw.status == 201: "Error creating Webhook, API returned stauts ${responseRaw.status}, and body:" + responseRaw?.body


        ArrayList<BitbucketWebhook> hooks = fromJson(responseRaw.body.toString(), BitbucketWebhook, repo.instance, repo)


        assert hooks.size() == 1: "Library failed to parse response from API:" + responseRaw.body.toPrettyString()

        return hooks.first()

    }

    String toString() {
        return this.name + " (${this.id}) in ${projectKey}/${repo?.name}"
    }

    static boolean deleteWebhook(BitbucketWebhook webhook) {

        assert webhook.isValid(): "Cant delete webhook ${webhook?.name}, it´s invalid"
        String url = "/rest/api/latest/projects/${webhook.repo.projectKey}/repos/${webhook.repo.repositorySlug}/webhooks/" + webhook.id

        UnirestInstance unirest =  webhook.newUnirest
        HttpResponse response = unirest.delete(url).asEmpty()
        unirest.shutDown()

        assert response.status == 204: "Error deleting ${webhook.toString()}, API returned status ${response.status}"

        return true

    }


    //Get the name/value used by Bitbucket API to refer to an Event type
    static ArrayList<String> getEventNames(ArrayList<WebhookEventType> events) {
        return events.collect { getSerializedName(it) }
    }


    /**
     * Gets an object representing the last invocation of the webhook
     * @return BitbucketWebhookInvocation
     */
    BitbucketWebhookInvocation getLastInvocation() {

        log ?:  (this.log = LoggerFactory.getLogger(this.class))
        log.info("Getting last invocation for webhook:" + toString())
        ArrayList<JsonNode> rawInvocation = getJsonPages(newUnirest, "/rest/api/1.0/projects/${projectKey}/repos/${repo.slug}/webhooks/${id}/latest",1,[:],false)


        if(rawInvocation.isEmpty() || rawInvocation.first().object.isEmpty()) {
            log.info("\tWebhook has not been invoked")
            return null
        }

        BitbucketWebhookInvocation invocation = BitbucketWebhookInvocation.fromJson(rawInvocation.first().toString())
        log.info("\tGot invocation:" + invocation.id)
        return invocation


    }
}




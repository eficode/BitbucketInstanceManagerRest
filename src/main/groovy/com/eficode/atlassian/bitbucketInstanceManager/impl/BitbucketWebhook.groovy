package com.eficode.atlassian.bitbucketInstanceManager.impl

import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest
import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest.BitbucketRepo as BitbucketRepo

import com.eficode.atlassian.bitbucketInstanceManager.model.BitbucketJsonEntity2
import com.eficode.atlassian.bitbucketInstanceManager.model.WebhookEventType
import com.google.gson.annotations.SerializedName
import kong.unirest.HttpResponse
import kong.unirest.UnirestInstance
import org.slf4j.LoggerFactory
import org.slf4j.Logger
import unirest.shaded.com.google.gson.JsonObject

import java.lang.reflect.Field


class BitbucketWebhook implements BitbucketJsonEntity2 {


    int id
    String name
    long createdDate
    long updatedDate
    Map configuration
    String url
    ArrayList<WebhookEventType> events
    boolean active
    BitbucketRepo repo
    static Logger log = LoggerFactory.getLogger(this.class)


    BitbucketWebhook(BitbucketRepo repo) {
        this.repo = repo
    }

    @Override
    BitbucketRepo getParent() {
        return this.repo
    }

    @Override
    void setParent(Object repo) {

        assert repo instanceof BitbucketRepo
        this.repo = repo as BitbucketRepo

        assert this.repo instanceof BitbucketRepo
    }

    @Override
    boolean isValid() {
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

        if (events) {
            url += "?" + getEventNames(events).collect { "event=" + it }.join("&")

        }

        ArrayList<JsonObject> rawResponse = getJsonPages(unirest, url, maxReturns, true)

        ArrayList<BitbucketWebhook> hooks = fromJson(rawResponse.toString(), BitbucketWebhook, repo.parentObject as BitbucketInstanceManagerRest, repo)

        hooks.every { it.isValid() }

        return hooks
    }


    static BitbucketWebhook createWebhook(String name, String remoteUrl, BitbucketRepo repo, ArrayList<WebhookEventType> events, String secret = "", UnirestInstance unirest) {
        //TODO Should use unirest from BitbucketRepo
        String url = "/rest/api/latest/projects/${repo.projectKey}/repos/${repo.repositorySlug}/webhooks"


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

        assert responseRaw.status == 201: "Error creating Webhook, API returned stauts ${responseRaw.status}, and body:" + responseRaw?.body


        ArrayList<BitbucketWebhook> hooks = fromJson(responseRaw.body.toString(), BitbucketWebhook, repo.parentObject as BitbucketInstanceManagerRest, repo)


        assert hooks.size() == 1: "Library failed to parse response from API:" + responseRaw.body.toPrettyString()

        return hooks.first()

    }

    String toString() {
        return this.name + " (${this.id}) in ${projectKey}/${repo?.name}"
    }

    static boolean deleteWebhook(BitbucketWebhook webhook) {

        assert webhook.isValid(): "Cant delete webhook ${webhook?.name}, it´s invalid"
        String url = "/rest/api/latest/projects/${webhook.repo.projectKey}/repos/${webhook.repo.repositorySlug}/webhooks/" + webhook.id

        HttpResponse response = webhook.newUnirest.delete(url).asEmpty()

        assert response.status == 204: "Error deleting ${webhook.toString()}, API returned status ${response.status}"

        return true

    }


    static ArrayList<String> getEventNames(ArrayList<WebhookEventType> events) {
        return events.collect { getEventName(it) }
    }

    static String getEventName(WebhookEventType event) {

        //https://clevercoder.net/2016/12/12/getting-annotation-value-enum-constant/
        Field f = event.getClass().getField(event.name())
        SerializedName a = f.getAnnotation(SerializedName.class)
        return a.value()
    }


}




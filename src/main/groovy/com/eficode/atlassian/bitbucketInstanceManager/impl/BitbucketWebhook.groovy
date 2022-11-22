package com.eficode.atlassian.bitbucketInstanceManager.impl

import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest
import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest.BitbucketRepo as BitbucketRepo

import com.eficode.atlassian.bitbucketInstanceManager.model.BitbucketJsonEntity2
import com.google.gson.annotations.SerializedName
import kong.unirest.HttpResponse
import kong.unirest.JsonNode
import kong.unirest.UnirestInstance
import org.slf4j.LoggerFactory
import org.slf4j.Logger
import unirest.shaded.com.google.gson.JsonObject

import java.lang.reflect.Field


class BitbucketWebhook implements BitbucketJsonEntity2 {


    String name
    Map configuration
    String url
    ArrayList<Event> events
    boolean active
    BitbucketRepo repo
    static Logger log = LoggerFactory.getLogger(this.class)


    BitbucketWebhook(BitbucketRepo repo) {
        this.repo = repo
    }

    @Override
    BitbucketRepo getParent() {
        return repo
    }

    @Override
    void setParent(Object repo) {

        assert repo instanceof BitbucketRepo
        this.repo = repo as BitbucketRepo
    }

    @Override
    boolean isValid() {
        name && configuration && url && repo && parent && instance && events.every { it instanceof Event }
    }

    String getRepositorySlug() {
        return repo.slug
    }

    String getProjectKey() {
        return repo.project.key
    }


    /**
     * Get any webhook in the repo that has at least one of the supplied events enebled
     * @param repo
     * @param events Return only webhooks that has at least one of these events enabled, if [] all webhooks will be returned
     * @param maxReturns
     * @param unirest
     * @return
     */
    static ArrayList<BitbucketWebhook> getWebhooks(BitbucketRepo repo, ArrayList<Event> events = [], long maxReturns, UnirestInstance unirest) {

        String url = "/rest/api/latest/projects/${repo.projectKey}/repos/${repo.repositorySlug}/webhooks"

        if (events) {
            url += "?" + getEventNames(events).collect { "event=" + it }.join("&")

        }

        ArrayList<JsonObject> rawResponse = getJsonPages(unirest, url, maxReturns, true)

        ArrayList<BitbucketWebhook> hooks = fromJson(rawResponse.toString(), BitbucketWebhook, repo.parentObject as BitbucketInstanceManagerRest, repo)

        hooks.every { it.isValid() }

        //log.info("\n" + hooks.first().events.collect {"@SerializedName(\"$it\")\n${it.toString().toUpperCase().replace(":","_")}"}.sort().join(",\n") )

        return hooks
    }


    static BitbucketWebhook createWebhook(String name, String remoteUrl, BitbucketRepo repo, ArrayList<Event> events = [], String secret = "", UnirestInstance unirest) {

        String url = "/rest/api/latest/projects/${repo.projectKey}/repos/${repo.repositorySlug}/webhooks"

        Map outBody = [
                active: true,
                events: getEventNames(events),
                name : name,
                url : remoteUrl
        ]

        if (secret) {
            outBody.put("configuration",
                    [
                            secret: secret
                    ]
            )
        }

        HttpResponse responseRaw = unirest.post(url).body(outBody).contentType("application/json").asJson()

        assert responseRaw.status == 201 : "Error creating Webhook, API returned stauts ${responseRaw.status}, and body:" + responseRaw?.body


        return null

    }


    static ArrayList<String> getEventNames(ArrayList<Event> events) {
        return events.collect { getEventName(it) }
    }

    static String getEventName(Event event) {

        //https://clevercoder.net/2016/12/12/getting-annotation-value-enum-constant/
        Field f = event.getClass().getField(event.name())
        SerializedName a = f.getAnnotation(SerializedName.class)
        return a.value()
    }


    public enum Event {


        @SerializedName("mirror:repo_synchronized")
        MIRROR_REPO_SYNCHRONIZED,
        @SerializedName("pr:comment:added")
        PR_COMMENT_ADDED,
        @SerializedName("pr:comment:deleted")
        PR_COMMENT_DELETED,
        @SerializedName("pr:comment:edited")
        PR_COMMENT_EDITED,
        @SerializedName("pr:declined")
        PR_DECLINED,
        @SerializedName("pr:deleted")
        PR_DELETED,
        @SerializedName("pr:from_ref_updated")
        PR_FROM_REF_UPDATED,
        @SerializedName("pr:merged")
        PR_MERGED,
        @SerializedName("pr:modified")
        PR_MODIFIED,
        @SerializedName("pr:opened")
        PR_OPENED,
        @SerializedName("pr:reviewer:approved")
        PR_REVIEWER_APPROVED,
        @SerializedName("pr:reviewer:needs_work")
        PR_REVIEWER_NEEDS_WORK,
        @SerializedName("pr:reviewer:unapproved")
        PR_REVIEWER_UNAPPROVED,
        @SerializedName("pr:reviewer:updated")
        PR_REVIEWER_UPDATED,
        @SerializedName("repo:comment:added")
        REPO_COMMENT_ADDED,
        @SerializedName("repo:comment:deleted")
        REPO_COMMENT_DELETED,
        @SerializedName("repo:comment:edited")
        REPO_COMMENT_EDITED,
        @SerializedName("repo:forked")
        REPO_FORKED,
        @SerializedName("repo:modified")
        REPO_MODIFIED,
        @SerializedName("repo:refs_changed")
        REPO_REFS_CHANGED


    }
}




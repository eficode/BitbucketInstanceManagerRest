package com.eficode.atlassian.bitbucketInstanceManager.impl

import com.eficode.atlassian.bitbucketInstanceManager.model.WebhookEventType
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

import java.lang.reflect.Type

/**
 * Represents an invocation of a Webhook
 */

class BitbucketWebhookInvocation {

    String id
    WebhookEventType event
    Integer duration
    Long start
    Long finish
    Request request
    Result result
    static Gson objectMapper = new Gson()

    class Request {
        String url
        LinkedHashMap headers
        String method
        String body
    }
    class Result {
        String description
        LinkedHashMap headers
        String outcome
        String body
        Integer statusCode
    }



    static BitbucketWebhookInvocation fromJson(String jsonString) {
        Type webhookBodyType = TypeToken.get(BitbucketWebhookInvocation).getType()
        BitbucketWebhookInvocation invocation = objectMapper.fromJson(jsonString, webhookBodyType)

        return invocation

    }


}

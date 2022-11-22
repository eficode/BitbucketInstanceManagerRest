package com.eficode.atlassian.bitbucketInstanceManager.model

import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kong.unirest.HttpResponse
import kong.unirest.JsonNode
import kong.unirest.UnirestInstance
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.lang.reflect.Field
import java.lang.reflect.Type

trait BitbucketJsonEntity2 {


    //log.info("\n" + hooks.first().events.collect {"@SerializedName(\"$it\")\n${it.toString().toUpperCase().replace(":","_")}"}.sort().join(",\n") )



    abstract static Logger log
    BitbucketInstanceManagerRest instance
    abstract Object parent
    static Gson objectMapper = new Gson()

    static ArrayList<JsonNode> getJsonPages(UnirestInstance unirest, String subPath, long maxResponses, boolean returnValueOnly = true) {


        int start = 0
        boolean isLastPage = false

        ArrayList responses = []

        while (!isLastPage && start >= 0) {


            HttpResponse<JsonNode> response = unirest.get(subPath).accept("application/json").queryString("start", start).asJson()


            isLastPage = response?.body?.object?.has("isLastPage") ? response?.body?.object?.get("isLastPage") as boolean : true
            //start = response?.body?.object?.has("nextPageStart") ? response?.body?.object?.get("nextPageStart") as int : -1
            start = response?.body?.object?.has("nextPageStart") && response.body.object["nextPageStart"] != null ? response.body.object["nextPageStart"] as int : -1

            if (returnValueOnly) {
                if (response.body.object.has("values")) {

                    responses += response.body.object.get("values") as ArrayList<Map>
                } else {

                    throw new InputMismatchException("Unexpected body returned from $subPath, expected JSON with \"values\"-node but got: " + response.body.toString())
                }

            } else {
                responses += response.body
            }

            if (maxResponses != 0) {
                if (responses.size() > maxResponses) {
                    responses = responses[0..maxResponses - 1]
                    break
                } else if (responses.size() == maxResponses) {
                    break
                }
            }


        }

        unirest.shutDown()
        return responses


    }

    static ArrayList<BitbucketJsonEntity2> fromJson(String rawJson, Class clazz, BitbucketInstanceManagerRest instance, Object parent) {

        Type type
        ArrayList<BitbucketJsonEntity2> result


        if (rawJson.startsWith("[")) {

            type = TypeToken.getParameterized(ArrayList.class, clazz).getType()

            result = getObjectMapper().fromJson(rawJson, type)
        } else if (rawJson.startsWith("{")) {
            type = TypeToken.get(clazz).getType()
            result = [getObjectMapper().fromJson(rawJson, type)]
        } else {
            throw new InputMismatchException("Unexpected json format:" + rawJson.take(15))
        }

        result.each {
            it.setParent(parent)
            it.instance = instance
        }



        return result


    }


    UnirestInstance getNewUnirest() {
        return instance.newUnirest
    }



    abstract boolean isValid()


}
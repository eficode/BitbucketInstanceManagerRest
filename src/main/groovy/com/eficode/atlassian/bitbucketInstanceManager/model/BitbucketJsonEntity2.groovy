package com.eficode.atlassian.bitbucketInstanceManager.model

import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
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

    static ArrayList<JsonNode> getJsonPages(UnirestInstance unirest, String subPath, long maxResponses, Map<String, Object> urlParameters = [:], boolean returnValueOnly = true) {


        int start = 0
        boolean isLastPage = false

        ArrayList responses = []


        Map<String, Object> parsedParams = urlParameters.findAll { it.value || it.value == 0 } // Remove empty values

        String parameterString = "?"

        parsedParams.eachWithIndex { key, value, index ->
            //Create key=value1&key=value2... for arrays
            if (value instanceof ArrayList) {
                parameterString += value.collect { key + "=" + it }.join("&")
            }else {
                parameterString += key + "=" + value
            }
            //If not the last parameter, append &
            if (!(parsedParams.size()  == index + 1)) {
                parameterString += "&"
            }
        }



        parameterString = (parameterString == "?" ? "" : parameterString)



        while (!isLastPage && start >= 0) {


            HttpResponse<JsonNode> response = unirest.get(subPath + parameterString ).accept("application/json").queryString("start", start).asJson()


            assert response.status >= 200 && response.status < 300: "Error getting JSON from API got return status: " + response.status + " and body: " + response?.body?.toPrettyString()

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

    //TODO move to enum trait/interface
    static String getSerializedName(Enum en) {

        //https://clevercoder.net/2016/12/12/getting-annotation-value-enum-constant/
        Field f = en.getClass().getField(en.name())
        SerializedName a = f.getAnnotation(SerializedName.class)
        return a.value()
    }

    UnirestInstance getNewUnirest() {
        return instance.newUnirest
    }


    abstract boolean isValid()


}
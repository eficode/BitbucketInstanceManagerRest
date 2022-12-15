package com.eficode.atlassian.bitbucketInstanceManager.model

import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest
import com.eficode.atlassian.bitbucketInstanceManager.impl.BitbucketPullRequest
import com.eficode.atlassian.bitbucketInstanceManager.impl.BitbucketRepo
import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.type.ArrayType
import com.google.gson.reflect.TypeToken
import kong.unirest.HttpResponse
import kong.unirest.JsonNode
import kong.unirest.UnirestInstance
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.transform.trait.Traits
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.lang.reflect.Field
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

trait BitbucketEntity {


    //log.info("\n" + hooks.first().events.collect {"@SerializedName(\"$it\")\n${it.toString().toUpperCase().replace(":","_")}"}.sort().join(",\n") )


    static abstract Logger log
    static Logger entityLog = LoggerFactory.getLogger(BitbucketEntity.class)
    private BitbucketInstanceManagerRest localInstance


    static ObjectMapper objectMapper = new ObjectMapper()


    @JsonAnySetter
    Map<String, Object> dynamicValues = [:]

    BitbucketInstanceManagerRest getInstance() {
        return this.localInstance
    }



    static {
        //Add Groovy 3 functionality, even if Groovy 2
        String.metaClass.takeRight = {int num->
            String src = delegate.toString()
            return src.substring(src.length() - num, src.length())
        }
    }

    abstract void setParent(BitbucketEntity parent)

    /**
     * Returns true if the field should have an instance set
     */

    static boolean fieldNeedsInstance(Field field) {

        ArrayList<Class> fieldTraits


        if (field.name.startsWith("com_") || field.type == BitbucketInstanceManagerRest) {
            return false //Ignored inherited fields and BitbucketInstanceManagerRest
        } else if (field.type == ArrayList) {
            //Get parameter type of fields that are arrays (ex: ArrayList<BitbucketRepo> -> BitbucketEntity)
            Type parameterizedType = field.getGenericType()
            if (parameterizedType instanceof  ParameterizedType) {
                ArrayList<Class> types = parameterizedType.actualTypeArguments as ArrayList<Class>
                fieldTraits = types.collect { type -> Traits.findTraits(new ClassNode(type)).typeClass}.flatten()
            }else {
                return false
            }


        } else {
            //Get trait of field type (ex: BitbucketRepo -> BitbucketEntity)
            fieldTraits = Traits.findTraits(new ClassNode(field.type)).typeClass
        }

        return fieldTraits == [BitbucketEntity]

    }

    void setInstance(BitbucketInstanceManagerRest instance) {

        entityLog.info("For object: " + this.toString() + " (${getClass().simpleName})")
        entityLog.info("\tSetting instance to: " + instance.toString())
        this.localInstance = instance


        ArrayList<Field> entityFields = getClass().declaredFields.findAll {fieldNeedsInstance(it)}


        if (entityFields) {

            entityFields.each { field ->


                boolean accessible = field.canAccess(this)
                accessible ? null : field.setAccessible(true)

                def childObject = field.get(this)

                //If field contains a BitbucketEntity
                if (childObject instanceof BitbucketEntity) {

                    //If instance is not already set
                    if (!childObject.getInstance()) {
                        entityLog.debug("\t\tUpdating object child field: " + field.name + " (${field.type.simpleName})")
                        childObject.invokeMethod("setInstance", instance)
                    }
                //If field contains an array of BitbucketEntity´s
                } else if (childObject instanceof ArrayList && fieldNeedsInstance(field)) {
                    entityLog.debug("\t\tUpdating object child field: " + field.name + " (${field.type.simpleName})")
                    childObject.each {
                        entityLog.debug("\t\tSetting instance on: " + it )
                        it.setInstance(instance)
                    }
                } else {
                    entityLog.trace("\t\tObject child field is empty, not updating: " + field.name + " (${field.type.simpleName})")
                }

                accessible ? null : field.setAccessible(false)
            }
        }


    }


    static String createUrlParameterString(Map<String, Object> urlParameters) {

        Map<String, Object> parsedParams = urlParameters.findAll { it.value || it.value == 0 } // Remove empty values

        String parameterString = "?"

        parsedParams.eachWithIndex { key, value, index ->
            //Create key=value1&key=value2... for arrays
            if (value instanceof ArrayList) {
                parameterString += value.collect { key + "=" + it }.join("&")
            } else {
                parameterString += key + "=" + value
            }
            //If not the last parameter, append &
            if (!(parsedParams.size() == index + 1)) {
                parameterString += "&"
            }
        }


        parameterString = (parameterString == "?" ? "" : parameterString)

        return parameterString
    }

    static ArrayList<JsonNode> getJsonPages(UnirestInstance unirest, String subPath, long maxResponses, Map<String, Object> urlParameters = [:], boolean returnValueOnly = true) {


        int start = 0
        boolean isLastPage = false

        ArrayList responses = []


        String parameterString = createUrlParameterString(urlParameters)


        while (!isLastPage && start >= 0) {


            HttpResponse<JsonNode> response = unirest.get(subPath + parameterString).accept("application/json").queryString("start", start).asJson()


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

    static ArrayList<BitbucketEntity> fromJson(String rawJson, Class clazz, BitbucketInstanceManagerRest instance, BitbucketEntity parent) {


        entityLog.info("Creating ${clazz.simpleName} from json")
        entityLog.debug("\tWith instance:" + instance.baseUrl)
        entityLog.trace("\tWith JSON:" + rawJson)

        ArrayList<BitbucketEntity> result


        if (rawJson.startsWith("[")) {

            ArrayType type = getObjectMapper().getTypeFactory().constructArrayType(clazz)

            result = getObjectMapper().readValue(rawJson, type) as ArrayList<BitbucketEntity>
        } else if (rawJson.startsWith("{")) {

            result = [getObjectMapper().readValue(rawJson, clazz)] as ArrayList<BitbucketEntity>
        } else {
            throw new InputMismatchException("Unexpected json format:" + rawJson.take(15))
        }

        entityLog.debug("\tCreated ${result.size()} " + clazz.simpleName + " object" + (result.size() > 1 ? "s" : ""))
        result.each {
            it.setInstance(instance)
            it.setParent(parent)
        }

        assert result.every {it.isValid()} : "Error creating valid ${clazz.simpleName} from JSON"

        return result


    }


    static ArrayList<Map> jsonPagesToGenerics(ArrayList jsonPages) {

        return objectMapper.readValue(jsonPages.toString(), Map[].class)
    }

    static Map jsonPagesToGenerics(JsonNode jsonNode) {

        return objectMapper.readValue(jsonNode.toString(), Map.class)
    }

    //TODO move to enum trait/interface
    static String getSerializedName(Enum en) {

        //https://clevercoder.net/2016/12/12/getting-annotation-value-enum-constant/
        Field f = en.getClass().getField(en.name())
        JsonProperty a = f.getAnnotation(JsonProperty.class)
        return a.value()
    }

    UnirestInstance getNewUnirest() {
        return instance.newUnirest
    }


    String getBaseUrl() {
        return instance.baseUrl
    }

    abstract boolean isValid()

    boolean isValidJsonEntity() {


        assert this.getInstance() instanceof BitbucketInstanceManagerRest
        this.getInstance() instanceof BitbucketInstanceManagerRest

    }


    /** --- GET --- **/
    /**
     * All static methods
     *
     * isValid uses isValidJsonEntity()
     */


}
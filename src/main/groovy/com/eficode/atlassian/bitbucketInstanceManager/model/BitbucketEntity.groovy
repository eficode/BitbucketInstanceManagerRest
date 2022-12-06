package com.eficode.atlassian.bitbucketInstanceManager.model

import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest
import com.eficode.atlassian.bitbucketInstanceManager.impl.BitbucketPullRequest
import com.eficode.atlassian.bitbucketInstanceManager.impl.BitbucketRepo
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
import java.lang.reflect.Type

trait BitbucketEntity {


    //log.info("\n" + hooks.first().events.collect {"@SerializedName(\"$it\")\n${it.toString().toUpperCase().replace(":","_")}"}.sort().join(",\n") )


    static abstract Logger log
    static Logger entityLog = LoggerFactory.getLogger(BitbucketEntity.class)
    private BitbucketInstanceManagerRest localInstance
    BitbucketEntity localParent

    static ObjectMapper objectMapper = new ObjectMapper()

    BitbucketInstanceManagerRest getInstance() {
        return this.localInstance
    }

    void setInstance(BitbucketInstanceManagerRest instance) {

        entityLog.info("For object: " +  this.toString()  + " (${getClass().simpleName})")
        entityLog.info("\tSetting instance to: " + instance.toString())
        this.localInstance = instance

        ArrayList<Field> entityFields = getClass().declaredFields.findAll {it.type != BitbucketInstanceManagerRest && Traits.findTraits(new ClassNode(it.type)).size() == 1}

        entityFields.removeAll{it.name.startsWith("com_")}

        if (entityFields) {
            entityLog.debug("\tObject has fields that are BitbucketEntity's, setting instance for them as well")

            entityFields.each {field ->

                boolean accessible = field.canAccess(this)

                accessible ? null : field.setAccessible(true)

                field.get(this).invokeMethod("setInstance", instance)

                accessible ? null : field.setAccessible(false)
            }
        }


    }


    //abstract BitbucketEntity refreshInfo()
    BitbucketEntity refreshInfo(){}

    BitbucketEntity getParent() {

        if(this.localParent != null && this.localParent.isValid()) {
            return this.localParent
        }else {
            entityLog.info("Local parent:" + this.localParent.toString())
            this.localParent = this.localParent.refreshInfo()
            assert this.localParent.isValid()
            return this.localParent
        }

    }

    void setParent(BitbucketEntity parent) {
        this.localParent = parent
    }
    /*
    void setParent(BitbucketEntity parent) {

        entityLog.info("For object: " +  this.toString()  + " (${getClass().simpleName})")
        entityLog.info("\tSetting parent to to: " + parent.toString() +  " (${parent.getClass().simpleName})")
        this.localParent = parent

        ArrayList<Field> entityFields = getClass().declaredFields.findAll {it.type != BitbucketInstanceManagerRest && Traits.findTraits(new ClassNode(it.type)).size() == 1}

        entityFields.removeAll{it.name.startsWith("com_")}

        if (entityFields) {
            entityLog.debug("\tObject has fields that are BitbucketEntity's, setting their parents to:" + this.toString() + " (${this.getClass().simpleName})")

            entityFields.each {field ->

                boolean accessible = field.canAccess(this)

                accessible ? null : field.setAccessible(true)

                field.get(this).invokeMethod("setParent", this)

                accessible ? null : field.setAccessible(false)
            }
        }


    }

     */

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

    static ArrayList<BitbucketEntity> fromJson(String rawJson, Class clazz, BitbucketInstanceManagerRest instance, Object parent) {


        entityLog.info("Creating ${clazz.simpleName} from json")
        entityLog.debug("\tWith parent:" + parent.toString())
        entityLog.debug("\tWith instance:" + instance.baseUrl)
        entityLog.trace("\tWith JSON:" + rawJson)

        ArrayList<BitbucketEntity> result


        if (rawJson.startsWith("[")) {

            ArrayType type = getObjectMapper().getTypeFactory().constructArrayType(clazz)

            result = getObjectMapper().readValue(rawJson,type) as ArrayList<BitbucketEntity>
        } else if (rawJson.startsWith("{")) {

            result = [getObjectMapper().readValue(rawJson, clazz)] as ArrayList<BitbucketEntity>
        } else {
            throw new InputMismatchException("Unexpected json format:" + rawJson.take(15))
        }

        entityLog.debug("\tCreated ${result.size()} " + clazz.simpleName + " object" + (result.size() > 1 ? "s" : ""))
        result.each {
            it.setParent(parent as BitbucketEntity)
            it.setInstance(instance)

        }


        entityLog.info("Is valid json:" +  result.first().validJsonEntity.toString())

        if (false && clazz == BitbucketPullRequest) {
            BitbucketPullRequest pr = result.first()
            ArrayList<Field> fields = clazz.getFields().findAll{ Traits.findTraits(new ClassNode(it.type)).size() == 1}
            ArrayList<Field> dfields = clazz.declaredFields.findAll{ Traits.findTraits(new ClassNode(it.type)).size() == 1}


            println("Declared fields:")
            dfields.each {field ->
                boolean accessible = field.canAccess(pr)

                accessible ? null : field.setAccessible(true)

                println("Field name: " + field.name)
                field.setAccessible(true)
                println("\tField value: " + field.get(pr) )

                if (! field.get(pr)?.invokeMethod("getParent", null)) {
                    println("\t\tValue has NO parent")
                }else {
                    println("\t\tValue has YES parent: Field" + field.get(pr).invokeMethod("getParent", null))
                }


                accessible ? null : field.setAccessible(false)




            }

            println("Pr Instance:" + pr.instance)
            println("Participant Instance:" + pr.participants.instance)

            true
        }

        if (clazz == BitbucketRepo && false) {
            BitbucketRepo repo = result.first()
            ArrayList<Field> decFields = clazz.declaredFields
            ArrayList<Field> allFields = clazz.fields
            ArrayList<Field> fields = clazz.getFields().findAll{ Traits.findTraits(new ClassNode(it.type)).size() == 1}
            ArrayList<Field> dfields = clazz.declaredFields.findAll{ Traits.findTraits(new ClassNode(it.type)).size() == 1}



            fields.each {field ->
                println(field.name + "\n\t" + field.get(result.first()) )

            }
            printf("Declared fields:")
            dfields.each {field ->
                println(field.name)
                field.setAccessible(true)

                println("\t" + field.get(repo) )

            }

            println(repo.instance.baseUrl)
            println(repo.project)


            true
        }



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


        assert this.getInstance() instanceof BitbucketInstanceManagerRest && this.getParent() != null
        this.getInstance() instanceof BitbucketInstanceManagerRest && this.getParent() != null

    }


    /** --- GET --- **/
    /**
     * All static methods
     *
     * isValid uses isValidJsonEntity()
     */



}
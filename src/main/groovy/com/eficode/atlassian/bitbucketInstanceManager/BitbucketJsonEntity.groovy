package com.eficode.atlassian.bitbucketInstanceManager

import com.google.gson.Gson
import com.google.gson.internal.LinkedTreeMap
import com.google.gson.reflect.TypeToken
import groovy.json.JsonOutput
import kong.unirest.GenericType
import kong.unirest.Unirest
import kong.unirest.UnirestInstance

import java.lang.reflect.Field
import java.lang.reflect.Type


trait BitbucketJsonEntity {


    static Gson objectMapper = new Gson()


    //An array of maps
    static ArrayList<BitbucketJsonEntity> fromJson(ArrayList<Map> rawJson, BitbucketInstanceManagerRest parent) {

        return fromJson(JsonOutput.toJson(rawJson), parent)
    }
    //A single map
    static ArrayList<BitbucketJsonEntity> fromJson(Map rawJson, BitbucketInstanceManagerRest parent) {


        return fromJson(JsonOutput.toJson(rawJson), parent)
    }


     static ArrayList<BitbucketJsonEntity> fromJson(String rawJson, Class clazz, Object parent) {

        Type type
        ArrayList<BitbucketJsonEntity> result


        if (rawJson.startsWith("[")) {

            type = TypeToken.getParameterized(ArrayList.class, clazz).getType()

            result = getObjectMapper().fromJson(rawJson, type)
        } else if (rawJson.startsWith("{")) {
            type = TypeToken.get(clazz).getType()
            result = [getObjectMapper().fromJson(rawJson, type)]
        } else {
            throw new InputMismatchException("Unexpected json format:" + rawJson.take(15))
        }


        result =  result.collect() { unOrphan(it, parent) }


        if (clazz == BitbucketInstanceManagerRest.BitbucketRepo) {
            result.each {unOrphan(it.project, parent)}
        }



        return result


    }


    def getParentObject() {

        def detta = this

        Field field = getThisFiled()
        field.setAccessible(true)
        def parent = field.get(this)
        field.setAccessible(false)
        return parent

    }

    Field getThisFiled() {
        Class clazz = this.class
        Field field = clazz.declaredFields.find { it.name == "this\$0" }
        return field
    }

    static def unOrphan(def child, def parent) {

        Class clazz = child.getClass()
        Field thisField = clazz.declaredFields.find { it.name == "this\$0" }

        try {
            thisField.setAccessible(true)
            thisField.set(child, parent)
            assert thisField.get(child) == parent
            thisField.setAccessible(false)

        } catch (ex) {
            throw new ClassCastException("Could not set parent of $child in field \"$thisField\", to value:" + parent.toString() + ", got error:" + ex.message)
        }

        return child

    }


    abstract boolean isValid()

    abstract String toString()

    abstract boolean equals(Object object)
}
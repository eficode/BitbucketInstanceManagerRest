package com.eficode.atlassian.bitbucketInstanceManager.model

import com.google.gson.annotations.SerializedName

import java.lang.reflect.Field

enum MergeStrategy {

    @SerializedName("no-ff")
    NO_FF,
    @SerializedName("ff")
    FF,
    @SerializedName("ff-only")
    FF_ONLY,
    @SerializedName("rebase-no-ff")
    REBASE_NO_FF,
    @SerializedName("rebase-ff-only")
    REBASE_FF_ONLY,
    @SerializedName("squash")
    SQUASH,
    @SerializedName("squash-ff-only")
    SQUASH_FF_ONLY,




    public String getSerializedName() {


        //https://clevercoder.net/2016/12/12/getting-annotation-value-enum-constant/
        Field f = this.getClass().getField(this.name())
        SerializedName a = f.getAnnotation(SerializedName.class)
        return a.value()

    }

    public static MergeStrategy getFromSerializedName(String inputName) {

        String inputNameParsed = inputName.toUpperCase().replaceAll(/\W/,"")
        return values().find {
            String availableStratName = it.serializedName.replaceAll(/-/, "").toUpperCase()
            if (availableStratName == inputNameParsed) {
                return it
            }
        }


    }




}



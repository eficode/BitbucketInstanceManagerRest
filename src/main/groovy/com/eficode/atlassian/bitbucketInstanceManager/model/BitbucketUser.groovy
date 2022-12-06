package com.eficode.atlassian.bitbucketInstanceManager.model

import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest
import kong.unirest.HttpResponse
import kong.unirest.JsonNode
import kong.unirest.UnirestInstance
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets

class BitbucketUser implements BitbucketEntity{

    String name
    String emailAddress
    Boolean active
    String displayName
    Integer id
    String slug
    String type
    String directoryName
    boolean deletable
    boolean mutableDetails
    boolean mutableGroups
    long lastAuthenticationTimestamp
    LinkedHashMap links
    static Logger log = LoggerFactory.getLogger(BitbucketUser.class)


    String getProfileUrl(String baseUrl) {

        return baseUrl + "/users/" + URLEncoder.encode(name, StandardCharsets.UTF_8)
    }

    @Override
    boolean isValid() {
        return validJsonEntity && name && emailAddress && displayName
    }


    String toString() {
        return name + " (${id})"
    }

    String toAtlassianWikiMarkup() {
        return "[~${name}] (Remote user: [${name}|${getProfileUrl(instance.baseUrl)}])"
    }


    /**
     * Set the global permissions of one or several users
     * @param permission The permission that the users should have: LICENSED_USER, PROJECT_CREATE, ADMIN, SYS_ADMIN
     * @return true on success
     */
    boolean setUserGlobalPermission(String permission) {
        return setUsersGlobalPermission(instance, [this], permission)
    }


    /**
     * Set the global permissions of several users
     * @param instance The instance where the users should be altered
     * @param users userNames of the users
     * @param permission The permission that the users should have: LICENSED_USER, PROJECT_CREATE, ADMIN, SYS_ADMIN
     * @return true on success
     */
    static boolean setUsersGlobalPermission(BitbucketInstanceManagerRest instance, ArrayList<BitbucketUser> users, String permission) {

        assert permission in ["LICENSED_USER", "PROJECT_CREATE", "ADMIN", "SYS_ADMIN"] : "Unknown permission supplied when setting users global permission"

        UnirestInstance unirest = instance.newUnirest

        String url = "/rest/api/latest/admin/permissions/users?permission=${permission}"
        users.each {
            url+="&name=" + it.name
        }

        HttpResponse<JsonNode> rawResponse = unirest.put(url).asJson()

        Map rawOut = jsonPagesToGenerics(rawResponse.body)

        if (rawOut.containsKey("errors")) {
            throw new Exception(rawOut.errors.collect { it?.message }?.join(", "))
        }

        assert rawResponse.status == 204: "API returned unexpected output when setting user global permission:" + rawResponse?.body?.toString()

        return true


    }


    /**
     * Get users matching the filter
     * @param instance  The instance where the users should be fetched from
     * @param filter only users with usernames, display name or email addresses containing the supplied string will be returned.
     * @param maxUsers Max number of users to return
     * @return An array of BitbucketUser objects
     */
    static ArrayList<BitbucketUser> getUsers(BitbucketInstanceManagerRest instance, String filter, long maxUsers) {

        UnirestInstance unirest = instance.newUnirest
        ArrayList<JsonNode> rawUsers = getJsonPages(unirest, "/rest/api/latest/admin/users", maxUsers, [filter:filter],true)

        ArrayList<BitbucketUser> usersParsed = fromJson(rawUsers.toString(), BitbucketUser,instance, instance)

        assert usersParsed.every {it.isValid()}

        return usersParsed
    }


    /**
     * Creates a new user

     * @param instance The instance where the users should be created
     * @param email
     * @param password required if notify == false
     * @param displayName
     * @param userName
     * @param notify If true instead of requiring a password,<br>
     *      the created user will be notified via email their account has been created and requires a password to be reset.<br>
     *      This option can only be used if a mail server has been configured.
     * @param addToDefaultGroup
     * @return
     */
    static BitbucketUser createUser(BitbucketInstanceManagerRest instance, String email, String password, String displayName, String userName, boolean notify = false,  boolean addToDefaultGroup = true) {

        if (!notify) {
            assert password : "If notify is false, a password must be supplied when creating a user"
        }



        Map parameters = [
                emailAddress:email,
                addToDefaultGroup:addToDefaultGroup,
                displayName: displayName,
                name: userName,
                notify: notify
        ]

        password ? (parameters.put("password", password)) : null

        String url = "/rest/api/latest/admin/users" + createUrlParameterString(parameters)

        UnirestInstance unirest = instance.newUnirest
        HttpResponse<JsonNode> rawResponse = unirest.post(url).headers(["X-Atlassian-Token":"no-check"]).asJson()
        unirest.shutDown()

        Map rawOut = jsonPagesToGenerics(rawResponse.body)

        if (rawOut.containsKey("errors")) {
            throw new Exception(rawOut.errors.collect { it?.message }?.join(", "))
        }

        assert rawResponse.status == 204: "API returned unexpected output when creating new user:" + rawResponse?.body?.toString()



        ArrayList<BitbucketUser> matchingUsers = getUsers(instance, email, 10).findAll {it.displayName == displayName && it.name == userName}

        if(matchingUsers.size()>1) {
            log.warn("\tA new users was created but could not determine the new user object, returning users with largets ID")
            return matchingUsers.sort{it.id}.last()
        }else if (matchingUsers.size() ==1 ) {
            log.info("\tCreated user:" + matchingUsers.first().toString())
            return matchingUsers.first()
        }

        throw new InputMismatchException("Error when creating user $userName, API did not return it when qureid")

    }

}


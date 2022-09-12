package com.eficode.atlassian.bitbucketInstanceManager

import kong.unirest.Cookie
import kong.unirest.Cookies
import kong.unirest.GetRequest
import kong.unirest.HttpResponse
import kong.unirest.Unirest
import kong.unirest.UnirestException
import kong.unirest.UnirestInstance
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import unirest.shaded.org.apache.http.NoHttpResponseException

import java.util.regex.Matcher
import java.util.regex.Pattern

class BitbucketInstanceManagerRest {

    static Logger log = LoggerFactory.getLogger(BitbucketInstanceManagerRest.class)
    public static String baseUrl = "http://localhost:7990"
    static Cookies cookies
    public String adminUsername = "admin"
    public String adminPassword = "admin"


    /**
     * Setup BitbucketInstanceManagerRest with admin/admin as credentials.
     * @param BaseUrl Defaults to http://localhost:8080
     */
    BitbucketInstanceManagerRest(String BaseUrl = baseUrl) {
        baseUrl = BaseUrl
        Unirest.config().defaultBaseUrl(BaseUrl)

    }

    /**
     * Setup BitbucketInstanceManagerRest with custom credentials
     * @param BaseUrl Defaults to http://localhost:7990
     * @param username
     * @param password
     */
    BitbucketInstanceManagerRest(String username, String password, String BaseUrl = baseUrl) {
        baseUrl = BaseUrl
        Unirest.config().defaultBaseUrl(baseUrl)
        adminUsername = username
        adminPassword = password

    }

    static Cookies extractCookiesFromResponse(HttpResponse response, Cookies existingCookies = null) {

        if (existingCookies == null) {
            existingCookies = new Cookies()
        }

        response.headers.all().findAll { it.name == "Set-Cookie" }.each {

            String name = it.value.split(";")[0].split("=")[0]
            String value = it.value.split(";")[0].split("=")[1]

            existingCookies.removeAll { it.name == name }
            existingCookies.add(new Cookie(name, value))

        }


        return existingCookies

    }

    /**
     * Unirest by default gets lost when several redirects return cookies, this method will retain them
     * @param path
     * @return
     */
    Cookies getCookiesFromRedirect(String path, String username = adminUsername, String password = adminPassword, Map headers = [:]) {

        UnirestInstance unirestInstance = Unirest.spawnInstance()
        unirestInstance.config().followRedirects(false).defaultBaseUrl(baseUrl)

        Cookies cookies = new Cookies()
        GetRequest getRequest = unirestInstance.get(path).headers(headers)
        if (username && password) {
            getRequest.basicAuth(username, password)
        }
        HttpResponse getResponse = getRequest.asString()
        cookies = extractCookiesFromResponse(getResponse, cookies)

        String newLocation = getResponse.headers.getFirst("Location")

        while (getResponse.status == 302) {


            newLocation = resolveRedirectPath(getResponse, newLocation)
            getResponse = unirestInstance.get(newLocation).asString()
            cookies = extractCookiesFromResponse(getResponse, cookies)

        }


        unirestInstance.shutDown()
        return cookies
    }

    static String resolveRedirectPath(HttpResponse response, String previousPath = null) {

        String newLocation = response.headers.getFirst("Location")
        if (!newLocation) {
            return null
        } else if (!newLocation.startsWith("/")) {
            newLocation = previousPath.substring(0, previousPath.lastIndexOf("/") + 1) + newLocation
        }

        return newLocation

    }

    String searchBodyForAtlToken(String body) {

        Pattern pattern = Pattern.compile(/<input type="hidden" name="atl_token" value="(.*?)"/)
        Matcher matcher = pattern.matcher(body)

        String token = matcher.size() ? matcher[0][1] : ""

        return token

    }

    boolean setApplicationProperties(String bbLicense, String appTitle = "Bitbucket", String baseUrl = this.baseUrl) {
        log.info("Setting up initial application properties")

        UnirestInstance unirestInstance = Unirest.spawnInstance()
        unirestInstance.config().defaultBaseUrl(baseUrl).socketTimeout(1000)

        long startTime = System.currentTimeMillis()

        while (startTime + (5 * 60000) > System.currentTimeMillis()) {
            try {
                HttpResponse<String> response = unirestInstance.get("/setup").asString()


                if (response.body.contains("<span>Database</span>")) {
                    log.info("\tBitbucket has started and the Setup dialog has appeared")
                    unirestInstance.shutDown()
                    break
                } else {
                    log.info("\tBitbucket has started but the Setup dialog has not appeared yet, waited ${((System.currentTimeMillis() - startTime)/1000).round(0)}s")
                    sleep(5000)
                }

            } catch (UnirestException ex) {

                log.info("---- Bitbucket not available yet ----")
                log.debug("\tGot error when trying to access bitbucket:" + ex.message)
                sleep(1000)
            }
        }
        unirestInstance.shutDown()


        cookies = Unirest.get("/setup").asString().cookies
        assert cookies.find { it.name == "BITBUCKETSESSIONID" }
        HttpResponse bodyString = Unirest.get("/setup").cookie(cookies).asString()

        String atlToken = searchBodyForAtlToken(bodyString.body)
        HttpResponse setDBResponse = Unirest.post("/setup")
                .cookie(cookies)
                .field("atl_token", atlToken)
                .field("locale", "en_US")
                .field("step", "database")
                .field("internal", "true")
                .field("type", "postgres")
                .field("hostname", "")
                .field("port", "5432")
                .field("database", "")
                .field("username", "")
                .field("password", "")
                .field("submit", "next")
                .asString()

        assert setDBResponse.status == 302: "Error setting local database"
        log.info("\tFinished setting up local database")

        HttpResponse setPropResponse = Unirest.post("/setup")
                .cookie(cookies)
                .field("step", "settings")
                .field("applicationTitle", appTitle)
                .field("baseUrl", baseUrl)
                .field("license", bbLicense)
                .field("licenseDisplay", bbLicense)
                .field("submit", "next")
                .field("atl_token", atlToken)
                .asString()
        assert setPropResponse.status == 302: "Error setting application properties or license"
        log.info("\tFinished setting up licence")

        HttpResponse setAdminResponse = Unirest.post("/setup")
                .cookie(cookies)
                .field("step", "user")
                .field("atl_token", atlToken)
                .field("username", adminUsername)
                .field("fullname", adminUsername)
                .field("email", adminUsername + "@" + adminUsername + ".com")
                .field("password", adminPassword)
                .field("confirmPassword", adminPassword)
                .field("skipJira", "Go to Bitbucket")
                .asString()

        assert setAdminResponse.status == 302: "Error setting admin account"
        log.info("\tFinished setting up admin account, you should be able to reach Bitbucket on:" + baseUrl)

        return true
    }

    String getStatus() {

        Map statusMap = Unirest.get("/status").asJson()?.body?.object?.toMap()

        return statusMap?.state
    }
}

package com.eficode.atlassian.bitbucketInstanceManager.impl

import com.eficode.atlassian.bitbucketInstanceManager.model.BitbucketEntity
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class BitbucketChange implements BitbucketEntity{

    String contentId
    String fromContentId
    Map path = [components: [], parent: "", name: "", extension: "", toString: ""]
    boolean executable
    long percentUnchanged
    String type
    String nodeType
    boolean srcExecutable
    Map<String, ArrayList> links = ["self": [[href: ""]]]
    Map properties = [gitChangeType: ""]
    BitbucketCommit commit
    static Logger log = LoggerFactory.getLogger(BitbucketChange)

    @Override
    void setParent(BitbucketEntity commit) {
        this.commit = commit as BitbucketCommit
    }



    boolean isValid() {

        return isValidJsonEntity() && contentId &&  commit.isValid()

    }




    String getActionSymbol() {

        switch (type) {
            case "MODIFY":
                return "š"
            case "ADD":
                return "ā"
            case "DELETE":
                return "šļø"
            case "COPY":
                return "š"
            case "MOVE":
                return "šļø"
            default:
                return "ā - " + type
        }


    }


    String getFileNameTruncated(int maxLen) {

        assert maxLen > 4
        String completeName = path.toString

        if (completeName.length() <= maxLen) {
            return completeName
        }

        return "..." + completeName.takeRight(maxLen - 3)

    }


    static getAtlassianWikiHeader() {
        return "|| Action || File ||\n"
    }

    static getMarkdownHeader() {

        return "| Action | File |\n|--|--|\n"

    }

    static getAtlassianWikiFooter() {
        return markdownFooter
    }

    static getMarkdownFooter() {

        return "\n\nā - Added\tš - Modified\tš - Copied\tšļø - Moved\tš - Deleted\t"

    }


    String toAtlassianWikiMarkup() {

        return "| $actionSymbol | [${getFileNameTruncated(60)}|${links.self.href.first()}] | \n"
    }

    String toMarkdown() {

        String out = "|  " + actionSymbol + "  | [${getFileNameTruncated(60)}](${links.self.href.first()}) |" + "\n"


        return out

    }
}

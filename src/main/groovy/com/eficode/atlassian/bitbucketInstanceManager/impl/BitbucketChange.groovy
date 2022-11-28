package com.eficode.atlassian.bitbucketInstanceManager.impl

import com.eficode.atlassian.bitbucketInstanceManager.model.BitbucketJsonEntity
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class BitbucketChange implements BitbucketJsonEntity{

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


    boolean isValid() {

        return isValidJsonEntity() && contentId &&  commit.isValid() && parent instanceof BitbucketCommit

    }

    @Override
    BitbucketCommit getParent() {

        return this.commit
    }

    @Override
    void setParent(Object commit) {

        assert commit instanceof BitbucketCommit
        this.commit = commit as BitbucketCommit

    }

    String getActionSymbol() {

        switch (type) {
            case "MODIFY":
                return "📝"
            case "ADD":
                return "➕"
            case "DELETE":
                return "🗑️"
            case "COPY":
                return "📋"
            case "MOVE":
                return "🗂️"
            default:
                return "❓ - " + type
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

        return "\n\n➕ - Added\t📝 - Modified\t📋 - Copied\t🗂️ - Moved\t🗑 - Deleted\t"

    }


    String toAtlassianWikiMarkup() {

        return "| $actionSymbol | [${getFileNameTruncated(60)}|${links.self.href.first()}] | \n"
    }

    String toMarkdown() {

        String out = "|  " + actionSymbol + "  | [${getFileNameTruncated(60)}](${links.self.href.first()}) |" + "\n"


        return out

    }
}

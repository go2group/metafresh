// For parsing YAML
@Grab('org.yaml:snakeyaml:1.25')
import org.yaml.snakeyaml.Yaml

// For working with Git
@GrabResolver(name = 'jcenter', root = 'http://jcenter.bintray.com/')
@Grab(group = 'org.ajoberstar', module = 'grgit', version = '1.9.3')
import org.ajoberstar.grgit.Grgit

// For avoiding some junk logging
@Grab(group='org.slf4j', module='slf4j-api', version='1.6.1')
@Grab(group='org.slf4j', module='slf4j-nop', version='1.6.1')

Yaml parser = new Yaml()

String sourceYaml = new File("manifest.yml").text
println "\nSource YAML: " + sourceYaml

Map parsedYaml = (Map) parser.load(sourceYaml)
println "Parsed YAML: " + parsedYaml
println ""

def quickstartHome = "go2group"
def quickstartTarget = "go2group" //TODO: Pass in as parameter instead based on the MetaFresh repo's user/org home

parsedYaml.each { quickStart ->
    println "Repos for quickstart '" + quickStart.key + "' are " + quickStart.value
    retrieveItem(quickStart.key, quickstartHome)

    quickStart.value.each {
        println "Processing request to duplicate quickstart '${quickStart.key}' to: " + it
        addRemote(quickStart.key, it, "https://github.com/${quickstartTarget}/${it}.git")
    }
    println ""
}

/**
 * Retrieves a single item via Git Clone
 * @param itemName the target item to retrieve
 */
def retrieveItem(String itemName, String targetHome) {
    File targetDir = new File(itemName)
    println "Request to retrieve quickstart '$itemName' would store it at $targetDir - exists? " + targetDir.exists()
    if (targetDir.exists()) {
        println "That already had an existing directory locally. If something is wrong with it please delete and try again"
    } else {
        def targetUrl = "https://github.com/${targetHome}/${itemName}"
        if (!isUrlValid(targetUrl)) {
            println "Can't retrieve quickstart from $targetUrl - URL appears invalid. Typo? Not created yet?"
            return
        }
        println "Retrieving $itemName from $targetUrl"

        println "Cloning from quickstart: ${targetHome}/${itemName} - will name it as such"
        Grgit.clone dir: targetDir, uri: targetUrl
        //println "Primary clone operation complete, about to add the '$defaultRemote' remote for the $githubDefaultHome org address"
        //addRemote(itemName, defaultRemote, "https://github.com/${githubDefaultHome}/${itemName}")
    }
}

/**
 * Add a new Git remote for the given item.
 * @param itemName the item to add the remote for
 * @param remoteName the name to give the new remote
 * @param URL address to the remote Git repo
 */
def addRemote(String itemName, String remoteName, String url) {
    File targetGitDir = new File(itemName)
    if (!targetGitDir.exists()) {
        println "'$itemName' not found."
        return
    }
    def remoteGit = Grgit.open(dir: itemName)
    def remote = remoteGit.remote.list()
    //println "Remotes so far? " + remote
    def check = remote.find {
        it.name == "$remoteName"
    }
    if (!check) {
        remoteGit.remote.add(name: "$remoteName", url: "$url")
        if (isUrlValid(url)) {
            println "Successfully added remote '$remoteName' for '$itemName' and the URL looks valid"
            //remoteGit.fetch remote: remoteName
        } else {
            println "Added the remote '$remoteName' for '$itemName' - but the URL $url failed a test lookup. Typo? Not created yet?"
        }
    } else {
        println "Remote already exists"
    }
}

/**
 * Tests a URL via a HEAD request (no body) to see if it is valid
 * @param url the URL to test
 * @return boolean indicating whether the URL is valid (code 200) or not
 */
boolean isUrlValid(String url) {
    def code = new URL(url).openConnection().with {
        requestMethod = 'HEAD'
        connect()
        responseCode
    }
    return code.toString() == "200"
}

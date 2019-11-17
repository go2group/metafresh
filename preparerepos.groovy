// Parse some YAML
@Grab('org.yaml:snakeyaml:1.25')
import org.yaml.snakeyaml.Yaml

Yaml parser = new Yaml()

String sourceYaml = new File("manifest.yml").text
println "\nSource YAML: " + sourceYaml

Map parsedYaml = (Map) parser.load(sourceYaml)
println "Parsed YAML: " + parsedYaml
println ""

// For working with Git
@GrabResolver(name = 'jcenter', root = 'http://jcenter.bintray.com/')
@Grab(group = 'org.ajoberstar', module = 'grgit', version = '1.9.3')
import org.ajoberstar.grgit.Grgit

// For avoiding some junk logging
@Grab(group='org.slf4j', module='slf4j-api', version='1.6.1')
@Grab(group='org.slf4j', module='slf4j-nop', version='1.6.1')

// For GitHub API
@Grab(group='org.kohsuke', module='github-api', version='1.99')
import org.kohsuke.github.*

// Where to get the quick start repos from
def quickstartHome = "go2group"

// Assume a GitHub token has been set as an env variable, use that for API work
def env = System.getenv()
String githubToken= env['GRGIT_USER']
String githubRepoHome = env['CF_REPO_OWNER']

// Initialize interactions with GitHub using the given token - it is also used directly as an env var by GrGit
GitHub github = new GitHubBuilder().withOAuthToken(githubToken).build()

// Figure out who we are. Assume either the user forked MetaFresh to self or an organization
GHMyself activeGitHubUser = github.getMyself()
println "Am thinking we're working with GitHub user : " + activeGitHubUser.login
String activeGitHubUserString = activeGitHubUser.login
boolean workingWithOrg = false
GHOrganization targetGitHubOrg
if (activeGitHubUserString.equalsIgnoreCase(githubRepoHome)) {
    println "Active GitHub user on our credential matches the home of the MetaFresh repo, so that's where quickstarted repos will go"
} else {
    println "The active GitHub user on our credential doesn't match the home of the MetaFresh repo, so assuming it lives in an organization"
    targetGitHubOrg = github.getOrganization(githubRepoHome)
    println "Pulled up the target GitHub org, its name is: " + targetGitHubOrg.login
    workingWithOrg = true
}

// Go through the YAML tree and do repo stuff
parsedYaml.each { quickStart ->
    println "Repos for quickstart '" + quickStart.key + "' are " + quickStart.value
    retrieveItem(quickStart.key, quickstartHome)

    quickStart.value.each {
        println "Processing request to duplicate quickstart '${quickStart.key}' to: " + it
        def repoUrl = "https://github.com/${githubRepoHome}/${it}.git"
        if (isUrlValid(repoUrl)) {
            // Nothing to do - assume that if the repo exists it is ready or the user will have to delete it to retry
            println "Looks like a repo already exists for $repoUrl so no need to create it via GitHub API"
        } else {
            // If no repo exists yet we'll make a new remote for it in the quickstart workspace and attempt to push there (soft fork of sorts)
            addRemote(quickStart.key, it, repoUrl)
            println "Not seeing a repo yet for $repoUrl so going to create it via GitHub API"
            // Need to treat orgs vs users differently
            if (workingWithOrg) {
                println "We're working on creating a new repo for organization " + targetGitHubOrg.login + " that'll go to " + it
                GHCreateRepositoryBuilder orgRepoBuilder = targetGitHubOrg.createRepository(it)
                orgRepoBuilder.description("This is an API created org repo")
                orgRepoBuilder.create()
            } else {
                // TODO: Validate this scenario, update to non-deprecated variant
                println "We're working on creating a new repo for user " + activeGitHubUserString
                GHRepository repo = github.createRepository(it, "This is an API created user repo", "", true)
            }
            // Now actually attempt to push to the new remote to create the soft fork
            println "Going to try git pushing to the new remote $it from " + quickStart.key
            def softForkGit = Grgit.open(dir: quickStart.key)
            softForkGit.push(remote: it)

            // Create the initial pipeline on Codefresh - TODO: Only on the first (or last?) use of the quickstart, otherwise just add Git Hooks
            String cfPipelineCreate = "./codefresh create pipeline -f ${quickStart.key}/codefresh.spec.yml"
            println "Going to try creating a pipeline with the following command: " + cfPipelineCreate

            def proc = cfPipelineCreate.execute()
            def b = new StringBuffer()
            proc.consumeProcessErrorStream(b)
            println "Result from execution: " + proc.text
            println "Possible error output: " + b.toString()
        }

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

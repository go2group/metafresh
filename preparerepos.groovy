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

// Various bits related to String manipulation in preparing pipeline stuff
String pipelineSpecFilename = "codefresh.spec.yml"
String pipelineOwnerToken = "OWNERTOKEN"
String pipelineRepoToken = "REPOTOKEN"
String pipelineVerifiedSnippet = "verified: true"
String gitTriggerTemplate = """verified: true
    - name: OWNERTOKEN/REPOTOKEN
      type: git
      repo: OWNERTOKEN/REPOTOKEN
      events:
        - push.heads
      pullRequestAllowForkEvents: false
      commentRegex: /.*/gi
      branchRegex: /.*/gi
      branchRegexInput: regex
      provider: github
      context: github
      verified: true"""

// Initialize interactions with GitHub using the given token - it is also used directly as an env var by GrGit
GitHub github = new GitHubBuilder().withOAuthToken(githubToken).build()

// Figure out who we are. Assume either the user forked MetaFresh to self or an organization
GHMyself activeGitHubUser = github.getMyself()
println "Am thinking we're working with GitHub user : " + activeGitHubUser.login
String activeGitHubUserString = activeGitHubUser.login
boolean workingWithOrg = false
GHOrganization targetGitHubOrg
String targetOwner
if (activeGitHubUserString.equalsIgnoreCase(githubRepoHome)) {
    println "Active GitHub user on our credential matches the home of the MetaFresh repo, so that's where quickstarted repos will go"
    targetOwner = activeGitHubUserString
} else {
    println "The active GitHub user on our credential doesn't match the home of the MetaFresh repo, so assuming it lives in an organization"
    targetGitHubOrg = github.getOrganization(githubRepoHome)
    targetOwner = githubRepoHome
    println "Pulled up the target GitHub org, its name is: " + targetGitHubOrg.login
    workingWithOrg = true
}

// Go through the YAML tree and do repo stuff
parsedYaml.each { quickStart ->
    println "Repos for quickstart '" + quickStart.key + "' are " + quickStart.value
    File targetQuickstartDir = new File(quickStart.key)
    if (targetQuickstartDir.exists()) {
        println "That quickstart already looks to have a local cache, cleaning it up just in case"
        targetQuickstartDir.deleteDir()
    }
    retrieveItem(quickStart.key, quickstartHome)
    File targetPipelineSpec = new File(quickStart.key, pipelineSpecFilename)
    if (targetPipelineSpec.exists() && targetPipelineSpec.text.contains(pipelineOwnerToken)) {
        String pipelineSpecText = targetPipelineSpec.text
        //println "Got the following pipeline spec to work with: \n" + pipelineSpecText

        quickStart.value.each {
            println "Processing request to duplicate quickstart '${quickStart.key}' to: " + it

            // For the first place replace tokens only. After that just add new Git Trigger blocks
            if (pipelineSpecText.contains(pipelineOwnerToken)) {
                // Replace tokens in the pipeline spec with owner + repo - only does its thing once, only Git triggers vary after
                pipelineSpecText = pipelineSpecText.replace(pipelineOwnerToken, targetOwner)
                pipelineSpecText = pipelineSpecText.replace(pipelineRepoToken, it)
                //println "Did token replacement, pipeline spec is now:\n" + pipelineSpecText
            } else {
                int lastVerifiedIndex = pipelineSpecText.lastIndexOf(pipelineVerifiedSnippet)
                if (lastVerifiedIndex == -1) {
                    println "Warning! Did not find 'verified: true' in the spec file? Broken?"
                } else {
                    String firstPart = pipelineSpecText.substring(0, lastVerifiedIndex)
                    String addedTemplate = gitTriggerTemplate.replace(pipelineOwnerToken, targetOwner).replace(pipelineRepoToken, it)
                    String lastPart = pipelineSpecText.substring(lastVerifiedIndex + pipelineVerifiedSnippet.length())
                    pipelineSpecText = firstPart + addedTemplate + lastPart
                    //println "Added new Git Trigger section, spec is now:\n" + pipelineSpecText
                }
            }

            // Deal with creating new repos based on the quickstart itself
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
            }
        }

        // Create the initial pipeline on Codefresh - to simplify create vs replace we issue a delete call first. TODO: Update instead?
        String deleteCommand = "./codefresh delete pipeline -name ${quickStart.key}"
        def deleteProc = deleteCommand.execute()
        def deleteBuffer = new StringBuffer()
        deleteProc.consumeProcessErrorStream(deleteBuffer)
        println "Attempting to delete pipeline in case it exists already: " + deleteProc.text
        println "Possible error output: " + deleteBuffer.toString()

        new File(quickStart.key, pipelineSpecFilename).text = pipelineSpecText
        String cfPipelineCreate = "./codefresh create pipeline -f ${quickStart.key}/${pipelineSpecFilename}"
        println "Going to try creating a pipeline with the following command: " + cfPipelineCreate
        println "It will be using the following pipeline spec:\n" + pipelineSpecText

        // Execute the Codefresh pipeline create command and capture both standard and error output
        def proc = cfPipelineCreate.execute()
        def b = new StringBuffer()
        proc.consumeProcessErrorStream(b)
        println "Result from execution: " + proc.text
        println "Possible error output: " + b.toString()
    } else {
        println "Invalid quickstart? Failed to find pipeline spec file $pipelineSpecFilename or it had no 'OWNERTOKEN'! Skipping"
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
    def check = remote.find {
        it.name == "$remoteName"
    }
    if (!check) {
        remoteGit.remote.add(name: "$remoteName", url: "$url")
        if (isUrlValid(url)) {
            println "Successfully added remote '$remoteName' for '$itemName' and the URL looks valid"
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


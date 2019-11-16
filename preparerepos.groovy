@Grab('org.yaml:snakeyaml:1.25')

import org.yaml.snakeyaml.Yaml

Yaml parser = new Yaml()

String sourceYaml = new File("manifest.yml").text
println "Source YAML: " + sourceYaml

Map parsedYaml = (Map) parser.load(sourceYaml)
println "Parsed YAML: " + parsedYaml

parsedYaml.each { quickStart ->
    println "Repos for quickstart " + quickStart.key + " are " + quickStart.value
    quickStart.value.each {
        println "Individually: " + it
    }
}

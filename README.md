# MetaFresh

Developer workspace and meta-pipeline for [Codefresh](https://codefresh.io)!

The first element to this utility project is a meta pipeline for Codefresh that makes other pipelines.

Simply fork this repo to your GitHub account and create a Codefresh pipeline using the provided `codefresh.yaml`

Edit the `manifest.yml` to target specific quickstarts and for each give a set of instances of the quickstart to create

If you've originally signed up via GitHub OAuth or add GitHub as a provider it should just magically work, creating new repos based on the quickstarts and pipelines to build them!

See [this search](https://github.com/go2group?q=cf-quickstar) for available quickstarts. The initial set is based on the [sample apps provided by Codefresh](https://codefresh.io/docs/docs/yaml-examples/examples/)

Repos will be recreated if you delete them (if something went wrong or the quickstart updated), the quickstart pipelines likewise will regenerate each run

## Future improvements

This is fairly crude and quick effort - plenty of room for improvements!

* Vagrantfile to allow easy local development against the Codefresh CLI, even on Windows
* Provide configuration options inside the manifest to control regeneration or customization of new apps
* Make more quickstarts!
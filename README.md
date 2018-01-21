# Jenkins deb-s3 Plugin

Jenkins plugin for automating the deb-s3 tool to publish to Debian repositories in S3

This is a ripoff of the [Jenkins DEB Sign Plugin](https://github.com/ivankelly/debsign-plugin), which in turn is a ripoff of the [Jenkins RPM Sign plugin](https://github.com/jenkinsci/rpmsign-plugin). Yay plagiarism!

This plugin adds a post-build step to let you utilize Ken Robertson's [deb-s3](https://github.com/krobertson/deb-s3) tool to publish to a Debian repository in S3 and sign the repository using GPG.

## Dependencies

This plugin depends on having the following installed on the host machine (i.e. slave node):

* **gpg**
* **expect**
* **deb-s3**

In addition you should also update your `~/.gnupg/gpg.conf` file to include (at least) the following:

```
personal-digest-preferences SHA256
cert-digest-algo SHA256
```

## Building and installing

To build the plugin, run

```
$ mvn package
```

This will create a hpi file (target/debs3-plugin.hpi). Install this through the Jenkins web UI.



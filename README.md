jenkins-notify-plugin
=====================

Jenkins plugin sending POST request as a post-build action with configurable JSON payload.

By default payload submitted contains the details of the build (build number, build result, job and log URLs), 
URLs of artifacts generated, Git branch and commit SHA.
 
But being a configurable [Groovy template](http://groovy.codehaus.org/Groovy+Templates) it can contain any Jenkins, job or build details you may think of!

![Post-build action with JSON payload submitted as POST request](https://raw.githubusercontent.com/cloudnative/jenkins-notify-plugin/master/screenshots/jenkins-notify-plugin.png "Post-build action with JSON payload submitted as POST request")

JSON payload is rendered as a [Groovy template](http://groovy.codehaus.org/Groovy+Templates), having the following variables in scope:
 
* **`jenkins`** - instance of [`jenkins.model.Jenkins`](http://javadoc.jenkins-ci.org/jenkins/model/Jenkins.html)
* **`build`** - instance of [`hudson.model.AbstractBuild`](http://javadoc.jenkins-ci.org/hudson/model/AbstractBuild.html)
* **`env`** - instance of [`hudson.EnvVars`](http://javadoc.jenkins-ci.org/hudson/EnvVars.html) corresponding to the current build process

Here's a [RequestBin](http://requestb.in/) of submitting a default payload:

![RequestBin for the default JSON payload](https://raw.githubusercontent.com/cloudnative/jenkins-notify-plugin/master/screenshots/request-bin.png "RequestBin for the default JSON payload")

In addition, **`json( Object )`** helper function is available, rendering any `Object` provided as JSON.

For example:

    {
      "items":       ${ json( jenkins.allItems ) },
      "computers":   ${ json( jenkins.computers.collect{ it.displayName }) },
      "moduleRoots": ${ json( build.moduleRoots )},
      "artifacts":   ${ json( build.artifacts )},
      "env":         ${ json( env ) },
      "properties":  ${ json( [ system: System.properties.keySet(), env: env.keySet() ]) }
    }

### Building and installing the plugin

    mvn clean package -s settings.xml
    cp -f target/*.hpi ~/.jenkins/plugins

### Pipeline

To use the plugin in a pipeline, currently simple build steps are available. You can use it like that:

    step([$class: 'VictorOpsNotifier', notifyUrl:'https://alert.victorops.com/integrations/generic/XXX/alert/XXX/XXX', notifyOn:'FAILURE', notifyTemplate:'XXX'])

    step([$class: 'StatuspageNotifier', notifyUrl:'https://api.statuspage.io/v1/pages/XXX/components/XXX.json', notifyOn:'FAILURE', notifyTemplate:'XXX', notifyAuthorization:'OAuth XXX'])

    step([$class: 'GenericNotifier', notifyUrl:'https://XXX', notifyOn:'FAILURE', notifyTemplate:'XXX'])

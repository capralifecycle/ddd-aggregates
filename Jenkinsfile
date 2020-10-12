#!/usr/bin/env groovy

// See https://github.com/capralifecycle/jenkins-pipeline-library
@Library('cals') _

simpleMavenLibPipeline(
  buildConfigParams: [
    slack: [channel: "#cals-dev-info"],
  ],
  dockerBuildImage: toolImageDockerReference('maven:3-jdk-11-alpine')
)

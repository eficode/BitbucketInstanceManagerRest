#!/bin/bash

rm -rf repository/com/

mkdir localm2
mkdir -p repository/com/eficode/atlassian/bitbucketinstancemanager

for pomFile in pom-[0-9]*.xml; do

  echo -e "Installing pom file: $pomFile"

   mvn install -f "$pomFile"

done

# mvn install -f pom-2.5.xml -Dmaven.repo.local=localm2/
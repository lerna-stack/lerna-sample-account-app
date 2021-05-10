#!/bin/bash -xe

cd "${WORKSPACE}"

# clone project into workspace
rm -rf *
git clone "${PROJECT_PATH}" .

# build
/usr/bin/sbt "$@"

# copy artifacts to project directory
find . -name '*.rpm' | xargs -I %RPM% cp --parents '%RPM%' "${PROJECT_PATH}"/

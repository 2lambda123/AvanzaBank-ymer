#!/bin/bash -e

# Script that executes build and tests (i.e. mvn verify) in a container.

pushd "${BASH_SOURCE%/*}/../.."

echo "Executing Maven command in: '$PWD'"

docker run -it --rm -v $PWD:/opt/javabuild avanzabank/ubuntu-openjdk8:0.1.1 /bin/bash -c "cd /opt/javabuild; ./mvnw clean verify"

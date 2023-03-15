#!/bin/bash

class=$1
mvn dependency:build-classpath -Dmdep.outputFile=cp.txt && CP=target/classes:target/test-classes:$(cat cp.txt)
javac -cp $CP $(find . -name $class) -d target/classes

#!/bin/bash

set -ex

cd target/
YCSB_HOME=/mnt/sdb/YCSB

javac -classpath $YCSB_HOME/core/target/classes/:$YCSB_HOME/rocksdb/target/dependency/*:$YCSB_HOME/replicator/lib/*:.: $1.java
java -ea -classpath $YCSB_HOME/core/target/classes/:$YCSB_HOME/rocksdb/target/dependency/*:$YCSB_HOME/replicator/lib/*:.:  $1

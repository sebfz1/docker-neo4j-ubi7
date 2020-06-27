#!/bin/bash

docker save neo4j-ubi7:4.1.0 | gzip > 4.1/docker_neo4j_ubi7_41.tar.gz

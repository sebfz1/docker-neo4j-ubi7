#!/bin/bash

docker save neo4j-ubi7:4.1.0 | gzip > neo4j-ubi7-410.tar.gz

#!/bin/bash

docker run -p 7474:7474 -p 7687:7687 neo4j-ubi7:4.1.0
# -v $HOME/neo4j/data:/data
# -v $HOME/neo4j/logs:/logs

package com.neo4j.docker.plugins;

import java.util.stream.Stream;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.logging.Log;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;

/*
This class is a basic Neo4J plugin that defines a procedure which can be called via Cypher.
 */
public class ExampleNeo4jPlugin
{
    // Output data class containing primitive types
    public static class PrimitiveOutput
    {
        public String string;
        public long integer;
        public double aFloat;
        public boolean aBoolean;

        public PrimitiveOutput( String string, long integer, double aFloat, boolean aBoolean )
        {
            this.string = string;
            this.integer = integer;
            this.aFloat = aFloat;
            this.aBoolean = aBoolean;
        }
    }

    @Context
    public GraphDatabaseService db;

    @Context
    public Log log;

    // A Neo4j procedure that always returns fixed values
    @Procedure
    public Stream<PrimitiveOutput> defaultValues( @Name( value = "string", defaultValue = "a string" ) String string,
            @Name( value = "integer", defaultValue = "42" ) long integer, @Name( value = "float", defaultValue = "3.14" ) double aFloat,
            @Name( value = "boolean", defaultValue = "true" ) boolean aBoolean )
    {
        return Stream.of( new PrimitiveOutput( string, integer, aFloat, aBoolean ) );
    }
}
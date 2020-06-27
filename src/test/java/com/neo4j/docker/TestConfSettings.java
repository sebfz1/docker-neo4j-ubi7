package com.neo4j.docker;

import com.neo4j.docker.utils.HostFileSystemOperations;
import com.neo4j.docker.utils.Neo4jVersion;
import com.neo4j.docker.utils.SetContainerUser;
import com.neo4j.docker.utils.TestSettings;
import org.junit.Assert;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.output.WaitingConsumer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class TestConfSettings {
    private static Logger log = LoggerFactory.getLogger(TestConfSettings.class);

    private GenericContainer createContainer()
    {
        return new GenericContainer(TestSettings.IMAGE_ID)
                .withEnv("NEO4J_AUTH", "none")
                .withEnv("NEO4J_ACCEPT_LICENSE_AGREEMENT", "yes")
                .withExposedPorts(7474, 7687)
                .withLogConsumer(new Slf4jLogConsumer(log));
    }

    private Map<String, String> parseConfFile(File conf) throws FileNotFoundException
    {
        Map<String, String> configurations = new HashMap<>();
        Scanner scanner = new Scanner(conf);
        while ( scanner.hasNextLine() )
        {
            String[] params = scanner.nextLine().split( "=", 2 );
            if(params.length < 2)
            {
                continue;
            }
            log.debug( params[0] + "\t:\t" + params[1] );
            configurations.put( params[0], params[1] );
        }
        return configurations;
    }

    private boolean isStringPresentInDebugLog( Path debugLog, String matchThis) throws IOException
    {
        // searches the debug log for the given string, returns true if present
        Stream<String> lines = Files.lines(debugLog);
        Optional<String> isMatch = lines.filter(s -> s.contains(matchThis)).findFirst();
        lines.close();
        return isMatch.isPresent();
    }

    @Test
    void testIgnoreNumericVars()
    {
        try(GenericContainer container = createContainer())
        {
            container.withEnv( "NEO4J_1a", "1" );
            container.start();
            Assertions.assertTrue( container.isRunning() );

            WaitingConsumer waitingConsumer = new WaitingConsumer();
            container.followOutput( waitingConsumer );

			Assertions.assertDoesNotThrow( () -> waitingConsumer.waitUntil( frame -> frame.getUtf8String()
					  .contains( "WARNING: 1a not written to conf file because settings that start with a number are not permitted" ),
				15, TimeUnit.SECONDS ),
			   "Neo4j did not warn about invalid numeric config variable `Neo4j_1a`" );
		}
	}

    @Test
    void testEnvVarsOverrideDefaultConfigurations() throws Exception
    {
        Assumptions.assumeTrue(TestSettings.NEO4J_VERSION.isAtLeastVersion(new Neo4jVersion(3, 0, 0)),
                               "No neo4j-admin in 2.3: skipping neo4j-admin-conf-override test");

        File conf;
        try(GenericContainer container = createContainer()
                .withEnv("NEO4J_dbms_memory_pagecache_size", "1000m")
                .withEnv("NEO4J_dbms_memory_heap_initial__size", "2000m")
                .withEnv("NEO4J_dbms_memory_heap_max__size", "3000m")
                .withEnv( "NEO4J_dbms_directories_logs", "/notdefaultlogs" )
                .withEnv( "NEO4J_dbms_directories_data", "/notdefaultdata" )
				.withCommand("dump-config") )
		{
			Path confMount = HostFileSystemOperations.createTempFolderAndMountAsVolume(
					container,
					"overriddenbyenv-conf-",
					"/conf" );
			conf = confMount.resolve( "neo4j.conf" ).toFile();
			container.setWaitStrategy(
                    Wait.forLogMessage( ".*Config Dumped.*", 1 ).withStartupTimeout( Duration.ofSeconds( 30 ) ) );
            SetContainerUser.nonRootUser( container );
            container.start();
        }

        // now check the settings we set via env are in the new conf file
        Assertions.assertTrue( conf.exists(), "configuration file not written" );
        Assertions.assertTrue( conf.canRead(), "configuration file not readable for some reason?" );

        Map<String,String> configurations = parseConfFile( conf );
        Assertions.assertTrue( configurations.containsKey( "dbms.memory.pagecache.size" ), "pagecache size not overridden" );
        Assertions.assertEquals( "1000m",
                                 configurations.get( "dbms.memory.pagecache.size" ),
                                 "pagecache size not overridden" );

        Assertions.assertTrue( configurations.containsKey( "dbms.memory.heap.initial_size" ), "initial heap size not overridden" );
        Assertions.assertEquals( "2000m",
                                 configurations.get( "dbms.memory.heap.initial_size" ),
                                 "initial heap size not overridden" );

        Assertions.assertTrue( configurations.containsKey( "dbms.memory.heap.max_size" ), "maximum heap size not overridden" );
        Assertions.assertEquals( "3000m",
                                 configurations.get( "dbms.memory.heap.max_size" ),
                                 "maximum heap size not overridden" );

        Assertions.assertTrue( configurations.containsKey( "dbms.directories.logs" ), "log folder not overridden" );
        Assertions.assertEquals( "/notdefaultlogs",
                                 configurations.get( "dbms.directories.logs" ),
                                 "log directory not overridden" );
        Assertions.assertTrue( configurations.containsKey( "dbms.directories.data" ), "data folder not overridden" );
        Assertions.assertEquals( "/notdefaultdata",
                                 configurations.get( "dbms.directories.data" ),
                                 "data directory not overridden" );
    }

    @Test
    void testReadsTheConfFile() throws Exception
    {
        Path debugLog;

        try(GenericContainer container = createContainer())
        {
			Path testOutputFolder = HostFileSystemOperations.createTempFolder( "confIsRead-" );
            //Mount /conf
            Path confMount = HostFileSystemOperations.createTempFolderAndMountAsVolume(
            		container,
					testOutputFolder,
					"conf-",
					"/conf" );
            Path logMount = HostFileSystemOperations.createTempFolderAndMountAsVolume(
            		container,
					testOutputFolder,
					"logs-",
					"/logs" );
            debugLog = logMount.resolve("debug.log");
            SetContainerUser.nonRootUser( container );
            //Create ReadConf.conf file with the custom env variables
            Path confFile = Paths.get( "src", "test", "resources", "confs", "ReadConf.conf" );
            Files.copy( confFile, confMount.resolve( "neo4j.conf" ) );
            //Start the container
            container.setWaitStrategy( Wait.forHttp( "/" ).forPort( 7474 ).forStatusCode( 200 ) );
            container.start();
        }

        //Check if the container reads the conf file
        Assertions.assertTrue( isStringPresentInDebugLog( debugLog, "dbms.memory.heap.max_size=512m" ),
                               "dbms.memory.heap.max_size was not set correctly");
    }

    @Test
    void testCommentedConfigsAreReplacedByDefaultOnes() throws Exception
    {
        File conf;
        try(GenericContainer container = createContainer())
        {
            //Mount /conf
            Path confMount = HostFileSystemOperations.createTempFolderAndMountAsVolume(
            		container,
					"replacedbydefault-conf-",
					"/conf" );
            conf = confMount.resolve( "neo4j.conf" ).toFile();
            SetContainerUser.nonRootUser( container );
            //Create ConfsReplaced.conf file
            Path confFile = Paths.get( "src", "test", "resources", "confs", "ConfsReplaced.conf" );
            Files.copy( confFile, confMount.resolve( "neo4j.conf" ) );
            //Start the container
            container.setWaitStrategy(
                    Wait.forLogMessage( ".*Config Dumped.*", 1 ).withStartupTimeout( Duration.ofSeconds( 30 ) ) );
            container.setCommand( "dump-config" );
            container.start();
        }
        //Read the config file to check if the config is set correctly
        Map<String,String> configurations = parseConfFile( conf );
        Assertions.assertTrue( configurations.containsKey( "dbms.memory.pagecache.size" ),
                               "conf settings not set correctly by docker-entrypoint" );
        Assertions.assertEquals( "512M",
                                 configurations.get( "dbms.memory.pagecache.size" ),
                                 "conf settings not appended correctly by docker-entrypoint" );
    }

    @Test
    void testConfigsAreNotOverridenByDockerentrypoint() throws Exception
    {
        File conf;
        try(GenericContainer container = createContainer())
        {
            //Mount /conf
            Path confMount = HostFileSystemOperations.createTempFolderAndMountAsVolume(
            		container,
					"notoverriddenbydefault-conf-",
					"/conf" );
            conf = confMount.resolve( "neo4j.conf" ).toFile();
            SetContainerUser.nonRootUser( container );
            //Create ConfsNotOverriden.conf file
            Path confFile = Paths.get( "src", "test", "resources", "confs", "ConfsNotOverriden.conf" );
            Files.copy( confFile, confMount.resolve( "neo4j.conf" ) );
            //Start the container
            container.setWaitStrategy(
                    Wait.forLogMessage( ".*Config Dumped.*", 1 )
						.withStartupTimeout( Duration.ofSeconds( 30 ) ) );
            container.setCommand( "dump-config" );
            container.start();
        }

        //Read the config file to check if the config is not overriden
        Map<String, String> configurations = parseConfFile(conf);
        Assertions.assertTrue(configurations.containsKey("dbms.memory.pagecache.size"), "conf settings not set correctly by docker-entrypoint");
        Assertions.assertEquals("1024M",
                                configurations.get("dbms.memory.pagecache.size"),
                                "docker-entrypoint has overriden custom setting set from user's conf");
    }

    @Test
    void testEnvVarsOverride() throws Exception
    {
        Path debugLog;
        try(GenericContainer container = createContainer().withEnv("NEO4J_dbms_memory_pagecache_size", "512m"))
        {
			Path testOutputFolder = HostFileSystemOperations.createTempFolder( "envoverrideworks-" );
            Path confMount = HostFileSystemOperations.createTempFolderAndMountAsVolume(
            		container,
					testOutputFolder,
					"conf-",
					"/conf" );
            Path logMount = HostFileSystemOperations.createTempFolderAndMountAsVolume(
            		container,
					testOutputFolder,
					"logs-",
					"/logs" );
            debugLog = logMount.resolve( "debug.log" );
            SetContainerUser.nonRootUser( container );
            //Create EnvVarsOverride.conf file
            Path confFile = Paths.get( "src", "test", "resources", "confs", "EnvVarsOverride.conf" );
            Files.copy( confFile, confMount.resolve( "EnvVarsOverride.conf" ) );
            //Start the container
            container.setWaitStrategy( Wait.forHttp( "/" ).forPort( 7474 ).forStatusCode( 200 ) );
            container.start();
        }

        Assertions.assertTrue( isStringPresentInDebugLog( debugLog, "dbms.memory.pagecache.size=512m" ),
                              "dbms.memory.pagecache.size was not set correctly");
    }

    @Test
    void testEnterpriseOnlyDefaultsConfigsAreSet () throws Exception
    {
        Assumptions.assumeTrue(TestSettings.EDITION == TestSettings.Edition.ENTERPRISE,
                               "This is testing only ENTERPRISE EDITION configs");

        try(GenericContainer container = createContainer().withEnv("NEO4J_dbms_memory_pagecache_size", "512m"))
        {
            //Mount /logs
            Path logMount = HostFileSystemOperations.createTempFolderAndMountAsVolume(
            		container,
					"enterpriseonlysettings-logs-",
					"/logs" );
            SetContainerUser.nonRootUser( container );
            //Start the container
            container.setWaitStrategy( Wait.forHttp( "/" ).forPort( 7474 ).forStatusCode( 200 ) );
            container.start();
            //Read debug.log to check that causal_clustering confs are set successfully
            String expectedTxAddress = container.getContainerId().substring( 0, 12 ) + ":6000";

            Assertions.assertTrue( isStringPresentInDebugLog(logMount.resolve( "debug.log" ),
                                                             "causal_clustering.transaction_advertised_address=" + expectedTxAddress ),
                                   "causal_clustering.transaction_advertised_address was not set correctly" );
        }
    }

    // disabled because there are currently no enterprise-only settings
    @Disabled
    @Test
    void testCommunityDoesNotHaveEnterpriseConfigs() throws Exception
    {
		Assert.fail( "This test should not have run!" );
        Assumptions.assumeTrue(TestSettings.EDITION == TestSettings.Edition.COMMUNITY,
                               "This is testing only COMMUNITY EDITION configs");
        Path debugLog;
        try(GenericContainer container = createContainer().withEnv("NEO4J_dbms_memory_pagecache_size", "512m"))
        {
            //Mount /logs
			Path logMount = HostFileSystemOperations.createTempFolderAndMountAsVolume(
					container,
					"enterprisesettingsnotincommunity-logs-",
					"/logs" );
            debugLog = logMount.resolve( "debug.log" );
            SetContainerUser.nonRootUser( container );
            //Start the container
            container.setWaitStrategy( Wait.forHttp( "/" ).forPort( 7474 ).forStatusCode( 200 ) );
            container.start();
        }

        //Read debug.log to check that causal_clustering confs are not present
        Assertions.assertFalse(isStringPresentInDebugLog( debugLog, "causal_clustering.transaction_listen_address" ),
                               "causal_clustering.transaction_listen_address should not be on the Community debug.log");
    }

    @Test
    void testJvmAdditionalNotOverridden() throws Exception
    {
        Path logMount;

        try(GenericContainer container = createContainer())
        {
            Assumptions.assumeFalse( TestSettings.NEO4J_VERSION.isAtLeastVersion( Neo4jVersion.NEO4J_VERSION_400), "test not applicable in versions newer than 4.0." );

			Path testOutputFolder = HostFileSystemOperations.createTempFolder( "jvmaddnotoverridden-" );
            //Mount /conf
			Path confMount = HostFileSystemOperations.createTempFolderAndMountAsVolume(
					container,
					testOutputFolder,
					"conf-",
					"/conf" );
			logMount = HostFileSystemOperations.createTempFolderAndMountAsVolume(
					container,
					testOutputFolder,
					"logs-",
					"/logs" );
			SetContainerUser.nonRootUser( container );
            //Create JvmAdditionalNotOverriden.conf file
            Path confFile = Paths.get( "src", "test", "resources", "confs", "JvmAdditionalNotOverriden.conf" );
            Files.copy( confFile, confMount.resolve( "neo4j.conf" ) );
            //Start the container
            container.setWaitStrategy( Wait.forHttp( "/" ).forPort( 7474 ).forStatusCode( 200 ) );
            container.start();
        }

        //Read the debug.log to check that dbms.jvm.additional was set correctly
        Stream<String> lines = Files.lines(logMount.resolve("debug.log"));
        Optional<String> jvmAdditionalMatch = lines.filter(s -> s.contains("dbms.jvm.additional=-Dunsupported.dbms.udc.source=docker,-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005")).findFirst();
        lines.close();
        Assertions.assertTrue(isStringPresentInDebugLog( logMount.resolve("debug.log"),
                "dbms.jvm.additional=-Dunsupported.dbms.udc.source=docker,-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005"),
            "dbms.jvm.additional is overriden by Docker-entrypoint");
    }

    @Test
    void testShellExpansionAvoided() throws Exception
    {
        Assumptions.assumeTrue( TestSettings.NEO4J_VERSION.isAtLeastVersion( Neo4jVersion.NEO4J_VERSION_400), "test only applicable to 4.0 and beyond." );

        Path confMount;
        try(GenericContainer container = createContainer().withEnv("NEO4J_dbms_security_procedures_unrestricted", "*"))
        {
			confMount = HostFileSystemOperations.createTempFolderAndMountAsVolume(
					container,
					"shellexpansionavoided-conf-",
					"/conf" );

            SetContainerUser.nonRootUser( container );
            //Start the container
            container.setWaitStrategy(
                    Wait.forLogMessage( ".*Config Dumped.*", 1 )
						.withStartupTimeout( Duration.ofSeconds( 30 ) ) );
            container.setCommand( "dump-config" );
            container.start();
        }
        File conf = confMount.resolve( "neo4j.conf" ).toFile();
        Map<String, String> configurations = parseConfFile(conf);
        Assertions.assertTrue(configurations.containsKey("dbms.security.procedures.unrestricted"), "configuration not set from env var");
        Assertions.assertEquals("*",
                configurations.get("dbms.security.procedures.unrestricted"),
                "Configuration value should be *. If it's not docker-entrypoint.sh probably evaluated it as a glob expression.");
    }
}
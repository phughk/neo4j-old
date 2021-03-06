/*
 * Copyright (c) 2002-2018 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.consistency;

import org.apache.commons.lang3.StringUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.time.ZoneOffset;
import java.util.Properties;
import java.util.TimeZone;

import org.neo4j.consistency.ConsistencyCheckTool.ToolFailureException;
import org.neo4j.consistency.checking.full.CheckConsistencyConfig;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.helpers.progress.ProgressMonitorFactory;
import org.neo4j.io.fs.DefaultFileSystemAbstraction;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.logging.LogProvider;
import org.neo4j.logging.LogTimeZone;
import org.neo4j.test.TestGraphDatabaseFactory;
import org.neo4j.test.rule.TestDirectory;
import org.neo4j.test.rule.fs.EphemeralFileSystemRule;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.neo4j.graphdb.Label.label;

public class ConsistencyCheckToolTest
{
    private final TestDirectory storeDirectory = TestDirectory.testDirectory();
    private final EphemeralFileSystemRule fs = new EphemeralFileSystemRule();

    @Rule
    public RuleChain ruleChain = RuleChain.outerRule( storeDirectory ).around( fs );

    @Test
    public void runsConsistencyCheck() throws Exception
    {
        // given
        File storeDir = storeDirectory.directory();
        String[] args = {storeDir.getPath()};
        ConsistencyCheckService service = mock( ConsistencyCheckService.class );

        // when
        runConsistencyCheckToolWith( service, args );

        // then
        verify( service ).runFullConsistencyCheck( eq( storeDir ), any( Config.class ),
                any( ProgressMonitorFactory.class ), any( LogProvider.class ), any( FileSystemAbstraction.class ),
                anyBoolean(), any( CheckConsistencyConfig.class ) );
    }

    @Test
    public void consistencyCheckerLogUseSystemTimezoneIfConfigurable() throws Exception
    {
        TimeZone defaultTimeZone = TimeZone.getDefault();
        try
        {
            ConsistencyCheckService service = mock( ConsistencyCheckService.class );
            Mockito.when( service.runFullConsistencyCheck( any( File.class ), any( Config.class ),
                    any( ProgressMonitorFactory.class ), any( LogProvider.class ), any( FileSystemAbstraction.class ),
                    eq( false ), any( CheckConsistencyConfig.class ) ) )
                    .then( invocationOnMock ->
                    {
                        LogProvider provider = invocationOnMock.getArgumentAt( 3, LogProvider.class );
                        provider.getLog( "test" ).info( "testMessage" );
                        return ConsistencyCheckService.Result.success( new File( StringUtils.EMPTY ) );
                    } );
            File storeDir = storeDirectory.directory();
            File configFile = storeDirectory.file( Config.DEFAULT_CONFIG_FILE_NAME );
            Properties properties = new Properties();
            properties.setProperty( GraphDatabaseSettings.log_timezone.name(), LogTimeZone.SYSTEM.name() );
            properties.store( new FileWriter( configFile ), null );
            String[] args = {storeDir.getPath(), "-config", configFile.getPath()};

            checkLogRecordTimeZone( service, args, 5, "+0500" );
            checkLogRecordTimeZone( service, args, -5, "-0500" );
        }
        finally
        {
            TimeZone.setDefault( defaultTimeZone );
        }
    }

    @Test
    public void appliesDefaultTuningConfigurationForConsistencyChecker() throws Exception
    {
        // given
        File storeDir = storeDirectory.directory();
        String[] args = {storeDir.getPath()};
        ConsistencyCheckService service = mock( ConsistencyCheckService.class );

        // when
        runConsistencyCheckToolWith( service, args );

        // then
        ArgumentCaptor<Config> config = ArgumentCaptor.forClass( Config.class );
        verify( service ).runFullConsistencyCheck( eq( storeDir ), config.capture(),
                any( ProgressMonitorFactory.class ), any( LogProvider.class ), any( FileSystemAbstraction.class ),
                anyBoolean(), any( CheckConsistencyConfig.class ) );
        assertFalse( config.getValue().get( ConsistencyCheckSettings.consistency_check_property_owners ) );
    }

    @Test
    public void passesOnConfigurationIfProvided() throws Exception
    {
        // given
        File storeDir = storeDirectory.directory();
        File configFile = storeDirectory.file( Config.DEFAULT_CONFIG_FILE_NAME );
        Properties properties = new Properties();
        properties.setProperty( ConsistencyCheckSettings.consistency_check_property_owners.name(), "true" );
        properties.store( new FileWriter( configFile ), null );

        String[] args = {storeDir.getPath(), "-config", configFile.getPath()};
        ConsistencyCheckService service = mock( ConsistencyCheckService.class );

        // when
        runConsistencyCheckToolWith( service, args );

        // then
        ArgumentCaptor<Config> config = ArgumentCaptor.forClass( Config.class );
        verify( service ).runFullConsistencyCheck( eq( storeDir ), config.capture(),
                any( ProgressMonitorFactory.class ), any( LogProvider.class ), any( FileSystemAbstraction.class ),
                anyBoolean(), any(CheckConsistencyConfig.class) );
        assertTrue( config.getValue().get( ConsistencyCheckSettings.consistency_check_property_owners ) );
    }

    @Test
    public void exitWithFailureIndicatingCorrectUsageIfNoArgumentsSupplied() throws Exception
    {
        // given
        ConsistencyCheckService service = mock( ConsistencyCheckService.class );
        String[] args = {};

        try
        {
            // when
            runConsistencyCheckToolWith( service, args );
            fail( "should have thrown exception" );
        }
        catch ( ConsistencyCheckTool.ToolFailureException e )
        {
            // then
            assertThat( e.getMessage(), containsString( "USAGE:" ) );
        }
    }

    @Test
    public void exitWithFailureIfConfigSpecifiedButConfigFileDoesNotExist() throws Exception
    {
        // given
        File configFile = storeDirectory.file( "nonexistent_file" );
        String[] args = {storeDirectory.directory().getPath(), "-config", configFile.getPath()};
        ConsistencyCheckService service = mock( ConsistencyCheckService.class );

        try
        {
            // when
            runConsistencyCheckToolWith( service, args );
            fail( "should have thrown exception" );
        }
        catch ( ConsistencyCheckTool.ToolFailureException e )
        {
            // then
            assertThat( e.getMessage(), containsString( "Could not read configuration file" ) );
            assertThat( e.getCause(), instanceOf( IOException.class ) );
        }

        verifyZeroInteractions( service );
    }

    @Test( expected = ToolFailureException.class )
    public void failWhenStoreWasNonCleanlyShutdown() throws Exception
    {
        createGraphDbAndKillIt();

        runConsistencyCheckToolWith( fs.get(), storeDirectory.graphDbDir().getAbsolutePath() );
    }

    private void checkLogRecordTimeZone( ConsistencyCheckService service, String[] args, int hoursShift,
            String timeZoneSuffix ) throws ToolFailureException, IOException
    {
        TimeZone.setDefault( TimeZone.getTimeZone( ZoneOffset.ofHours( hoursShift ) ) );
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream( outputStream );
        runConsistencyCheckToolWith( service, printStream, args );
        String logLine = readLogLine( outputStream );
        assertTrue( logLine, logLine.contains( timeZoneSuffix ) );
    }

    private String readLogLine( ByteArrayOutputStream outputStream ) throws IOException
    {
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream( outputStream.toByteArray() );
        BufferedReader bufferedReader = new BufferedReader( new InputStreamReader( byteArrayInputStream ) );
        return bufferedReader.readLine();
    }

    private void createGraphDbAndKillIt() throws Exception
    {
        final GraphDatabaseService db = new TestGraphDatabaseFactory()
                .setFileSystem( fs.get() )
                .newImpermanentDatabaseBuilder( storeDirectory.graphDbDir() )
                .newGraphDatabase();

        try ( Transaction tx = db.beginTx() )
        {
            db.createNode( label( "FOO" ) );
            db.createNode( label( "BAR" ) );
            tx.success();
        }

        fs.snapshot( db::shutdown );
    }

    private void runConsistencyCheckToolWith( FileSystemAbstraction fileSystem, String... args )
            throws IOException, ToolFailureException
    {
        new ConsistencyCheckTool( mock( ConsistencyCheckService.class ), fileSystem, mock( PrintStream.class),
                mock( PrintStream.class ) ).run( args );
    }

    private void runConsistencyCheckToolWith( ConsistencyCheckService
            consistencyCheckService, String... args ) throws ToolFailureException, IOException
    {
        runConsistencyCheckToolWith( consistencyCheckService, mock( PrintStream.class ), args );
    }

    private void runConsistencyCheckToolWith( ConsistencyCheckService
            consistencyCheckService, PrintStream printStream, String... args ) throws ToolFailureException, IOException
    {
        try ( FileSystemAbstraction fileSystemAbstraction = new DefaultFileSystemAbstraction() )
        {
            new ConsistencyCheckTool( consistencyCheckService, fileSystemAbstraction, printStream, printStream )
                    .run( args );
        }
    }
}

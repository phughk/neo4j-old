/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.backup;

import org.apache.commons.lang3.SystemUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.neo4j.causalclustering.core.CausalClusteringSettings;
import org.neo4j.causalclustering.core.CoreGraphDatabase;
import org.neo4j.causalclustering.core.consensus.roles.Role;
import org.neo4j.causalclustering.discovery.Cluster;
import org.neo4j.causalclustering.discovery.CoreClusterMember;
import org.neo4j.causalclustering.helpers.CausalClusteringTestHelpers;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.kernel.impl.store.format.highlimit.HighLimit;
import org.neo4j.kernel.impl.store.format.standard.Standard;
import org.neo4j.test.DbRepresentation;
import org.neo4j.test.causalclustering.ClusterRule;
import org.neo4j.test.rule.SuppressOutput;
import org.neo4j.test.rule.TestDirectory;
import org.neo4j.util.TestHelpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeFalse;

@RunWith( Parameterized.class )
public class OnlineBackupCommandCcIT
{
    @Rule
    public final TestDirectory testDirectory = TestDirectory.testDirectory();

    @Rule
    public ClusterRule clusterRule = new ClusterRule( getClass() )
            .withNumberOfCoreMembers( 3 )
            .withNumberOfReadReplicas( 3 )
            .withSharedCoreParam( CausalClusteringSettings.cluster_topology_refresh, "5s" );

    @Rule
    public final RuleChain ruleChain = RuleChain.outerRule( SuppressOutput.suppressAll() ).around( clusterRule );

    private File backupDir;

    private List<Runnable> oneOffShutdownTasks;
    private static final Label label = Label.label( "any_label" );
    private static final String PROP_NAME = "name";
    private static final String PROP_RANDOM = "random";

    @Parameter
    public String recordFormat;

    @Parameters( name = "{0}" )
    public static List<String> recordFormats()
    {
        return Arrays.asList( Standard.LATEST_NAME, HighLimit.NAME );
    }

    @Before
    public void initialiseBackupDirectory()
    {
        oneOffShutdownTasks = new ArrayList<>();
        backupDir = testDirectory.directory( "backups" );
    }

    @After
    public void performShutdownTasks()
    {
        oneOffShutdownTasks.forEach( Runnable::run );
    }

    @Test
    public void backupCanBePerformedOverCcWithCustomPort() throws Exception
    {
        assumeFalse( SystemUtils.IS_OS_WINDOWS );

        Cluster cluster = startCluster( recordFormat );
        String customAddress = CausalClusteringTestHelpers.transactionAddress( clusterLeader( cluster ).database() );
        assertEquals(
                1,
                runBackupToolFromOtherJvmToGetExitCode( "--cc-report-dir=" + backupDir,
                        "--backup-dir=" + backupDir,
                        "--name=defaultport" ) );
        assertEquals(
                0,
                runBackupToolFromOtherJvmToGetExitCode( "--from", customAddress,
                        "--cc-report-dir=" + backupDir,
                        "--backup-dir=" + backupDir,
                        "--name=defaultport" ) );
        assertEquals( DbRepresentation.of( clusterDatabase( cluster ) ), getBackupDbRepresentation( "defaultport" ) );

        createSomeData( clusterDatabase( cluster ) );
        assertEquals(
                0,
                runBackupToolFromOtherJvmToGetExitCode( "--from", customAddress,
                        "--cc-report-dir=" + backupDir,
                        "--backup-dir=" + backupDir,
                        "--name=defaultport" ) );
        assertEquals( DbRepresentation.of( clusterDatabase( cluster ) ), getBackupDbRepresentation( "defaultport" ) );
    }

    @Test
    public void backupCanNotBePerformedOverBackupProtocol() throws Exception
    {
        assumeFalse( SystemUtils.IS_OS_WINDOWS );

        Cluster cluster = startCluster( recordFormat );
        String ip = TestHelpers.backupAddressHa( clusterLeader( cluster ).database() );
        assertEquals(
                1,
                runBackupToolFromOtherJvmToGetExitCode( "--from", ip,
                        "--cc-report-dir=" + backupDir,
                        "--backup-dir=" + backupDir,
                        "--name=defaultport" ) );
    }

    @Test
    public void dataIsInAUsableStateAfterBackup() throws Exception
    {
        // given database exists
        Cluster cluster = startCluster( recordFormat );

        // and the database has indexes
        createIndexes( cluster.getDbWithAnyRole( Role.LEADER ).database() );

        // and the database is being populated
        AtomicBoolean continueFlagReference = new AtomicBoolean( false );
        new Thread( () -> repeatedlyPopulateDatabase( clusterLeader( cluster ).database(), continueFlagReference ) )
                .start(); // populate db with number properties etc.
        oneOffShutdownTasks.add( () -> continueFlagReference.set( false ) ); // kill thread

        // then backup is successful
        String ip = TestHelpers.backupAddressCc( clusterLeader( cluster ).database() );
        assertEquals( 0,
                runBackupToolFromOtherJvmToGetExitCode( "--from", ip, "--cc-report-dir=" + backupDir, "--backup-dir=" + backupDir, "--name=defaultport" ) );
    }

    private void repeatedlyPopulateDatabase( GraphDatabaseService db, AtomicBoolean continueFlagReference )
    {
        while ( continueFlagReference.get() )
        {
            createSomeData( db );
        }
    }

    private void createIndexes( CoreGraphDatabase db )
    {
        try ( Transaction tx = db.beginTx() )
        {
            db.schema().indexFor( label ).on( PROP_NAME ).on( PROP_RANDOM ).create();
            tx.success();
        }
    }

    private static CoreGraphDatabase clusterDatabase( Cluster cluster )
    {
        return clusterLeader( cluster ).database();
    }

    private Cluster startCluster( String recordFormat )
            throws Exception
    {
        ClusterRule clusterRule = this.clusterRule
                .withSharedCoreParam( GraphDatabaseSettings.record_format, recordFormat )
                .withSharedReadReplicaParam( GraphDatabaseSettings.record_format, recordFormat );
        Cluster cluster = clusterRule.startCluster();
        createSomeData( clusterDatabase( cluster ) );
        return cluster;
    }

    public static DbRepresentation createSomeData( GraphDatabaseService db )
    {
        try ( Transaction tx = db.beginTx() )
        {
            Node node = db.createNode( label );
            node.setProperty( PROP_NAME, "Neo" );
            node.setProperty( PROP_RANDOM, Math.random() * 10000 );
            db.createNode().createRelationshipTo( node, RelationshipType.withName( "KNOWS" ) );
            tx.success();
        }
        return DbRepresentation.of( db );
    }

    private static CoreClusterMember clusterLeader( Cluster cluster )
    {
        return cluster.getDbWithRole( Role.LEADER );
    }

    private DbRepresentation getBackupDbRepresentation( String name )
    {
        return DbRepresentation.of( new File( backupDir, name ) );
    }

    private int runBackupToolFromOtherJvmToGetExitCode( String... args ) throws Exception
    {
        return TestHelpers.runBackupToolFromOtherJvmToGetExitCode( testDirectory.absolutePath(), args );
    }
}

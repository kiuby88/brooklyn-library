/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.brooklyn.entity.webapp;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.brooklyn.api.entity.Application;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.policy.PolicySpec;
import org.apache.brooklyn.camp.brooklyn.BrooklynCampConstants;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.EntityRelations;
import org.apache.brooklyn.core.entity.trait.Startable;
import org.apache.brooklyn.core.location.PortRanges;
import org.apache.brooklyn.core.test.BrooklynAppLiveTestSupport;
import org.apache.brooklyn.core.test.entity.TestApplication;
import org.apache.brooklyn.entity.webapp.tomcat.TomcatServer;
import org.apache.brooklyn.launcher.SimpleYamlLauncherForTests;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

/**
 * A simple test of installing+running on AWS-EC2, using various OS distros and versions.
 */
public class ApplicationMigrationTest extends BrooklynAppLiveTestSupport {

    private static final Logger log = LoggerFactory.getLogger(ApplicationMigrationTest.class);


    @AfterMethod(alwaysRun=true)
    @Override
    public void tearDown() throws Exception {

    }

    protected EntitySpec<? extends TestApplication> newAppSpec() {
        return EntitySpec.create(TestApplication.class);
    }

    @Test(groups = {"Live"})
    public void doTestt() throws Exception {

        SimpleYamlLauncherForTests launcher = new SimpleYamlLauncherForTests();
        launcher.setShutdownAppsOnExit(true);

        final Application app = launcher.launchAppYaml("application-migration-policy.yml");


        final TomcatServer server = (TomcatServer)
                findChildEntitySpecByPlanId(app, "webapp1");

        final TomcatServer server2 = (TomcatServer)
                findChildEntitySpecByPlanId(app, "webapp2");

        Asserts.succeedsEventually(
                new Runnable() {
                    public void run() {
                        assertTrue(app.getAttribute(Attributes.SERVICE_UP));

                    }
                });

        //final Map<String, String> childrenToMigrate = ImmutableMap.of("webapp1", "aws-ec2:eu-west-1");
        //final Map<String, Map<String, String>> effectorParameters = ImmutableMap.of(ApplicationMigrateEffector.MIGRATE_CHILDREN_LOCATIONS_SPEC, childrenToMigrate);

        //app.invoke(ApplicationMigrateEffector.MIGRATE_APPLICATION, effectorParameters).blockUntilEnded();

        System.out.println();


       // launcher.destroyAll();
    }


    @Test(groups = {"Live"})
    public void testApplicationMigration() throws Exception {

        final EntitySpec<TomcatServer> sonSpec = EntitySpec.create(TomcatServer.class)
                .configure(TomcatServer.ROOT_WAR, "http://search.maven.org/remotecontent?filepath=org/apache/brooklyn/example/brooklyn-example-hello-world-sql-webapp/0.8.0-incubating/brooklyn-example-hello-world-sql-webapp-0.8.0-incubating.war")
                .configure(TomcatServer.HTTP_PORT, PortRanges.fromString("8080+"))
                .configure(BrooklynCampConstants.PLAN_ID, "webapp1")
                .location(mgmt.getLocationRegistry().getLocationSpec("localhost").get())
                //.location(mgmt.getLocationRegistry().getLocationSpec("aws-ec2:eu-west-1").get())
                ;

        final EntitySpec<TomcatServer> parentSpec = EntitySpec.create(TomcatServer.class)
                .configure(TomcatServer.ROOT_WAR, "http://search.maven.org/remotecontent?filepath=org/apache/brooklyn/example/brooklyn-example-hello-world-sql-webapp/0.8.0-incubating/brooklyn-example-hello-world-sql-webapp-0.8.0-incubating.war")
                .configure(TomcatServer.HTTP_PORT, PortRanges.fromString("8080+"))
                .configure(BrooklynCampConstants.PLAN_ID, "parent")
                .location(mgmt.getLocationRegistry().getLocationSpec("localhost").get())
                //.location(mgmt.getLocationRegistry().getLocationSpec("aws-ec2:eu-west-1").get())
                ;

        final EntitySpec<TomcatServer> gParentSpec = EntitySpec.create(TomcatServer.class)
                .configure(TomcatServer.ROOT_WAR, "http://search.maven.org/remotecontent?filepath=org/apache/brooklyn/example/brooklyn-example-hello-world-sql-webapp/0.8.0-incubating/brooklyn-example-hello-world-sql-webapp-0.8.0-incubating.war")
                .configure(TomcatServer.HTTP_PORT, PortRanges.fromString("8080+"))
                .location(mgmt.getLocationRegistry().getLocationSpec("aws-ec2:eu-west-1").get())
                ;

        //adding relations
        //parentSpec.getRelations().addTargetRelationSpec(sonSpec);
        //gParentSpec.getRelations().addTargetRelationSpec(parentSpec);

        //final TomcatServer gParent = app.createAndManageChild(gParentSpec);
        final TomcatServer son = app.createAndManageChild(sonSpec);
        final TomcatServer parent = app.createAndManageChild(parentSpec);


        //mgmt.getLocationRegistry().getLocationSpec("aws-ec2:eu-west-2");
       // mgmt.getLocationRegistry().getLocationSpec("localhost");
        //final Location loc = mgmt.getLocationRegistry().getLocationManaged("aws-ec2:eu-west-2");
        final Location loc = mgmt.getLocationRegistry().getLocationManaged("localhost");


        app.addPolicy(PolicySpec.create(ApplicationMigrationPolicy.class));
        //location necessary
        app.start(ImmutableList.<Location>of(
                        //loc
                ));


        MutableMap<String, Duration> flags = MutableMap.of("timeout", Duration.ONE_HOUR);

        Asserts.succeedsEventually(flags, new Runnable() {
            public void run() {
                assertTrue(app.getAttribute(Startable.SERVICE_UP));
                //assertTrue(entity.getAttribute(VanillaCloudFoundryApplication
                //        .SERVICE_PROCESS_IS_RUNNING));
            }
        });

        assertTrue(app.getAttribute(Startable.SERVICE_UP));


        parent.relations().add(EntityRelations.HAS_TARGET, son);

        log.info("************************ Relations SON:" + son.relations().getRelations(EntityRelations.TARGETTED_BY).size());
        log.info("************************ Relations Parent:" + parent.relations().getRelations(EntityRelations.TARGETTED_BY).size());


        assertEquals(son.getLocations().size(), 2);
        assertEquals(((Location)son.getLocations().toArray()[0]).getDisplayName(), "localhost");



        final Map<String, String> childrenToMigrate = ImmutableMap.of("webapp1", "pivotal-ws2");
        final Map<String, Map<String, String>> effectorParameters = ImmutableMap.of(ApplicationMigrateEffector.MIGRATE_CHILDREN_LOCATIONS_SPEC, childrenToMigrate);

        log.info("************************ READY TO MIGRATE *************");
        log.info("************************ READY TO MIGRATE *************");
        log.info("************************ READY TO MIGRATE *************");
        log.info("************************ READY TO MIGRATE *************");
        log.info("************************ READY TO MIGRATE *************");
        log.info("************************ READY TO MIGRATE *************");
        log.info("************************ READY TO MIGRATE *************");


        app.invoke(ApplicationMigrateEffector.MIGRATE_APPLICATION, effectorParameters).blockUntilEnded();




        Asserts.succeedsEventually( MutableMap.of("timeout", new Duration(10, TimeUnit.MINUTES)), new Runnable() {
            public void run() {
                assertEquals(son.getLocations().size(), 1);
            }
        });


        Asserts.succeedsEventually( MutableMap.of("timeout", new Duration(10, TimeUnit.MINUTES)), new Runnable() {
            public void run() {
                assertTrue(son.getAttribute(TomcatServer.SERVICE_PROCESS_IS_RUNNING));
                assertTrue(son.getAttribute(TomcatServer.SERVICE_UP));
                //assertTrue(app.getAttribute(Startable.SERVICE_UP));
                assertTrue(app.getAttribute(Startable.SERVICE_UP));
                //assertTrue(entity.getAttribute(VanillaCloudFoundryApplication
                //        .SERVICE_PROCESS_IS_RUNNING));
            }
        });


/*
        Asserts.succeedsEventually( MutableMap.of("timeout", new Duration(10, TimeUnit.MINUTES)), new Runnable() {
            public void run() {
                assertFalse(son.getAttribute(TomcatServer.SERVICE_PROCESS_IS_RUNNING));
                assertFalse(son.getAttribute(TomcatServer.SERVICE_UP));
                //assertTrue(app.getAttribute(Startable.SERVICE_UP));
                assertTrue(app.getAttribute(Startable.SERVICE_UP));
                //assertTrue(entity.getAttribute(VanillaCloudFoundryApplication
                //        .SERVICE_PROCESS_IS_RUNNING));
            }
        });

        assertTrue(app.getAttribute(Startable.SERVICE_UP));
        assertFalse(son.getAttribute(Startable.SERVICE_UP));
        assertFalse(parent.getAttribute(Startable.SERVICE_UP));*/

    }


    protected Entity findChildEntitySpecByPlanId(Application app, String planId) {
        for (Entity child : app.getChildren()) {
            String childPlanId = child.getConfig(BrooklynCampConstants.PLAN_ID);
            if ((childPlanId != null) && (childPlanId.equals(planId))) {
                return child;
            }
        }
        return null;
    }

}

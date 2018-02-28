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

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
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
import org.apache.brooklyn.core.internal.BrooklynProperties;
import org.apache.brooklyn.core.location.PortRanges;
import org.apache.brooklyn.core.test.BrooklynAppLiveTestSupport;
import org.apache.brooklyn.core.test.entity.LocalManagementContextForTests;
import org.apache.brooklyn.core.test.entity.TestApplication;
import org.apache.brooklyn.entity.software.base.SoftwareProcess;
import org.apache.brooklyn.entity.webapp.tomcat.TomcatServer;
import org.apache.brooklyn.launcher.SimpleYamlLauncherForTests;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

/**
 * A simple test of installing+running on AWS-EC2, using various OS distros and versions.
 */
public class ApplicationMigrationTdTest
        extends BrooklynAppLiveTestSupport {

    private static final Logger log = LoggerFactory.getLogger(ApplicationMigrationTdTest.class);


    @BeforeMethod(alwaysRun = true)
    public void setUp() throws Exception {
        if (mgmt != null) {
            app = mgmt.getEntityManager().createEntity(newAppSpec());
        } else {
            mgmt = new LocalManagementContextForTests(BrooklynProperties.Factory.newDefault());
            app = mgmt.getEntityManager().createEntity(newAppSpec());
        }
    }


    @AfterMethod(alwaysRun = true)
    @Override
    public void tearDown() throws Exception {

    }

    protected EntitySpec<? extends TestApplication> newAppSpec() {
        return EntitySpec.create(TestApplication.class);
    }

    private static URL getTargetURL(String target) {
        try {
            return URI.create(target).toURL();
        } catch (MalformedURLException e) {
            throw new RuntimeException("The target URL is not valid: " + e.getMessage());
        }
    }

    @Test(groups = {"Live"})
    public void doTestt() throws Exception {


//        CloudFoundryClient client = new CloudFoundryClient(
//                new CloudCredentials("kiuby_88@hotmail.com", "Cloud_88"),
//                getTargetURL("https://api.run.pivotal.io"),
//                "gsoc", "development", true);


        SimpleYamlLauncherForTests launcher = new SimpleYamlLauncherForTests();
        launcher.setShutdownAppsOnExit(true);

        final Application app = launcher.launchAppYaml("application-migration-policy-td.yml");


        final TomcatServer server = (TomcatServer)
                findChildEntitySpecByPlanId(app, "webapp1");

        final TomcatServer server2 = (TomcatServer)
                findChildEntitySpecByPlanId(app, "webapp2");

        Asserts.succeedsEventually(
                ImmutableMap.of("timeout", Duration.PRACTICALLY_FOREVER),
                new Runnable() {
                    public void run() {
                        assertTrue(app.getAttribute(Attributes.SERVICE_UP));

                    }
                });

        final Map<String, String> childrenToMigrate = ImmutableMap.of("webapp1", "pivotal");
        final Map<String, Map<String, String>> effectorParameters = ImmutableMap.of(ApplicationMigrateEffector.MIGRATE_CHILDREN_LOCATIONS_SPEC, childrenToMigrate);

        app.invoke(ApplicationMigrateEffector.MIGRATE_APPLICATION, effectorParameters).blockUntilEnded();

        System.out.println();


        // launcher.destroyAll();
    }


    @Test(groups = {"Live"})
    public void testApplicationMigration() throws Exception {

        final EntitySpec<TomcatServer> sonSpec = EntitySpec.create(TomcatServer.class)
                .configure(TomcatServer.ROOT_WAR, "http://search.maven.org/remotecontent?filepath=org/apache/brooklyn/example/brooklyn-example-hello-world-sql-webapp/0.8.0-incubating/brooklyn-example-hello-world-sql-webapp-0.8.0-incubating.war")
                .configure(TomcatServer.HTTP_PORT, PortRanges.fromString("8080+"))
                .configure(BrooklynCampConstants.PLAN_ID, "webapp1")
                .configure(SoftwareProcess.START_TIMEOUT, Duration.PRACTICALLY_FOREVER)
                .location(mgmt.getLocationRegistry().getLocationSpec("pivotal-ws2").get())
                //.location(mgmt.getLocationRegistry().getLocationSpec("aws-ec2:eu-west-1").get())
                ;

        final EntitySpec<TomcatServer> parentSpec = EntitySpec.create(TomcatServer.class)
                .configure(TomcatServer.ROOT_WAR, "http://search.maven.org/remotecontent?filepath=org/apache/brooklyn/example/brooklyn-example-hello-world-sql-webapp/0.8.0-incubating/brooklyn-example-hello-world-sql-webapp-0.8.0-incubating.war")
                .configure(TomcatServer.HTTP_PORT, PortRanges.fromString("8080+"))
                .configure(BrooklynCampConstants.PLAN_ID, "parent")
                .configure(SoftwareProcess.START_TIMEOUT, Duration.PRACTICALLY_FOREVER)
                .location(mgmt.getLocationRegistry().getLocationSpec("pivotal-ws2").get())
                //.location(mgmt.getLocationRegistry().getLocationSpec("aws-ec2:eu-west-1").get())
                ;

        final EntitySpec<TomcatServer> gParentSpec = EntitySpec.create(TomcatServer.class)
                .configure(TomcatServer.ROOT_WAR, "http://search.maven.org/remotecontent?filepath=org/apache/brooklyn/example/brooklyn-example-hello-world-sql-webapp/0.8.0-incubating/brooklyn-example-hello-world-sql-webapp-0.8.0-incubating.war")
                .configure(TomcatServer.HTTP_PORT, PortRanges.fromString("8080+"))
                .configure(BrooklynCampConstants.PLAN_ID, "gparent")
                .configure(SoftwareProcess.START_TIMEOUT, Duration.PRACTICALLY_FOREVER)
                .location(mgmt.getLocationRegistry().getLocationSpec("pivotal-ws2").get())
                //.location(mgmt.getLocationRegistry().getLocationSpec("aws-ec2:eu-west-1").get())
                ;

        //adding relations
        //parentSpec.getRelations().addTargetRelationSpec(sonSpec);
        //gParentSpec.getRelations().addTargetRelationSpec(parentSpec);

        //final TomcatServer gParent = app.createAndManageChild(gParentSpec);
        final TomcatServer son = app.createAndManageChild(sonSpec);
        final TomcatServer parent = app.createAndManageChild(parentSpec);
        final TomcatServer gParent = app.createAndManageChild(gParentSpec);


        //mgmt.getLocationRegistry().getLocationSpec("aws-ec2:eu-west-2");
        // mgmt.getLocationRegistry().getLocationSpec("localhost");
        //final Location loc = mgmt.getLocationRegistry().getLocationManaged("aws-ec2:eu-west-2");
        //final Location loc = mgmt.getLocationRegistry().getLocationManaged("localhost");


        //app.addPolicy(PolicySpec.create(ApplicationMigrationTdPolicy.class));
        app.addPolicy(PolicySpec.create(ApplicationMigrationPolicy.class));

        //location necessary
        app.start(ImmutableList.<Location>of(
                //loc
        ));

        app.subscriptions();


        MutableMap<String, Duration> flags = MutableMap.of("timeout", Duration.ONE_HOUR);

        Asserts.succeedsEventually(flags, new Runnable() {
            public void run() {
                assertTrue(gParent.getAttribute(Startable.SERVICE_UP));
                //assertTrue(app.getAttribute(Startable.SERVICE_UP));
                //assertTrue(entity.getAttribute(VanillaCloudFoundryApplication
                //        .SERVICE_PROCESS_IS_RUNNING));
            }
        });
        log.info("************************ GPARENT IS UP:" + gParent.getAttribute(Startable.SERVICE_UP));
        //to check policy((AbstractEntity.BasicPolicySupport)app.policies()).asList().toArray()[0]
        assertTrue(app.getAttribute(Startable.SERVICE_UP));


        parent.relations().add(EntityRelations.HAS_TARGET, son);
        gParent.relations().add(EntityRelations.HAS_TARGET, parent);

        log.info("************************ Relations SON:" + son.relations().getRelations(EntityRelations.TARGETTED_BY).size());
        log.info("************************ Relations Parent:" + parent.relations().getRelations(EntityRelations.TARGETTED_BY).size());


        assertEquals(son.getLocations().size(), 1);
        // assertEquals(((Location)son.getLocations().toArray()[0]).getDisplayName(), "pivotal-ws2");


        final Map<String, String> childrenToMigrate = ImmutableMap
                .of(
                        //"webapp5", "pivotal-ws2",
                        //"webapp1", "aws-ec2:eu-west-1",
                        //"gparent", "aws-ec2:eu-west-1"

                        "webapp1", "bluemix1",
                        "gparent", "bluemix1"
        );
        final Map<String, Map<String, String>> effectorParameters = ImmutableMap.of(ApplicationMigrateEffector.MIGRATE_CHILDREN_LOCATIONS_SPEC, childrenToMigrate);

        log.info("************************ READY TO MIGRATE *************");
        log.info("************************ READY TO MIGRATE *************");
        log.info("************************ READY TO MIGRATE *************");
        log.info("************************ READY TO MIGRATE *************");
        log.info("************************ READY TO MIGRATE *************");
        log.info("************************ READY TO MIGRATE *************");
        log.info("************************ READY TO MIGRATE *************");


        app.invoke(ApplicationMigrateEffector.MIGRATE_APPLICATION, effectorParameters).blockUntilEnded();


        /*
        Asserts.succeedsEventually(MutableMap.of("timeout", Duration.PRACTICALLY_FOREVER), new Runnable() {
            public void run() {
                assertFalse(gParent.getAttribute(TomcatServer.SERVICE_UP));

            }
        });
        Asserts.succeedsEventually(MutableMap.of("timeout", Duration.PRACTICALLY_FOREVER), new Runnable() {
            public void run() {

                assertFalse(son.getAttribute(TomcatServer.SERVICE_PROCESS_IS_RUNNING));

            }
        });
        Asserts.succeedsEventually(MutableMap.of("timeout", Duration.PRACTICALLY_FOREVER), new Runnable() {
            public void run() {
                assertFalse(parent.getAttribute(TomcatServer.SERVICE_UP));
            }
        });*/


        log.info("************************ ALL MIGRATED *************");
        log.info("************************ ALL MIGRATED *************");
        log.info("************************ ALL MIGRATED *************");
        log.info("************************ ALL MIGRATED *************");
        log.info("************************ ALL MIGRATED *************");
        log.info("************************ ALL MIGRATED *************");



        Asserts.succeedsEventually(MutableMap.of("timeout", new Duration(10, TimeUnit.MINUTES)), new Runnable() {
            public void run() {
                assertTrue(gParent.getAttribute(TomcatServer.SERVICE_UP));
                assertTrue(son.getAttribute(TomcatServer.SERVICE_PROCESS_IS_RUNNING));
                assertTrue(son.getAttribute(TomcatServer.SERVICE_UP));
                //assertTrue(app.getAttribute(Startable.SERVICE_UP));
                assertTrue(app.getAttribute(Startable.SERVICE_UP));
                //assertTrue(entity.getAttribute(VanillaCloudFoundryApplication
                //        .SERVICE_PROCESS_IS_RUNNING));
            }
        });

        System.out.println();

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








    @Test(groups = {"Live"})
    public void testElement() throws Exception {

        final EntitySpec<TomcatServer> sonSpec = EntitySpec.create(TomcatServer.class)
                //.configure(TomcatServer.ROOT_WAR, "http://search.maven.org/remotecontent?filepath=org/apache/brooklyn/example/brooklyn-example-hello-world-sql-webapp/0.8.0-incubating/brooklyn-example-hello-world-sql-webapp-0.8.0-incubating.war")
                //.configure(TomcatServer.ROOT_WAR, "file:///Users/Jose/dev/planner-spring/target/planner-spring-1.0-SNAPSHOT.jar")
                .configure(TomcatServer.ROOT_WAR, "file:///Users/Jose/dev/planner-spring/target/planner-spring-1.0-SNAPSHOT.war")
                .configure(TomcatServer.HTTP_PORT, PortRanges.fromString("8080+"))
                .configure(BrooklynCampConstants.PLAN_ID, "webapp1")
                .location(mgmt.getLocationRegistry().getLocationSpec("pivotal-ws2").get())
                //.location(mgmt.getLocationRegistry().getLocationSpec("aws-ec2:eu-west-1").get())
                ;


        final TomcatServer son = app.createAndManageChild(sonSpec);


        //location necessary
        app.start(ImmutableList.<Location>of(
                //loc
        ));


        MutableMap<String, Duration> flags = MutableMap.of("timeout", Duration.ONE_HOUR);

        Asserts.succeedsEventually(flags, new Runnable() {
            public void run() {
                assertTrue(app.getAttribute(Startable.SERVICE_UP));
            }
        });
        Asserts.succeedsEventually(flags, new Runnable() {
            public void run() {

                assertTrue(son.getAttribute(TomcatServer.SERVICE_PROCESS_IS_RUNNING));
            }
        });
        assertTrue(son.getAttribute(TomcatServer.SERVICE_PROCESS_IS_RUNNING));


        //son.invoke(Startable.START, MutableMap.<String, Object>of("locations", MutableList.of(loc))).blockUntilEnded();

        //Asserts.succeedsEventually(MutableMap.of("timeout", Duration.PRACTICALLY_FOREVER), new Runnable() {
        //    public void run() {
        //        assertFalse(son.getAttribute(TomcatServer.SERVICE_UP));
        //    }
        //});

        //assertTrue(son.getAttribute(TomcatServer.SERVICE_UP));

    }

}

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

import static org.testng.Assert.assertTrue;

import org.apache.brooklyn.api.entity.Application;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.camp.brooklyn.BrooklynCampConstants;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.entity.webapp.tomcat.TomcatServer;
import org.apache.brooklyn.launcher.SimpleYamlLauncherForTests;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.util.collections.MutableMap;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

/**
 * A simple test of installing+running on AWS-EC2, using various OS distros and versions.
 */
public class MigrationTest {


    @Test(groups = {"Live"})
    public void doTestt() throws Exception {

        SimpleYamlLauncherForTests launcher = new SimpleYamlLauncherForTests();
        launcher.setShutdownAppsOnExit(true);

        Application app = launcher.launchAppYaml("migration-policy.yml");


        final TomcatServer server = (TomcatServer)
                findChildEntitySpecByPlanId(app, "webapp1");

        final TomcatServer server2 = (TomcatServer)
                findChildEntitySpecByPlanId(app, "webapp1");

        Asserts.succeedsEventually(
                new Runnable() {
                    public void run() {
                        assertTrue(server.getAttribute(Attributes.SERVICE_UP));

                    }
                });


        server2.invoke(MigrateEffector.MIGRATE, MutableMap.of("locationSpec", "aws-ec2:eu-west-1")).blockUntilEnded();

        System.out.println();


        launcher.destroyAll();
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

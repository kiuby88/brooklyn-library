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

import org.apache.brooklyn.api.catalog.Catalog;
import org.apache.brooklyn.api.effector.Effector;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.api.sensor.SensorEvent;
import org.apache.brooklyn.api.sensor.SensorEventListener;
import org.apache.brooklyn.camp.brooklyn.BrooklynCampConstants;
import org.apache.brooklyn.core.effector.EffectorBody;
import org.apache.brooklyn.core.effector.Effectors;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.EntityInternal;
import org.apache.brooklyn.core.entity.EntityRelations;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.entity.trait.Startable;
import org.apache.brooklyn.core.policy.AbstractPolicy;
import org.apache.brooklyn.entity.software.base.SoftwareProcess;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.text.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.Beta;

@Beta
@Catalog(name = "Migration element", description = "")
public class MigrationPolicy extends AbstractPolicy {

    private static final Logger log = LoggerFactory.getLogger(MigrationPolicy.class);


    @Override
    public void setEntity(EntityLocal entity) {
        super.setEntity(entity);

        if (!(entity instanceof Startable)) {
            //TODO: Check if this exception stops the deployment process
            throw new RuntimeException("AddMigrateEffectorEntityPolicy must be attached to an JavaWebAppSoftwareProcess entity");
        }
        //entity.subscriptions().subscribe(entity, Attributes.SERVICE_STATE_ACTUAL, new LifecycleListener());
        ((EntityInternal) entity).getMutableEntityType().addEffector(migratteEffector());

    }

    private Effector<Void> migratteEffector() {
        return Effectors.effector(MigrateEffector.MIGRATE)
                .impl(new EffectorBody<Void>() {
                    @Override
                    public Void call(ConfigBag parameters) {
                        migrate((EntityInternal) entity, (String) parameters.getStringKey(MigrateEffector.MIGRATE_LOCATION_SPEC));
                        return null;
                    }
                })
                .build();
    }

    private void migrate(EntityInternal entity, String locationSpec) {
        log.info("********* MIGRATION ******");
        if (entity.sensors().get(Attributes.SERVICE_STATE_ACTUAL) != Lifecycle.RUNNING) {
            // Is it necessary to check if the whole application is healthy?
            throw new RuntimeException("The entity needs to be healthy before the migration starts");
        }

        stopTargettedEntities(entity);

        log.info("Stopping entity " + getEntityName(entity) + " finished.");
        entity.invoke(Startable.STOP, MutableMap.<String, Object>of()).blockUntilEnded();

        //Location newLocation = Entities.getManagementContext(entity).getLocationRegistry().resolve(locationSpec);
        //Location newLocation = entity.getManagementContext().getLocationRegistry().getLocationManaged(locationSpec);

        entity.clearLocations();
        //entity.addLocations(Lists.newArrayList(newLocation));
        log.info("*******************************");
        log.info("******* LOCATIONS ***** => " + entity.getLocations().size());
        log.info("*******************************");
        entity.invoke(Startable.START, MutableMap.<String, Object>of("locations", MutableList.of(locationSpec))).blockUntilEnded();

        log.info("******* Migrated Entity => " + entity.getLocations().size());
        startTargettedEntities(entity);

        log.info("Migration process of " + entity.getId() + " finished.");
    }

    private void stopTargettedEntities(EntityInternal entity) {
        for (Entity targetted : entity.relations().getRelations(EntityRelations.TARGETTED_BY)) {
            if (targetted.getAttribute(Startable.SERVICE_UP)) {
                stopTargettedEntitiesAndItself(targetted);
            }
        }
    }

    private void stopTargettedEntitiesAndItself(Entity entity) {

        log.info("stopTargettedEntitiesAndItself => **");
        log.info("Entity: "+ entity.getDisplayName());
        log.info("Relations targetted size: "+ entity.relations().getRelations(EntityRelations.TARGETTED_BY).size());
        for (Entity rel : entity.relations().getRelations(EntityRelations.TARGETTED_BY)) {
            log.info("Relations Targetted: "+ rel.getDisplayName());
        }
        log.info("!!END Relations Targetted: "+ entity.getDisplayName());



        for (Entity targetted : entity.relations().getRelations(EntityRelations.TARGETTED_BY)) {
            if (targetted.getAttribute(Startable.SERVICE_UP)) {
                stopTargettedEntitiesAndItself(targetted);
            }
        }
        log.info("Partial Stopping == " + getEntityName(entity));
        entity.invoke(Startable.STOP, MutableMap.<String, Object>of("stopMachineMode",
                SoftwareProcess.StopSoftwareParameters.StopMode.NEVER)).blockUntilEnded();
    }

    private void startTargettedEntities(EntityInternal entity) {
        for (Entity targetted : entity.relations().getRelations(EntityRelations.TARGETTED_BY)) {
            if (!targetted.getAttribute(Startable.SERVICE_UP)) {
                startTargettedEntitiesAndItself(targetted);
            }
        }
    }

    private void startTargettedEntitiesAndItself(Entity entity) {
        log.info("*** RESTART == " + getEntityName(entity));
        entity.invoke(Startable.RESTART, MutableMap.<String, Object>of()).blockUntilEnded();
        for (Entity targetted : entity.relations().getRelations(EntityRelations.TARGETTED_BY)) {
            if (!targetted.getAttribute(Startable.SERVICE_UP)) {
                startTargettedEntitiesAndItself(targetted);
            }
        }
    }


    private String getEntityName(Entity entity) {
        if (Strings.isNonBlank(entity.getConfig(BrooklynCampConstants.PLAN_ID))) {
            return entity.getConfig(BrooklynCampConstants.PLAN_ID);
        } else if (Strings.isNonBlank(entity.getDisplayName())) {
            return entity.getDisplayName();
        } else {
            return entity.getId();
        }
    }

    private class LifecycleListener implements SensorEventListener<Lifecycle> {
        @Override
        public void onEvent(SensorEvent<Lifecycle> event) {
            if (event.getValue().equals(Lifecycle.RUNNING)) {
                addMigrateEffector();
            }
        }

        private void addMigrateEffector() {
            ((EntityInternal) entity).getMutableEntityType().addEffector(migratteEffector());
        }
    }

}

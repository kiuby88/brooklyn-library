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

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.apache.brooklyn.api.catalog.Catalog;
import org.apache.brooklyn.api.effector.Effector;
import org.apache.brooklyn.api.entity.Application;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.api.mgmt.TaskAdaptable;
import org.apache.brooklyn.api.sensor.SensorEvent;
import org.apache.brooklyn.api.sensor.SensorEventListener;
import org.apache.brooklyn.camp.brooklyn.BrooklynCampConstants;
import org.apache.brooklyn.core.effector.EffectorBody;
import org.apache.brooklyn.core.effector.Effectors;
import org.apache.brooklyn.core.entity.EntityInternal;
import org.apache.brooklyn.core.entity.EntityRelations;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.entity.lifecycle.ServiceStateLogic;
import org.apache.brooklyn.core.entity.trait.Startable;
import org.apache.brooklyn.core.policy.AbstractPolicy;
import org.apache.brooklyn.entity.software.base.SoftwareProcess;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.core.task.DynamicTasks;
import org.apache.brooklyn.util.core.task.ParallelTask;
import org.apache.brooklyn.util.core.task.Tasks;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.text.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.Beta;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

@Beta
@Catalog(name = "Migration element", description = "")
public class ApplicationMigrationPolicy extends AbstractPolicy {

    private static final Logger log = LoggerFactory.getLogger(ApplicationMigrationPolicy.class);


    @Override
    public void setEntity(EntityLocal entity) {
        if (!entity.getApplication().equals(entity)) {
            throw new RuntimeException("Application Migration Policy must be attached to an application");
        }

        super.setEntity(entity);

        //if (!(entity instanceof Startable)) {
        //    //TODO: Check if this exception stops the deployment process
        //    throw new RuntimeException("AddMigrateEffectorEntityPolicy must be attached to an JavaWebAppSoftwareProcess entity");
        //}
        //entity.subscriptions().subscribe(entity, Attributes.SERVICE_STATE_ACTUAL, new LifecycleListener());
        ((EntityInternal) entity).getMutableEntityType().addEffector(migratteEffector());

    }

    private Effector<Void> migratteEffector() {
        return Effectors.effector(ApplicationMigrateEffector.MIGRATE_APPLICATION)
                .impl(new EffectorBody<Void>() {
                    @Override
                    public Void call(ConfigBag parameters) {
                        migrate((Application) entity, (Map<String, String>) parameters.getObjKeyMaybe(ApplicationMigrateEffector.MIGRATE_CHILDREN_LOCATIONS_SPEC).get());
                        return null;
                    }
                })
                .build();
    }


    private void t() {


    }

    /*private class StopParentsTasks implements Runnable {



        @Override
        public void run() {

        }
    }*/


    //private void migrate(EntityInternal entity, Map<String, String> locationSpec) {
    private void migrate(final Application application, final Map<String, String> locationSpec) {

        //para cada entity hay que añadir un algoritmo para para todos los componentes


        //final Map<String, String> restOfLocations = ImmutableMap.copyOf(locationSpec);

        final ImmutableList<String> brothers = ImmutableList.copyOf(locationSpec.keySet());
        final List<TaskAdaptable<Void>> tasks = Lists.newArrayList();

        for (String s : brothers) {

            log.info("************************ brother:" + s + " *************");

        }

        for (final Map.Entry<String, String> entry : locationSpec.entrySet()) {


            //Parece que las entities se pueden castear a internal entities en tiempo de compilacion, pero se podra en tiempo de ejecicion??
            //Collection<Entity> l = application.getChildren();
            //for(Entity ee : l){
            //    EntityInternal ei = ((EntityInternal) ee);
            //}

            //tasks.add( Effectors.invocation(entity, effector, parameters) );

            /*Sacar los hijos a partir del Id que viene en el mapa y pasarlos a la tarea hija.
            En brooklny -CloudFoundry deberia de haber un monton de funciones para recuperar los
            hijos de una aplicacion a partir de los IDs
            En Entities hay una funcion que se llama descendats;
*/

            final EntityInternal child = (EntityInternal) findChildEntitySpecByPlanId(application, entry.getKey());

            log.info("************************ FOUND CHILD0>:" + child.getConfig(BrooklynCampConstants.PLAN_ID) + " *************");


            final String childLocation = entry.getValue();

            if (child == null) {
                throw new RuntimeException("Child does not exist camp id: " + entry.getKey());
            }

            tasks.add(
                    //Task<Void> stopTask =
                    Tasks.<Void>builder()
                            .displayName("stopping (machine)")
                            .body(new StopParentsTask(child, brothers))
                                    //.flags(stopMachineFlags)
                            .build());
        }


        ParallelTask<Void> invoke =
                new ParallelTask<Void>(
                        MutableMap.of(
                                "displayName", " stop (parallel)",
                                "description", "stop tast"),
                        tasks);


        //TaskTags.markInessential(invoke);
        //return

        try {
            DynamicTasks.queue(invoke).get();//.orSubmitAsync(application).asTask().blockUntilEnded();
            //DynamicTasks.queueIfPossible(invoke).orSubmitAsync(application).asTask().get();
        } catch (Throwable e) {
            ServiceStateLogic.setExpectedState(entity, Lifecycle.ON_FIRE);
            Exceptions.propagate(e);
        }


//        Task<Void> stopTask = Tasks.<Void>builder()
//                .displayName("stopping (machine)")
//                .body((Runnable) new StopParentsTask(entity, locationSpec.keySet()))
//                //.flags(stopMachineFlags)
//                .build();
//        tasks.add(stopTask);


        /*
        ParallelTask<Void> invoke = new ParallelTask<Void>(
                MutableMap.of(
                        "displayName", effector.getName() + " (parallel)",
                        "description", "Invoking effector \"" + effector.getName() + "\" on " + tasks.size() + (tasks.size() == 1 ? " entity" : " entities"),
                        "tag", BrooklynTaskTags.tagForCallerEntity(callingEntity)),
                tasks);
        TaskTags.markInessential(invoke);
        return DynamicTasks.queueIfPossible(invoke).orSubmitAsync(callingEntity).asTask();


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

*/
        log.info("Migration process of " + entity.getId() + " finished.");
    }

    private class StopParentsTask implements Callable<Void> {

        private final EntityInternal entity;
        private final List<String> brotherToMigrate;


        private StopParentsTask(final EntityInternal entity, final List<String> brotherToMigrate) {
            this.entity = entity;
            this.brotherToMigrate = brotherToMigrate;
        }

        public Void call() {

            //para todos los padres hasta que encuentra que uno de ellos esta en el brotherToMigrate
            log.info("************************ funcionando *************");


            stopTargettedEntities(entity, brotherToMigrate);

            return null;
        }
    }

    private void stopTargettedEntities(EntityInternal entity, List<String> brotherToMigrate) {


        log.info("Ancestros OF == " + entity.getConfig(BrooklynCampConstants.PLAN_ID));

        for (Entity targetted : entity.relations().getRelations(EntityRelations.TARGETTED_BY)) {
            log.info("Ancestro== " + targetted.getConfig(BrooklynCampConstants.PLAN_ID));
            if (ancestorCanBeStopped(targetted, brotherToMigrate)) {
                log.info("Ancestro TO STOP== " + targetted.getConfig(BrooklynCampConstants.PLAN_ID));
                stopTargettedAncestors(targetted, brotherToMigrate);
            }

        }
        log.info("Total Stopping == " + entity.getConfig(BrooklynCampConstants.PLAN_ID));
        Task<Void> stopTask = entity.invoke(Startable.STOP, MutableMap.<String, Object>of());
        try {
            DynamicTasks.queueIfPossible(stopTask).getTask().get();
            //DynamicTasks.queue(stopTask).get();
        } catch (Throwable e) {
            log.info("ERROR TOTAL STOPPING == " + entity.getConfig(BrooklynCampConstants.PLAN_ID));
            ServiceStateLogic.setExpectedState(entity, Lifecycle.ON_FIRE);
            Exceptions.propagate(e);
        }
        log.info("Fin Total Stopping == " + entity.getConfig(BrooklynCampConstants.PLAN_ID));
    }

    private boolean ancestorCanBeStopped(Entity targetted, List<String> brotherToMigrate) {
        return entityIsUp(targetted) && !entityPlanIdIsContained(targetted, brotherToMigrate);
    }

    private void stopTargettedAncestors(Entity entity, List<String> brotherToMigrate) {

        log.info("stopTargettedEntitiesAndItself => **");
        log.info("Entity: " + entity.getDisplayName());
        log.info("Relations targetted size: " + entity.relations().getRelations(EntityRelations.TARGETTED_BY).size());

        for (Entity rel : entity.relations().getRelations(EntityRelations.TARGETTED_BY)) {
            log.info("Relations Targetted: " + rel.getDisplayName());
        }

        log.info("!!END Relations Targetted: " + entity.getDisplayName());


        for (Entity targetted : entity.relations().getRelations(EntityRelations.TARGETTED_BY)) {
            if (ancestorCanBeStopped(targetted, brotherToMigrate)) {
                stopTargettedAncestors(targetted, brotherToMigrate);
            }
        }
        log.info("Partial Stopping == " + getEntityName(entity));

        try {
            entity.invoke(Startable.STOP, MutableMap.<String, Object>of("stopMachineMode",
                    SoftwareProcess.StopSoftwareParameters.StopMode.NEVER)).get();
        } catch (Throwable e) {
            ServiceStateLogic.setExpectedState(entity, Lifecycle.ON_FIRE);
            Exceptions.propagate(e);
        }
    }

    private boolean entityIsUp(final Entity entity) {
        return entity.getAttribute(Startable.SERVICE_UP);
    }

    private boolean entityPlanIdIsContained(Entity entity, List<String> brotherToMigrate) {
        return brotherToMigrate.contains(entity.getConfig(BrooklynCampConstants.PLAN_ID));
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

    private Entity findChildEntitySpecByPlanId(Application app, String planId) {
        for (Entity child : app.getChildren()) {
            String childPlanId = child.getConfig(BrooklynCampConstants.PLAN_ID);
            if ((childPlanId != null) && (childPlanId.equals(planId))) {
                return child;
            }
        }
        return null;
    }

    private Entity findChildEntitySpecById(final Application app, final String id) {
        for (Entity child : app.getChildren()) {
            if (child.getId().equals(id)) {
                return child;
            }
        }
        return null;
    }

}

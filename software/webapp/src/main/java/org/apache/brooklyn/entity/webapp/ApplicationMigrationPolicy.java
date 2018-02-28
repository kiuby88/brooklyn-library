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

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.apache.brooklyn.api.catalog.Catalog;
import org.apache.brooklyn.api.effector.Effector;
import org.apache.brooklyn.api.entity.Application;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.api.location.Location;
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
import org.apache.brooklyn.core.objs.proxy.EntityProxyImpl;
import org.apache.brooklyn.core.policy.AbstractPolicy;
import org.apache.brooklyn.entity.software.base.SoftwareProcess;
import org.apache.brooklyn.entity.software.base.SoftwareProcessImpl;
import org.apache.brooklyn.util.collections.MutableList;
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
import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

@Beta
@Catalog(name = "Components migration", description = "")
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

                        log.info("************************ MAP BEGINNING *************");




                        final Map<String, String> map = getChildrenParameter(parameters);
                        //final Map<String, String> map =
                        //        (Map<String, String>) parameters
                        //                .getStringKeyMaybe(ApplicationMigrateEffector.MIGRATE_CHILDREN_LOCATIONS_SPEC).get();


                        for (final String key : map.keySet()) {
                            log.info("************************ KEY = "+ key+" *- "+map.get(key)+ " -************");

                        }

                        log.info("************************ MAP END *************");

                        migrate((Application) entity, map);
                        return null;
                    }
                })
                .build();
    }

    private Map<String, String> getChildrenParameter(final ConfigBag parameters) {

        //final Map<String, String> map =
        //        (Map<String, String>) parameters
        //                .getStringKeyMaybe(ApplicationMigrateEffector.MIGRATE_CHILDREN_LOCATIONS_SPEC).get();

        Object parameter = parameters
                .getStringKeyMaybe(ApplicationMigrateEffector.MIGRATE_CHILDREN_LOCATIONS_SPEC).get();

        if(parameter instanceof Map) {
            return (Map<String, String>) parameter;
        }


        return Splitter.on(",")
                .omitEmptyStrings()
                //.trimResults()
                .trimResults(CharMatcher.BREAKING_WHITESPACE)
                .withKeyValueSeparator(": ")
                .split((String) parameter);
    }


    private void migrate(final Application application, final Map<String, String> locationSpec) {
        //para cada entity hay que añadir un algoritmo para para todos los componentes

        final ImmutableList<String> brothers = ImmutableList.copyOf(locationSpec.keySet());
        final List<TaskAdaptable<Void>> tasks = Lists.newArrayList();


        for (final String s : brothers) {
            log.info("************************ brother:" + s + " *************");
            if (!checkChildExist(application, s)) {
                throw new IllegalArgumentException("Child " + s + " is not a child");
            }
        }


        final List<String> filtered = filterDescendantsWithout(brothers, application);

        for (final String s : filtered) {
            log.info("************************ FILTERED:" + s + " *************");
        }


        //for (final Map.Entry<String, String> entry : locationSpec.entrySet()) {
        for (final String filteredEntityToStop : filtered) {


            final EntityInternal child = (EntityInternal) findChildEntitySpecByPlanId(application, filteredEntityToStop);

            log.info("************************ FOUND CHILD0>:" + child.getConfig(BrooklynCampConstants.PLAN_ID) + " *************");


            if (child == null) {
                throw new RuntimeException("Child does not exist camp id: " + filteredEntityToStop);
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


        try {
            DynamicTasks.queue(invoke).get();//.orSubmitAsync(application).asTask().blockUntilEnded();
            //DynamicTasks.queueIfPossible(invoke).orSubmitAsync(application).asTask().get();
        } catch (Throwable e) {
            ServiceStateLogic.setExpectedState(entity, Lifecycle.ON_FIRE);
            Exceptions.propagate(e);
        }


        /*ParallelTask<Void> invokeStopNode =
                new ParallelTask<Void>(
                        MutableMap.of(
                                "displayName", " stop (parallel)",
                                "description", "stop tast"),
                        stopNodes(application, locationSpec, brothers));


        try {
            DynamicTasks.queue(invokeStopNode).get();//.orSubmitAsync(application).asTask().blockUntilEnded();
            //DynamicTasks.queueIfPossible(invoke).orSubmitAsync(application).asTask().get();
        } catch (Throwable e) {
            ServiceStateLogic.setExpectedState(entity, Lifecycle.ON_FIRE);
            Exceptions.propagate(e);
        }*/


        //ya estan todos los nodos parados. Iniciarlos dentro de un for para hacer la ejecucion paralela
        startNodes(application, locationSpec);


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

        final List<TaskAdaptable<Void>> tasks = Lists.newArrayList();


        for (final Entity ancestor : entity.relations().getRelations(EntityRelations.TARGETTED_BY)) {
            //log.info("Ancestro== " + targetted.getConfig(BrooklynCampConstants.PLAN_ID));
            if (entityIsUp(ancestor)) { //, brotherToMigrate)) {
                //log.info("Ancestro TO STOP== " + targetted.getConfig(BrooklynCampConstants.PLAN_ID));
                //stopTargettedAncestors(targetted, brotherToMigrate);

                tasks.add(
                        //Task<Void> stopTask =
                        Tasks.<Void>builder()
                                .displayName("")
                                        //.body(new StopElementsTask(child, brothers))
                                .body(new StopParentsTask((EntityInternal) ancestor, brotherToMigrate))
                                .build());

            }

        }


        ParallelTask<Void> invokeStopTasks =
                new ParallelTask<Void>(
                        MutableMap.of(
                                "displayName", " stop (parallel)",
                                "description", "stop tast"), tasks
                );

        try {
            DynamicTasks.queue(invokeStopTasks).get();//.orSubmitAsync(application).asTask().blockUntilEnded();
            //DynamicTasks.queueIfPossible(invoke).orSubmitAsync(application).asTask().get();
        } catch (Throwable e) {
            ServiceStateLogic.setExpectedState(entity, Lifecycle.ON_FIRE);
            Exceptions.propagate(e);
        }


        if (!(entity instanceof SoftwareProcessImpl)) {
        entity = (SoftwareProcessImpl) ((EntityProxyImpl) Proxy.getInvocationHandler(entity)).getDelegate();
        }
        log.info("************************ DISCONECTING SENSORS C :  *************" + entity.getConfig(BrooklynCampConstants.PLAN_ID));
        //entityImpl.disconnectSensors();
        ((SoftwareProcessImpl) entity).disconnectSensors();
        log.info("************************ DISCONECTED SENSORS C :  *************" + entity.getConfig(BrooklynCampConstants.PLAN_ID));


        try {
            if (brotherToMigrate.contains(getEntityName(entity))) {
                log.info("Total Stopping == " + entity.getConfig(BrooklynCampConstants.PLAN_ID));
                //hay que pararlo completamente
                        //entity.invoke(Startable.STOP, MutableMap.<String, Object>of("stopMachineMode",NEVER
                        entity.invoke(Startable.STOP, MutableMap.<String, Object>of(SoftwareProcess.StopSoftwareParameters.STOP_MACHINE_MODE.getName(),
                                SoftwareProcess.StopSoftwareParameters.StopMode.ALWAYS)).get();
                log.info("Fin Total Stopping == " + entity.getConfig(BrooklynCampConstants.PLAN_ID));
            } else {
                log.info("Partial Stopping == " + entity.getConfig(BrooklynCampConstants.PLAN_ID));
                entity.invoke(Startable.STOP, MutableMap.<String, Object>of(SoftwareProcess.StopSoftwareParameters.STOP_MACHINE_MODE.getName(),
                        SoftwareProcess.StopSoftwareParameters.StopMode.NEVER)).get();
                log.info("Fin Partial Stopping == " + entity.getConfig(BrooklynCampConstants.PLAN_ID));
            }
        } catch (Throwable e) {
            ServiceStateLogic.setExpectedState(entity, Lifecycle.ON_FIRE);
            Exceptions.propagate(e);
        }


    }

    private boolean ancestorCanBeStopped(final Entity targetted, final List<String> brotherToMigrate) {
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

            final SoftwareProcessImpl entityImpl = (SoftwareProcessImpl) ((EntityProxyImpl) Proxy.getInvocationHandler(entity)).getDelegate();
            log.info("************************ DISCONECTING SENSORS :  *************" + entity.getConfig(BrooklynCampConstants.PLAN_ID));
            entityImpl.disconnectSensors();
            log.info("************************ DISCONECTED SENSORS :  *************" + entity.getConfig(BrooklynCampConstants.PLAN_ID));

            entity.invoke(Startable.STOP, MutableMap.<String, Object>of("stopMachineMode",
                    SoftwareProcess.StopSoftwareParameters.StopMode.NEVER)).get();
        } catch (Throwable e) {
            ServiceStateLogic.setExpectedState(entity, Lifecycle.ON_FIRE);
            Exceptions.propagate(e);
        }
    }


    private List<TaskAdaptable<Void>> stopNodes(Application application, Map<String, String> locationSpec, ImmutableList<String> brothers) {

        final List<TaskAdaptable<Void>> tasks = Lists.newArrayList();

        for (final Map.Entry<String, String> entry : locationSpec.entrySet()) {


            final EntityInternal node = (EntityInternal) findChildEntitySpecByPlanId(application, entry.getKey());


            if (node == null) {
                throw new RuntimeException("STOP not possible Child does not exist camp id: " + entry.getKey());
            }

            tasks.add(
                    //Task<Void> stopTask =
                    Tasks.<Void>builder()
                            .displayName("stopping (machine)")
                                    //.body(new StopElementsTask(child, brothers))
                            .body(new StopNodeTask(node))
                            .build());
        }


        return tasks;


    }


    private class StopNodeTask implements Callable<Void> {

        private final EntityInternal entity;


        private StopNodeTask(final EntityInternal entity) {
            this.entity = entity;
        }

        public Void call() {
            log.info("Partial Stopping == " + getEntityName(entity));

            entity.invoke(Startable.STOP, MutableMap.<String, Object>of("stopMachineMode",
                    SoftwareProcess.StopSoftwareParameters.StopMode.NEVER))
                    .blockUntilEnded();
            log.info("Partial Stopping == " + getEntityName(entity));

            return null;
        }
    }


    private void startNodes(final Application application, final Map<String, String> locationSpec) {


        final List<TaskAdaptable<Void>> tasks = Lists.newArrayList();
        for (final Map.Entry<String, String> entry : locationSpec.entrySet()) {

            final EntityInternal child = (EntityInternal) findChildEntitySpecByPlanId(application, entry.getKey());

            log.info("************************ FOUND CHILD0>:" + child.getConfig(BrooklynCampConstants.PLAN_ID) + " *************");


            if (child == null) {
                throw new RuntimeException("Child does not exist camp id: " + entry.getKey());
            }


            tasks.add(
                    //Task<Void> stopTask =
                    Tasks.<Void>builder()
                            .displayName("start (machine)")
                            .body(new StartNodesTask(child, locationSpec))
                                    //.flags(stopMachineFlags)
                            .build());
        }

        ParallelTask<Void> invokeStandUpTasks =
                new ParallelTask<Void>(
                        MutableMap.of(
                                "displayName", " start (parallel)",
                                "description", "start tast"), tasks
                );

        try {
            DynamicTasks.queue(invokeStandUpTasks).get();//.orSubmitAsync(application).asTask().blockUntilEnded();
            //DynamicTasks.queueIfPossible(invoke).orSubmitAsync(application).asTask().get();
        } catch (Throwable e) {
            ServiceStateLogic.setExpectedState(entity, Lifecycle.ON_FIRE);
            Exceptions.propagate(e);
        }
    }


    private class StartNodesTask implements Callable<Void> {

        private final EntityInternal entity;
        private final Map<String, String> locationSpec;
        private final ImmutableList<String> brothers;

        private StartNodesTask(final EntityInternal entity, final Map<String, String> locationSpec) {
            this.entity = entity;
            this.locationSpec = locationSpec;
            this.brothers = ImmutableList.copyOf(locationSpec.keySet());
        }

        public Void call() {

            final String entityPlanId = getPlanId(entity);
            if (ServiceStateLogic.getExpectedState(entity) != Lifecycle.STOPPING && ServiceStateLogic.getExpectedState(entity) != Lifecycle.STOPPED) {
                log.info("Entity {} can not be started because it is not STOPPED", entityPlanId);
                return null;

            }

            if (!allChildrenAreRunning(entity)) {
                log.info("Entity {} can not be started because any child is not running", entityPlanId);
                return null;
            }


            //inicia la entity en la expected location
            standUptEntity(entity);


            final List<TaskAdaptable<Void>> standUptTasks = Lists.newArrayList();
            for (final Entity targetted : entity.relations().getRelations(EntityRelations.TARGETTED_BY)) {
                standUptTasks.add(Tasks.<Void>builder()
                        .displayName("start (machine)")
                        .body(new StartNodesTask((EntityInternal) targetted, locationSpec))
                        .build());
            }


            final ParallelTask<Void> invokeStandUpNode =
                    new ParallelTask<Void>(
                            MutableMap.of(
                                    "displayName", " standUp (parallel)",
                                    "description", "standUp"),
                            standUptTasks);

            try {
                DynamicTasks.queue(invokeStandUpNode).get();//.orSubmitAsync(application).asTask().blockUntilEnded();
                //DynamicTasks.queueIfPossible(invoke).orSubmitAsync(application).asTask().get();
            } catch (Throwable e) {
                ServiceStateLogic.setExpectedState(entity, Lifecycle.ON_FIRE);
                Exceptions.propagate(e);
            }


            return null;
        }

        private boolean allChildrenAreRunning(final EntityInternal entity) {
            for (final Entity target : entity.relations().getRelations(EntityRelations.HAS_TARGET)) {
                if (!target.getAttribute(SoftwareProcess.SERVICE_PROCESS_IS_RUNNING)) {
                    return false;
                }
            }
            return true;
        }

        private void standUptEntity(final EntityInternal entity) {
            final String entityPlanId = getPlanId(entity);
            if (brothers.contains(entityPlanId)) {
                startEntityInLocation(entity, locationSpec.get(entityPlanId));
            } else {
                log.info("*******************************");
                log.info("******* RESTART location ***** => "+ getEntityName(entity) + "-" + entity.getLocations().size());
                log.info("*******************************");
                entity.invoke(Startable.RESTART, MutableMap.<String, Object>of()).blockUntilEnded();
                //final SoftwareProcessImpl entityImpl = (SoftwareProcessImpl) ((EntityProxyImpl) Proxy.getInvocationHandler(entity)).getDelegate();
                log.info("************************ WAITTING RESTART :  *************" + entity.getConfig(BrooklynCampConstants.PLAN_ID));
                //entityImpl.waitForServiceUp();
                ((SoftwareProcessImpl)entity).waitForServiceUp();
                log.info("************************ WAIT RESTART :  *************" + entity.getConfig(BrooklynCampConstants.PLAN_ID));

            }
        }

        private void startEntityInLocation(EntityInternal entity, final String newLocation) {
            entity.clearLocations();
            log.info("*******************************");
            log.info("******* START location ***** => " + getEntityName(entity) + "-" + entity.getLocations().size());
            log.info("*******************************");
            Location loc = getManagementContext().getLocationRegistry().getLocationManaged(newLocation);
            //entity.invoke(Startable.START, MutableMap.<String, Object>of("locations", MutableList.of(newLocation))).blockUntilEnded();
            entity.addLocations(ImmutableList.of(loc));
            entity.invoke(Startable.START, MutableMap.<String, Object>of("locations", MutableList.of())).blockUntilEnded();
            if(!(entity instanceof SoftwareProcessImpl)){
                entity = (SoftwareProcessImpl) ((EntityProxyImpl) Proxy.getInvocationHandler(entity)).getDelegate();

            }
            log.info("************************ WAITTING START :  *************" + entity.getConfig(BrooklynCampConstants.PLAN_ID));
            ((SoftwareProcessImpl)entity).waitForServiceUp();
            log.info("************************ WAIT START :  *************" + entity.getConfig(BrooklynCampConstants.PLAN_ID));
        }


    }


    private boolean entityIsUp(final Entity entity) {
        return entity.getAttribute(Startable.SERVICE_UP);
    }

    private boolean entityPlanIdIsContained(Entity entity, List<String> brotherToMigrate) {
        return brotherToMigrate.contains(getPlanId(entity));
    }

    private String getPlanId(final Entity entity) {
        return entity.getConfig(BrooklynCampConstants.PLAN_ID);
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


    private boolean checkChildExist(final Application app, final String childId) {

        boolean result = false;
        for (final Entity entity : app.getChildren()) {

            result = result || isInDescendant(entity, childId);
        }
        return result;

    }

    private boolean checkChildIsDescendant(final Entity entity, final String childId) {

        boolean result = false;
        for (final Entity target : entity.relations().getRelations(EntityRelations.HAS_TARGET)) {
            result = result || isInDescendant(target, childId);
        }
        return result;

    }


    private boolean isInDescendant(final Entity entity, final String childId) {
        if (childId.equals(getEntityName(entity))) {
            return true;
        }

        boolean result = false;
        for (final Entity target : entity.relations().getRelations(EntityRelations.HAS_TARGET)) {
            result = result || isInDescendant(target, childId);
        }
        return result;
    }


    private List<String> filterDescendantsWithout(final List<String> entitieToMigrateId, final Application app) {

        final List<String> filtered = MutableList.of();

        for (String entityToMigrateId : entitieToMigrateId) {

            final EntityInternal entityToMigrate = (EntityInternal) findChildEntitySpecByPlanId(app, entityToMigrateId);
            if (!checkIfAnyEntityToMigrateIsDecendant(entityToMigrate, entitieToMigrateId)) {
                filtered.add(entityToMigrateId);
            }
        }
        return filtered;
    }

    private boolean checkIfAnyEntityToMigrateIsDecendant(final Entity entity, List<String> entitieToMigrateId) {

        boolean result = false;
        for (final String entityToMigrateId : entitieToMigrateId) {

            if (!getEntityName(entity).equals(entityToMigrateId)) {
                result = result || isInDescendant(entity, entityToMigrateId);
            }
        }

        return result;


    }


}

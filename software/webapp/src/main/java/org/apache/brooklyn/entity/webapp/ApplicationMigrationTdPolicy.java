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
import java.util.ArrayList;
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
import org.apache.brooklyn.camp.brooklyn.BrooklynCampConstants;
import org.apache.brooklyn.core.effector.EffectorBody;
import org.apache.brooklyn.core.effector.Effectors;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.EntityInternal;
import org.apache.brooklyn.core.entity.EntityRelations;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.entity.lifecycle.ServiceStateLogic;
import org.apache.brooklyn.core.entity.trait.Startable;
import org.apache.brooklyn.core.objs.proxy.EntityProxyImpl;
import org.apache.brooklyn.core.policy.AbstractPolicy;
import org.apache.brooklyn.entity.software.base.SoftwareProcess;
import org.apache.brooklyn.entity.software.base.SoftwareProcessImpl;
import org.apache.brooklyn.entity.webapp.migration.ReleaseTaskSupplierFactory;
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
public class ApplicationMigrationTdPolicy extends AbstractPolicy {

    private static final Logger log = LoggerFactory.getLogger(ApplicationMigrationTdPolicy.class);
    private ReleaseTaskSupplierFactory releaseFactory = new ReleaseTaskSupplierFactory();

    @Override
    public void setEntity(EntityLocal entity) {
        if (!entity.getApplication().equals(entity)) {
            throw new RuntimeException("Application Migration Policy must be attached to an application");
        }

        super.setEntity(entity);
        ((EntityInternal) entity).getMutableEntityType().addEffector(migratteEffector());
    }

    private Effector<Void> migratteEffector() {
        return Effectors.effector(ApplicationMigrateEffector.MIGRATE_APPLICATION)
                .impl(new EffectorBody<Void>() {
                    @Override
                    public Void call(ConfigBag parameters) {

                        log.info("************************ MAP BEGINNING *************");
                        final Map<String, String> map = getChildrenParameter(parameters);
                        for (final String key : map.keySet()) {
                            log.info("************************ KEY = " + key + " *- " + map.get(key) + " -************");
                        }
                        log.info("************************ MAP END *************");
                        migrate((Application) entity, map);
                        return null;
                    }
                })
                .build();
    }

    private Map<String, String> getChildrenParameter(final ConfigBag parameters) {
        Object parameter = parameters
                .getStringKeyMaybe(ApplicationMigrateEffector.MIGRATE_CHILDREN_LOCATIONS_SPEC).get();

        if (parameter instanceof Map) {
            return (Map<String, String>) parameter;
        }
        return Splitter.on(",")
                .omitEmptyStrings()
                .trimResults(CharMatcher.BREAKING_WHITESPACE)
                .withKeyValueSeparator(": ")
                .split((String) parameter);
    }


    private void migrate(final Application application, final Map<String, String> locationSpec) {

        final ImmutableList<String> brothers = ImmutableList.copyOf(locationSpec.keySet());
        final List<TaskAdaptable<Void>> tasks = Lists.newArrayList();


        for (final String s : brothers) {
            log.info("************************ brother:" + s + " *************");
            if (!checkChildExist(application, s)) {
                throw new IllegalArgumentException("Child " + s + " is not a child");
            }
        }

        final List<TaskAdaptable<Void>> releaseTasks = getReleaseTasks(application, locationSpec);


        //ahora hay que reiniciar todos los componentes


        //se inicia cada componente
        startComponentTasks(application, locationSpec);


        final List<String> filtered = filterDescendantsWithout(brothers, application);

        for (final String s : filtered) {
            log.info("************************ FILTERED:" + s + " *************");
        }

        stopComponents(application, filtered);

        restartComponents(application, filtered);

        releaseOldLocations(releaseTasks);

    }

    private void releaseOldLocations(final List<TaskAdaptable<Void>> releaseTasks) {
        ParallelTask<Void> parallelTask =
                new ParallelTask<>(
                        MutableMap.of(
                                "displayName", " release (parallel)",
                                "description", "release tasks"), releaseTasks
                );
        if(!releaseTasks.isEmpty()){
        try {
            DynamicTasks.queue(parallelTask).get();
        } catch (Throwable e) {
            ServiceStateLogic.setExpectedState(entity, Lifecycle.ON_FIRE);
            Exceptions.propagate(e);
        }}
    }

    private void restartComponents(final Application application, final List<String> filtered) {


        final List<TaskAdaptable<Void>> restartTasks = Lists.newArrayList();
        for (final String entityId : filtered) {

            final EntityInternal child = (EntityInternal) findChildEntitySpecByPlanId(application, entityId);

            log.info("************************ FOUND CHILD0>:" + child.getConfig(BrooklynCampConstants.PLAN_ID) + " *************");

            if (child == null) {
                throw new RuntimeException("Child does not exist camp id: " + entityId);
            }


            restartTasks.add(
                    //Task<Void> stopTask =
                    Tasks.<Void>builder()
                            .displayName("restart task #"+child.getConfig(BrooklynCampConstants.PLAN_ID))
                            .body(new RestartParentsTask(child))
                            .build());
        }

        ParallelTask<Void> invokeStandUpTasks =
                new ParallelTask<Void>(
                        MutableMap.of(
                                "displayName", " restart (parallel)",
                                "description", "restart tasks"), restartTasks
                );

        if(!restartTasks.isEmpty()){

        try {
            DynamicTasks.queue(invokeStandUpTasks).get();
        } catch (Throwable e) {
            ServiceStateLogic.setExpectedState(entity, Lifecycle.ON_FIRE);
            Exceptions.propagate(e);
        }
        }
    }

    private void stopComponents(Application application, final List<String> filtered) {

        final List<TaskAdaptable<Void>> stopTasks = new ArrayList<>();

        for (final String filteredEntityToStop : filtered) {
            final EntityInternal child = (EntityInternal) findChildEntitySpecByPlanId(application, filteredEntityToStop);

            log.info("************************ To STOP PARENTS OF:  *************" + child.getConfig(BrooklynCampConstants.PLAN_ID));


            if (child == null) {
                throw new RuntimeException("Child does not exist camp id: " + filteredEntityToStop);
            }

            stopTasks.add(
                    //Task<Void> stopTask =
                    Tasks.<Void>builder()
                            .displayName("stopping master #" + child.getConfig(BrooklynCampConstants.PLAN_ID))
                            .body(new StopParentsTask(child))
                            .build());
        }


        ParallelTask<Void> invoke =
                new ParallelTask<Void>(
                        MutableMap.of(
                                "displayName", " stop (parallel)",
                                "description", "stop tast"),
                        stopTasks);


        if (!stopTasks.isEmpty()) {
            try {
                log.info("************************ Executing stop parents strategy*************");
                DynamicTasks.queue(invoke).get();//.orSubmitAsync(application).asTask().blockUntilEnded();
                log.info("************************ Executed stop parents strategy*************");

                //DynamicTasks.queueIfPossible(invoke).orSubmitAsync(application).asTask().get();
            } catch (Throwable e) {
                ServiceStateLogic.setExpectedState(entity, Lifecycle.ON_FIRE);
                Exceptions.propagate(e);
            }
            //Los componentes mas bajos no se paran, solo los padres.
        }

    }

    private void startComponentTasks(final Application application,
                                     final Map<String, String> locationSpec) {
        final List<TaskAdaptable<Void>> startTasks = Lists.newArrayList();

        for (final Map.Entry<String, String> entry : locationSpec.entrySet()) {

            final EntityInternal entity = (EntityInternal) findChildEntitySpecByPlanId(application, entry.getKey());
            final String location = entry.getValue();

            final SoftwareProcessImpl entityImpl = (SoftwareProcessImpl) ((EntityProxyImpl) Proxy.getInvocationHandler(entity)).getDelegate();
            log.info("************************ DISCONECTING SENSORS :  *************" + entity.getConfig(BrooklynCampConstants.PLAN_ID));
            entityImpl.disconnectSensors();
            log.info("************************ DISCONECTED SENSORS :  *************" + entity.getConfig(BrooklynCampConstants.PLAN_ID));

            if (entity == null) {
                throw new RuntimeException("Child does not exist camp id: " + entry.getKey());
            }

            startTasks.add(
                    Tasks.<Void>builder()
                            .displayName("startAlt #" + entity.getConfig(BrooklynCampConstants.PLAN_ID))
                            .body(new StartNodeInAlternativeLocationTask(entityImpl, location))
                            .build());
        }

        ParallelTask<Void> invokeStandUpTasks =
                new ParallelTask<Void>(
                        MutableMap.of(
                                "displayName", " start (parallel)",
                                "description", "start each component in new location"),
                        startTasks
                );

        if (!startTasks.isEmpty()) {
            try {
                DynamicTasks.queue(invokeStandUpTasks).get();//.orSubmitAsync(application).asTask().blockUntilEnded();
                //DynamicTasks.queueIfPossible(invoke).orSubmitAsync(application).asTask().get();
            } catch (Throwable e) {
                ServiceStateLogic.setExpectedState(entity, Lifecycle.ON_FIRE);
                Exceptions.propagate(e);
            }
        }
    }

    private List<TaskAdaptable<Void>> getReleaseTasks(Application application, Map<String, String> locationSpec) {
        final List<TaskAdaptable<Void>> releaseTasks = new ArrayList<>();
        for (final Map.Entry<String, String> entry : locationSpec.entrySet()) {
            final EntityInternal entityToRelease = (EntityInternal) findChildEntitySpecByPlanId(application, entry.getKey());
            if (entityToRelease == null) {
                throw new RuntimeException("Child does not exist camp id: " + locationSpec);
            }
            releaseTasks.add(releaseFactory.getSupplier(entityToRelease));
        }
        return releaseTasks;
    }


    private class StopParentsTask implements Callable<Void> {

        private final EntityInternal entity;

        private StopParentsTask(final EntityInternal entity) {
            this.entity = entity;
        }

        public Void call() {
            stopTargettedEntities(entity);
            return null;
        }
    }


    private void stopTargettedEntities(final EntityInternal entity) {
        log.info("************************ Task to Stop parents of:  *************" + entity.getConfig(BrooklynCampConstants.PLAN_ID));


        final List<TaskAdaptable<Void>> stopParents = Lists.newArrayList();
        for (final Entity ancestor : entity.relations().getRelations(EntityRelations.TARGETTED_BY)) {
            log.info("************************ CHECK IF IS STOPPABLE THE PARENT :  *************" + ancestor.getConfig(BrooklynCampConstants.PLAN_ID));

            if (entityIsUp(ancestor)) { //, brotherToMigrate)) {
                log.info("************************ IS STOPPABLE THE PARENT:  *************" + ancestor.getConfig(BrooklynCampConstants.PLAN_ID));
                stopParents.add(
                        Tasks.<Void>builder()
                                .displayName("Stopping component and Parents 1 #"+ancestor.getConfig(BrooklynCampConstants.PLAN_ID))
                                .body(new StopComponentAndParentsTask((EntityInternal) ancestor))
                                .build());

            }
        }

        ParallelTask<Void> invokeStopTasks =
                new ParallelTask<Void>(
                        MutableMap.of(
                                "displayName", " stop (parallel)",
                                "description", "stop tast"), stopParents
                );

        if(!stopParents.isEmpty()) {
            try {
                log.info("************************ Stopping targetted entities of (" + stopParents.size() + "):  *************" + entity.getConfig(BrooklynCampConstants.PLAN_ID));
                DynamicTasks.queue(invokeStopTasks).get();
                log.info("************************ Stopped targetted entities of :  *************" + entity.getConfig(BrooklynCampConstants.PLAN_ID));

            } catch (Throwable e) {
                ServiceStateLogic.setExpectedState(entity, Lifecycle.ON_FIRE);
                Exceptions.propagate(e);
            }
        }
    }


    private class StopComponentAndParentsTask implements Callable<Void> {

        private final EntityInternal entity;

        private StopComponentAndParentsTask(final EntityInternal entity) {
            this.entity = entity;
        }

        public Void call() {
            stopTargettedAndEntities(entity);
            return null;
        }
    }

    private void stopTargettedAndEntities(final EntityInternal entity) {
        log.info("************************ Task to stop entity and parents:  *************" + entity.getConfig(BrooklynCampConstants.PLAN_ID));


        final List<TaskAdaptable<Void>> stopParents = Lists.newArrayList();
        for (final Entity ancestor : entity.relations().getRelations(EntityRelations.TARGETTED_BY)) {
            log.info("************************ CHECK IF IS STOPPABLE 2 :  *************" + ancestor.getConfig(BrooklynCampConstants.PLAN_ID));

            if (entityIsUp(ancestor)) { //, brotherToMigrate)) {
                //  log.info("************************ IS STOPPABLE 2:  *************" + ancestor.getConfig(BrooklynCampConstants.PLAN_ID));
                log.info("************************ IS STOPPABLE 2:  *************" + ancestor.getConfig(BrooklynCampConstants.PLAN_ID));
                stopParents.add(
                        Tasks.<Void>builder()
                                .displayName("Stopping component and Parents #"+ancestor.getConfig(BrooklynCampConstants.PLAN_ID))

                                .body(new StopComponentAndParentsTask((EntityInternal) ancestor))
                                .build());

            }
        }

        ParallelTask<Void> invokeStopTasks =
                new ParallelTask<Void>(
                        MutableMap.of(
                                "displayName", " stop (parallel)",
                                "description", "stop tast"), stopParents
                );
        if(!stopParents.isEmpty()) {
            try {
                log.info("************************ STOPING PARENTS OF 2 (" + stopParents.size() + "):  *************" + entity.getConfig(BrooklynCampConstants.PLAN_ID));
                DynamicTasks.queue(invokeStopTasks).get();
                log.info("************************ STOPPED PARENTS OF 2:  *************" + entity.getConfig(BrooklynCampConstants.PLAN_ID));

            } catch (Throwable e) {
                ServiceStateLogic.setExpectedState(entity, Lifecycle.ON_FIRE);
                Exceptions.propagate(e);
            }
        }
        stopComponent(entity);
    }

    private void stopComponent(final EntityInternal entity) {
        try {

            MutableMap<String, Object> parameters = MutableMap.<String, Object>of(
                    SoftwareProcess.StopSoftwareParameters.STOP_MACHINE_MODE.getName(), SoftwareProcess.StopSoftwareParameters.StopMode.NEVER,
                    SoftwareProcess.StopSoftwareParameters.STOP_PROCESS_MODE.getName(), SoftwareProcess.StopSoftwareParameters.StopMode.IF_NOT_STOPPED
            );

            if(entityIsUp(entity)) {
                log.info("Partial Stopping == " + entity.getConfig(BrooklynCampConstants.PLAN_ID));
                entity.invoke(Startable.STOP, parameters).get();
                log.info("Fin Partial Stopping == " + entity.getConfig(BrooklynCampConstants.PLAN_ID));
            }
        } catch (Throwable e) {
            ServiceStateLogic.setExpectedState(entity, Lifecycle.ON_FIRE);
            Exceptions.propagate(e);
        }
    }

    private boolean entityIsUp(final Entity entity) {
        /**
         * 2018-02-12 22:30:08,461 WARN  Error invoking start at TomcatServerImpl{id=jOI2NaA5}: NullPointerException: keystore URL must be specified if using HTTPS for TomcatServerImpl{id=jOI2NaA5}
         2018-02-12 22:30:25,615 WARN  Error invoking start at TomcatServerImpl{id=BagX33hf}: NullPointerException: keystore URL must be specified if using HTTPS for TomcatServerImpl{id=BagX33hf}
         2018-02-12 22:30:25,616 INFO  ************************ FILTERED:webapp1 *************
         2018-02-12 22:30:25,617 INFO  ************************ FOUND CHILD0>:webapp1 *************
         2018-02-12 22:30:25,617 INFO  ************************ To STOP PARENTS OF:  *************webapp1
         2018-02-12 22:30:25,617 INFO  ************************ STOPPING PARENTS FROM POLICY:  *************
         2018-02-12 22:30:25,618 INFO  ************************ CHECK TARGETTED OF  :  *************webapp1
         2018-02-12 22:30:25,618 INFO  ************************ CHECK IF STOP :  *************parent
         2018-02-12 22:30:25,618 INFO  ************************ CHECK IF STOP :  *************parent
         2018-02-12 22:30:25,618 INFO  ************************ IS STOPPABLE:  *************parent
         2018-02-12 22:30:25,618 INFO  ************************ STOPING PARENTS OF (1):  *************webapp1
         2018-02-12 22:30:25,618 INFO  ************************ CHECK TARGETTED OF  :  *************parent
         2018-02-12 22:30:25,618 INFO  ************************ CHECK IF STOP :  *************gparent
         2018-02-12 22:30:25,618 INFO  ************************ STOPING PARENTS OF (0):  *************parent
         2018-02-12 22:30:25,619 INFO  ************************ STOPPED PARENTS OF :  *************parent
         2018-02-12 22:30:25,619 INFO  Partial Stopping == parent
         2018-02-12 22:30:25,619 INFO  Stopping TomcatServerImpl{id=Sr4ioOqm} in [CloudFoundryPaasLocation{id=JI756pFM, name=CloudFoundryPaasLocation:JI75}]
         2018-02-12 22:30:27,015 INFO  ************************ DELETING from driver-->brooklyn-example-hello-world-sql-webapp-0-Sr4ioOqm
         2018-02-12 22:30:27,787 INFO  ************************ DELETED from driver-->brooklyn-example-hello-world-sql-webapp-0-Sr4ioOqm
         2018-02-12 22:30:27,789 INFO  Fin Partial Stopping == parent
         2018-02-12 22:30:27,789 INFO  ************************ STOPPED PARENTS OF :  *************webapp1
         2018-02-12 22:30:27,789 INFO  Partial Stopping == webapp1
         2018-02-12 22:30:27,791 INFO  Stopping TomcatServerImpl{id=jOI2NaA5} in [JcloudsLocation[AWS Dublin:AKIAJXK5XXSQSL52XYRQ/aws-ec2:eu-west-1@dxRzeT4M], SshMachineLocation[ec2-34-242-163-164.eu-west-1.compute.amazonaws.com:Jose@ec2-34-242-163-164.eu-west-1.compute.amazonaws.com/34.242.163.164:22(id=rw2TiVmW)]]
         2018-02-12 22:30:28,588 INFO  Fin Partial Stopping == webapp1
         2018-02-12 22:30:28,588 INFO  ************************ STOPPING PARENTS FROM POLICY:  **************
         2018-02-12 22:30:28,588 INFO  ************************ FOUND CHILD0>:webapp1 *************
         2018-02-12 22:30:28,589 INFO  *******************************
         2018-02-12 22:30:28,589 INFO  ******* Restart Entity ***** => webapp1
         2018-02-12 22:30:28,590 INFO  *******************************
         [GC (Allocation Failure)  488229K->308610K(750592K), 0.0078539 secs]
         [GC (Allocation Failure)  497026K->308627K(748544K), 0.0055928 secs]
         2018-02-12 22:35:35,163 WARN  Software process entity TomcatServerImpl{id=jOI2NaA5} did not pass is-running check within the required 5m limit (5m 3s elapsed) (throwing)
         2018-02-12 22:35:35,370 WARN  Service is not up when setting running on TomcatServerImpl{id=jOI2NaA5}; delayed 206ms but Sensor: service.isUp (java.lang.Boolean) did not recover from false; not-up-indicators={service.process.isRunning=The software process for this entity does not appear to be running}
         2018-02-12 22:35:35,370 WARN  Setting TomcatServerImpl{id=jOI2NaA5} on-fire due to problems when expected running, up=false, not-up-indicators: {service.process.isRunning=The software process for this entity does not appear to be running}
         2018-02-12 22:35:35,370 WARN  Setting TomcatServerImpl{id=jOI2NaA5} on-fire due to problems when expected running, up=false, not-up-indicators: {service.process.isRunning=The software process for this entity does not appear to be running}
         2018-02-12 22:35:35,572 WARN  Service is not up when setting running on TomcatServerImpl{id=jOI2NaA5}; delayed 201ms but Sensor: service.isUp (java.lang.Boolean) did not recover from false; not-up-indicators={service.process.isRunning=The software process for this entity does not appear to be running}
         2018-02-12 22:35:35,573 WARN  Error invoking restart at TomcatServerImpl{id=jOI2NaA5}: Software process entity TomcatServerImpl{id=jOI2NaA5} did not pass is-running check within the required 5m limit (5m 3s elapsed)
         2018-02-12 22:35:35,573 INFO  Entity parent can not be started because any child is not running

         *
         */
        log.info("Getting UP of " + entity.getConfig(BrooklynCampConstants.PLAN_ID) + " ==" + entity.getAttribute(Startable.SERVICE_UP));

        return entity.getAttribute(Startable.SERVICE_UP)
                && entity.getAttribute(SoftwareProcess.SERVICE_PROCESS_IS_RUNNING)
                && entity.getAttribute(Attributes.SERVICE_STATE_ACTUAL) == Lifecycle.RUNNING; //Aqui puse esto
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


    private Entity findChildEntitySpecByPlanId(Application app, String planId) {
        for (Entity child : app.getChildren()) {
            String childPlanId = child.getConfig(BrooklynCampConstants.PLAN_ID);
            if ((childPlanId != null) && (childPlanId.equals(planId))) {
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


    private class StartNodeInAlternativeLocationTask implements Callable<Void> {

        private final SoftwareProcessImpl entity;
        private final String newLocation;

        private StartNodeInAlternativeLocationTask(final SoftwareProcessImpl entity, final String newLocation) {
            this.entity = entity;
            this.newLocation = newLocation;
        }

        public Void call() {
            startEntityInLocation(entity, newLocation);
            return null;
        }

        private void startEntityInLocation(final SoftwareProcessImpl entity, final String newLocation) {
            entity.clearLocations();
            log.info("*******************************");
            log.info("******* START location ***** => " + getEntityName(entity) + "-" + entity.getLocations().size());
            log.info("*******************************");
            Location loc = getManagementContext().getLocationRegistry().getLocationManaged(newLocation);
            //entity.invoke(Startable.START, MutableMap.<String, Object>of("locations", MutableList.of(newLocation))).blockUntilEnded();
            entity.addLocations(ImmutableList.of(loc));

            try {
                entity.invoke(Startable.START, MutableMap.<String, Object>of("locations", MutableList.of())).get();
                entity.connectSensors();
                entity.waitForServiceUp();

            } catch (Throwable e) {
                ServiceStateLogic.setExpectedState(entity, Lifecycle.ON_FIRE);
                Exceptions.propagate(e);
            }

        }

    }

    private class RestartParentsTask implements Callable<Void> {
        private final EntityInternal entity;

        private RestartParentsTask(final EntityInternal entity) {
            this.entity = entity;
        }

        public Void call() {
            final String entityPlanId = getPlanId(entity);
            /*if (ServiceStateLogic.getExpectedState(entity) != Lifecycle.STOPPING && ServiceStateLogic.getExpectedState(entity) != Lifecycle.STOPPED) {
                log.info("Entity {} can not be started because it is not STOPPED", entityPlanId);
                return null;
            }*/

            /*if (!allChildrenAreRunning(entity)) {
                log.info("Entity {} can not be started because any child is not running", entityPlanId);
                return null;
            }*/

            final List<TaskAdaptable<Void>> standUpParentstTasks = Lists.newArrayList();

            for (final Entity targetted : entity.relations().getRelations(EntityRelations.TARGETTED_BY)) {
                standUpParentstTasks.add(Tasks.<Void>builder()
                        .displayName("Restart component and Parents #"+entityPlanId)
                        .body(new RestartNodesAndParentsTask((EntityInternal) targetted))
                        .build());
            }

            final ParallelTask<Void> invokeStandUpNode =
                    new ParallelTask<Void>(
                            MutableMap.of(
                                    "displayName", " restart (parallel)",
                                    "description", "restart"),
                            standUpParentstTasks);

            if(!standUpParentstTasks.isEmpty()) {

                try {
                    DynamicTasks.queue(invokeStandUpNode).get();
                } catch (Throwable e) {
                    ServiceStateLogic.setExpectedState(entity, Lifecycle.ON_FIRE);
                    Exceptions.propagate(e);
                }
            }
            return null;

        }

    }


    private class RestartNodesAndParentsTask implements Callable<Void> {

        private final EntityInternal entity;

        private RestartNodesAndParentsTask(final EntityInternal entity) {
            this.entity = entity;
        }

        public Void call() {

            final String entityPlanId = getPlanId(entity);
            if (//ServiceStateLogic.getExpectedState(entity) == Lifecycle.STOPPING &&
                    ServiceStateLogic.getExpectedState(entity) != Lifecycle.STOPPED) {
                log.info("Entity {} can not be started because it is not STOPPED", entityPlanId);
                return null;
            }

            //esto hay que depurarlo aqui, no se xq no se puede iniciar si los hijos si estan running, si acaso hay que usar el state
            if (!allChildrenAreRunning(entity)) {
                log.info("Entity {} can not be restarted because any child is not running", entityPlanId);
                return null;
            }
            restartEntity(entity);

            final List<TaskAdaptable<Void>> standUptTasks = Lists.newArrayList();
            for (final Entity targetted : entity.relations().getRelations(EntityRelations.TARGETTED_BY)) {
                standUptTasks.add(Tasks.<Void>builder()
                        .displayName("Restart component and Parents #"+entityPlanId)
                        .body(new RestartNodesAndParentsTask((EntityInternal) targetted))
                        .build());
            }

            final ParallelTask<Void> invokeStandUpNode =
                    new ParallelTask<Void>(
                            MutableMap.of(
                                    "displayName", " restart (parallel)",
                                    "description", "restart"),
                            standUptTasks);

            if(!standUptTasks.isEmpty()) {

                try {
                    DynamicTasks.queue(invokeStandUpNode).get();
                } catch (Throwable e) {
                    ServiceStateLogic.setExpectedState(entity, Lifecycle.ON_FIRE);
                    Exceptions.propagate(e);
                }
            }
            return null;
        }

        /*
        private boolean allChildrenAreRunning(final EntityInternal entity) {
            for (final Entity target : entity.relations().getRelations(EntityRelations.HAS_TARGET)) {
                if (!target.getAttribute(SoftwareProcess.SERVICE_PROCESS_IS_RUNNING)) {
                    return false;
                }
            }
            return true;
        }*/

        private void restartEntity(final EntityInternal entity) {
            entity.clearLocations();
            log.info("*******************************");
            log.info("******* Restart Entity ***** => " + getEntityName(entity));
            log.info("*******************************");
            entity.invoke(Startable.RESTART, MutableMap.<String, Object>of()).blockUntilEnded();


            //final SoftwareProcessImpl entityImpl = (SoftwareProcessImpl) ((EntityProxyImpl) Proxy.getInvocationHandler(entity)).getDelegate();
            ((SoftwareProcessImpl) entity).waitForServiceUp();
        }


    }

    private boolean allChildrenAreRunning(final EntityInternal entity) {
        for (final Entity target : entity.relations().getRelations(EntityRelations.HAS_TARGET)) {
            if (!target.getAttribute(SoftwareProcess.SERVICE_PROCESS_IS_RUNNING)) {
                return false;
            }
        }
        return true;
    }


}

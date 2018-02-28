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
 */package org.apache.brooklyn.entity.webapp.migration;

import java.lang.reflect.Proxy;
import java.util.concurrent.Callable;

import org.apache.brooklyn.api.entity.drivers.DriverDependentEntity;
import org.apache.brooklyn.api.entity.drivers.EntityDriver;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.location.MachineLocation;
import org.apache.brooklyn.api.location.MachineProvisioningLocation;
import org.apache.brooklyn.api.mgmt.TaskAdaptable;
import org.apache.brooklyn.camp.brooklyn.BrooklynCampConstants;
import org.apache.brooklyn.core.entity.EntityInternal;
import org.apache.brooklyn.core.objs.proxy.EntityProxyImpl;
import org.apache.brooklyn.entity.software.base.AbstractApplicationCloudFoundryDriver;
import org.apache.brooklyn.location.cloudfoundry.CloudFoundryPaasLocation;
import org.apache.brooklyn.util.core.task.Tasks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by Jose on 21/01/18.
 */
public class ReleaseTaskSupplierFactory {

    private static final Logger log = LoggerFactory.getLogger(ReleaseTaskSupplierFactory.class);


    public TaskAdaptable<Void> getSupplier(final EntityInternal entity) {

        //if (!(entity instanceof DriverDependentEntity)) {
        if (!(((EntityProxyImpl) Proxy.getInvocationHandler(entity)).getDelegate() instanceof DriverDependentEntity)) {
            throw new MigrationException("Entity " + entity.getDisplayName() + " does not implements driver interface.");
        }

        final EntityDriver driver = getDriver(entity);

        return Tasks.<Void>builder()
                .displayName("start " + entity.getDisplayName())
                .body(getSupplier(entity, driver))
                .build();
    }

    private Callable<Void> getSupplier(final EntityInternal entity, final EntityDriver driver) {

        final Location driverLocation = driver.getLocation();

        if ((driverLocation instanceof CloudFoundryPaasLocation)
                && (driver instanceof AbstractApplicationCloudFoundryDriver)) {
            final CloudFoundryPaasLocation cfLocation = (CloudFoundryPaasLocation) driverLocation;

            return new ReleaseCloudFoundryLocationTask((AbstractApplicationCloudFoundryDriver)driver, cfLocation);
        }

        final Location parentLocation = driverLocation.getParent();
        //if (driverLocation instanceof JcloudsMachineLocation) {
        //    final JcloudsMachineLocation jcLocation = (JcloudsMachineLocation) driverLocation;
        //    return new ReleaseJCloudsLocationTask(entity, jcLocation);
        //}

        if (parentLocation instanceof MachineProvisioningLocation) {
            final MachineLocation providionedMachine = (MachineLocation) driverLocation;
            return new ReleaseJCloudsLocationTask(entity, providionedMachine);
        }


        throw new MigrationException("Location " + driverLocation.getClass()
                + " does not supported by supplier");
    }


    private EntityDriver getDriver(final EntityInternal entity) {
        final DriverDependentEntity entityWithDrievr = (DriverDependentEntity) ((EntityProxyImpl) Proxy.getInvocationHandler(entity)).getDelegate();
        final EntityDriver driver = entityWithDrievr.getDriver();
        if (driver == null) {
            throw new MigrationException("Entity " + entity.getDisplayName() + " with null driver.");

        }
        return driver;
    }


    private class ReleaseCloudFoundryLocationTask implements Callable<Void> {

        private final String applicationName;
        private final CloudFoundryPaasLocation location;


        private ReleaseCloudFoundryLocationTask(final AbstractApplicationCloudFoundryDriver driver, final CloudFoundryPaasLocation location) {
            this.applicationName = driver.getApplicationName();
            this.location = location;
        }

        public Void call() {
            location.getCloudFoundryClient().deleteApplication(applicationName);
            return null;
        }
    }


    private class ReleaseJCloudsLocationTask implements Callable<Void> {

        private final MachineLocation machine;
        private final EntityInternal entity;

        private ReleaseJCloudsLocationTask(final EntityInternal entity, final MachineLocation machine) {
            this.entity = entity;
            this.machine = machine;
        }

        public Void call() {
            final MachineProvisioningLocation parent = (MachineProvisioningLocation) machine.getParent();
            parent.release(machine);
            return null;
        }
    }

}

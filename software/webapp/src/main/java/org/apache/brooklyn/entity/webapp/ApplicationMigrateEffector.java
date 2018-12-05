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

import java.util.Map;

import org.apache.brooklyn.core.annotation.Effector;
import org.apache.brooklyn.core.annotation.EffectorParam;
import org.apache.brooklyn.core.effector.MethodEffector;
import org.apache.brooklyn.util.collections.MutableMap;

import com.google.common.annotations.Beta;
import com.google.common.collect.ImmutableMap;

@Beta
public interface ApplicationMigrateEffector {

    public MethodEffector<Void> MIGRATE_APPLICATION = new MethodEffector<Void>(ApplicationMigrateEffector.class, "migrateApp");
    public String MIGRATE_CHILDREN_LOCATIONS_SPEC = "migrateMap";
    public final static Map<String, String > EMPTY_CHILDREN =   ImmutableMap.of();

    /**
     * Starts a migration process.
     * It calls stop() on the original locations and start() on the new one.
     * <p/>
     * After this process finishes it refreshes all the sibling entities dependent data (ConfigKeys, Env variables...)
     */
    @Beta
    @Effector(description = "Migrates the application's children to different locations")
    void migrateApp(@EffectorParam(name = MIGRATE_CHILDREN_LOCATIONS_SPEC, description = "Children Location Specs", nullable = false) Map<String, String> migrateMap);
}

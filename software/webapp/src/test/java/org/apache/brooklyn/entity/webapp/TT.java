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
import java.util.concurrent.Callable;

import org.apache.brooklyn.api.mgmt.TaskAdaptable;
import org.apache.brooklyn.core.entity.EntityInternal;
import org.apache.brooklyn.util.core.task.Tasks;
import org.testng.annotations.Test;

import com.google.common.collect.Lists;

/**
 * Created by Jose on 07/06/17.
 */
public class TT {



    @Test
    public void t(){

        final List<TaskAdaptable<Void>> tasks = Lists.newArrayList();
        tasks.add(
                //Task<Void> stopTask =
                Tasks.<Void>builder()
                        .displayName("stopping (machine)")
                        .body(new StopParentsTask())
                                //.flags(stopMachineFlags)
                        .build());

    }




    private class StopParentsTask implements Callable<Void> {


        private StopParentsTask() {

        }

        public Void call() {

            //para todos los padres hasta que encuentra que uno de ellos esta en el brotherToMigrate
            //log.info("************************ funcionando *************");
            //stopTargettedEntities(entity, brotherToMigrate);

            return null;
        }
    }


}
